package com.nutomic.syncthingandroid.model

import com.nutomic.syncthingandroid.service.Constants
import java.io.Serializable

@Suppress("unused")
class Folder {
    @JvmField
    var id: String? = null
    @JvmField
    var label: String? = null
    var filesystemType: String = "basic"
    @JvmField
    var path: String? = null
    @JvmField
    var type: String = Constants.FOLDER_TYPE_SEND_RECEIVE
    @JvmField
    var fsWatcherEnabled: Boolean = true
    @JvmField
    var fsWatcherDelayS: Int = 10
    private val devices: MutableList<Device> = ArrayList<Device>()
    @JvmField
    var rescanIntervalS: Int = 0
    val ignorePerms: Boolean = true
    var autoNormalize: Boolean = true
    var minDiskFree: MinDiskFree? = null
    @JvmField
    var versioning: Versioning? = null
    var copiers: Int = 0
    var pullerMaxPendingKiB: Int = 0
    var hashers: Int = 0
    @JvmField
    var order: String? = null
    var ignoreDelete: Boolean = false
    var scanProgressIntervalS: Int = 0
    var pullerPauseS: Int = 0
    var maxConflicts: Int = 10
    var disableSparseFiles: Boolean = false
    var disableTempIndexes: Boolean = false
    @JvmField
    var paused: Boolean = false
    var useLargeBlocks: Boolean = false
    var weakHashThresholdPct: Int = 25
    var markerName: String = ".stfolder"
    @JvmField
    var invalid: String? = null

    class Versioning : Serializable {
        @JvmField
        var type: String? = null
        @JvmField
        var params: MutableMap<String?, String?> = HashMap()
    }

    class MinDiskFree {
        var value: Float = 0f
        var unit: String? = null
    }

    fun addDevice(deviceId: String) {
        val d = Device()
        d.deviceID = deviceId
        devices.add(d)
    }

    fun getDevice(deviceId: String?): Device? {
        for (d in devices) {
            if (d.deviceID == deviceId) {
                return d
            }
        }
        return null
    }

    fun removeDevice(deviceId: String?) {
        val it = devices.iterator()
        while (it.hasNext()) {
            val currentId = it.next().deviceID!!
            if (currentId == deviceId) {
                it.remove()
            }
        }
    }

    override fun toString(): String {
        return (if (!android.text.TextUtils.isEmpty(label)) label else id)!!
    }

    class Device {
        var deviceID: String? = null
        var introducedBy: String? = null
        var encryptionPassword: String? = null
    }
}
