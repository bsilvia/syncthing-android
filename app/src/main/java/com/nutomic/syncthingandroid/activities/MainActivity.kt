package com.nutomic.syncthingandroid.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.annimon.stream.function.Consumer
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.fragments.DeviceListFragment
import com.nutomic.syncthingandroid.fragments.DrawerFragment
import com.nutomic.syncthingandroid.fragments.FolderListFragment
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingService.OnServiceStateChangeListener
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.util.PermissionUtil.haveStoragePermission
import com.nutomic.syncthingandroid.util.Util.copyDeviceId
import com.nutomic.syncthingandroid.util.Util.dismissDialogSafe
import com.nutomic.syncthingandroid.util.Util.getAlertDialogBuilder
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Shows [FolderListFragment] and
 * [DeviceListFragment] in different tabs, and
 * [DrawerFragment] in the navigation drawer.
 */
class MainActivity : StateDialogActivity(), OnServiceStateChangeListener {
    private var mBatteryOptimizationsDialog: AlertDialog? = null
    private var mQrCodeDialog: AlertDialog? = null
    private var mRestartDialog: Dialog? = null

    private var mBatteryOptimizationDialogDismissed = false

    private var mViewPager: ViewPager2? = null

    private var mFolderListFragment: FolderListFragment? = null
    private var mDeviceListFragment: DeviceListFragment? = null
    private var mDrawerFragment: DrawerFragment? = null

    private var mDrawerToggle: ActionBarDrawerToggle? = null
    private var mDrawerLayout: DrawerLayout? = null

//    @Inject
//    override var mPreferences: SharedPreferences? = null

    /**
     * Handles various dialogs based on current state.
     */
    override fun onServiceStateChange(currentState: SyncthingService.State?) {
        when (currentState) {
            SyncthingService.State.STARTING -> {}
            SyncthingService.State.ACTIVE -> {
                intent.putExtra(
                    EXTRA_KEY_GENERATION_IN_PROGRESS,
                    false
                )
                showBatteryOptimizationDialogIfNecessary()
                mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                mDrawerFragment!!.requestGuiUpdate()

                // Check if the usage reporting minimum delay passed by.
                val usageReportingDelayPassed =
                    (Date().time > this.firstStartTime + USAGE_REPORTING_DIALOG_DELAY)
                val restApi = api
                if (usageReportingDelayPassed && restApi != null && !restApi.isUsageReportingDecided) {
                    showUsageReportingDialog(restApi)
                }
            }

            SyncthingService.State.ERROR -> finish()
            SyncthingService.State.DISABLED -> {}
            else -> {}
        }
    }

    private fun showBatteryOptimizationDialogIfNecessary() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val doNotShowAgain = mPreferences!!.getBoolean("battery_optimization_dont_show_again", false)
        if (doNotShowAgain || mBatteryOptimizationsDialog != null ||
            pm.isIgnoringBatteryOptimizations(packageName) ||
            mBatteryOptimizationDialogDismissed
        ) {
            return
        }

        mBatteryOptimizationsDialog = getAlertDialogBuilder(this)
            .setTitle(R.string.dialog_disable_battery_optimization_title)
            .setMessage(R.string.dialog_disable_battery_optimization_message)
            .setPositiveButton(
                R.string.dialog_disable_battery_optimization_turn_off
            ) { _: DialogInterface?, _: Int ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.setData(("package:$packageName").toUri())
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // Some devices dont seem to support this request (according to Google Play
                    // crash reports).
                    Log.w(TAG, "Request ignore battery optimizations not supported", e)
                    Toast.makeText(
                        this,
                        R.string.dialog_disable_battery_optimizations_not_supported,
                        Toast.LENGTH_LONG
                    ).show()
                    mPreferences!!.edit {
                        putBoolean("battery_optimization_dont_show_again", true)
                    }
                }
            }
            .setNeutralButton(
                R.string.dialog_disable_battery_optimization_later
            ) { _: DialogInterface?, _: Int ->
                mBatteryOptimizationDialogDismissed = true
            }
            .setNegativeButton(
                R.string.dialog_disable_battery_optimization_dont_show_again
            ) { _: DialogInterface?, _: Int ->
                mPreferences!!.edit {
                    putBoolean("battery_optimization_dont_show_again", true)
                }
            }
            .setOnCancelListener { _: DialogInterface? ->
                mBatteryOptimizationDialogDismissed = true
            }
            .show()
    }

    private val firstStartTime: Long
        /**
         * Returns the unix timestamp at which the app was first installed.
         */
        get() {
            val pm = packageManager
            var firstInstallTime: Long = 0
            try {
                firstInstallTime = pm.getPackageInfo(packageName, 0).firstInstallTime
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "This should never happen", e)
            }
            return firstInstallTime
        }

    private lateinit var mSectionsPagerAdapter: FragmentStateAdapter

    private inner class SectionsPagerAdapter(activity: FragmentActivity) :
        FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return if (position == 0)
                mFolderListFragment ?: FolderListFragment()
            else
                mDeviceListFragment ?: DeviceListFragment()
        }
    }

    /**
     * Initializes tab navigation.
     */
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component()!!.inject(this)

        setContentView(R.layout.activity_main)
        mDrawerLayout = findViewById(R.id.drawer_layout)

        val fm = supportFragmentManager
        if (savedInstanceState != null) {
            mFolderListFragment = fm.getFragment(
                savedInstanceState, FolderListFragment::class.java.getName()
            ) as FolderListFragment?
            mDeviceListFragment = fm.getFragment(
                savedInstanceState, DeviceListFragment::class.java.getName()
            ) as DeviceListFragment?
            mDrawerFragment = fm.getFragment(
                savedInstanceState, DrawerFragment::class.java.getName()
            ) as DrawerFragment?
        } else {
            mFolderListFragment = FolderListFragment()
            mDeviceListFragment = DeviceListFragment()
            mDrawerFragment = DrawerFragment()
        }

        mViewPager = findViewById(R.id.pager)
        mSectionsPagerAdapter = SectionsPagerAdapter(this)
        mViewPager!!.adapter = mSectionsPagerAdapter
        val tabLayout = findViewById<TabLayout>(R.id.tabContainer)
        TabLayoutMediator(tabLayout, mViewPager!!) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.folders_fragment_title)
                1 -> getString(R.string.devices_fragment_title)
                else -> position.toString()
            }
        }.attach()

        if (savedInstanceState != null) {
            mViewPager!!.currentItem = savedInstanceState.getInt("currentTab")
            if (savedInstanceState.getBoolean(IS_SHOWING_RESTART_DIALOG)) {
                showRestartDialog()
            }
            mBatteryOptimizationDialogDismissed = savedInstanceState.getBoolean(
                BATTERY_DIALOG_DISMISSED
            )
            if (savedInstanceState.getBoolean(IS_QRCODE_DIALOG_DISPLAYED)) {
                val deviceId = savedInstanceState.getString(DEVICE_ID_KEY)
                val qrBitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    savedInstanceState.getParcelable(QRCODE_BITMAP_KEY, Bitmap::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    savedInstanceState.getParcelable(QRCODE_BITMAP_KEY)
                }
                showQrCodeDialog(deviceId, qrBitmap)
            }
        }

        fm.beginTransaction().replace(R.id.drawer, mDrawerFragment!!).commit()
        mDrawerToggle = Toggle(this, mDrawerLayout)
        mDrawerLayout!!.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        mDrawerLayout!!.addDrawerListener(mDrawerToggle!!)
        setOptimalDrawerWidth(findViewById(R.id.drawer))

        // SyncthingService needs to be started from this activity as the user
        // can directly launch this activity from the recent activity switcher.
        val serviceIntent = Intent(this, SyncthingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        onNewIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mDrawerLayout!!.isDrawerOpen(GravityCompat.START)) {
                    // Close drawer on back button press.
                    closeDrawer()
                } else {
                    /*
                     * Leave MainActivity in its state as the home button was pressed.
                     * This will avoid waiting for the loading spinner when getting back
                     * and give changes to do UI updates based on EventProcessor in the future.
                     */
                    moveTaskToBack(true)
                }
            }
        })

        // handle edge-to-edge layout by preventing the top and bottom bars from overlapping the app content
        ViewCompat.setOnApplyWindowInsetsListener(mDrawerLayout!!) { v, windowInsets ->
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

    public override fun onResume() {
        // Check if storage permission has been revoked at runtime.
        if (!haveStoragePermission(this)) {
            startActivity(Intent(this, FirstStartActivity::class.java))
            this.finish()
        }

        // Evaluate run conditions to detect changes made to the metered wifi flags.
        val mSyncthingService = service
        mSyncthingService?.evaluateRunConditions()
        super.onResume()
    }

    public override fun onDestroy() {
        super.onDestroy()
        val mSyncthingService = service
        if (mSyncthingService != null) {
            mSyncthingService.unregisterOnServiceStateChangeListener(this)
            mSyncthingService.unregisterOnServiceStateChangeListener(mFolderListFragment)
            mSyncthingService.unregisterOnServiceStateChangeListener(mDeviceListFragment)
        }
    }

    override fun onServiceConnected(componentName: ComponentName?, iBinder: IBinder?) {
        super.onServiceConnected(componentName, iBinder)
        val syncthingServiceBinder = iBinder as SyncthingServiceBinder
        val syncthingService = syncthingServiceBinder.service
        syncthingService!!.registerOnServiceStateChangeListener(this)
        syncthingService.registerOnServiceStateChangeListener(mFolderListFragment!!)
        syncthingService.registerOnServiceStateChangeListener(mDeviceListFragment!!)
    }

    /**
     * Saves current tab index and fragment states.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val fm = supportFragmentManager
        val putFragment = Consumer { fragment: Fragment? ->
            if (fragment != null && fragment.isAdded) {
                fm.putFragment(outState, fragment.javaClass.getName(), fragment)
            }
        }
        putFragment.accept(mFolderListFragment)
        putFragment.accept(mDeviceListFragment)
        putFragment.accept(mDrawerFragment)

        outState.putInt("currentTab", mViewPager!!.currentItem)
        outState.putBoolean(
            BATTERY_DIALOG_DISMISSED,
            mBatteryOptimizationsDialog == null || !mBatteryOptimizationsDialog!!.isShowing
        )
        outState.putBoolean(
            IS_SHOWING_RESTART_DIALOG,
            mRestartDialog != null && mRestartDialog!!.isShowing
        )
        if (mQrCodeDialog != null && mQrCodeDialog!!.isShowing) {
            outState.putBoolean(IS_QRCODE_DIALOG_DISPLAYED, true)
            val qrCode = mQrCodeDialog!!.findViewById<ImageView?>(R.id.qrcode_image_view)
            val deviceID = mQrCodeDialog!!.findViewById<TextView?>(R.id.device_id)
            checkNotNull(qrCode)
            outState.putParcelable(
                QRCODE_BITMAP_KEY,
                (qrCode.getDrawable() as BitmapDrawable).bitmap
            )
            checkNotNull(deviceID)
            outState.putString(DEVICE_ID_KEY, deviceID.getText().toString())
        }
        dismissDialogSafe(mRestartDialog, this)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        mDrawerToggle!!.syncState()

        val actionBar = supportActionBar
        actionBar?.setHomeButtonEnabled(true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mDrawerToggle!!.onConfigurationChanged(newConfig)
    }

    fun showRestartDialog() {
        mRestartDialog = createRestartDialog()
        mRestartDialog!!.show()
    }

    private fun createRestartDialog(): Dialog {
        return getAlertDialogBuilder(this)
            .setMessage(R.string.dialog_confirm_restart)
            .setPositiveButton(
                android.R.string.ok
            ) { _: DialogInterface?, _: Int ->
                this.startService(
                    Intent(this, SyncthingService::class.java)
                        .setAction(SyncthingService.ACTION_RESTART)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    fun showQrCodeDialog(deviceId: String?, qrCode: Bitmap?) {
        @SuppressLint("InflateParams") val qrCodeDialogView =
            this.layoutInflater.inflate(R.layout.dialog_qrcode, null)
        val deviceIdTextView = qrCodeDialogView.findViewById<TextView>(R.id.device_id)
        val shareDeviceIdTextView = qrCodeDialogView.findViewById<TextView>(R.id.actionShareId)
        val qrCodeImageView = qrCodeDialogView.findViewById<ImageView>(R.id.qrcode_image_view)

        deviceIdTextView.text = deviceId
        deviceIdTextView.setOnClickListener { _: View? ->
            copyDeviceId(
                this,
                deviceIdTextView.getText().toString()
            )
        }
        shareDeviceIdTextView.setOnClickListener { _: View? ->
            shareDeviceId(
                deviceId
            )
        }
        qrCodeImageView.setImageBitmap(qrCode)

        mQrCodeDialog = getAlertDialogBuilder(this)
            .setTitle(R.string.device_id)
            .setView(qrCodeDialogView)
            .setPositiveButton(R.string.finish, null)
            .create()

        mQrCodeDialog!!.show()
    }

    private fun shareDeviceId(deviceId: String?) {
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.setType("text/plain")
        shareIntent.putExtra(Intent.EXTRA_TEXT, deviceId)
        startActivity(
            Intent.createChooser(
                shareIntent,
                getString(R.string.share_device_id_chooser)
            )
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return mDrawerToggle!!.onOptionsItemSelected(item) || super.onOptionsItemSelected(item)
    }

    /**
     * Handles drawer opened and closed events, toggling option menu state.
     */
    private inner class Toggle(activity: Activity?, drawerLayout: DrawerLayout?) :
        ActionBarDrawerToggle(activity, drawerLayout, R.string.app_name, R.string.app_name) {
        override fun onDrawerOpened(drawerView: View) {
            super.onDrawerOpened(drawerView)
            mDrawerFragment!!.onDrawerOpened()
        }

        override fun onDrawerClosed(view: View) {
            super.onDrawerClosed(view)
            mDrawerFragment!!.onDrawerClosed()
        }

        override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
            super.onDrawerSlide(drawerView, 0f)
        }
    }

    /**
     * Closes the drawer. Use when navigating away from activity.
     */
    fun closeDrawer() {
        mDrawerLayout!!.closeDrawer(GravityCompat.START)
    }

    /**
     * Toggles the drawer on menu button press.
     */
    override fun onKeyDown(keyCode: Int, e: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!mDrawerLayout!!.isDrawerOpen(GravityCompat.START)) mDrawerLayout!!.openDrawer(
                GravityCompat.START
            )
            else closeDrawer()

            return true
        }
        return super.onKeyDown(keyCode, e)
    }

    /**
     * Calculating width based on
     * [navigation-drawer-specs](http://www.google.com/design/spec/patterns/navigation-drawer.html#navigation-drawer-specs).
     */
    private fun setOptimalDrawerWidth(drawerContainer: View) {
        var actionBarSize = 0
        val tv = TypedValue()
        if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            actionBarSize =
                TypedValue.complexToDimensionPixelSize(tv.data, getResources().displayMetrics)
        }

        val params = drawerContainer.layoutParams
        val displayMetrics = getResources().displayMetrics
        val minScreenWidth = min(displayMetrics.widthPixels, displayMetrics.heightPixels)

        params.width = min(minScreenWidth - actionBarSize, 5 * actionBarSize)
        drawerContainer.requestLayout()
    }

    /**
     * Displays dialog asking user to accept/deny usage reporting.
     */
    private fun showUsageReportingDialog(restApi: RestApi) {
        val listener = DialogInterface.OnClickListener { _: DialogInterface?, which: Int ->
            try {
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        restApi.setUsageReporting(true)
                        restApi.saveConfigAndRestart()
                    }

                    DialogInterface.BUTTON_NEGATIVE -> {
                        restApi.setUsageReporting(false)
                        restApi.saveConfigAndRestart()
                    }

                    DialogInterface.BUTTON_NEUTRAL -> {
                        val uri = "https://data.syncthing.net".toUri()
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "showUsageReportingDialog:OnClickListener", e)
            }
        }

        restApi.getUsageReport { report: String? ->
            @SuppressLint("InflateParams") val v = LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.dialog_usage_reporting, null)
            val tv = v.findViewById<TextView>(R.id.example)
            tv.text = report
            getAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.usage_reporting_dialog_title)
                .setView(v)
                .setPositiveButton(R.string.yes, listener)
                .setNegativeButton(R.string.no, listener)
                .setNeutralButton(R.string.open_website, listener)
                .show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val IS_SHOWING_RESTART_DIALOG = "RESTART_DIALOG_STATE"
        private const val BATTERY_DIALOG_DISMISSED = "BATTERY_DIALOG_STATE"
        private const val IS_QRCODE_DIALOG_DISPLAYED = "QRCODE_DIALOG_STATE"
        private const val QRCODE_BITMAP_KEY = "QRCODE_BITMAP"
        private const val DEVICE_ID_KEY = "DEVICEID"

        /**
         * Time after first start when usage reporting dialog should be shown.
         *
         * @see .showUsageReportingDialog
         */
        private val USAGE_REPORTING_DIALOG_DELAY = TimeUnit.DAYS.toMillis(3)
    }
}
