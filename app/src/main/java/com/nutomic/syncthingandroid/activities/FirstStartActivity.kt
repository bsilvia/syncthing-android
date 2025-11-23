package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.text.HtmlCompat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
// Note: avoid annotating lifecycle methods with @RequiresApi; use runtime checks instead
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.databinding.ActivityFirstStartBinding
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.Constants.PermissionRequestType
import com.nutomic.syncthingandroid.service.Constants.getConfigFile
import com.nutomic.syncthingandroid.util.PermissionUtil.haveStoragePermission
import com.nutomic.syncthingandroid.util.PermissionUtil.locationPermissions
import com.nutomic.syncthingandroid.util.Util.runShellCommand
import org.apache.commons.io.FileUtils
import java.io.File
import javax.inject.Inject

class FirstStartActivity : Activity() {
    private enum class Slide(val layout: Int) {
        INTRO(R.layout.activity_firststart_slide_intro),

        STORAGE(R.layout.activity_firststart_slide_storage),
        LOCATION(R.layout.activity_firststart_slide_location),
        API_LEVEL_30(R.layout.activity_firststart_slide_api_level_30),
        NOTIFICATION(R.layout.activity_firststart_slide_notification)
    }

    private var mViewPagerAdapter: ViewPagerAdapter? = null
    private var mDots: Array<TextView?> = arrayOf()

    private var binding: ActivityFirstStartBinding? = null

    @JvmField
    @Inject
    var mPreferences: SharedPreferences? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component()!!.inject(this)

        /**
         * Recheck storage permission. If it has been revoked after the user
         * completed the welcome slides, displays the slides again.
         */
        if (!this.isFirstStart && haveStoragePermission(this) && upgradedToApiLevel30()) {
            startApp()
            return
        }

        // Show first start welcome wizard UI.
        binding = ActivityFirstStartBinding.inflate(layoutInflater)
        setContentView(binding!!.getRoot())

        binding!!.viewPager.setOnTouchListener { v, _ -> // Consume the event to prevent swiping through the slides.
            v.performClick()
            true
        }

        // Add bottom dots
        addBottomDots()
        setActiveBottomDot(0)

        mViewPagerAdapter = ViewPagerAdapter()
        binding!!.viewPager.adapter = mViewPagerAdapter
        binding!!.viewPager.registerOnPageChangeCallback(mViewPagerPageChangeCallback)

        binding!!.btnBack.setOnClickListener { onBtnBackClick() }

        binding!!.btnNext.setOnClickListener { onBtnNextClick() }

        if (!this.isFirstStart) {
            // Skip intro slide
            onBtnNextClick()
        }
    }

    fun onBtnBackClick() {
        val current = binding!!.viewPager.currentItem - 1
        if (current >= 0) {
            // Move to previous slider.
            binding!!.viewPager.currentItem = current
            if (current == 0) {
                binding!!.btnBack.visibility = View.GONE
            }
        }
    }

    fun onBtnNextClick() {
        val slide = currentSlide()
        // Check if we are allowed to advance to the next slide.
        when (slide) {
            Slide.STORAGE -> {
                // As the storage permission is a prerequisite to run syncthing, refuse to continue without it.
                val storagePermissionsGranted = haveStoragePermission(this)
                if (!storagePermissionsGranted) {
                    Toast.makeText(
                        this, R.string.toast_write_storage_permission_required,
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }

            Slide.API_LEVEL_30 -> if (!upgradedToApiLevel30()) {
                Toast.makeText(
                    this, R.string.toast_api_level_30_must_reset,
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            else -> {}
        }

        var next = binding!!.viewPager.currentItem + 1
        while (next < slides.size) {
            if (!shouldSkipSlide(slides[next])) {
                binding!!.viewPager.currentItem = next
                binding!!.btnBack.visibility = View.VISIBLE
                break
            }
            next++
        }
        if (next == slides.size) {
            // Start the app after "mNextButton" was hit on the last slide.
            Log.v(TAG, "User completed first start UI.")
            mPreferences!!.edit { putBoolean(Constants.PREF_FIRST_START, false) }
            startApp()
        }
    }

    private val isFirstStart: Boolean
        get() = mPreferences!!.getBoolean(
            Constants.PREF_FIRST_START,
            true
        )

    private val isNotificationPermissionGranted: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Prior to Android 13 notifications do not require runtime permission
            true
        }


    private fun upgradedToApiLevel30(): Boolean {
        if (mPreferences!!.getBoolean(Constants.PREF_UPGRADED_TO_API_LEVEL_30, false)) {
            return true
        }
        if (this.isFirstStart) {
            mPreferences!!.edit { putBoolean(Constants.PREF_UPGRADED_TO_API_LEVEL_30, true) }
            return true
        }
        return false
    }

    private fun upgradeToApiLevel30() {
        val dbDir = File(this.filesDir, "index-v0.14.0.db")
        if (dbDir.exists()) {
            try {
                FileUtils.deleteQuietly(dbDir)
            } catch (e: Throwable) {
                Log.w(TAG, "Deleting database with FileUtils failed", e)
                runShellCommand("rm -r " + dbDir.absolutePath, false)
                if (dbDir.exists()) {
                    throw RuntimeException("Failed to delete existing database")
                }
            }
        }
        mPreferences!!.edit { putBoolean(Constants.PREF_UPGRADED_TO_API_LEVEL_30, true) }
    }

    private fun currentSlide(): Slide {
        return slides[binding!!.viewPager.currentItem]
    }

    private fun shouldSkipSlide(slide: Slide): Boolean {
        return when (slide) {
            Slide.INTRO -> !this.isFirstStart
            Slide.STORAGE -> haveStoragePermission(this)
            Slide.LOCATION -> hasLocationPermission()
            Slide.API_LEVEL_30 ->                 // Skip if running as root, as that circumvents any Android FS restrictions.
                upgradedToApiLevel30()
                        || mPreferences!!.getBoolean(Constants.PREF_USE_ROOT, false)

            Slide.NOTIFICATION -> this.isNotificationPermissionGranted

        }
    }

    private fun addBottomDots() {
        mDots = arrayOfNulls(slides.size)
        for (i in mDots.indices) {
            mDots[i] = TextView(this)
            mDots[i]!!.text = HtmlCompat.fromHtml("&#8226;", HtmlCompat.FROM_HTML_MODE_LEGACY)
            mDots[i]!!.textSize = 35f
            binding!!.layoutDots.addView(mDots[i])
        }
    }

    private fun setActiveBottomDot(currentPage: Int) {
        val colorInactive = MaterialColors.getColor(this, R.attr.colorPrimary, Color.BLUE)
        val colorActive = MaterialColors.getColor(this, R.attr.colorSecondary, Color.BLUE)
        for (mDot in mDots) {
            mDot!!.setTextColor(colorInactive)
        }
        mDots[currentPage]!!.setTextColor(colorActive)
    }

    // ViewPager2 change callback
    private val mViewPagerPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            setActiveBottomDot(position)
            // Change the next button text from next to finish on last slide.
            binding!!.btnNext.text = getString(if (position == slides.size - 1) R.string.finish else R.string.cont)
        }
    }

    /**
     * View pager adapter
     */
    inner class ViewPagerAdapter : RecyclerView.Adapter<ViewPagerAdapter.ViewHolder>() {
        inner class ViewHolder(val root: View) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = inflater.inflate(viewType, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (slides[position]) {
                Slide.INTRO -> {}
                Slide.STORAGE -> {
                    val btnGrantStoragePerm = holder.root.findViewById<Button>(R.id.btnGrantStoragePerm)
                    btnGrantStoragePerm?.setOnClickListener { requestStoragePermission() }
                }

                Slide.LOCATION -> {
                    val btnGrantLocationPerm = holder.root.findViewById<Button>(R.id.btnGrantLocationPerm)
                    btnGrantLocationPerm?.setOnClickListener { requestLocationPermission() }
                }

                Slide.API_LEVEL_30 -> {
                    val btnResetDatabase = holder.root.findViewById<Button>(R.id.btnResetDatabase)
                    btnResetDatabase?.setOnClickListener {
                        upgradeToApiLevel30()
                        onBtnNextClick()
                    }
                }

                Slide.NOTIFICATION -> {
                    val notificationBtn = holder.root.findViewById<Button>(R.id.btn_notification)
                    notificationBtn?.setOnClickListener { requestNotificationPermission() }
                }
            }
        }

        override fun getItemCount(): Int = slides.size

        override fun getItemViewType(position: Int): Int = slides[position].layout
    }

    /**
     * Preconditions:
     * Storage permission has been granted.
     */
    private fun startApp() {
        val doInitialKeyGeneration = !getConfigFile(this).exists()
        val mainIntent = Intent(this, MainActivity::class.java)
        mainIntent.putExtra(
            SyncthingActivity.EXTRA_KEY_GENERATION_IN_PROGRESS,
            doInitialKeyGeneration
        )
        /**
         * In case start_into_web_gui option is enabled, start both activities
         * so that back navigation works as expected.
         */
        if (mPreferences!!.getBoolean(Constants.PREF_START_INTO_WEB_GUI, false)) {
            startActivities(arrayOf(mainIntent, Intent(this, WebGuiActivity::class.java)))
        } else {
            startActivity(mainIntent)
        }
        finish()
    }

    private fun hasLocationPermission(): Boolean {
        for (perm in locationPermissions) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    perm!!
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Permission check and request functions
     */
    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            locationPermissions,
            PermissionRequestType.LOCATION.ordinal
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestAllFilesAccessPermission()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf<String?>(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                PermissionRequestType.STORAGE.ordinal
            )
        }
    }

    private fun requestAllFilesAccessPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.setData(("package:$packageName").toUri())
        try {
            val componentName = intent.resolveActivity(packageManager)
            if (componentName != null) {
                // Launch "Allow all files access?" dialog.
                startActivity(intent)
                return
            }
            Log.w(TAG, "Request all files access not supported")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Request all files access not supported", e)
        }
        Toast.makeText(this, R.string.dialog_all_files_access_not_supported, Toast.LENGTH_LONG)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>,
        grantResults: IntArray
    ) {
        when (PermissionRequestType.entries[requestCode]) {
            PermissionRequestType.LOCATION -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "User denied foreground location permission")
                    return
                }
                Log.i(TAG, "User granted foreground location permission")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ActivityCompat.requestPermissions(
                        this,
                        locationPermissions,
                        PermissionRequestType.LOCATION_BACKGROUND.ordinal
                    )
                }
            }

            PermissionRequestType.LOCATION_BACKGROUND -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "User denied background location permission")
                    return
                }
                Log.i(TAG, "User granted background location permission")
                Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            }

            PermissionRequestType.STORAGE -> if (grantResults.isEmpty() ||
                grantResults[0] != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "User denied WRITE_EXTERNAL_STORAGE permission.")
            } else {
                Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
                Log.i(TAG, "User granted WRITE_EXTERNAL_STORAGE permission.")
            }

        }
    }

    companion object {
        private val slides = Slide.entries.toTypedArray()
        private const val TAG = "FirstStartActivity"
    }
}
