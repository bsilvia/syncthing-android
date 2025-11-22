package com.nutomic.syncthingandroid.service

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.concurrent.TimeUnit

object Constants {
    const val FILENAME_SYNCTHING_BINARY: String = "libsyncthing.so"

    // Preferences - Run conditions
    const val PREF_START_SERVICE_ON_BOOT: String = "always_run_in_background"
    const val PREF_RUN_CONDITIONS: String = "static_run_conditions"
    const val PREF_RUN_ON_MOBILE_DATA: String = "run_on_mobile_data"
    const val PREF_RUN_ON_WIFI: String = "run_on_wifi"
    const val PREF_RUN_ON_METERED_WIFI: String = "run_on_metered_wifi"
    const val PREF_WIFI_SSID_WHITELIST: String = "wifi_ssid_whitelist"
    const val PREF_POWER_SOURCE: String = "power_source"
    const val PREF_RESPECT_BATTERY_SAVING: String = "respect_battery_saving"
    const val PREF_RESPECT_MASTER_SYNC: String = "respect_master_sync"
    const val PREF_RUN_IN_FLIGHT_MODE: String = "run_in_flight_mode"

    // Preferences - Behaviour
    const val PREF_FIRST_START: String = "first_start"
    const val PREF_START_INTO_WEB_GUI: String = "start_into_web_gui"
    const val PREF_APP_THEME: String = "theme"
    const val PREF_USE_ROOT: String = "use_root"
    const val PREF_ENVIRONMENT_VARIABLES: String = "environment_variables"
    const val PREF_DEBUG_FACILITIES_ENABLED: String = "debug_facilities_enabled"
    const val PREF_USE_WAKE_LOCK: String = "wakelock_while_binary_running"
    const val PREF_USE_TOR: String = "use_tor"
    const val PREF_SOCKS_PROXY_ADDRESS: String = "socks_proxy_address"
    const val PREF_HTTP_PROXY_ADDRESS: String = "http_proxy_address"
    const val PREF_UPGRADED_TO_API_LEVEL_30: String = "upgraded_to_api_level_30"

    /**
     * Available options cache for preference [app_settings.debug_facilities_enabled]
     * Read via REST API call in [RestApi.updateDebugFacilitiesCache] after first successful binary startup.
     */
    const val PREF_DEBUG_FACILITIES_AVAILABLE: String = "debug_facilities_available"

    /**
     * Available folder types.
     */
    const val FOLDER_TYPE_SEND_ONLY: String = "sendonly"
    const val FOLDER_TYPE_SEND_RECEIVE: String = "sendreceive"
    const val FOLDER_TYPE_RECEIVE_ONLY: String = "receiveonly"

    /**
     * Interval in ms at which the GUI is updated (eg [com.nutomic.syncthingandroid.fragments.DrawerFragment]).
     */
    @JvmField
    val GUI_UPDATE_INTERVAL: Long = TimeUnit.SECONDS.toMillis(5)

    /**
     * Directory where config is exported to and imported from.
     */
    @JvmField
    val EXPORT_PATH: File = File(Environment.getExternalStorageDirectory(), "backups/syncthing")

    /**
     * File in the config folder that contains configuration.
     */
    const val CONFIG_FILE: String = "config.xml"

    @JvmStatic
    fun getConfigFile(context: Context): File {
        return File(context.filesDir, CONFIG_FILE)
    }

    /**
     * File in the config folder we write to temporarily before renaming to CONFIG_FILE.
     */
    const val CONFIG_TEMP_FILE: String = "config.xml.tmp"

    fun getConfigTempFile(context: Context): File {
        return File(context.filesDir, CONFIG_TEMP_FILE)
    }

    /**
     * Name of the public key file in the data directory.
     */
    const val PUBLIC_KEY_FILE: String = "cert.pem"

    fun getPublicKeyFile(context: Context): File {
        return File(context.filesDir, PUBLIC_KEY_FILE)
    }

    /**
     * Name of the private key file in the data directory.
     */
    const val PRIVATE_KEY_FILE: String = "key.pem"

    fun getPrivateKeyFile(context: Context): File {
        return File(context.filesDir, PRIVATE_KEY_FILE)
    }

    /**
     * Name of the public HTTPS CA file in the data directory.
     */
    const val HTTPS_CERT_FILE: String = "https-cert.pem"

    @JvmStatic
    fun getHttpsCertFile(context: Context): File {
        return File(context.filesDir, HTTPS_CERT_FILE)
    }

    /**
     * Key of the public HTTPS CA file in the data directory.
     */
    const val HTTPS_KEY_FILE: String = "https-key.pem"

    fun getHttpsKeyFile(context: Context): File {
        return File(context.filesDir, HTTPS_KEY_FILE)
    }

    fun getSyncthingBinary(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir, FILENAME_SYNCTHING_BINARY)
    }

    fun getLogFile(context: Context): File {
        return File(context.getExternalFilesDir(null), "syncthing.log")
    }

    /**
     * Decide if we should enforce HTTPS when accessing the Web UI and REST API.
     * Android 4.4 and earlier don't have support for TLS 1.2 requiring us to
     * fall back to an unencrypted HTTP connection to localhost. This applies
     * to syncthing core v0.14.53+.
     */
    fun osSupportsTLS12(): Boolean {
        return Build.VERSION.SDK_INT != Build.VERSION_CODES.N
    }

    var FLAG_IMMUTABLE: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    /**
     * These are the request codes used when requesting the permissions.
     */
    enum class PermissionRequestType {
        LOCATION, LOCATION_BACKGROUND, STORAGE
    }
}
