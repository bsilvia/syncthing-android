package com.nutomic.syncthingandroid.model

@Suppress("unused")
class Options {
    @JvmField
    var listenAddresses: Array<String?>? = arrayOf()
    @JvmField
    var globalAnnounceServers: Array<String?>? = arrayOf()
    @JvmField
    var globalAnnounceEnabled: Boolean = false
    @JvmField
    var localAnnounceEnabled: Boolean = false
    var localAnnouncePort: Int = 0
    var localAnnounceMCAddr: String? = null
    @JvmField
    var maxSendKbps: Int = 0
    @JvmField
    var maxRecvKbps: Int = 0
    var reconnectionIntervalS: Int = 0
    @JvmField
    var relaysEnabled: Boolean = false
    var relayReconnectIntervalM: Int = 0
    var startBrowser: Boolean = false
    @JvmField
    var natEnabled: Boolean = false
    var natLeaseMinutes: Int = 0
    var natRenewalMinutes: Int = 0
    var natTimeoutSeconds: Int = 0
    @JvmField
    var urAccepted: Int = 0
    var urUniqueId: String? = null
    var urURL: String? = null
    var urPostInsecurely: Boolean = false
    var urInitialDelayS: Int = 0
    var autoUpgradeIntervalH: Int = 0
    var keepTemporariesH: Int = 0
    var cacheIgnoredFiles: Boolean = false
    var progressUpdateIntervalS: Int = 0
    var symlinksEnabled: Boolean = false
    var limitBandwidthInLan: Boolean = false
    var minHomeDiskFreePct: Int = 0
    var releasesURL: String? = null
//    var alwaysLocalNets: Array<String?>?
    var overwriteRemoteDeviceNamesOnConnect: Boolean = false
    var tempIndexMinBlocks: Int = 0

    fun isUsageReportingAccepted(urVersionMax: Int): Boolean {
        return urAccepted == urVersionMax
    }

    fun isUsageReportingDecided(urVersionMax: Int): Boolean {
        return isUsageReportingAccepted(urVersionMax) || urAccepted == USAGE_REPORTING_DENIED
    }

    companion object {
        const val USAGE_REPORTING_UNDECIDED: Int = 0
        const val USAGE_REPORTING_DENIED: Int = -1
    }
}
