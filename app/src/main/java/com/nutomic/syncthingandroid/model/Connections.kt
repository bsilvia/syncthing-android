package com.nutomic.syncthingandroid.model

import kotlin.math.max

class Connections {
    @JvmField
    var total: Connection? = null
    @JvmField
    var connections: MutableMap<String?, Connection?>? = null

    class Connection {
        @JvmField
        var paused: Boolean = false
        @JvmField
        var clientVersion: String? = null
        var at: String? = null
        @JvmField
        var connected: Boolean = false
        var inBytesTotal: Long = 0
        var outBytesTotal: Long = 0
        var type: String? = null
        @JvmField
        var address: String? = null

        // These fields are not sent from Syncthing, but are populated on the client side.
        @JvmField
        var completion: Int = 0
        @JvmField
        var inBits: Long = 0
        @JvmField
        var outBits: Long = 0

        fun setTransferRate(previous: Connection, msElapsed: Long) {
            val secondsElapsed = msElapsed / 1000
            val inBytes = 8 * (inBytesTotal - previous.inBytesTotal) / secondsElapsed
            val outBytes = 8 * (outBytesTotal - previous.outBytesTotal) / secondsElapsed
            inBits = max(0, inBytes)
            outBits = max(0, outBytes)
        }
    }
}
