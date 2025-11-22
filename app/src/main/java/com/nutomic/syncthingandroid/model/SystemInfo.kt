package com.nutomic.syncthingandroid.model

@Suppress("unused")
class SystemInfo {
    var alloc: Long = 0
    var cpuPercent: Double = 0.0
    var goroutines: Int = 0
    var myID: String? = null
    @JvmField
    var sys: Long = 0
    var discoveryEnabled: Boolean = false
    @JvmField
    var discoveryMethods: Int = 0
    @JvmField
    var discoveryErrors: MutableMap<String?, String?>? = null
    @JvmField
    var urVersionMax: Int = 0
}
