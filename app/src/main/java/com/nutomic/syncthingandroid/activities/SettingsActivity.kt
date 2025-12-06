package com.nutomic.syncthingandroid.activities

import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.TaskStackBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.google.common.base.Joiner
import com.google.common.base.Splitter
import com.google.common.collect.Iterables
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.databinding.ActivityPreferencesBinding
import com.nutomic.syncthingandroid.model.Config.Gui
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.model.SystemInfo
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.Constants.PermissionRequestType
import com.nutomic.syncthingandroid.service.NotificationHandler
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingService.OnServiceStateChangeListener
import com.nutomic.syncthingandroid.util.Languages
import com.nutomic.syncthingandroid.util.Util.fixAppDataPermissions
import com.nutomic.syncthingandroid.util.Util.getAlertDialogBuilder
import com.nutomic.syncthingandroid.views.SttracePreference
import com.nutomic.syncthingandroid.views.WifiSsidPreference
import eu.chainfire.libsuperuser.Shell
import java.security.InvalidParameterException
import java.util.Objects
import javax.inject.Inject

class SettingsActivity : SyncthingActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ActivityPreferencesBinding.inflate(layoutInflater).root
        setContentView(view)
        setTitle(R.string.settings_title)

        val settingsFragment = SettingsFragment()
        val bundle = Bundle()
        bundle.putString(
            EXTRA_OPEN_SUB_PREF_SCREEN, intent.getStringExtra(
                EXTRA_OPEN_SUB_PREF_SCREEN
            )
        )
        settingsFragment.setArguments(bundle)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, settingsFragment)
            .commit()

        // handle edge-to-edge layout by preventing the top and bottom bars from overlapping the app content
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams>(
                block = {
                    leftMargin = insets.left
                    topMargin = insets.top
                    rightMargin = insets.right
                    bottomMargin = insets.bottom
                }
            )
            // Return CONSUMED if you don't want the window insets to keep passing
            // down to descendant views.
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionRequestType.LOCATION.ordinal) {
            var granted = grantResults.isNotEmpty()
            for (i in grantResults.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    granted = false
                    break
                }
            }
            if (granted) {
                this.startService(
                    Intent(this, SyncthingService::class.java)
                        .setAction(SyncthingService.ACTION_REFRESH_NETWORK_INFO)
                )
            } else {
                getAlertDialogBuilder(this)
                    .setTitle(R.string.sync_only_wifi_ssids_location_permission_rejected_dialog_title)
                    .setMessage(R.string.sync_only_wifi_ssids_location_permission_rejected_dialog_content)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), OnServiceConnectedListener,
        OnServiceStateChangeListener, Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {
        @JvmField
        @Inject
        var mNotificationHandler: NotificationHandler? = null

        @JvmField
        @Inject
        var mPreferences: SharedPreferences? = null

        private var mCategoryRunConditions: PreferenceGroup? = null
        private var mRunConditions: CheckBoxPreference? = null
        private var mStartServiceOnBoot: CheckBoxPreference? = null
        private var mPowerSource: ListPreference? = null
        private var mRunOnMobileData: CheckBoxPreference? = null
        private var mRunOnWifi: CheckBoxPreference? = null
        private var mRunOnMeteredWifi: CheckBoxPreference? = null
        private var mWifiSsidWhitelist: WifiSsidPreference? = null
        private var mRunInFlightMode: CheckBoxPreference? = null

        private var mCategorySyncthingOptions: Preference? = null
        private var mDeviceName: EditTextPreference? = null
        private var mListenAddresses: EditTextPreference? = null
        private var mMaxRecvKbps: EditTextPreference? = null
        private var mMaxSendKbps: EditTextPreference? = null
        private var mNatEnabled: CheckBoxPreference? = null
        private var mLocalAnnounceEnabled: CheckBoxPreference? = null
        private var mGlobalAnnounceEnabled: CheckBoxPreference? = null
        private var mRelaysEnabled: CheckBoxPreference? = null
        private var mGlobalAnnounceServers: EditTextPreference? = null
        private var mAddress: EditTextPreference? = null
        private var mUrAccepted: CheckBoxPreference? = null

        private var mCategoryBackup: Preference? = null

        /* Experimental options */
        private var mUseRoot: CheckBoxPreference? = null
        private var mUseWakelock: CheckBoxPreference? = null
        private var mUseTor: CheckBoxPreference? = null
        private var mSocksProxyAddress: EditTextPreference? = null
        private var mHttpProxyAddress: EditTextPreference? = null

        private var mSyncthingVersion: Preference? = null

        private var mSyncthingService: SyncthingService? = null
        private var mApi: RestApi? = null

        private var mOptions: Options? = null
        private var mGui: Gui? = null

        private var mPendingConfig = false

        /**
         * Indicates if run conditions were changed and need to be
         * re-evaluated when the user leaves the preferences screen.
         */
        private var mPendingRunConditions = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            (requireActivity().application as SyncthingApp).component()!!.inject(this)
            (activity as SyncthingActivity).registerOnServiceConnectedListener(this)
        }

        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?
        ) {
            setPreferencesFromResource(R.xml.app_settings, rootKey)
        }

        /**
         * Loads layout, sets version from Rest API.
         *
         * Manual target API as we manually check if ActionBar is available (for ActionBar back button).
         */
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val screen = preferenceScreen
            mRunConditions = findPreference(Constants.PREF_RUN_CONDITIONS)
            mStartServiceOnBoot = findPreference(Constants.PREF_START_SERVICE_ON_BOOT)
            mPowerSource = findPreference(Constants.PREF_POWER_SOURCE)
            mRunOnMobileData = findPreference(Constants.PREF_RUN_ON_WIFI)
            mRunOnWifi = findPreference(Constants.PREF_RUN_ON_WIFI)
            mRunOnMeteredWifi = findPreference(Constants.PREF_RUN_ON_METERED_WIFI)
            mWifiSsidWhitelist = findPreference(Constants.PREF_WIFI_SSID_WHITELIST)
            mRunInFlightMode = findPreference(Constants.PREF_RUN_IN_FLIGHT_MODE)

            val languagePref = findPreference<ListPreference>(Languages.PREFERENCE_LANGUAGE)
            val categoryBehaviour = findPreference<PreferenceScreen>("category_behaviour")
            categoryBehaviour?.removePreference(languagePref!!)

            mDeviceName = findPreference("deviceName")
            mListenAddresses = findPreference("listenAddresses")
            mMaxRecvKbps = findPreference("maxRecvKbps")
            mMaxSendKbps = findPreference("maxSendKbps")
            mNatEnabled = findPreference("natEnabled")
            mLocalAnnounceEnabled = findPreference("localAnnounceEnabled")
            mGlobalAnnounceEnabled = findPreference("globalAnnounceEnabled")
            mRelaysEnabled = findPreference("relaysEnabled")
            mGlobalAnnounceServers = findPreference("globalAnnounceServers")
            mAddress = findPreference("address")
            mUrAccepted = findPreference("urAccepted")

            mCategoryBackup = findPreference("category_backup")
            val exportConfig = findPreference<Preference>("export_config")
            val importConfig = findPreference<Preference>("import_config")

            val undoIgnoredDevicesFolders = findPreference<Preference>(KEY_UNDO_IGNORED_DEVICES_FOLDERS)
            val debugFacilitiesEnabled = findPreference<SttracePreference>(Constants.PREF_DEBUG_FACILITIES_ENABLED)
            val environmentVariables = findPreference<EditTextPreference>("environment_variables")
            val stResetDatabase = findPreference<Preference>("st_reset_database")
            val stResetDeltas = findPreference<Preference>("st_reset_deltas")

            mUseRoot = findPreference(Constants.PREF_USE_ROOT)
            mUseWakelock = findPreference(Constants.PREF_USE_WAKE_LOCK)
            mUseTor = findPreference(Constants.PREF_USE_TOR)
            mSocksProxyAddress = findPreference(Constants.PREF_SOCKS_PROXY_ADDRESS)
            mHttpProxyAddress = findPreference(Constants.PREF_HTTP_PROXY_ADDRESS)

            mSyncthingVersion = findPreference("syncthing_version")
            val appVersion = screen.findPreference<Preference>("app_version")

            mRunOnMeteredWifi!!.isEnabled = mRunOnWifi!!.isChecked
            mWifiSsidWhitelist!!.isEnabled = mRunOnWifi!!.isChecked

            mCategorySyncthingOptions = findPreference("category_syncthing_options")
            setPreferenceCategoryChangeListener(mCategorySyncthingOptions ) {
                preference: Preference?, o: Any? ->
                this.onSyncthingPreferenceChange(
                    preference!!,
                    o
                )
            }
            mCategoryRunConditions = findPreference("category_run_conditions")
            setPreferenceCategoryChangeListener(mCategoryRunConditions
            ) { preference: Preference?, o: Any? ->
                this.onRunConditionPreferenceChange(
                    preference!!,
                    o!!
                )
            }

            if (!mRunConditions!!.isChecked) {
                for (index in 1..<mCategoryRunConditions!!.preferenceCount) {
                    mCategoryRunConditions!!.getPreference(index).isEnabled = false
                }
            }

            exportConfig?.onPreferenceClickListener = this
            importConfig?.onPreferenceClickListener = this

            undoIgnoredDevicesFolders?.onPreferenceClickListener = this
            debugFacilitiesEnabled?.onPreferenceChangeListener = this
            environmentVariables?.onPreferenceChangeListener = this
            stResetDatabase?.onPreferenceClickListener = this
            stResetDeltas?.onPreferenceClickListener = this

            /* Experimental options */
            mUseRoot!!.onPreferenceClickListener = this
            mUseWakelock!!.onPreferenceChangeListener = this
            mUseTor!!.onPreferenceChangeListener = this

            mSocksProxyAddress!!.isEnabled = (!mUseTor!!.isChecked as Boolean?)!!
            mSocksProxyAddress!!.onPreferenceChangeListener = this
            mHttpProxyAddress!!.isEnabled = (!mUseTor!!.isChecked as Boolean?)!!
            mHttpProxyAddress!!.onPreferenceChangeListener = this

            /* Initialize summaries */
            screen.findPreference<ListPreference>(Constants.PREF_POWER_SOURCE)?.summary = mPowerSource!!.getEntry()
            val wifiSsidSummary = TextUtils.join(
                ", ", mPreferences!!.getStringSet(
                    Constants.PREF_WIFI_SSID_WHITELIST,
                    java.util.HashSet()
                )!!
            )
            screen.findPreference<WifiSsidPreference>(Constants.PREF_WIFI_SSID_WHITELIST)?.summary =
                if (TextUtils.isEmpty(wifiSsidSummary)) getString(R.string.run_on_all_wifi_networks) else getString(
                R.string.run_on_whitelisted_wifi_networks,
                wifiSsidSummary
            )
            handleSocksProxyPreferenceChange(
                screen.findPreference<EditTextPreference>(Constants.PREF_SOCKS_PROXY_ADDRESS)!!, mPreferences!!.getString(
                    Constants.PREF_SOCKS_PROXY_ADDRESS,
                    ""
                )!!
            )
            handleHttpProxyPreferenceChange(
                screen.findPreference<EditTextPreference>(Constants.PREF_HTTP_PROXY_ADDRESS)!!, mPreferences!!.getString(
                    Constants.PREF_HTTP_PROXY_ADDRESS,
                    ""
                )!!
            )

            val themePreference = findPreference(Constants.PREF_APP_THEME) as ListPreference?
            themePreference?.onPreferenceChangeListener = this

            try {
                appVersion?.summary = requireActivity().packageManager
                    ?.getPackageInfo(requireActivity().packageName, 0)?.versionName
            } catch (_: PackageManager.NameNotFoundException) {
                Log.d(TAG, "Failed to get app version name")
            }

//            openSubPrefScreen(screen)
        }

//        private fun openSubPrefScreen(prefScreen: PreferenceScreen) {
//            val bundle = arguments ?: return
//            val openSubPrefScreen = bundle.getString(EXTRA_OPEN_SUB_PREF_SCREEN, "")
//            // Open sub preferences screen if EXTRA_OPEN_SUB_PREF_SCREEN was passed in bundle.
//            if (openSubPrefScreen != null && !TextUtils.isEmpty(openSubPrefScreen)) {
//                Log.v(TAG, "Transitioning to pref screen $openSubPrefScreen")
//                val categoryRunConditions = findPreference(openSubPrefScreen) as PreferenceScreen?
//                // TODO - find the proper way to simulate a click
//                val itemsCount = prefScreen.preferenceCount
//                for (itemNumber in 0..<itemsCount) {
//                    if (prefScreen[itemNumber] == categoryRunConditions) {
//                        // Simulates click on the sub-preference
//                        prefScreen[itemNumber].performClick()
//                        break
//                    }
//                }
//            }
//        }

        override fun onServiceConnected() {
            Log.v(TAG, "onServiceConnected")
            if (activity == null) return

            mSyncthingService = (activity as SyncthingActivity).service
            mSyncthingService!!.registerOnServiceStateChangeListener(this)
        }

        override fun onServiceStateChange(currentState: SyncthingService.State?) {
            mApi = mSyncthingService!!.api
            val isSyncthingRunning = (mApi != null) &&
                    mApi!!.isConfigLoaded &&
                    (currentState == SyncthingService.State.ACTIVE)
            mCategorySyncthingOptions!!.isEnabled = isSyncthingRunning
            mCategoryBackup!!.isEnabled = isSyncthingRunning

            if (!isSyncthingRunning) return

            mSyncthingVersion!!.summary = mApi!!.version
            mOptions = mApi!!.options
            mGui = mApi!!.gui

            val joiner = Joiner.on(", ")
            mDeviceName!!.setText(Objects.requireNonNull<Device>(mApi!!.localDevice).name)
            checkNotNull(mOptions!!.listenAddresses)
            mListenAddresses!!.setText(joiner.join(mOptions!!.listenAddresses!!))
            mMaxRecvKbps!!.setText(mOptions!!.maxRecvKbps.toString())
            mMaxSendKbps!!.setText(mOptions!!.maxSendKbps.toString())
            mNatEnabled!!.setChecked(mOptions!!.natEnabled)
            mLocalAnnounceEnabled!!.setChecked(mOptions!!.localAnnounceEnabled)
            mGlobalAnnounceEnabled!!.setChecked(mOptions!!.globalAnnounceEnabled)
            mRelaysEnabled!!.setChecked(mOptions!!.relaysEnabled)
            checkNotNull(mOptions!!.globalAnnounceServers)
            mGlobalAnnounceServers!!.setText(joiner.join(mOptions!!.globalAnnounceServers!!))
            mAddress!!.setText(mGui!!.address)
            mApi!!.getSystemInfo { systemInfo: SystemInfo? ->
                checkNotNull(systemInfo)
                mUrAccepted!!.setChecked(mOptions!!.isUsageReportingAccepted(systemInfo.urVersionMax))
            }
        }

        override fun onDestroy() {
            if (mSyncthingService != null) {
                mSyncthingService!!.unregisterOnServiceStateChangeListener(this)
            }
            super.onDestroy()
        }

        private fun setPreferenceCategoryChangeListener(
            category: Preference?, listener: Preference.OnPreferenceChangeListener?
        ) {
            val ps = category as PreferenceScreen
            for (i in 0..<ps.preferenceCount) {
                val p = ps.getPreference(i)
                p.onPreferenceChangeListener = listener
            }
        }

        fun onRunConditionPreferenceChange(preference: Preference, o: Any): Boolean {
            when (preference.key) {
                Constants.PREF_RUN_CONDITIONS -> {
                    val enabled = o as Boolean
                    var index = 1
                    while (index < mCategoryRunConditions!!.preferenceCount) {
                        mCategoryRunConditions!!.getPreference(index).isEnabled = enabled
                        ++index
                    }
                    if (enabled) {
                        mRunOnMeteredWifi!!.isEnabled = mRunOnWifi!!.isChecked
                        mWifiSsidWhitelist!!.isEnabled = mRunOnWifi!!.isChecked
                    }
                }

                Constants.PREF_POWER_SOURCE -> {
                    mPowerSource!!.setValue(o.toString())
                    preference.summary = mPowerSource!!.getEntry()
                }

                Constants.PREF_RUN_ON_WIFI -> {
                    mRunOnMeteredWifi!!.isEnabled = (o as Boolean?)!!
                    mWifiSsidWhitelist!!.isEnabled = o
                }

                Constants.PREF_WIFI_SSID_WHITELIST -> {
                    val wifiSsidSummary = TextUtils.join(
                        ", ",
                        (o as MutableSet<*>)
                    )
                    preference.summary = if (TextUtils.isEmpty(wifiSsidSummary)) getString(R.string.run_on_all_wifi_networks) else getString(
                        R.string.run_on_whitelisted_wifi_networks,
                        wifiSsidSummary
                    )
                }
            }
            mPendingRunConditions = true
            return true
        }

        fun onSyncthingPreferenceChange(preference: Preference, o: Any?): Boolean {
            val splitter = Splitter.on(",").trimResults().omitEmptyStrings()
            when (preference.key) {
                "deviceName" -> {
                    val localDevice = mApi!!.localDevice
                    localDevice!!.name = (o as String?)!!
                    mApi!!.editDevice(localDevice)
                }

                "listenAddresses" -> mOptions!!.listenAddresses = Iterables.toArray(
                    splitter.split((o as String?)!!),
                    String::class.java
                )

                "maxRecvKbps" -> {
                    var maxRecvKbps: Int
                    try {
                        maxRecvKbps = (o as String?)!!.toInt()
                    } catch (_: Exception) {
                        Toast.makeText(
                            activity,
                            resources.getString(
                                R.string.invalid_integer_value,
                                0,
                                Int.MAX_VALUE
                            ),
                            Toast.LENGTH_LONG
                        )
                            .show()
                        return false
                    }
                    mOptions!!.maxRecvKbps = maxRecvKbps
                }

                "maxSendKbps" -> {
                    var maxSendKbps: Int
                    try {
                        maxSendKbps = (o as String?)!!.toInt()
                    } catch (_: Exception) {
                        Toast.makeText(
                            activity,
                            resources.getString(
                                R.string.invalid_integer_value,
                                0,
                                Int.MAX_VALUE
                            ),
                            Toast.LENGTH_LONG
                        )
                            .show()
                        return false
                    }
                    mOptions!!.maxSendKbps = maxSendKbps
                }

                "natEnabled" -> mOptions!!.natEnabled = o as Boolean
                "localAnnounceEnabled" -> mOptions!!.localAnnounceEnabled = o as Boolean
                "globalAnnounceEnabled" -> mOptions!!.globalAnnounceEnabled = o as Boolean
                "relaysEnabled" -> mOptions!!.relaysEnabled = o as Boolean
                "globalAnnounceServers" -> mOptions!!.globalAnnounceServers =
                    Iterables.toArray(
                        splitter.split((o as String?)!!),
                        String::class.java
                    )

                "address" -> mGui!!.address = o as String?
                "urAccepted" -> mApi!!.getSystemInfo { systemInfo: SystemInfo? ->
                    mOptions!!.urAccepted = if (o as Boolean)
                        systemInfo!!.urVersionMax
                    else
                        Options.USAGE_REPORTING_DENIED
                }

                else -> throw InvalidParameterException()
            }

            mApi!!.editSettings(mGui, mOptions)
            mPendingConfig = true
            return true
        }

        override fun onStop() {
            if (mSyncthingService != null) {
                mNotificationHandler!!.updatePersistentNotification(mSyncthingService!!)
                if (mPendingConfig) {
                    if (mApi != null &&
                        mSyncthingService!!.currentState != SyncthingService.State.DISABLED
                    ) {
                        mApi!!.saveConfigAndRestart()
                        mPendingConfig = false
                    }
                }
                if (mPendingRunConditions) {
                    mSyncthingService!!.evaluateRunConditions()
                }
            }
            super.onStop()
        }

        /**
         * Sends the updated value to [RestApi], and sets it as the summary
         * for EditTextPreference.
         */
        override fun onPreferenceChange(preference: Preference, o: Any): Boolean {
            when (preference.key) {
                Constants.PREF_DEBUG_FACILITIES_ENABLED -> mPendingConfig = true
                Constants.PREF_ENVIRONMENT_VARIABLES -> if ((o as String).matches("^(\\w+=[\\w:/.]+)?( \\w+=[\\w:/.]+)*$".toRegex())) {
                    mPendingConfig = true
                } else {
                    Toast.makeText(
                        activity,
                        R.string.toast_invalid_environment_variables,
                        Toast.LENGTH_SHORT
                    )
                        .show()
                    return false
                }

                Constants.PREF_USE_WAKE_LOCK -> mPendingConfig = true
                Constants.PREF_USE_TOR -> {
                    mSocksProxyAddress!!.isEnabled = (o as Boolean?)!!
                    mHttpProxyAddress!!.isEnabled = o
                    mPendingConfig = true
                }

                Constants.PREF_SOCKS_PROXY_ADDRESS -> {
                    if (o.toString().trim { it <= ' ' } == mPreferences!!.getString(
                            Constants.PREF_SOCKS_PROXY_ADDRESS,
                            ""
                        )) return false
                    if (handleSocksProxyPreferenceChange(
                            preference,
                            o.toString().trim { it <= ' ' })
                    ) {
                        mPendingConfig = true
                    } else {
                        return false
                    }
                }

                Constants.PREF_HTTP_PROXY_ADDRESS -> {
                    if (o.toString().trim { it <= ' ' } == mPreferences!!.getString(
                            Constants.PREF_HTTP_PROXY_ADDRESS,
                            ""
                        )) return false
                    if (handleHttpProxyPreferenceChange(
                            preference,
                            o.toString().trim { it <= ' ' })
                    ) {
                        mPendingConfig = true
                    } else {
                        return false
                    }
                }

                // Recreate activities with the correct colors
                Constants.PREF_APP_THEME ->
                    TaskStackBuilder.create(requireContext())
                        .addNextIntent(Intent(activity, MainActivity::class.java))
                        .addNextIntent(requireActivity().intent)
                        .startActivities()
            }

            return true
        }

        override fun onPreferenceClick(preference: Preference): Boolean {
            val intent: Intent
            when (preference.key) {
                Constants.PREF_USE_ROOT -> {
                    if (mUseRoot!!.isChecked) {
                            // Only check preference after root was granted.
                            mUseRoot!!.setChecked(false)
                            testRoot()
                    } else {
                        Thread { fixAppDataPermissions(requireContext()) }.start()
                        mPendingConfig = true
                    }
                    return true
                }

                KEY_EXPORT_CONFIG -> {
                    getAlertDialogBuilder(requireContext())
                        .setMessage(R.string.dialog_confirm_export)
                        .setPositiveButton(
                            android.R.string.ok
                        ) { _: DialogInterface?, _: Int ->
                            mSyncthingService!!.exportConfig()
                            Toast.makeText(
                                activity,
                                getString(
                                    R.string.config_export_successful,
                                    Constants.EXPORT_PATH
                                ), Toast.LENGTH_LONG
                            ).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return true
                }

                KEY_IMPORT_CONFIG -> {
                    getAlertDialogBuilder(requireContext())
                        .setMessage(R.string.dialog_confirm_import)
                        .setPositiveButton(
                            android.R.string.ok
                        ) { _: DialogInterface?, _: Int ->
                            if (mSyncthingService!!.importConfig()) {
                                Toast.makeText(
                                    activity,
                                    getString(R.string.config_imported_successful),
                                    Toast.LENGTH_SHORT
                                ).show()
                                // No need to restart, as we shutdown to import the config, and
                                // then have to start Syncthing again.
                            } else {
                                Toast.makeText(
                                    activity,
                                    getString(
                                        R.string.config_import_failed,
                                        Constants.EXPORT_PATH
                                    ), Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return true
                }

                KEY_UNDO_IGNORED_DEVICES_FOLDERS -> {
                    getAlertDialogBuilder(requireContext())
                        .setMessage(R.string.undo_ignored_devices_folders_question)
                        .setPositiveButton(
                            android.R.string.ok
                        ) { _: DialogInterface?, _: Int ->
                            if (mApi == null) {
                                Toast.makeText(
                                    activity,
                                    getString(R.string.generic_error) + getString(R.string.syncthing_disabled_title),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@setPositiveButton
                            }
                            mApi!!.undoIgnoredDevicesAndFolders()
                            mPendingConfig = true
                            Toast.makeText(
                                activity,
                                getString(R.string.undo_ignored_devices_folders_done),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return true
                }

                KEY_ST_RESET_DATABASE -> {
                    intent = Intent(activity, SyncthingService::class.java)
                        .setAction(SyncthingService.ACTION_RESET_DATABASE)

                    getAlertDialogBuilder(requireContext())
                        .setTitle(R.string.st_reset_database_title)
                        .setMessage(R.string.st_reset_database_question)
                        .setPositiveButton(
                            android.R.string.ok
                        ) { _: DialogInterface?, _: Int ->
                            activity?.startService(intent)
                            Toast.makeText(
                                activity,
                                R.string.st_reset_database_done,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .setNegativeButton(
                            android.R.string.cancel
                        ) { _: DialogInterface?, _: Int -> }
                        .show()
                    return true
                }

                KEY_ST_RESET_DELTAS -> {
                    intent = Intent(activity, SyncthingService::class.java)
                        .setAction(SyncthingService.ACTION_RESET_DELTAS)

                    getAlertDialogBuilder(requireContext())
                        .setTitle(R.string.st_reset_deltas_title)
                        .setMessage(R.string.st_reset_deltas_question)
                        .setPositiveButton(
                            android.R.string.ok
                        ) { _: DialogInterface?, _: Int ->
                            activity?.startService(intent)
                            Toast.makeText(
                                activity,
                                R.string.st_reset_deltas_done,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .setNegativeButton(
                            android.R.string.cancel
                        ) { _: DialogInterface?, _: Int -> }
                        .show()
                    return true
                }

                else -> return false
            }
        }

        /**
         * Enables or disables [.mUseRoot] preference depending whether root is available.
         */
        private fun testRoot() {
            lifecycleScope.launch {
                val haveRoot = withContext(Dispatchers.IO) {
                    Shell.SU.available()
                }
                if (haveRoot) {
                    mPendingConfig = true
                    mUseRoot!!.isChecked = true
                } else {
                    Toast.makeText(
                        activity,
                        R.string.toast_root_denied,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        /**
         * Handles a new user input for the SOCKS proxy preference.
         * Returns if the changed setting requires a restart.
         */
        private fun handleSocksProxyPreferenceChange(
            preference: Preference,
            newValue: String
        ): Boolean {
            // Valid input is either a proxy address or an empty field to disable the proxy.
            if (newValue == "") {
                preference.summary = getString(R.string.do_not_use_proxy) + " " + getString(R.string.generic_example) + ": " + getString(
                    R.string.socks_proxy_address_example
                )
                return true
            } else if (newValue.matches("^socks5://.*:\\d{1,5}$".toRegex())) {
                preference.summary = getString(R.string.use_proxy) + " " + newValue
                return true
            } else {
                Toast.makeText(
                    activity,
                    R.string.toast_invalid_socks_proxy_address,
                    Toast.LENGTH_SHORT
                )
                    .show()
                return false
            }
        }

        /**
         * Handles a new user input for the HTTP(S) proxy preference.
         * Returns if the changed setting requires a restart.
         */
        private fun handleHttpProxyPreferenceChange(
            preference: Preference,
            newValue: String
        ): Boolean {
            // Valid input is either a proxy address or an empty field to disable the proxy.
            if (newValue == "") {
                preference.summary = getString(R.string.do_not_use_proxy) + " " + getString(R.string.generic_example) + ": " + getString(
                    R.string.http_proxy_address_example
                )
                return true
            } else if (newValue.matches("^http://.*:\\d{1,5}$".toRegex())) {
                preference.summary = getString(R.string.use_proxy) + " " + newValue
                return true
            } else {
                Toast.makeText(
                    activity,
                    R.string.toast_invalid_http_proxy_address,
                    Toast.LENGTH_SHORT
                )
                    .show()
                return false
            }
        }

        companion object {
            private const val TAG = "SettingsFragment"
            private const val KEY_EXPORT_CONFIG = "export_config"
            private const val KEY_IMPORT_CONFIG = "import_config"
            private const val KEY_UNDO_IGNORED_DEVICES_FOLDERS = "undo_ignored_devices_folders"
            private const val KEY_ST_RESET_DATABASE = "st_reset_database"
            private const val KEY_ST_RESET_DELTAS = "st_reset_deltas"
        }
    }

    companion object {
        const val EXTRA_OPEN_SUB_PREF_SCREEN: String =
            "com.nutomic.syncthingandroid.activities.SettingsActivity.OPEN_SUB_PREF_SCREEN"
    }
}
