package com.nutomic.syncthingandroid.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.util.Consumer
import com.annimon.stream.Stream
import com.annimon.stream.function.Predicate
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.model.CompletionInfo
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.RestApi.OnReceiveEventListener
import java.io.File
import java.util.Objects
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.Volatile
import androidx.core.content.edit

/**
 * Run by the syncthing service to convert syncthing events into local broadcasts.
 *
 *
 * It uses [RestApi.getEvents] to read the pending events and wait for new events.
 */
class EventProcessor(context: Context, api: RestApi?) : Runnable, OnReceiveEventListener {
    /**
     * Use the MainThread for all callbacks and message handling
     * or we have to track down nasty threading problems.
     */
    private val mMainThreadHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var mLastEventId: Long = 0

    @Volatile
    private var mShutdown = true

    private val mContext: Context
    private val mApi: RestApi?

    @Inject
    var mPreferences: SharedPreferences? = null

    @Inject
    var mNotificationHandler: NotificationHandler? = null

    init {
        (context.applicationContext as SyncthingApp).component()!!.inject(this)
        mContext = context
        mApi = api
    }

    override fun run() {
        // Restore the last event id if the event processor may have been restarted.
        if (mLastEventId == 0L) {
            mLastEventId = mPreferences!!.getLong(PREF_LAST_SYNC_ID, 0)
        }

        // First check if the event number ran backwards.
        // If that's the case we've to start at zero because syncthing was restarted.
        mApi!!.getEvents(0, 1, object : OnReceiveEventListener {
            override fun onEvent(event: Event?) {
            }

            override fun onDone(lastId: Long) {
                if (lastId < mLastEventId) mLastEventId = 0

                Log.d(TAG, "Reading events starting with id $mLastEventId")

                mApi.getEvents(mLastEventId, 0, this@EventProcessor)
            }
        })
    }

    /**
     * Performs the actual event handling.
     */
    override fun onEvent(event: Event?) {
        var mapData: MutableMap<String?, Any?>? = null
        try {
            mapData = event?.data as MutableMap<String?, Any?>?
        } catch (_: ClassCastException) {
        }
        when (event?.type) {
            "ConfigSaved" -> if (mApi != null) {
                Log.v(TAG, "Forwarding ConfigSaved event to RestApi to get the updated config.")
                mApi.reloadConfig()
            }

            "PendingDevicesChanged" -> {
                mapNullable<MutableMap<String?, String?>?>(
                    mapData!!["added"] as MutableList<MutableMap<String?, String?>?>?
                ) { added: MutableMap<String?, String?>? ->
                    this.onPendingDevicesChanged(added!!)
                }
            }

            "FolderCompletion" -> {
                val completionInfo = CompletionInfo()
                completionInfo.completion = (mapData.get("completion") as Double?)!!
                mApi!!.setCompletionInfo(
                    mapData!!["device"] as String?,  // deviceId
                    mapData["folder"] as String?,  // folderId
                    completionInfo
                )
            }

            "PendingFoldersChanged" -> {
                mapNullable<MutableMap<String?, String?>?>(
                    mapData!!["added"] as MutableList<MutableMap<String?, String?>?>?
                ) { added: MutableMap<String?, String?>? ->
                    this.onPendingFoldersChanged(added!!)
                }
            }

            "ItemFinished" -> {
                val folder = mapData!!["folder"] as String?
                var folderPath: String? = null
                for (f in mApi!!.folders) {
                    if (f?.id == folder) {
                        folderPath = f?.path
                    }
                }
                val updatedFile =
                    File(folderPath, Objects.requireNonNull<Any?>(mapData["item"]) as String)
                if ("delete" != mapData["action"]) {
                    Log.i(TAG, "Rescanned file via MediaScanner: $updatedFile")
                    MediaScannerConnection.scanFile(
                        mContext, arrayOf<String>(updatedFile.path),
                        null, null
                    )
                } else {
                    // Starting with Android 10/Q and targeting API level 29/removing legacy storage flag,
                    // reports of files being spuriously deleted came up.
                    // Best guess is that Syncthing directly interacted with the filesystem before,
                    // and there's a virtualization layer there now. Also there's reports this API
                    // changed behaviour with scoped storage. In any case it now does not only
                    // update the media db, but actually delete the file on disk. Which is bad,
                    // as it can race with the creation of the same file and thus delete it. See:
                    // https://github.com/syncthing/syncthing-android/issues/1801
                    // https://github.com/syncthing/syncthing/issues/7974
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        return
                    }
                    // https://stackoverflow.com/a/29881556/1837158
                    Log.i(TAG, "Deleted file from MediaStore: $updatedFile")
                    val contentUri = MediaStore.Files.getContentUri("external")
                    val resolver = mContext.contentResolver
                    resolver.delete(
                        contentUri, MediaStore.Images.ImageColumns.DATA + " = ?",
                        arrayOf<String>(updatedFile.path)
                    )
                }
            }

            "Ping" -> {}
            "DeviceConnected", "DeviceDisconnected", "DeviceDiscovered", "DownloadProgress", "FolderPaused", "FolderScanProgress", "FolderSummary", "ItemStarted", "LocalIndexUpdated", "LoginAttempt", "RemoteDownloadProgress", "RemoteIndexUpdated", "Starting", "StartupComplete", "StateChanged" -> {}
            else -> Log.v(TAG, "Unhandled event " + event?.type)
        }
    }

    override fun onDone(lastId: Long) {
        if (mLastEventId < lastId) {
            mLastEventId = lastId

            // Store the last EventId in case we get killed
            mPreferences!!.edit { putLong(PREF_LAST_SYNC_ID, mLastEventId) }
        }

        synchronized(mMainThreadHandler) {
            if (!mShutdown) {
                mMainThreadHandler.removeCallbacks(this)
                mMainThreadHandler.postDelayed(this, EVENT_UPDATE_INTERVAL)
            }
        }
    }

    fun start() {
        Log.d(TAG, "Starting event processor.")

        // Remove all pending callbacks and add a new one. This makes sure that only one
        // event poller is running at any given time.
        synchronized(mMainThreadHandler) {
            mShutdown = false
            mMainThreadHandler.removeCallbacks(this)
            mMainThreadHandler.postDelayed(this, EVENT_UPDATE_INTERVAL)
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping event processor.")
        synchronized(mMainThreadHandler) {
            mShutdown = true
            mMainThreadHandler.removeCallbacks(this)
        }
    }

    private fun onPendingDevicesChanged(added: MutableMap<String?, String?>) {
        val deviceId = added["deviceID"]
        val deviceName = added["name"]
        val deviceAddress = added["address"]
        if (deviceId == null) {
            return
        }
        Log.d(TAG, "Unknown device $deviceName($deviceId) wants to connect")

        checkNotNull(deviceName)
        val title = mContext.getString(
            R.string.device_rejected,
            deviceName.ifEmpty { deviceId.take(7) }
        )
        val notificationId = mNotificationHandler!!.getNotificationIdFromText(title)

        // Prepare "accept" action.
        val intentAccept = Intent(mContext, DeviceActivity::class.java)
            .putExtra(DeviceActivity.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(DeviceActivity.EXTRA_IS_CREATE, true)
            .putExtra(DeviceActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(DeviceActivity.EXTRA_DEVICE_NAME, deviceName)
        val piAccept = PendingIntent.getActivity(
            mContext, notificationId,
            intentAccept, Constants.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Prepare "ignore" action.
        val intentIgnore = Intent(mContext, SyncthingService::class.java)
            .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
            .putExtra(SyncthingService.EXTRA_DEVICE_NAME, deviceName)
            .putExtra(SyncthingService.EXTRA_DEVICE_ADDRESS, deviceAddress)
        intentIgnore.setAction(SyncthingService.ACTION_IGNORE_DEVICE)
        val piIgnore = PendingIntent.getService(
            mContext, 0,
            intentIgnore, Constants.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Show notification.
        mNotificationHandler!!.showConsentNotification(notificationId, title, piAccept, piIgnore)
    }

    private fun onPendingFoldersChanged(added: MutableMap<String?, String?>) {
        val deviceId = added["deviceID"]
        val folderId = added["folderID"]
        val folderLabel = added["folderLabel"]
        if (deviceId == null || folderId == null) {
            return
        }
        Log.d(
            TAG, "Device " + deviceId + " wants to share folder " +
                    folderLabel + " (" + folderId + ")"
        )

        // Find the deviceName corresponding to the deviceId
        var deviceName: String? = null
        for (d in mApi!!.getDevices(false)) {
            if (d.deviceID == deviceId) {
                deviceName = d.displayName
                break
            }
        }
        checkNotNull(folderLabel)
        val title = mContext.getString(
            R.string.folder_rejected, deviceName,
            if (folderLabel.isEmpty()) folderId else "$folderLabel ($folderId)"
        )
        val notificationId = mNotificationHandler!!.getNotificationIdFromText(title)

        // Prepare "accept" action.
        val isNewFolder = Stream.of<Folder?>(mApi.folders)
            .noneMatch(Predicate { f: Folder? -> f!!.id == folderId })
        val intentAccept = Intent(mContext, FolderActivity::class.java)
            .putExtra(FolderActivity.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(FolderActivity.EXTRA_IS_CREATE, isNewFolder)
            .putExtra(FolderActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(FolderActivity.EXTRA_FOLDER_ID, folderId)
            .putExtra(FolderActivity.EXTRA_FOLDER_LABEL, folderLabel)
        val piAccept = PendingIntent.getActivity(
            mContext, notificationId,
            intentAccept, Constants.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Prepare "ignore" action.
        val intentIgnore = Intent(mContext, SyncthingService::class.java)
            .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
            .putExtra(SyncthingService.EXTRA_FOLDER_ID, folderId)
            .putExtra(SyncthingService.EXTRA_FOLDER_LABEL, folderLabel)
        intentIgnore.setAction(SyncthingService.ACTION_IGNORE_FOLDER)
        val piIgnore = PendingIntent.getService(
            mContext, 0,
            intentIgnore, Constants.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Show notification.
        mNotificationHandler!!.showConsentNotification(notificationId, title, piAccept, piIgnore)
    }

    private fun <T> mapNullable(l: MutableList<T?>?, c: Consumer<T?>) {
        if (l != null) {
            for (m in l) {
                c.accept(m)
            }
        }
    }

    companion object {
        private const val TAG = "EventProcessor"
        private const val PREF_LAST_SYNC_ID = "last_sync_id"

        /**
         * Minimum interval in seconds at which the events are polled from syncthing and processed.
         * This intervall will not wake up the device to save battery power.
         */
        private val EVENT_UPDATE_INTERVAL = TimeUnit.SECONDS.toMillis(15)
    }
}
