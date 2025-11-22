package com.nutomic.syncthingandroid.model

import android.text.TextUtils

class Device {
    @JvmField
    var deviceID: String? = null
    @JvmField
    var name: String = ""
    @JvmField
    var addresses: MutableList<String?>? = null
    @JvmField
    var compression: String? = null

    @JvmField
    var introducer: Boolean = false
    @JvmField
    var paused: Boolean = false
    var ignoredFolders: MutableList<IgnoredFolder?>? = null

    val displayName: String?
        /**
         * Returns the device name, or the first characters of the ID if the name is empty.
         */
        get() = if (TextUtils.isEmpty(name))
            deviceID!!.substring(0, 7)
        else
            name
}
