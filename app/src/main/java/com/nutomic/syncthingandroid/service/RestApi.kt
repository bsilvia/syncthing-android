package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.common.base.Function
import com.google.common.base.Objects
import com.google.common.base.Optional
import com.google.common.collect.ImmutableMap
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.ShareActivity
import com.nutomic.syncthingandroid.http.ApiRequest.OnSuccessListener
import com.nutomic.syncthingandroid.http.GetRequest
import com.nutomic.syncthingandroid.http.PostConfigRequest
import com.nutomic.syncthingandroid.http.PostRequest
import com.nutomic.syncthingandroid.model.Completion
import com.nutomic.syncthingandroid.model.CompletionInfo
import com.nutomic.syncthingandroid.model.Config
import com.nutomic.syncthingandroid.model.Config.Gui
import com.nutomic.syncthingandroid.model.Connections
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.model.IgnoredFolder
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.model.RemoteIgnoredDevice
import com.nutomic.syncthingandroid.model.SystemInfo
import com.nutomic.syncthingandroid.model.SystemVersion
import java.lang.reflect.Type
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Provides functions to interact with the syncthing REST API.
 */
class RestApi(
    context: Context, url: URL?, apiKey: String?, apiListener: OnApiAvailableListener,
    configListener: OnConfigChangedListener
) {
    fun interface OnConfigChangedListener {
        fun onConfigChanged()
    }

    fun interface OnResultListener1<T> {
        fun onResult(t: T?)
    }

    fun interface OnResultListener2<T, R> {
        fun onResult(t: T?, r: R?)
    }

    private val mContext: Context
    val url: URL?
    private val mApiKey: String?

    private var mVersion: String? = null
    private var mConfig: Config? = null

    /**
     * Results cached from systemInfo
     */
    private var mLocalDeviceId: String? = null
    private var mUrVersionMax: Int? = null

    /**
     * Stores the result of the last successful request to [GetRequest.URI_CONNECTIONS],
     * or an empty Map.
     */
    private var mPreviousConnections = Optional.absent<Connections>()

    /**
     * Stores the timestamp of the last successful request to [GetRequest.URI_CONNECTIONS].
     */
    private var mPreviousConnectionTime: Long = 0

    /**
     * In the last-finishing [.readConfigFromRestApi] callback, we have to call
     * [SyncthingService.onApiAvailable]} to indicate that the RestApi class is fully initialized.
     * We do this to avoid getting stuck with our main thread due to synchronous REST queries.
     * The correct indication of full initialisation is crucial to stability as other listeners of
     * [SettingsActivity.onServiceStateChange] needs cached config and system information available.
     * e.g. SettingsFragment need "mLocalDeviceId"
     */
    private var asyncQueryConfigComplete = false
    private var asyncQueryVersionComplete = false
    private var asyncQuerySystemInfoComplete = false

    /**
     * Object that must be locked upon accessing the following variables:
     * asyncQueryConfigComplete, asyncQueryVersionComplete, asyncQuerySystemInfoComplete
     */
    private val mAsyncQueryCompleteLock = Any()

    /**
     * Object that must be locked upon accessing mConfig
     */
    private val mConfigLock = Any()

    /**
     * Stores the latest result of [.getFolderStatus] for each folder
     */
    private val mCachedFolderStatuses = HashMap<String?, FolderStatus?>()

    /**
     * Stores the latest result of device and folder completion events.
     */
    private val mCompletion = Completion()

    @JvmField
    @Inject
    var mNotificationHandler: NotificationHandler? = null

    fun interface OnApiAvailableListener {
        fun onApiAvailable()
    }

    private val mOnApiAvailableListener: OnApiAvailableListener

    private val mOnConfigChangedListener: OnConfigChangedListener

    init {
        (context.applicationContext as SyncthingApp).component()!!.inject(this)
        mContext = context
        this.url = url
        mApiKey = apiKey
        mOnApiAvailableListener = apiListener
        mOnConfigChangedListener = configListener
    }

    /**
     * Gets local device ID, syncthing version and config, then calls all OnApiAvailableListeners.
     */
    fun readConfigFromRestApi() {
        Log.v(TAG, "Reading config from REST ...")
        synchronized(mAsyncQueryCompleteLock) {
            asyncQueryVersionComplete = false
            asyncQueryConfigComplete = false
            asyncQuerySystemInfoComplete = false
        }
        GetRequest(
            mContext,
            this.url,
            GetRequest.URI_VERSION,
            mApiKey,
            null
        ) { result: String? ->
            val json = JsonParser.parseString(result).getAsJsonObject()
            mVersion = json.get("version").asString
            Log.i(TAG, "Syncthing version is $mVersion")
            updateDebugFacilitiesCache()
            synchronized(mAsyncQueryCompleteLock) {
                asyncQueryVersionComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
        GetRequest(
            mContext,
            this.url,
            GetRequest.URI_CONFIG,
            mApiKey,
            null
        ) { result: String? ->
            onReloadConfigComplete(result)
            synchronized(mAsyncQueryCompleteLock) {
                asyncQueryConfigComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
        getSystemInfo { info: SystemInfo? ->
            mLocalDeviceId = info!!.myID
            mUrVersionMax = info.urVersionMax
            synchronized(mAsyncQueryCompleteLock) {
                asyncQuerySystemInfoComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
    }

    private fun checkReadConfigFromRestApiCompleted() {
        if (asyncQueryVersionComplete && asyncQueryConfigComplete && asyncQuerySystemInfoComplete) {
            Log.v(TAG, "Reading config from REST completed.")
            mOnApiAvailableListener.onApiAvailable()
        }
    }

    fun reloadConfig() {
        GetRequest(
            mContext,
            this.url,
            GetRequest.URI_CONFIG,
            mApiKey,
            null
        ) { result: String? -> this.onReloadConfigComplete(result) }
    }

    private fun onReloadConfigComplete(result: String?) {
        val configParseSuccess: Boolean
        synchronized(mConfigLock) {
            mConfig = Gson().fromJson(result, Config::class.java)
            configParseSuccess = mConfig != null
        }
        if (!configParseSuccess) {
            throw RuntimeException("config is null: $result")
        }
        Log.v(TAG, "onReloadConfigComplete: Successfully parsed configuration.")

        //        if (BuildConfig.DEBUG) {
//            Log.v(TAG, "mConfig.remoteIgnoredDevices = " + new Gson().toJson(mConfig.remoteIgnoredDevices));
//        }

        // Update cached device and folder information stored in the mCompletion model.
        mCompletion.updateFromConfig(getDevices(true), this.folders)
    }

    /**
     * Queries debug facilities available from the currently running syncthing binary
     * if the syncthing binary version changed. First launch of the binary is also
     * considered as a version change.
     * Precondition: [.mVersion] read from REST
     */
    private fun updateDebugFacilitiesCache() {
        val PREF_LAST_BINARY_VERSION = "lastBinaryVersion"
        if (mVersion != PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(PREF_LAST_BINARY_VERSION, "")
        ) {
            // First binary launch or binary upgraded case.
            GetRequest(
                mContext,
                this.url,
                GetRequest.URI_DEBUG,
                mApiKey,
                null
            ) { result: String? ->
                try {
                    val json = JsonParser.parseString(result).getAsJsonObject()
                    val jsonFacilities = json.getAsJsonObject("facilities")
                    val facilitiesToStore: MutableSet<String?> =
                        HashSet(jsonFacilities.keySet())
                    PreferenceManager.getDefaultSharedPreferences(mContext).edit {
                        putStringSet(
                            Constants.PREF_DEBUG_FACILITIES_AVAILABLE,
                            facilitiesToStore
                        )
                    }

                    // Store current binary version so we will only store this information again
                    // after a binary update.
                    PreferenceManager.getDefaultSharedPreferences(mContext).edit {
                        putString(PREF_LAST_BINARY_VERSION, mVersion)
                    }
                } catch (_: Exception) {
                    Log.w(
                        TAG,
                        "updateDebugFacilitiesCache: Failed to get debug facilities. result=$result"
                    )
                }
            }
        }
    }

    /**
     * Permanently ignore a device when it tries to connect.
     * Ignored devices will not trigger the "DeviceRejected" event
     * in [EventProcessor.onEvent].
     */
    fun ignoreDevice(deviceId: String, deviceName: String?, deviceAddress: String?) {
        synchronized(mConfigLock) {
            // Check if the device has already been ignored.
            for (remoteIgnoredDevice in mConfig!!.remoteIgnoredDevices!!) {
                if (deviceId == remoteIgnoredDevice?.deviceID) {
                    // Device already ignored.
                    Log.d(TAG, "Device already ignored [$deviceId]")
                    return
                }
            }

            val remoteIgnoredDevice = RemoteIgnoredDevice()
            remoteIgnoredDevice.deviceID = deviceId
            remoteIgnoredDevice.address = deviceAddress!!
            remoteIgnoredDevice.name = deviceName!!
            remoteIgnoredDevice.time = dateFormat.format(Date())
            mConfig!!.remoteIgnoredDevices!!.add(remoteIgnoredDevice)
            sendConfig()
            Log.d(TAG, "Ignored device [$deviceId]")
        }
    }

    /**
     * Permanently ignore a folder share request.
     * Ignored folders will not trigger the "FolderRejected" event
     * in [EventProcessor.onEvent].
     */
    fun ignoreFolder(deviceId: String, folderId: String, folderLabel: String?) {
        synchronized(mConfigLock) {
            for (device in mConfig!!.devices!!) {
                if (deviceId == device.deviceID) {
                    /*
                     * Check if the folder has already been ignored.
                     */
                    for (ignoredFolder in device.ignoredFolders!!) {
                        if (folderId == ignoredFolder?.id) {
                            // Folder already ignored.
                            Log.d(
                                TAG,
                                "Folder [$folderId] already ignored on device [$deviceId]"
                            )
                            return
                        }
                    }

                    /*
                     * Ignore folder by moving its corresponding "pendingFolder" entry to
                     * a newly created "ignoredFolder" entry.
                     */
                    val ignoredFolder = IgnoredFolder()
                    ignoredFolder.id = folderId
                    ignoredFolder.label = folderLabel!!
                    ignoredFolder.time = dateFormat.format(Date())
                    device.ignoredFolders!!.add(ignoredFolder)
                    //                    if (BuildConfig.DEBUG) {
//                        Log.v(TAG, "device.ignoredFolders = " + new Gson().toJson(device.ignoredFolders));
//                    }
                    sendConfig()
                    Log.d(
                        TAG,
                        "Ignored folder [$folderId] announced by device [$deviceId]"
                    )

                    // Given deviceId handled.
                    break
                }
            }
        }
    }

    /**
     * Undo ignoring devices and folders.
     */
    fun undoIgnoredDevicesAndFolders() {
        Log.d(TAG, "Undo ignoring devices and folders ...")
        synchronized(mConfigLock) {
            mConfig!!.remoteIgnoredDevices?.clear()
            for (device in mConfig!!.devices!!) {
                device.ignoredFolders?.clear()
            }
        }
    }

    /**
     * Override folder changes. This is the same as hitting
     * the "override changes" button from the web UI.
     */
    fun overrideChanges(folderId: String) {
        Log.d(TAG, "overrideChanges '$folderId'")
        PostRequest(
            mContext,
            this.url, PostRequest.URI_DB_OVERRIDE, mApiKey,
            ImmutableMap.of("folder", folderId), null
        )
    }

    /**
     * Sends current config to Syncthing.
     * Will result in a "ConfigSaved" event.
     * EventProcessor will trigger this.reloadConfig().
     */
    private fun sendConfig() {
        val jsonConfig: String?
        synchronized(mConfigLock) {
            jsonConfig = Gson().toJson(mConfig)
        }
        PostConfigRequest(mContext, this.url, mApiKey, jsonConfig, null)
        mOnConfigChangedListener.onConfigChanged()
    }

    /**
     * Sends current config and restarts Syncthing.
     */
    fun saveConfigAndRestart() {
        val jsonConfig: String?
        synchronized(mConfigLock) {
            jsonConfig = Gson().toJson(mConfig)
        }
        PostConfigRequest(
            mContext,
            this.url,
            mApiKey,
            jsonConfig
        ) { _: String? ->
            val intent = Intent(mContext, SyncthingService::class.java)
                .setAction(SyncthingService.ACTION_RESTART)
            mContext.startService(intent)
        }
        mOnConfigChangedListener.onConfigChanged()
    }

    fun shutdown() {
        mNotificationHandler!!.cancelRestartNotification()
    }

    val version: String
        /**
         * Returns the version name, or a (text) error message on failure.
         */
        get() = mVersion!!

    val folders: MutableList<Folder?>
        get() {
            val folders: MutableList<Folder?>
            synchronized(mConfigLock) {
                folders =
                    deepCopy(
                        mConfig?.folders,
                        object :
                            com.google.common.reflect.TypeToken<MutableList<Folder>>() {}.type
                    )!!
            }
            Collections.sort<Folder?>(
                folders,
                FOLDERS_COMPARATOR
            )
            return folders
        }

    /**
     * This is only used for new folder creation, see [FolderActivity].
     */
    fun createFolder(folder: Folder?) {
        synchronized(mConfigLock) {
            // Add the new folder to the model.
            mConfig!!.folders?.add(folder)
            // Send model changes to syncthing, does not require a restart.
            sendConfig()
        }
    }

    fun updateFolder(newFolder: Folder) {
        synchronized(mConfigLock) {
            removeFolderInternal(newFolder.id)
            mConfig!!.folders?.add(newFolder)
            sendConfig()
        }
    }

    fun removeFolder(id: String?) {
        synchronized(mConfigLock) {
            removeFolderInternal(id)
            // mCompletion will be updated after the ConfigSaved event.
            sendConfig()
        }
        PreferenceManager.getDefaultSharedPreferences(mContext).edit {
            remove(ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + id)
        }
    }

    private fun removeFolderInternal(id: String?) {
        synchronized(mConfigLock) {
            val it = mConfig!!.folders?.iterator()!!
            while (it.hasNext()) {
                val f = it.next()
                if (f?.id == id) {
                    it.remove()
                    break
                }
            }
        }
    }

    /**
     * Returns a list of all existing devices.
     *
     * @param includeLocal True if the local device should be included in the result.
     */
    fun getDevices(includeLocal: Boolean): MutableList<Device> {
        val devices: MutableList<Device>
        synchronized(mConfigLock) {
            devices = deepCopy(
                mConfig!!.devices,
                object :
                    com.google.common.reflect.TypeToken<MutableList<Device>>() {}.type
            )!!
        }

        val it = devices.iterator()
        while (it.hasNext()) {
            val device = it.next()
            val isLocalDevice = Objects.equal(mLocalDeviceId, device.deviceID)
            if (!includeLocal && isLocalDevice) {
                it.remove()
                break
            }
        }
        return devices
    }

    val localDevice: Device?
        get() {
            val devices =
                getDevices(true)
            if (devices.isEmpty()) {
                throw RuntimeException("RestApi.getLocalDevice: devices is empty.")
            }
            Log.v(
                TAG,
                "getLocalDevice: Looking for local device ID $mLocalDeviceId"
            )
            for (d in devices) {
                if (d.deviceID == mLocalDeviceId) {
                    return deepCopy<Device?>(
                        d,
                        Device::class.java
                    )
                }
            }
            throw RuntimeException("RestApi.getLocalDevice: Failed to get the local device crucial to continuing execution.")
        }

    fun addDevice(device: Device, errorListener: OnResultListener1<String?>) {
        normalizeDeviceId(device.deviceID!!, { _: String? ->
            synchronized(mConfigLock) {
                mConfig!!.devices?.add(device)
                sendConfig()
            }
        }, errorListener)
    }

    fun editDevice(newDevice: Device) {
        synchronized(mConfigLock) {
            removeDeviceInternal(newDevice.deviceID)
            mConfig!!.devices?.add(newDevice)
            sendConfig()
        }
    }

    fun removeDevice(deviceId: String?) {
        synchronized(mConfigLock) {
            removeDeviceInternal(deviceId)
            // mCompletion will be updated after the ConfigSaved event.
            sendConfig()
        }
    }

    private fun removeDeviceInternal(deviceId: String?) {
        synchronized(mConfigLock) {
            val it = mConfig!!.devices?.iterator()!!
            while (it.hasNext()) {
                val d = it.next()
                if (d.deviceID == deviceId) {
                    it.remove()
                    break
                }
            }
        }
    }

    val options: Options?
        get() {
            synchronized(mConfigLock) {
                return deepCopy<Options?>(
                    mConfig!!.options,
                    Options::class.java
                )
            }
        }

    val gui: Gui?
        get() {
            synchronized(mConfigLock) {
                return deepCopy<Gui?>(mConfig!!.gui, Gui::class.java)
            }
        }

    fun editSettings(newGui: Gui?, newOptions: Options?) {
        synchronized(mConfigLock) {
            mConfig!!.gui = newGui
            mConfig!!.options = newOptions
        }
    }

    /**
     * Returns a deep copy of object.
     * This method uses Gson and only works with objects that can be converted with Gson.
     */
    private fun <T> deepCopy(`object`: T?, type: Type): T? {
        val gson = Gson()
        return gson.fromJson<T?>(gson.toJson(`object`, type), type)
    }

    /**
     * Requests and parses information about current system status and resource usage.
     */
    fun getSystemInfo(listener: OnResultListener1<SystemInfo?>) {
        GetRequest(
            mContext,
            this.url, GetRequest.URI_SYSTEM, mApiKey, null
        ) { result: String? ->
            listener.onResult(
                Gson().fromJson(result, SystemInfo::class.java)
            )
        }
    }

    val isConfigLoaded: Boolean
        get() {
            synchronized(mConfigLock) {
                return mConfig != null
            }
        }

    /**
     * Requests and parses system version information.
     */
    fun getSystemVersion(listener: OnResultListener1<SystemVersion?>) {
        GetRequest(
            mContext,
            this.url,
            GetRequest.URI_VERSION,
            mApiKey,
            null
        ) { result: String? ->
            val systemVersion =
                Gson().fromJson(result, SystemVersion::class.java)
            listener.onResult(systemVersion)
        }
    }

    /**
     * Returns connection info for the local device and all connected devices.
     */
    fun getConnections(listener: OnResultListener1<Connections?>) {
        GetRequest(
            mContext,
            this.url,
            GetRequest.URI_CONNECTIONS,
            mApiKey,
            null,
            OnSuccessListener { result: String? ->
                val now = System.currentTimeMillis()
                val msElapsed = now - mPreviousConnectionTime
                if (msElapsed < Constants.GUI_UPDATE_INTERVAL) {
                    listener.onResult(
                        deepCopy<Connections?>(
                            mPreviousConnections.get(),
                            Connections::class.java
                        )
                    )
                    return@OnSuccessListener
                }

                mPreviousConnectionTime = now
                val connections = Gson().fromJson(result, Connections::class.java)
                for (e in connections.connections?.entries!!) {
                    e.value?.completion = mCompletion.getDeviceCompletion(e.key)

                    val prev: Connections.Connection = checkNotNull(
                        if (mPreviousConnections.isPresent && mPreviousConnections.get().connections?.containsKey(
                                e.key
                            ) == true
                        )
                            mPreviousConnections.get().connections?.get(e.key)
                        else
                            Connections.Connection()
                    )
                    e.value?.setTransferRate(prev, msElapsed)
                }
                val prev =
                    mPreviousConnections.transform(Function { c: Connections -> c.total!! })
                        .or(
                            Connections.Connection()
                        )
                connections.total?.setTransferRate(prev, msElapsed)
                mPreviousConnections = Optional.of(connections)
                listener.onResult(deepCopy<Connections?>(connections, Connections::class.java))
            })
    }

    /**
     * Returns status information about the folder with the given id.
     */
    fun getFolderStatus(folderId: String, listener: OnResultListener2<String?, FolderStatus?>) {
        GetRequest(
            mContext,
            this.url,
            GetRequest.URI_STATUS,
            mApiKey,
            ImmutableMap.of("folder", folderId)
        ) { result: String? ->
            val m = Gson().fromJson(result, FolderStatus::class.java)
            mCachedFolderStatuses[folderId] = m
            listener.onResult(folderId, m)
        }
    }

    /**
     * Listener for [.getEvents].
     */
    interface OnReceiveEventListener {
        /**
         * Called for each event.
         */
        fun onEvent(event: Event?)

        /**
         * Called after all available events have been processed.
         * @param lastId The id of the last event processed. Should be used as a starting point for
         * the next round of event processing.
         */
        fun onDone(lastId: Long)
    }

    /**
     * Retrieves the events that have accumulated since the given event id.
     * The OnReceiveEventListeners onEvent method is called for each event.
     */
    fun getEvents(sinceId: Long, limit: Long, listener: OnReceiveEventListener) {
        val params: MutableMap<String, String> =
            ImmutableMap.of(
                "since",
                sinceId.toString(),
                "limit",
                limit.toString()
            )
        GetRequest(
            mContext,
            this.url,
            GetRequest.URI_EVENTS,
            mApiKey,
            params
        ) { result: String? ->
            val jsonEvents = JsonParser.parseString(result).getAsJsonArray()
            var lastId: Long = 0

            for (i in 0..<jsonEvents.size()) {
                val json = jsonEvents.get(i)
                val event = Gson().fromJson(json, Event::class.java)

                if (lastId < event.id) lastId = event.id.toLong()

                listener.onEvent(event)
            }
            listener.onDone(lastId)
        }
    }

    /**
     * Normalizes a given device ID.
     */
    private fun normalizeDeviceId(
        id: String, listener: OnResultListener1<String?>,
        errorListener: OnResultListener1<String?>
    ) {
        GetRequest(
            mContext,
            this.url, GetRequest.URI_DEVICE_ID, mApiKey,
            ImmutableMap.of<String, String>("id", id)
        ) { result: String? ->
            val json = JsonParser.parseString(result).getAsJsonObject()
            val normalizedId = json.get("id")
            val error = json.get("error")
            if (normalizedId != null) listener.onResult(normalizedId.asString)
            if (error != null) errorListener.onResult(error.asString)
        }
    }


    /**
     * Updates cached folder and device completion info according to event data.
     */
    fun setCompletionInfo(deviceId: String?, folderId: String?, completionInfo: CompletionInfo?) {
        mCompletion.setCompletionInfo(deviceId, folderId, completionInfo)
    }

    /**
     * Returns prettyfied usage report.
     */
    fun getUsageReport(listener: OnResultListener1<String?>) {
        GetRequest(
            mContext,
            this.url,
            GetRequest.URI_REPORT,
            mApiKey,
            null
        ) { result: String? ->
            val json = JsonParser.parseString(result)
            val gson = GsonBuilder().setPrettyPrinting().create()
            listener.onResult(gson.toJson(json))
        }
    }

    val isUsageReportingDecided: Boolean
        get() {
            val options = this.options
            if (options == null) {
                Log.e(
                    TAG,
                    "isUsageReportingDecided called while options == null"
                )
                return true
            }
            return options.isUsageReportingDecided(mUrVersionMax!!)
        }

    fun setUsageReporting(acceptUsageReporting: Boolean) {
        val options = this.options
        if (options == null) {
            Log.e(TAG, "setUsageReporting called while options == null")
            return
        }
        options.urAccepted =
            (if (acceptUsageReporting) mUrVersionMax else Options.USAGE_REPORTING_DENIED)!!
        synchronized(mConfigLock) {
            mConfig!!.options = options
        }
    }

    companion object {
        private const val TAG = "RestApi"

        private val dateFormat: SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

        /**
         * Compares folders by labels, uses the folder ID as fallback if the label is empty
         */
        private val FOLDERS_COMPARATOR = Comparator { lhs: Folder?, rhs: Folder? ->
            val lhsLabel =
                if (lhs!!.label != null && !lhs.label!!.isEmpty()) lhs.label else lhs.id
            val rhsLabel =
                if (rhs!!.label != null && !rhs.label!!.isEmpty()) rhs.label else rhs.id
            lhsLabel!!.compareTo(rhsLabel!!)
        }
    }
}
