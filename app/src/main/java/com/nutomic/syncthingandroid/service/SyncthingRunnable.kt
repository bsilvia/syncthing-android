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
import com.google.common.base.Charsets
import com.google.common.io.Files
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
import java.security.InvalidParameterException
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
        deviceid,  // Output the device ID to the command line.
        generate,  // Generate keys, a config file and immediately exit.
        main,  // Run the main Syncthing application.
        resetdatabase,  // Reset Syncthing's database
        resetdeltas,  // Reset Syncthing's delta indexes
    }

    /**
     * Constructs instance.
     *
     * @param command Which type of Syncthing command to execute.
     */
    init {
        (context.getApplicationContext() as SyncthingApp).component()!!.inject(this)
        mContext = context
        mSyncthingBinary = Constants.getSyncthingBinary(mContext)
        mLogFile = Constants.getLogFile(mContext)

        // Get preferences relevant to starting syncthing core.
        mUseRoot = mPreferences!!.getBoolean(Constants.PREF_USE_ROOT, false) && Shell.SU.available()
        when (command) {
            Command.deviceid -> mCommand = arrayOf<String>(
                mSyncthingBinary.getPath(),
                "-home",
                mContext.getFilesDir().toString(),
                "--device-id"
            )

            Command.generate -> mCommand = arrayOf<String>(
                mSyncthingBinary.getPath(),
                "-generate",
                mContext.getFilesDir().toString(),
                "-logflags=0"
            )

            Command.main -> mCommand = arrayOf<String>(
                mSyncthingBinary.getPath(),
                "-home",
                mContext.getFilesDir().toString(),
                "-no-browser",
                "-logflags=0"
            )

            Command.resetdatabase -> mCommand = arrayOf<String>(
                mSyncthingBinary.getPath(),
                "-home",
                mContext.getFilesDir().toString(),
                "-reset-database",
                "-logflags=0"
            )

            Command.resetdeltas -> mCommand = arrayOf<String>(
                mSyncthingBinary.getPath(),
                "-home",
                mContext.getFilesDir().toString(),
                "-reset-deltas",
                "-logflags=0"
            )

            else -> throw InvalidParameterException("Unknown command option")
        }
    }

    override fun run() {
        run(false)
    }

    @SuppressLint("WakelockTimeout")
    fun run(returnStdOut: Boolean): String {
        trimLogFile()
        val ret: Int
        var capturedStdOut = ""
        // Make sure Syncthing is executable
        try {
            val pb = ProcessBuilder("chmod", "500", mSyncthingBinary.getPath())
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
            if (wakeLock != null) wakeLock.acquire()
            increaseInotifyWatches()

            val targetEnv = buildEnvironment()
            process = setupAndLaunch(targetEnv)

            mSyncthing.set(process)

            var lInfo: Thread? = null
            var lWarn: Thread? = null
            if (returnStdOut) {
                var br: BufferedReader? = null
                try {
                    br = BufferedReader(InputStreamReader(process.getInputStream(), Charsets.UTF_8))
                    var line: String
                    while ((br.readLine().also { line = it }) != null) {
                        Log.println(Log.INFO, TAG_NATIVE, line)
                        capturedStdOut = capturedStdOut + line + "\n"
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to read Syncthing's command line output", e)
                } finally {
                    if (br != null) br.close()
                }
            } else {
                lInfo = log(process.getInputStream(), Log.INFO, true)
                lWarn = log(process.getErrorStream(), Log.WARN, true)
            }

            niceSyncthing()

            ret = process.waitFor()
            Log.i(TAG, "Syncthing exited with code " + ret)
            mSyncthing.set(null)
            if (lInfo != null) lInfo.join()
            if (lWarn != null) lWarn.join()

            when (ret) {
                0, 137 -> {}
                1 -> {
                    Log.w(
                        TAG,
                        "Another Syncthing instance is already running, requesting restart via SyncthingService intent"
                    )
                    // Restart was requested via Rest API call.
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
                    Log.w(TAG, "Syncthing has crashed (exit code " + ret + ")")
                    mNotificationHandler!!.showCrashedNotification(
                        R.string.notification_crash_title,
                        false
                    )
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
        } finally {
            if (wakeLock != null) wakeLock.release()
            if (process != null) process.destroy()
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
            environment.put(e2[0], e2[1])
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
                ArrayList<String?>()
            var ps: Process? = null
            var psOut: DataOutputStream? = null
            var br: BufferedReader? = null
            try {
                ps = Runtime.getRuntime().exec(if (mUseRoot) "su" else "sh")
                psOut = DataOutputStream(ps.getOutputStream())
                psOut.writeBytes("ps\n")
                psOut.writeBytes("exit\n")
                psOut.flush()
                ps.waitFor()
                br =
                    BufferedReader(InputStreamReader(ps.getInputStream(), "UTF-8"))
                var line: String?
                while ((br.readLine().also { line = it }) != null) {
                    if (line!!.contains(Constants.FILENAME_SYNCTHING_BINARY)) {
                        val syncthingPID: String? =
                            line.trim { it <= ' ' }.split("\\s+".toRegex())
                                .dropLastWhile { it.isEmpty() }.toTypedArray()[1]
                        Log.v(
                            TAG,
                            "getSyncthingPIDs: Found process PID [" + syncthingPID + "]"
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
                    if (br != null) {
                        br.close()
                    }
                    if (psOut != null) {
                        psOut.close()
                    }
                } catch (e: IOException) {
                    Log.w(
                        TAG,
                        "Failed to close psOut stream",
                        e
                    )
                }
                if (ps != null) {
                    ps.destroy()
                }
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
        Log.i(TAG, "increaseInotifyWatches: sysctl returned " + exitCode.toString())
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
            val exitCode = runShellCommand("/system/bin/ionice " + syncthingPID + " be 7\n", true)
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
                    exitCode = runShellCommand("kill -SIGKILL " + syncthingPID + "\n", mUseRoot)
                } else {
                    exitCode = runShellCommand("kill -SIGINT " + syncthingPID + "\n", mUseRoot)
                    SystemClock.sleep(1000)
                }
                if (exitCode == 0) {
                    Log.d(TAG, "Killed Syncthing process " + syncthingPID)
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
     * @param saveLog True if the log should be stored to [.mLogFile].
     */
    private fun log(`is`: InputStream?, priority: Int, saveLog: Boolean): Thread {
        val t = Thread(Runnable {
            var br: BufferedReader? = null
            try {
                br = BufferedReader(InputStreamReader(`is`, Charsets.UTF_8))
                var line: String?
                while ((br.readLine().also { line = it }) != null) {
                    Log.println(priority, TAG_NATIVE, line!!)

                    if (saveLog) {
                        Files.append(line + "\n", mLogFile, Charsets.UTF_8)
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
        })
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
            lnr.skip(Long.Companion.MAX_VALUE)

            val lineCount = lnr.getLineNumber()
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
        targetEnv.put("HOME", Environment.getExternalStorageDirectory().getAbsolutePath())
        targetEnv.put(
            "STTRACE", TextUtils.join(
                " ",
                mPreferences!!.getStringSet(
                    com.nutomic.syncthingandroid.service.Constants.PREF_DEBUG_FACILITIES_ENABLED,
                    java.util.HashSet<kotlin.String?>()
                )!!
            )
        )
        val externalFilesDir = mContext.getExternalFilesDir(null)
        if (externalFilesDir != null) targetEnv.put(
            "STGUIASSETS",
            externalFilesDir.getAbsolutePath() + "/gui"
        )
        targetEnv.put("STMONITORED", "1")
        targetEnv.put("STNOUPGRADE", "1")
        // Disable hash benchmark for faster startup.
        // https://github.com/syncthing/syncthing/issues/4348
        targetEnv.put("STHASHING", "minio")
        if (mPreferences!!.getBoolean(Constants.PREF_USE_TOR, false)) {
            targetEnv.put("all_proxy", "socks5://localhost:9050")
            targetEnv.put("ALL_PROXY_NO_FALLBACK", "1")
        } else {
            val socksProxyAddress: String = mPreferences!!.getString(
                com.nutomic.syncthingandroid.service.Constants.PREF_SOCKS_PROXY_ADDRESS,
                ""
            )!!
            if (socksProxyAddress != "") {
                targetEnv.put("all_proxy", socksProxyAddress)
            }

            val httpProxyAddress: String = mPreferences!!.getString(
                com.nutomic.syncthingandroid.service.Constants.PREF_HTTP_PROXY_ADDRESS,
                ""
            )!!
            if (httpProxyAddress != "") {
                targetEnv.put("http_proxy", httpProxyAddress)
                targetEnv.put("https_proxy", httpProxyAddress)
            }
        }
        if (mPreferences!!.getBoolean("use_legacy_hashing", false)) targetEnv.put(
            "STHASHING",
            "standard"
        )
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
            val suOut = DataOutputStream(process.getOutputStream())
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

    companion object {
        private const val TAG = "SyncthingRunnable"
        private const val TAG_NATIVE = "SyncthingNativeCode"
        private const val TAG_NICE = "SyncthingRunnableIoNice"
        private const val LOG_FILE_MAX_LINES = 10

        private val mSyncthing = AtomicReference<Process?>()
    }
}
