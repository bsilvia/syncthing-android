package com.nutomic.syncthingandroid.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

object PermissionUtil {
    @JvmStatic
    val locationPermissions: Array<String?>
        /**
         * Returns the location permissions required to access wifi SSIDs depending
         * on the respective Android version.
         */
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) { // before android 9
                return arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            }
            return arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    @JvmStatic
    fun haveStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        val permissionState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        return permissionState == PackageManager.PERMISSION_GRANTED
    }
}
