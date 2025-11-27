package com.nutomic.syncthingandroid.views

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.preference.MultiSelectListPreference
import android.util.AttributeSet
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.PermissionUtil.locationPermissions
import java.util.TreeSet

/**
 * MultiSelectListPreference which allows the user to select on which WiFi networks (based on SSID)
 * syncing should be allowed.
 *
 * Setting can be "All networks" (none selected), or selecting individual networks.
 *
 * Due to restrictions in Android, it is possible/likely, that the list of saved WiFi networks
 * cannot be retrieved if the WiFi is turned off. In this case, an explanation is shown.
 *
 * The preference is stored as Set&lt;String&gt; where an empty set represents
 * "all networks allowed".
 *
 * SSIDs are formatted according to the naming convention of WifiManager, i.e. they have the
 * surrounding double-quotes (") for UTF-8 names, or they are hex strings (if not quoted).
 */
class WifiSsidPreference @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null) :
    MultiSelectListPreference(context!!, attrs) {
    init {
        setDefaultValue(TreeSet<String?>())
    }

    /**
     * Show the dialog if WiFi is available and configured networks can be loaded.
     * Otherwise will display a Toast requesting to turn on WiFi.
     *
     *
     * On opening of the dialog, will also remove any SSIDs from the set that have been removed
     * by the user in the WiFi manager. This change will be persisted only if the user changes
     * any other setting
     */
    fun showDialog(state: Bundle?) {
        val context = getContext()

        var selected: MutableSet<String?> =
            getSharedPreferences()?.getStringSet(key, HashSet<String?>())!!
        // from JavaDoc: Note that you must not modify the set instance returned by this call.
        // therefore required to make a defensive copy of the elements
        selected = HashSet(selected)
        val all: MutableList<String?> = ArrayList(selected)

        var connected = false
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        if (wifiManager != null) {
            val info = wifiManager.connectionInfo
            if (info != null) {
                val ssid = info.getSSID()
                // api lvl 30 will have WifiManager.UNKNOWN_SSID
                if (ssid != null && ssid !== "" && !ssid.contains("unknown ssid")) {
                    if (!selected.contains(ssid)) {
                        all.add(ssid)
                    }
                    connected = true
                }
            }
        }

        val hasPerms = hasLocationPermissions()
        if (!connected) {
            if (!hasPerms) {
                Toast.makeText(
                    context,
                    R.string.sync_only_wifi_ssids_need_to_grant_location_permission,
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    context,
                    R.string.sync_only_wifi_ssids_connect_to_wifi,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        if (!all.isEmpty()) {
            entries = stripQuotes(all) // display without surrounding quotes
            entryValues = all.toTypedArray<CharSequence?>() // the value of the entry is the SSID "as is"
            setValues(selected) // the currently selected values (without meanwhile deleted networks)
            //super.showDialog(state)
        }

        if (!hasPerms && context is Activity) {
            ActivityCompat.requestPermissions(
                context,
                locationPermissions,
                Constants.PermissionRequestType.LOCATION.ordinal
            )
        }
    }

    /**
     * Checks if the required location permissions to obtain WiFi SSID are granted.
     */
    private fun hasLocationPermissions(): Boolean {
        val perms: Array<String?> = locationPermissions
        for (perm in perms) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    perm!!
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Returns a copy of the given WiFi SSIDs with quotes stripped.
     *
     * @param ssids the list of ssids to strip quotes from
     */
    private fun stripQuotes(ssids: MutableList<String?>): Array<CharSequence?> {
        val result = arrayOfNulls<CharSequence>(ssids.size)
        for (i in ssids.indices) {
            result[i] =
                ssids[i]!!.replaceFirst("^\"".toRegex(), "").replaceFirst("\"$".toRegex(), "")
        }
        return result
    }
}
