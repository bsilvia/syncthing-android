package com.nutomic.syncthingandroid.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import kotlin.text.Charsets
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.util.Util.runShellCommand
import eu.chainfire.libsuperuser.Shell
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.LineNumberReader
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Runs the syncthing binary from command line, and prints its output to logcat.
 *
 * @see [Command Line Docs](http://docs.syncthing.net/users/syncthing.html)
 */
class SyncthingRunnable(context: Context, command: Command) : Runnable {
    private val mContext: Context
    private val mSyncthingBinary: File
    private val mCommand: Array<String>
    private val mLogFile: File

    @JvmField
    @Inject
    var mPreferences: SharedPreferences? = null
    private val mUseRoot: Boolean

    @JvmField
    @Inject
    var mNotificationHandler: NotificationHandler? = null

    enum class Command {
        DeviceID,  // Output the device ID to the command line.
        Generate,  // Generate keys, a config file and immediately exit.
        Main,  // Run the main Syncthing application.
        ResetDatabase,  // Reset Syncthing's database
        ResetDeltas,  // Reset Syncthing's delta indexes
    }

    /**
     * Constructs instance.
     *
     * @param command Which type of Syncthing command to execute.
     */
    init {
        (context.applicationContext as SyncthingApp).component()!!.inject(this)
        mContext = context
        mSyncthingBinary = Constants.getSyncthingBinary(mContext)
        mLogFile = Constants.getLogFile(mContext)

        val logLevel = "DEBUG"

        // Get preferences relevant to starting syncthing core.
        mUseRoot = mPreferences!!.getBoolean(Constants.PREF_USE_ROOT, false) && Shell.SU.available()
        when (command) {
            Command.DeviceID -> mCommand = arrayOf<String>(
                mSyncthingBinary.path,
                "device-id",
                "--home=${mContext.filesDir}"
            )

            Command.Generate -> mCommand = arrayOf<String>(
                mSyncthingBinary.path,
                "generate",
                "--home=${mContext.filesDir}"
            )

            Command.Main -> mCommand = arrayOf<String>(
                mSyncthingBinary.path,
                "--home=${mContext.filesDir}",
                "--no-browser",
                "--log-level=$logLevel"
            )

            // TODO - this has the wrong cmd line arg
            Command.ResetDatabase -> mCommand = arrayOf<String>(
                mSyncthingBinary.path,
                "--home",
                mContext.filesDir.toString(),
                "--reset-database",
                "--log-level=$logLevel"
            )

            // TODO - this has the wrong cmd line arg
            Command.ResetDeltas -> mCommand = arrayOf<String>(
                mSyncthingBinary.path,
                "--home",
                mContext.filesDir.toString(),
                "-reset-deltas",
                "--log-level=$logLevel"
            )
        }
    }

    override fun run() {
        run(false)
    }

    @SuppressLint("WakelockTimeout")
    fun run(returnStdOut: Boolean): String {
        trimLogFile()
        var capturedStdOut = ""
        // Make sure Syncthing is executable
        try {
            val pb = ProcessBuilder("chmod", "500", mSyncthingBinary.path)
            val p = pb.start()
            p.waitFor()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to chmod Syncthing", e)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Failed to chmod Syncthing", e)
        }
        // Loop Syncthing
        var process: Process? = null
        // Potential fix for #498, keep the CPU running while native binary is running
        val pm = mContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = if (useWakeLock())
            pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                mContext.getString(R.string.app_name) + ":" + TAG
            )
        else
            null
        try {
            if (wakeLock != null) {
                wakeLock.acquire()
                try {
                    increaseInotifyWatches()
                    val (resultStdOut, proc) = runSyncthingCore(returnStdOut)
                    capturedStdOut = resultStdOut
                    process = proc
                } finally {
                    try {
                        wakeLock.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to release wakelock", e)
                    }
                }
            } else {
                increaseInotifyWatches()
                val (resultStdOut, proc) = runSyncthingCore(returnStdOut)
                capturedStdOut = resultStdOut
                process = proc
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
        } finally {
            process?.destroy()
        }
        return capturedStdOut
    }

    private fun putCustomEnvironmentVariables(
        environment: MutableMap<String?, String?>,
        sp: SharedPreferences
    ) {
        val customEnvironment = sp.getString("environment_variables", null)
        if (TextUtils.isEmpty(customEnvironment)) return

        for (e in customEnvironment!!.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()) {
            val e2 = e.split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            environment[e2[0]] = e2[1]
        }
    }

    /**
     * Returns true if the experimental setting for using wake locks has been enabled in settings.
     */
    private fun useWakeLock(): Boolean {
        return mPreferences!!.getBoolean(Constants.PREF_USE_WAKE_LOCK, false)
    }

    private val syncthingPIDs: MutableList<String?>
        /**
         * Look for running libsyncthing.so processes and return an array
         * containing the PIDs of found instances.
         */
        get() {
            val syncthingPIDs: MutableList<String?> =
                ArrayList()
            var ps: Process? = null
            var psOut: DataOutputStream? = null
            var br: BufferedReader? = null
            try {
                ps = Runtime.getRuntime().exec(if (mUseRoot) "su" else "sh")
                psOut = DataOutputStream(ps.outputStream)
                psOut.writeBytes("ps\n")
                psOut.writeBytes("exit\n")
                psOut.flush()
                ps.waitFor()
                br =
                    BufferedReader(InputStreamReader(ps.inputStream, "UTF-8"))
                var line: String?
                while ((br.readLine().also { line = it }) != null) {
                    if (line!!.contains(Constants.FILENAME_SYNCTHING_BINARY)) {
                        val syncthingPID: String =
                            line.trim { it <= ' ' }.split("\\s+".toRegex())
                                .dropLastWhile { it.isEmpty() }.toTypedArray()[1]
                        Log.v(
                            TAG,
                            "getSyncthingPIDs: Found process PID [$syncthingPID]"
                        )
                        syncthingPIDs.add(syncthingPID)
                    }
                }
            } catch (e: IOException) {
                Log.w(
                    TAG,
                    "Failed to list Syncthing processes",
                    e
                )
            } catch (e: InterruptedException) {
                Log.w(
                    TAG,
                    "Failed to list Syncthing processes",
                    e
                )
            } finally {
                try {
                    br?.close()
                    psOut?.close()
                } catch (e: IOException) {
                    Log.w(
                        TAG,
                        "Failed to close psOut stream",
                        e
                    )
                }
                ps?.destroy()
            }
            return syncthingPIDs
        }

    /**
     * Root-only: Temporarily increase "fs.inotify.max_user_watches"
     * as Android has a default limit of 8192 watches.
     * Manually run "sysctl fs.inotify" in a root shell terminal to check current limit.
     */
    private fun increaseInotifyWatches() {
        if (!mUseRoot || !Shell.SU.available()) {
            Log.i(
                TAG,
                "increaseInotifyWatches: Root is not available. Cannot increase inotify limit."
            )
            return
        }
        val exitCode = runShellCommand("sysctl -n -w fs.inotify.max_user_watches=131072\n", true)
        Log.i(TAG, "increaseInotifyWatches: sysctl returned $exitCode")
    }

    /**
     * Look for a running libsyncthing.so process and nice its IO.
     */
    private fun niceSyncthing() {
        if (!mUseRoot || !Shell.SU.available()) {
            Log.i(TAG_NICE, "Root is not available. Cannot nice syncthing.")
            return
        }

        val syncthingPIDs = this.syncthingPIDs
        if (syncthingPIDs.isEmpty()) {
            Log.i(TAG_NICE, "Found no running instances of " + Constants.FILENAME_SYNCTHING_BINARY)
            return
        }

        // Ionice all running syncthing processes.
        for (syncthingPID in syncthingPIDs) {
            // Set best-effort, low priority using ionice.
            val exitCode = runShellCommand("/system/bin/ionice $syncthingPID be 7\n", true)
            Log.i(
                TAG_NICE, "ionice returned " + exitCode.toString() +
                        " on " + Constants.FILENAME_SYNCTHING_BINARY
            )
        }
    }

    fun interface OnSyncthingKilled {
        fun onKilled()
    }

    /**
     * Look for running libsyncthing.so processes and kill them.
     * Try a SIGINT first, then try again with SIGKILL.
     */
    fun killSyncthing() {
        for (i in 0..1) {
            val syncthingPIDs = this.syncthingPIDs
            if (syncthingPIDs.isEmpty()) {
                Log.d(
                    TAG,
                    "killSyncthing: Found no more running instances of " + Constants.FILENAME_SYNCTHING_BINARY
                )
                break
            }

            var exitCode: Int
            for (syncthingPID in syncthingPIDs) {
                if (i > 0) {
                    // Force termination of the process by sending SIGKILL.
                    SystemClock.sleep(3000)
                    exitCode = runShellCommand("kill -SIGKILL $syncthingPID\n", mUseRoot)
                } else {
                    exitCode = runShellCommand("kill -SIGINT $syncthingPID\n", mUseRoot)
                    SystemClock.sleep(1000)
                }
                if (exitCode == 0) {
                    Log.d(TAG, "Killed Syncthing process $syncthingPID")
                } else {
                    Log.w(
                        TAG, "Failed to kill Syncthing process " + syncthingPID +
                                " exit code " + exitCode.toString()
                    )
                }
            }
        }
    }

    /**
     * Logs the outputs of a stream to logcat and mNativeLog.
     *
     * @param is The stream to log.
     * @param priority The priority level.
     */
    private fun log(`is`: InputStream?, priority: Int): Thread {
        val t = Thread {
            var br: BufferedReader? = null
            try {
                br = BufferedReader(InputStreamReader(`is`, Charsets.UTF_8))
                var line: String?
                while ((br.readLine().also { line = it }) != null) {
                    Log.println(priority, TAG_NATIVE, line!!)

                    try {
                        mLogFile.appendText(line + "\n", Charsets.UTF_8)
                    } catch (e: IOException) {
                        Log.w(TAG, "log: Failed to append to log file", e)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to read Syncthing's command line output", e)
            }
            if (br != null) {
                try {
                    br.close()
                } catch (e: IOException) {
                    Log.w(TAG, "log: Failed to close bufferedReader", e)
                }
            }
        }
        t.start()
        return t
    }

    /**
     * Only keep last [.LOG_FILE_MAX_LINES] lines in log file, to avoid bloat.
     */
    private fun trimLogFile() {
        if (!mLogFile.exists()) return

        try {
            val lnr = LineNumberReader(FileReader(mLogFile))
            lnr.skip(Long.MAX_VALUE)

            val lineCount = lnr.lineNumber
            lnr.close()

            val tempFile = File(mContext.getExternalFilesDir(null), "syncthing.log.tmp")

            val reader = BufferedReader(FileReader(mLogFile))
            val writer = BufferedWriter(FileWriter(tempFile))

            var currentLine: String?
            val startFrom: Int = lineCount - LOG_FILE_MAX_LINES
            var i = 0
            while ((reader.readLine().also { currentLine = it }) != null) {
                if (i > startFrom) {
                    writer.write(currentLine + "\n")
                }
                i++
            }
            writer.close()
            reader.close()
            tempFile.renameTo(mLogFile)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to trim log file", e)
        }
    }

    private fun buildEnvironment(): HashMap<String?, String?> {
        val targetEnv = HashMap<String?, String?>()
        // Set home directory to data folder for web GUI folder picker.
        targetEnv["HOME"] = Environment.getExternalStorageDirectory().absolutePath
        targetEnv["STTRACE"] = TextUtils.join(
            " ",
            mPreferences!!.getStringSet(
                Constants.PREF_DEBUG_FACILITIES_ENABLED,
                java.util.HashSet()
            )!!
        )
        val externalFilesDir = mContext.getExternalFilesDir(null)
        if (externalFilesDir != null) targetEnv["STGUIASSETS"] = externalFilesDir.absolutePath + "/gui"
        targetEnv["STMONITORED"] = "1"
        targetEnv["STNOUPGRADE"] = "1"
        // Disable hash benchmark for faster startup.
        // https://github.com/syncthing/syncthing/issues/4348
        targetEnv["STHASHING"] = "minio"
        if (mPreferences!!.getBoolean(Constants.PREF_USE_TOR, false)) {
            targetEnv["all_proxy"] = "socks5://localhost:9050"
            targetEnv["ALL_PROXY_NO_FALLBACK"] = "1"
        } else {
            val socksProxyAddress: String = mPreferences!!.getString(
                Constants.PREF_SOCKS_PROXY_ADDRESS,
                ""
            )!!
            if (socksProxyAddress != "") {
                targetEnv["all_proxy"] = socksProxyAddress
            }

            val httpProxyAddress: String = mPreferences!!.getString(
                Constants.PREF_HTTP_PROXY_ADDRESS,
                ""
            )!!
            if (httpProxyAddress != "") {
                targetEnv["http_proxy"] = httpProxyAddress
                targetEnv["https_proxy"] = httpProxyAddress
            }
        }
        if (mPreferences!!.getBoolean("use_legacy_hashing", false)) targetEnv["STHASHING"] = "standard"
        putCustomEnvironmentVariables(targetEnv, mPreferences!!)
        return targetEnv
    }

    @Throws(IOException::class)
    private fun setupAndLaunch(env: HashMap<String?, String?>): Process {
        if (mUseRoot) {
            val pb = ProcessBuilder("su")
            val process = pb.start()
            // The su binary prohibits the inheritance of environment variables.
            // Even with --preserve-environment the environment gets messed up.
            // We therefore start a root shell, and set all the environment variables manually.
            val suOut = DataOutputStream(process.outputStream)
            for (entry in env.entries) {
                suOut.writeBytes(String.format("export %s=\"%s\"\n", entry.key, entry.value))
            }
            suOut.flush()
            // Exec will replace the su process image by Syncthing as execlp in C does.
            // Without using exec, the process will drop to the root shell as soon as Syncthing terminates like a normal shell does.
            // If we did not use exec, we would wait infinitely for the process to terminate (ret = process.waitFor(); in run()).
            // With exec the whole process terminates when Syncthing exits.
            suOut.writeBytes("exec " + TextUtils.join(" ", mCommand) + "\n")
            // suOut.flush has to be called to fix issue - #1005 Endless loader after enabling "Superuser mode"
            suOut.flush()
            return process
        } else {
            val pb = ProcessBuilder(*mCommand)
            pb.environment().putAll(env)
            return pb.start()
        }
    }

    /**
     * Core syncthing execution logic extracted to a single method so both wakeLock
     * and non-wakeLock code paths reuse the same implementation.
     * Returns Pair(capturedStdOut, process)
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun runSyncthingCore(returnStdOut: Boolean): Pair<String, Process?> {
        val targetEnv = buildEnvironment()
        val process = setupAndLaunch(targetEnv)

        mSyncthing.set(process)

        var capturedStdOut = ""
        var lInfo: Thread? = null
        var lWarn: Thread? = null
        if (returnStdOut) {
            var br: BufferedReader? = null
            try {
                br = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                var line: String
                while ((br.readLine().also { line = it }) != null) {
                    Log.println(Log.INFO, TAG_NATIVE, line)
                    capturedStdOut = capturedStdOut + line + "\n"
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to read Syncthing's command line output", e)
            } finally {
                br?.close()
            }
        } else {
            lInfo = log(process.inputStream, Log.INFO)
            lWarn = log(process.errorStream, Log.WARN)
        }

        niceSyncthing()

        val ret = process.waitFor()
        Log.i(TAG, "Syncthing exited with code $ret")
        mSyncthing.set(null)
        lInfo?.join()
        lWarn?.join()

        when (ret) {
            0, 137 -> {
            }

            1 -> {
                Log.w(
                    TAG,
                    "Another Syncthing instance is already running, requesting restart via SyncthingService intent"
                )
                Log.i(TAG, "Restarting syncthing")
                mContext.startService(
                    Intent(mContext, SyncthingService::class.java)
                        .setAction(SyncthingService.ACTION_RESTART)
                )
            }

            3 -> {
                Log.i(TAG, "Restarting syncthing")
                mContext.startService(
                    Intent(mContext, SyncthingService::class.java)
                        .setAction(SyncthingService.ACTION_RESTART)
                )
            }

            else -> {
                Log.w(TAG, "Syncthing has crashed (exit code $ret)")
                mNotificationHandler!!.showCrashedNotification(
                    R.string.notification_crash_title,
                    false
                )
            }
        }

        return Pair(capturedStdOut, process)
    }

    companion object {
        private const val TAG = "SyncthingRunnable"
        private const val TAG_NATIVE = "SyncthingNativeCode"
        private const val TAG_NICE = "SyncthingRunnableIoNice"
        private const val LOG_FILE_MAX_LINES = 10

        private val mSyncthing = AtomicReference<Process?>()
    }
}
