package com.nutomic.syncthingandroid.service

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.http.PollWebGuiAvailableTask
import com.nutomic.syncthingandroid.model.RunConditionCheckResult
import com.nutomic.syncthingandroid.service.SyncthingRunnable.OnSyncthingKilled
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.ConfigXml.OpenConfigException
import com.nutomic.syncthingandroid.util.PermissionUtil
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Holds the native syncthing instance and provides an API to access it.
 */
class SyncthingService : Service() {
    fun interface OnServiceStateChangeListener {
        fun onServiceStateChange(currentState: State?)
    }

    fun interface OnRunConditionCheckResultListener {
        fun onRunConditionCheckResultChanged(result: RunConditionCheckResult?)
    }

    /**
     * Indicates the current state of SyncthingService and of Syncthing itself.
     */
    enum class State {
        /** Service is initializing, Syncthing was not started yet.  */
        INIT,

        /** Syncthing binary is starting.  */
        STARTING,

        /** Syncthing binary is running,
         * Rest API is available,
         * RestApi class read the config and is fully initialized.
         */
        ACTIVE,

        /** Syncthing binary is shutting down.  */
        DISABLED,

        /** There is some problem that prevents Syncthing from running.  */
        ERROR,
    }

    /**
     * Initialize the service with State.DISABLED as [RunConditionMonitor] will
     * send an update if we should run the binary after it got instantiated in
     * [onStartCommand].
     */
    var currentState: State = State.DISABLED
        private set
    private val mCurrentCheckResult =
        AtomicReference(RunConditionCheckResult.SHOULD_RUN)

    private var mConfig: ConfigXml? = null
    private var mPollWebGuiAvailableTask: PollWebGuiAvailableTask? = null
    var api: RestApi? = null
        private set
    private var mEventProcessor: EventProcessor? = null
    private var mRunConditionMonitor: RunConditionMonitor? = null
    private var mSyncthingRunnable: SyncthingRunnable? = null
    private var mExecutor: ExecutorService? = null
    private var mStartupTaskFuture: Future<*>? = null
    private var mSyncthingRunnableThread: Thread? = null
    private var mHandler: Handler? = null

    private val mOnServiceStateChangeListeners = HashSet<OnServiceStateChangeListener?>()
    private val mOnRunConditionCheckResultListeners = HashSet<OnRunConditionCheckResultListener?>()
    private val mBinder = SyncthingServiceBinder(this)

    @JvmField
    @Inject
    var notificationHandler: NotificationHandler? = null

    @JvmField
    @Inject
    var mPreferences: SharedPreferences? = null

    // Java callers expect a `getNotificationHandler()` method; expose it explicitly.
    fun getNotificationHandler(): NotificationHandler? = notificationHandler

    /**
     * Object that must be locked upon accessing mCurrentState
     */
    private val mStateLock = Any()

    /**
     * Stores the result of the last should run decision received by OnDeviceStateChangedListener.
     */
    private var mLastDeterminedShouldRun = false

    /**
     * True if a service [onDestroy] was requested while syncthing is starting,
     * in that case, perform stop in [onApiAvailable].
     */
    private var mDestroyScheduled = false

    /**
     * True if the user granted the storage permission.
     */
    private var mStoragePermissionGranted = false

    /**
     * Starts the native binary.
     */
    override fun onCreate() {
        Log.v(TAG, "onCreate")
        super.onCreate()
        (application as SyncthingApp).component()!!.inject(this)
        mHandler = Handler(Looper.getMainLooper())

        // Executor for background tasks that previously used AsyncTask
        mExecutor = Executors.newSingleThreadExecutor()

        /*
         * If runtime permissions are revoked, android kills and restarts the service.
         * see issue: https://github.com/syncthing/syncthing-android/issues/871
         * We need to recheck if we still have the storage permission.
         */
        mStoragePermissionGranted = PermissionUtil.haveStoragePermission(this)

        if (this.notificationHandler != null) {
            notificationHandler!!.setAppShutdownInProgress(false)
        }
    }

    /**
     * Handles intent actions, e.g. [.ACTION_RESTART]
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.v(TAG, "onStartCommand")
        if (!mStoragePermissionGranted) {
            Log.e(TAG, "User revoked storage permission. Stopping service.")
            if (this.notificationHandler != null) {
                notificationHandler!!.showStoragePermissionRevokedNotification()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        /*
         * Send current service state to listening endpoints.
         * This is required that components know about the service State.DISABLED
         * if RunConditionMonitor does not send a "shouldRun = true" callback
         * to start the binary according to preferences shortly after its creation.
         * See {@link mLastDeterminedShouldRun} defaulting to "false".
         */
        if (this.currentState == State.DISABLED) {
            synchronized(mStateLock) {
                onServiceStateChange(this.currentState)
            }
        }
        if (mRunConditionMonitor == null) {
            /*
             * Instantiate the run condition monitor on first onStartCommand and
             * enable callback on run condition change affecting the final decision to
             * run/terminate syncthing. After initial run conditions are collected
             * the first decision is sent to {@link onUpdatedShouldRunDecision}.
             */
            mRunConditionMonitor = RunConditionMonitor(
                this@SyncthingService
            ) { result: RunConditionCheckResult? ->
                this.onUpdatedShouldRunDecision(
                    result!!
                )
            }
        }
        notificationHandler!!.updatePersistentNotification(this)

        if (intent == null) return START_STICKY

        if (ACTION_RESTART == intent.action && this.currentState == State.ACTIVE) {
            shutdown(State.INIT) { this.launchStartupTask() }
        } else if (ACTION_RESET_DATABASE == intent.action) {
            shutdown(State.INIT) {
                SyncthingRunnable(this, SyncthingRunnable.Command.ResetDatabase).run()
                launchStartupTask()
            }
        } else if (ACTION_RESET_DELTAS == intent.action) {
            shutdown(State.INIT) {
                SyncthingRunnable(this, SyncthingRunnable.Command.ResetDeltas).run()
                launchStartupTask()
            }
        } else if (ACTION_REFRESH_NETWORK_INFO == intent.action) {
            mRunConditionMonitor!!.updateShouldRunDecision()
        } else if (ACTION_IGNORE_DEVICE == intent.action && this.currentState == State.ACTIVE) {
            // mApi is not null due to State.ACTIVE
            checkNotNull(this.api)
            api!!.ignoreDevice(
                intent.getStringExtra(EXTRA_DEVICE_ID)!!, intent.getStringExtra(
                    EXTRA_DEVICE_NAME
                ), intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            )
            notificationHandler!!.cancelConsentNotification(
                intent.getIntExtra(
                    EXTRA_NOTIFICATION_ID,
                    0
                )
            )
        } else if (ACTION_IGNORE_FOLDER == intent.action && this.currentState == State.ACTIVE) {
            // mApi is not null due to State.ACTIVE
            checkNotNull(this.api)
            api!!.ignoreFolder(
                intent.getStringExtra(EXTRA_DEVICE_ID)!!, intent.getStringExtra(
                    EXTRA_FOLDER_ID
                )!!, intent.getStringExtra(EXTRA_FOLDER_LABEL)
            )
            notificationHandler!!.cancelConsentNotification(
                intent.getIntExtra(
                    EXTRA_NOTIFICATION_ID,
                    0
                )
            )
        } else if (ACTION_OVERRIDE_CHANGES == intent.action && this.currentState == State.ACTIVE) {
            checkNotNull(this.api)
            api!!.overrideChanges(intent.getStringExtra(EXTRA_FOLDER_ID)!!)
        }
        return START_STICKY
    }

    /**
     * After run conditions monitored by [RunConditionMonitor] changed and
     * it had an influence on the decision to run/terminate syncthing, this
     * function is called to notify this class to run/terminate the syncthing binary.
     * [.onServiceStateChange] is called while applying the decision change.
     */
    private fun onUpdatedShouldRunDecision(result: RunConditionCheckResult) {
        val newShouldRunDecision = result.isShouldRun
        val reasonsChanged = mCurrentCheckResult.getAndSet(result) != result
        if (reasonsChanged) {
            onRunConditionCheckResultChange(result)
        }

        if (newShouldRunDecision != mLastDeterminedShouldRun) {
            Log.i(
                TAG,
                "shouldRun decision changed to $newShouldRunDecision according to configured run conditions."
            )
            mLastDeterminedShouldRun = newShouldRunDecision

            // React to the shouldRun condition change.
            if (newShouldRunDecision) {
                // Start syncthing.
                when (this.currentState) {
                    State.DISABLED, State.INIT ->                         // HACK: Make sure there is no syncthing binary left running from an improper
                        // shutdown (eg Play Store update).
                        shutdown(State.INIT) { this.launchStartupTask() }

                    State.STARTING, State.ACTIVE, State.ERROR -> {}
                }
            } else {
                // Stop syncthing.
                if (this.currentState == State.DISABLED) {
                    return
                }
                Log.v(TAG, "Stopping syncthing")
                shutdown(State.DISABLED) {}
            }
        }
    }

    /**
     * Prepares to launch the syncthing binary.
     */
    private fun launchStartupTask() {
        Log.v(TAG, "Starting syncthing")
        synchronized(mStateLock) {
            if (this.currentState != State.INIT) {
                Log.e(
                    TAG,
                    "launchStartupTask: Wrong state " + this.currentState + " detected. Cancelling."
                )
                return
            }
        }

        // Safety check: Log warning if a previously launched startup task did not finish properly.
        if (startupTaskIsRunning()) {
            Log.w(
                TAG,
                "launchStartupTask: StartupTask is still running. Skipped starting it twice."
            )
            return
        }
        onServiceStateChange(State.STARTING)
        if (mExecutor == null) {
            mExecutor = Executors.newSingleThreadExecutor()
        }
        mStartupTaskFuture = mExecutor!!.submit(StartupTask(this))
    }

    private fun startupTaskIsRunning(): Boolean {
        return mStartupTaskFuture != null && !mStartupTaskFuture!!.isDone
    }

    /**
     * Sets up the initial configuration, and updates the config when coming from an old
     * version.
     */
    private class StartupTask(context: SyncthingService?) : Runnable {
        private val refSyncthingService: WeakReference<SyncthingService?> = WeakReference<SyncthingService?>(context)

        override fun run() {
            val syncthingService = refSyncthingService.get() ?: return
            try {
                syncthingService.mConfig = ConfigXml(syncthingService)
                syncthingService.mConfig!!.updateIfNeeded()
            } catch (_: OpenConfigException) {
                syncthingService.notificationHandler!!.showCrashedNotification(
                    R.string.config_create_failed,
                    true
                )
                synchronized(syncthingService.mStateLock) {
                    syncthingService.onServiceStateChange(
                        State.ERROR
                    )
                }
                return
            }

            // Post back to the main thread to run the completion callback
            val svc = refSyncthingService.get()
            if (svc != null && svc.mHandler != null) {
                svc.mHandler!!.post { svc.onStartupTaskCompleteListener() }
            }
        }
    }

    /**
     * Callback on [StartupTask.onPostExecute].
     */
    private fun onStartupTaskCompleteListener() {
        if (this.api == null) {
            this.api = RestApi(
                this, mConfig!!.webGuiUrl, mConfig!!.apiKey,
                { this.onApiAvailable() }, {
                    onServiceStateChange(
                        this.currentState
                    )
                })
            Log.i(TAG, "Web GUI will be available at " + mConfig!!.webGuiUrl)
        }

        // Start the syncthing binary.
        if (mSyncthingRunnable != null || mSyncthingRunnableThread != null) {
            Log.e(TAG, "onStartupTaskCompleteListener: Syncthing binary lifecycle violated")
            return
        }
        mSyncthingRunnable = SyncthingRunnable(this, SyncthingRunnable.Command.Main)
        mSyncthingRunnableThread = Thread(mSyncthingRunnable)
        mSyncthingRunnableThread!!.start()

        /*
          * Wait for the web-gui of the native syncthing binary to come online.
          *
          * In case the binary is to be stopped, also be aware that another thread could request
          * to stop the binary in the time while waiting for the GUI to become active. See the comment
          * for {@link SyncthingService#onDestroy} for details.
          */
        if (mPollWebGuiAvailableTask == null) {
            mPollWebGuiAvailableTask = PollWebGuiAvailableTask(
                this, this.webGuiUrl, mConfig!!.apiKey
            ) { _: String? ->
                Log.i(TAG, "Web GUI has come online at " + mConfig!!.webGuiUrl)
                if (this.api != null) {
                    api!!.readConfigFromRestApi()
                }
            }
        }
    }

    /**
     * Called when [RestApi.checkReadConfigFromRestApiCompleted] detects
     * the RestApi class has been fully initialized.
     * UI stressing results in mApi getting null on simultaneous shutdown, so
     * we check it for safety.
     */
    private fun onApiAvailable() {
        if (this.api == null) {
            Log.e(TAG, "onApiAvailable: Did we stop the binary during startup? mApi == null")
            return
        }
        synchronized(mStateLock) {
            if (this.currentState != State.STARTING) {
                Log.e(
                    TAG,
                    "onApiAvailable: Wrong state " + this.currentState + " detected. Cancelling callback."
                )
                return
            }
            onServiceStateChange(State.ACTIVE)
        }

        /*
         * If the service instance got an onDestroy() event while being in
         * State.STARTING we'll trigger the service onDestroy() now. this
         * allows the syncthing binary to get gracefully stopped.
         */
        if (mDestroyScheduled) {
            mDestroyScheduled = false
            stopSelf()
            return
        }

        if (mEventProcessor == null) {
            mEventProcessor = EventProcessor(this@SyncthingService, this.api)
            mEventProcessor!!.start()
        }
    }

    override fun onBind(intent: Intent?): SyncthingServiceBinder {
        return mBinder
    }

    /**
     * Stops the native binary.
     * Shuts down RunConditionMonitor instance.
     */
    override fun onDestroy() {
        Log.v(TAG, "onDestroy")
        if (mRunConditionMonitor != null) {
            /*
             * Shut down the OnDeviceStateChangedListener so we won't get interrupted by run
             * condition events that occur during shutdown.
             */
            mRunConditionMonitor!!.shutdown()
        }
        if (this.notificationHandler != null) {
            notificationHandler!!.setAppShutdownInProgress(true)
        }
        if (mStoragePermissionGranted) {
            synchronized(mStateLock) {
                if (this.currentState == State.STARTING) {
                    Log.i(TAG, "Delay shutting down syncthing binary until initialisation finished")
                    mDestroyScheduled = true
                } else {
                    Log.i(TAG, "Shutting down syncthing binary immediately")
                    shutdown(State.DISABLED) {}
                }
            }
        } else {
            // If the storage permission got revoked, we did not start the binary and
            // are in State.INIT requiring an immediate shutdown of this service class.
            Log.i(TAG, "Shutting down syncthing binary due to missing storage permission.")
            shutdown(State.DISABLED) {}
        }
        super.onDestroy()
        if (mExecutor != null) {
            mExecutor!!.shutdownNow()
            mExecutor = null
        }
    }

    /**
     * Stop Syncthing and all helpers like event processor and api handler.
     *
     *
     * Sets [.mCurrentState] to newState, and calls onKilledListener once Syncthing is killed.
     */
    private fun shutdown(newState: State, onKilledListener: OnSyncthingKilled) {
        Log.i(TAG, "Shutting down background service")
        synchronized(mStateLock) {
            onServiceStateChange(newState)
        }

        if (mPollWebGuiAvailableTask != null) {
            mPollWebGuiAvailableTask!!.cancelRequestsAndCallback()
            mPollWebGuiAvailableTask = null
        }

        if (mEventProcessor != null) {
            mEventProcessor!!.stop()
            mEventProcessor = null
        }

        if (this.api != null) {
            api!!.shutdown()
            this.api = null
        }

        if (mSyncthingRunnable != null) {
            mSyncthingRunnable!!.killSyncthing()
            if (mSyncthingRunnableThread != null) {
                Log.v(TAG, "Waiting for mSyncthingRunnableThread to finish after killSyncthing ...")
                try {
                    mSyncthingRunnableThread!!.join()
                } catch (_: InterruptedException) {
                    Log.w(TAG, "mSyncthingRunnableThread InterruptedException")
                }
                Log.v(TAG, "Finished mSyncthingRunnableThread.")
                mSyncthingRunnableThread = null
            }
            mSyncthingRunnable = null
        }
        if (startupTaskIsRunning()) {
            mStartupTaskFuture!!.cancel(true)
            Log.v(TAG, "Waiting for mStartupTask to finish after cancelling ...")
            try {
                mStartupTaskFuture!!.get()
            } catch (_: Exception) {
            }
            mStartupTaskFuture = null
        }
        onKilledListener.onKilled()
    }

    /**
     * Force re-evaluating run conditions immediately e.g. after
     * preferences were modified by [SettingsActivity].
     */
    fun evaluateRunConditions() {
        if (mRunConditionMonitor == null) {
            return
        }
        Log.v(TAG, "Forced re-evaluating run conditions ...")
        mRunConditionMonitor!!.updateShouldRunDecision()
    }

    /**
     * Register a listener for the syncthing API state changing.
     *
     *
     * The listener is called immediately with the current state, and again whenever the state
     * changes. The call is always from the GUI thread.
     *
     * @see .unregisterOnServiceStateChangeListener
     */
    fun registerOnServiceStateChangeListener(listener: OnServiceStateChangeListener) {
        // Make sure we don't send an invalid state or syncthing might show a "disabled" message
        // when it's just starting up.
        listener.onServiceStateChange(this.currentState)
        mOnServiceStateChangeListeners.add(listener)
    }

    /**
     * Unregisters a previously registered listener.
     *
     * @see .registerOnServiceStateChangeListener
     */
    fun unregisterOnServiceStateChangeListener(listener: OnServiceStateChangeListener?) {
        mOnServiceStateChangeListeners.remove(listener)
    }

    /**
     * Called to notify listeners of an API change.
     */
    private fun onServiceStateChange(newState: State) {
        Log.v(TAG, "onServiceStateChange: from " + this.currentState + " to " + newState)
        this.currentState = newState
        mHandler!!.post {
            notificationHandler!!.updatePersistentNotification(this)
            val i = mOnServiceStateChangeListeners.iterator()
            while (i.hasNext()) {
                val listener = i.next()
                if (listener != null) {
                    listener.onServiceStateChange(this.currentState)
                } else {
                    i.remove()
                }
            }
        }
    }

    fun registerOnRunConditionCheckResultChange(listener: OnRunConditionCheckResultListener) {
        listener.onRunConditionCheckResultChanged(mCurrentCheckResult.get())
        mOnRunConditionCheckResultListeners.add(listener)
    }

    fun unregisterOnRunConditionCheckResultChange(listener: OnRunConditionCheckResultListener?) {
        mOnRunConditionCheckResultListeners.remove(listener)
    }

    private fun onRunConditionCheckResultChange(result: RunConditionCheckResult?) {
        mHandler!!.post {
            val i = mOnRunConditionCheckResultListeners.iterator()
            while (i.hasNext()) {
                val listener = i.next()
                if (listener != null) {
                    listener.onRunConditionCheckResultChanged(result)
                } else {
                    i.remove()
                }
            }
        }
    }


    val webGuiUrl: URL
        get() = mConfig!!.webGuiUrl

    val currentRunConditionCheckResult: RunConditionCheckResult?
        get() = mCurrentCheckResult.get()

    /**
     * Exports the local config and keys to [Constants.EXPORT_PATH].
     */
    fun exportConfig() {
        Constants.EXPORT_PATH.mkdirs()
        try {
            try {
                Constants.getConfigFile(this).copyTo(File(Constants.EXPORT_PATH, Constants.CONFIG_FILE), overwrite = true)
                Constants.getPrivateKeyFile(this).copyTo(File(Constants.EXPORT_PATH, Constants.PRIVATE_KEY_FILE), overwrite = true)
                Constants.getPublicKeyFile(this).copyTo(File(Constants.EXPORT_PATH, Constants.PUBLIC_KEY_FILE), overwrite = true)
                Constants.getHttpsCertFile(this).copyTo(File(Constants.EXPORT_PATH, Constants.HTTPS_CERT_FILE), overwrite = true)
                Constants.getHttpsKeyFile(this).copyTo(File(Constants.EXPORT_PATH, Constants.HTTPS_KEY_FILE), overwrite = true)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to export config", e)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to export config", e)
        }
    }

    /**
     * Imports config and keys from [Constants.EXPORT_PATH].
     *
     * @return True if the import was successful, false otherwise (eg if files aren't found).
     */
    fun importConfig(): Boolean {
        val config = File(Constants.EXPORT_PATH, Constants.CONFIG_FILE)
        val privateKey = File(Constants.EXPORT_PATH, Constants.PRIVATE_KEY_FILE)
        val publicKey = File(Constants.EXPORT_PATH, Constants.PUBLIC_KEY_FILE)
        val httpsCert = File(Constants.EXPORT_PATH, Constants.HTTPS_CERT_FILE)
        val httpsKey = File(Constants.EXPORT_PATH, Constants.HTTPS_KEY_FILE)
        if (!config.exists() || !privateKey.exists() || !publicKey.exists()) return false
        shutdown(State.INIT) {
                try {
                    config.copyTo(Constants.getConfigFile(this), overwrite = true)
                    privateKey.copyTo(Constants.getPrivateKeyFile(this), overwrite = true)
                    publicKey.copyTo(Constants.getPublicKeyFile(this), overwrite = true)
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to import config", e)
                }
            if (httpsCert.exists() && httpsKey.exists()) {
                try {
                    httpsCert.copyTo(Constants.getHttpsCertFile(this), overwrite = true)
                    httpsKey.copyTo(Constants.getHttpsKeyFile(this), overwrite = true)
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to import HTTPS config files", e)
                }
            }
            launchStartupTask()
        }
        return true
    }

    companion object {
        private const val TAG = "SyncthingService"

        /**
         * Intent action to perform a Syncthing restart.
         */
        const val ACTION_RESTART: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.RESTART"

        /**
         * Intent action to reset Syncthing's database.
         */
        const val ACTION_RESET_DATABASE: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.RESET_DATABASE"

        /**
         * Intent action to reset Syncthing's delta indexes.
         */
        const val ACTION_RESET_DELTAS: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.RESET_DELTAS"

        const val ACTION_REFRESH_NETWORK_INFO: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.REFRESH_NETWORK_INFO"

        /**
         * Intent action to permanently ignore a device connection request.
         */
        const val ACTION_IGNORE_DEVICE: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.IGNORE_DEVICE"

        /**
         * Intent action to permanently ignore a folder share request.
         */
        const val ACTION_IGNORE_FOLDER: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.IGNORE_FOLDER"

        /**
         * Intent action to override folder changes.
         */
        const val ACTION_OVERRIDE_CHANGES: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.OVERRIDE_CHANGES"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE, ACTION_IGNORE_FOLDER.
         */
        const val EXTRA_NOTIFICATION_ID: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.EXTRA_NOTIFICATION_ID"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE
         */
        const val EXTRA_DEVICE_ID: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.EXTRA_DEVICE_ID"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE
         */
        const val EXTRA_DEVICE_NAME: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.EXTRA_DEVICE_NAME"

        /**
         * Extra used together with ACTION_IGNORE_DEVICE
         */
        const val EXTRA_DEVICE_ADDRESS: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.EXTRA_DEVICE_ADDRESS"

        /**
         * Extra used together with ACTION_IGNORE_FOLDER
         */
        const val EXTRA_FOLDER_ID: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.EXTRA_FOLDER_ID"

        /**
         * Extra used together with ACTION_IGNORE_FOLDER
         */
        const val EXTRA_FOLDER_LABEL: String =
            "com.nutomic.syncthingandroid.service.SyncthingService.EXTRA_FOLDER_LABEL"
    }
}
