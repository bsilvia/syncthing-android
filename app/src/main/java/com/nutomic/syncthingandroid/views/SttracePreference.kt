package com.nutomic.syncthingandroid.views

import android.content.Context
import androidx.preference.MultiSelectListPreference
import android.util.AttributeSet
import android.util.Log
import java.util.Arrays
import java.util.TreeSet

/**
 * SttracePreference which allows the user to select which debug facilities
 * are enabled.
 *
 * Setting can be "no debug facility" (none selected), or selecting individual debug facilities.
 *
 * The preference is stored as Set&lt;String&gt; where an empty set represents
 * "no debug facility".
 *
 * Debug facilities are documented in https://docs.syncthing.net/dev/debugging.html
 *
 */
class SttracePreference @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null) :
    MultiSelectListPreference(context!!, attrs) {
    private val TAG = "SttracePreference"

    init {
        setDefaultValue(TreeSet<String?>())
    }

    /**
     * Show the dialog.
     */
//    fun showDialog(state: Bundle?) {
//        var selected: MutableSet<String?> =
//            getSharedPreferences()?.getStringSet(key, HashSet<String?>())!!
//        // from JavaDoc: Note that you must not modify the set instance returned by this call.
//        // therefore required to make a defensive copy of the elements
//        selected = HashSet(selected)
//        val all = this.debugFacilities
//        filterRemovedDebugFacilities(selected, all)
//        entries = all // display without surrounding quotes
//        entryValues = all // the value of the entry is the debug facility "as is"
//        setValues(selected) // the currently selected values
//        //super.showDialog(state)
//    }

    /**
     * Removes any debug facility that is no longer present in the current syncthing version.
     * Otherwise it will never be removed from the enabled facilities set by MultiSelectListPreference.
     */
//    private fun filterRemovedDebugFacilities(
//        selected: MutableSet<String?>,
//        all: Array<CharSequence?>
//    ) {
//        val availableDebugFacilities = HashSet(listOf(*all))
//        selected.retainAll(availableDebugFacilities)
//    }

//    private val debugFacilities: Array<CharSequence?>
//        /**
//         * Returns all debug facilities available in the currently syncthing version.
//         */
//        get() {
//            val retDebugFacilities: MutableList<String?> =
//                ArrayList()
//            var availableDebugFacilities: MutableSet<String?> =
//                getSharedPreferences()?.getStringSet(
//                    com.nutomic.syncthingandroid.service.Constants.PREF_DEBUG_FACILITIES_AVAILABLE,
//                    java.util.HashSet()
//                )!!
//            // from JavaDoc: Note that you must not modify the set instance returned by this call.
//            // therefore required to make a defensive copy of the elements
//            availableDebugFacilities = HashSet(availableDebugFacilities)
//            if (!availableDebugFacilities.isEmpty()) {
//                for (facilityName in availableDebugFacilities) {
//                    retDebugFacilities.add(facilityName)
//                }
//            } else {
//                Log.w(
//                    TAG,
//                    "getDebugFacilities: Failed to get facilities from prefs, falling back to hardcoded list."
//                )
//
//                // Syncthing v0.14.47 debug facilities.
//                retDebugFacilities.add("beacon")
//                retDebugFacilities.add("config")
//                retDebugFacilities.add("connections")
//                retDebugFacilities.add("db")
//                retDebugFacilities.add("dialer")
//                retDebugFacilities.add("discover")
//                retDebugFacilities.add("events")
//                retDebugFacilities.add("fs")
//                retDebugFacilities.add("http")
//                retDebugFacilities.add("main")
//                retDebugFacilities.add("model")
//                retDebugFacilities.add("nat")
//                retDebugFacilities.add("pmp")
//                retDebugFacilities.add("protocol")
//                retDebugFacilities.add("scanner")
//                retDebugFacilities.add("sha256")
//                retDebugFacilities.add("stats")
//                retDebugFacilities.add("sync")
//                retDebugFacilities.add("upgrade")
//                retDebugFacilities.add("upnp")
//                retDebugFacilities.add("versioner")
//                retDebugFacilities.add("walkfs")
//                retDebugFacilities.add("watchaggregator")
//            }
//            val retDebugFacilitiesArray =
//                retDebugFacilities.toTypedArray<CharSequence?>()
//            Arrays.sort(retDebugFacilitiesArray)
//            return retDebugFacilitiesArray
//        }
}
