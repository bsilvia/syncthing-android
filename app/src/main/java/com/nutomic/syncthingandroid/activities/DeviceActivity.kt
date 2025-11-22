package com.nutomic.syncthingandroid.activities

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.databinding.ActivityDeviceBinding
import com.nutomic.syncthingandroid.model.Connections
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.service.RestApi.OnResultListener1
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingService.OnServiceStateChangeListener
import com.nutomic.syncthingandroid.util.Compression
import com.nutomic.syncthingandroid.util.Compression.Companion.fromIndex
import com.nutomic.syncthingandroid.util.Compression.Companion.fromValue
import com.nutomic.syncthingandroid.util.TextWatcherAdapter
import com.nutomic.syncthingandroid.util.Util.copyDeviceId
import com.nutomic.syncthingandroid.util.Util.dismissDialogSafe
import com.nutomic.syncthingandroid.util.Util.getAlertDialogBuilder

/**
 * Shows device details and allows changing them.
 */
class DeviceActivity : SyncthingActivity(), View.OnClickListener {
    private var mDevice: Device? = null

    private var binding: ActivityDeviceBinding? = null

    private var mIsCreateMode = false

    private var mDeviceNeedsToUpdate = false

    private var mDeleteDialog: Dialog? = null
    private var mDiscardDialog: Dialog? = null
    private var mCompressionDialog: Dialog? = null

    private val mCompressionEntrySelectedListener: DialogInterface.OnClickListener =
        DialogInterface.OnClickListener { dialog, which ->
            dialog.dismiss()
            val compression = fromIndex(which)
            // Don't pop the restart dialog unless the value is actually different.
            if (compression != fromValue(this@DeviceActivity, mDevice!!.compression)) {
                mDeviceNeedsToUpdate = true

                mDevice!!.compression = compression.getValue(this@DeviceActivity)
                binding!!.compressionValue.text = compression.getTitle(this@DeviceActivity)
            }
        }

    private val mIdTextWatcher: TextWatcher = object : TextWatcherAdapter() {
        override fun afterTextChanged(s: Editable?) {
            if (s.toString() != mDevice!!.deviceID) {
                mDeviceNeedsToUpdate = true
                mDevice!!.deviceID = s.toString()
            }
        }
    }

    private val mNameTextWatcher: TextWatcher = object : TextWatcherAdapter() {
        override fun afterTextChanged(s: Editable?) {
            if (s.toString() != mDevice!!.name) {
                mDeviceNeedsToUpdate = true
                mDevice!!.name = s.toString()
            }
        }
    }

    private val mAddressesTextWatcher: TextWatcher = object : TextWatcherAdapter() {
        override fun afterTextChanged(s: Editable?) {
            if (s.toString() != displayableAddresses()) {
                mDeviceNeedsToUpdate = true
                mDevice!!.addresses = persistableAddresses(s)
            }
        }
    }

    private val mCheckedListener: CompoundButton.OnCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { view, isChecked ->
            when (view.id) {
                R.id.introducer -> {
                    mDevice!!.introducer = isChecked
                    mDeviceNeedsToUpdate = true
                }

                R.id.devicePause -> {
                    mDevice!!.paused = isChecked
                    mDeviceNeedsToUpdate = true
                }
            }
        }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBinding.inflate(layoutInflater)
        setContentView(binding!!.getRoot())

        mIsCreateMode = intent.getBooleanExtra(EXTRA_IS_CREATE, false)
        registerOnServiceConnectedListener(OnServiceConnectedListener { this.onServiceConnected() })
        setTitle(if (mIsCreateMode) R.string.add_device else R.string.edit_device)

        binding!!.qrButton.setOnClickListener(this)
        binding!!.compressionContainer.setOnClickListener(this)

        if (savedInstanceState != null) {
            if (mDevice == null) {
                mDevice = Gson().fromJson<Device?>(
                    savedInstanceState.getString("device"),
                    Device::class.java
                )
            }
            restoreDialogStates(savedInstanceState)
        }
        if (mIsCreateMode) {
            if (mDevice == null) {
                initDevice()
            }
        } else {
            prepareEditMode()
        }
    }

    private fun restoreDialogStates(savedInstanceState: Bundle) {
        if (savedInstanceState.getBoolean(IS_SHOWING_COMPRESSION_DIALOG)) {
            showCompressionDialog()
        }

        if (savedInstanceState.getBoolean(IS_SHOWING_DELETE_DIALOG)) {
            showDeleteDialog()
        }

        if (mIsCreateMode) {
            if (savedInstanceState.getBoolean(IS_SHOWING_DISCARD_DIALOG)) {
                showDiscardDialog()
            }
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        val syncthingService = service
        if (syncthingService != null) {
            syncthingService.getNotificationHandler()!!
                .cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
            val unregisterOnServiceStateChangeListener = OnServiceStateChangeListener { currentState: SyncthingService.State? ->
                this.onServiceStateChange(
                    currentState
                )
            }
            syncthingService.unregisterOnServiceStateChangeListener(unregisterOnServiceStateChangeListener)
        }
        binding!!.id.removeTextChangedListener(mIdTextWatcher)
        binding!!.name.removeTextChangedListener(mNameTextWatcher)
        binding!!.addresses.removeTextChangedListener(mAddressesTextWatcher)
    }

    public override fun onPause() {
        super.onPause()

        // We don't want to update every time a TextView's character changes,
        // so we hold off until the view stops being visible to the user.
        if (mDeviceNeedsToUpdate) {
            updateDevice()
        }
    }

    /**
     * Save current settings in case we are in create mode and they aren't yet
     * stored in the config.
     */
    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("device", Gson().toJson(mDevice))
        if (mIsCreateMode) {
            outState.putBoolean(
                IS_SHOWING_DISCARD_DIALOG,
                mDiscardDialog != null && mDiscardDialog!!.isShowing
            )
            dismissDialogSafe(mDiscardDialog, this)
        }

        outState.putBoolean(
            IS_SHOWING_COMPRESSION_DIALOG,
            mCompressionDialog != null && mCompressionDialog!!.isShowing
        )
        dismissDialogSafe(mCompressionDialog, this)

        outState.putBoolean(
            IS_SHOWING_DELETE_DIALOG,
            mDeleteDialog != null && mDeleteDialog!!.isShowing
        )
        dismissDialogSafe(mDeleteDialog, this)
    }

    private fun onServiceConnected() {
        Log.v(TAG, "onServiceConnected")
        val syncthingService = service
        syncthingService!!.getNotificationHandler()!!
            .cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        syncthingService.registerOnServiceStateChangeListener { currentState: SyncthingService.State? ->
            this.onServiceStateChange(
                currentState
            )
        }
    }

    /**
     * Sets version and current address of the device.
     *
     *
     * NOTE: This is only called once on startup, should be called more often to
     * properly display
     * version/address changes.
     */
    private fun onReceiveConnections(connections: Connections) {
        val viewsExist = true
        if (viewsExist && connections.connections!!.containsKey(mDevice!!.deviceID)) {
            binding!!.currentAddress.visibility = View.VISIBLE
            binding!!.syncthingVersion.visibility = View.VISIBLE
            binding!!.currentAddress.text = connections.connections!![mDevice!!.deviceID]!!.address
            binding!!.syncthingVersion.text = connections.connections!![mDevice!!.deviceID]!!.clientVersion
        }
    }

    private fun onServiceStateChange(currentState: SyncthingService.State?) {
        if (currentState != SyncthingService.State.ACTIVE) {
            finish()
            return
        }

        if (!mIsCreateMode) {
            val devices = api?.getDevices(false)
            mDevice = null
            for (device in devices!!) {
                if (device.deviceID == intent.getStringExtra(EXTRA_DEVICE_ID)) {
                    mDevice = device
                    break
                }
            }
            if (mDevice == null) {
                Log.w(TAG, "Device not found in API update, maybe it was deleted?")
                finish()
                return
            }
        }

        api?.getConnections(OnResultListener1 { connections: Connections? ->
            this.onReceiveConnections(
                connections!!
            )
        })

        updateViewsAndSetListeners()
    }

    private fun updateViewsAndSetListeners() {
        binding!!.id.removeTextChangedListener(mIdTextWatcher)
        binding!!.name.removeTextChangedListener(mNameTextWatcher)
        binding!!.addresses.removeTextChangedListener(mAddressesTextWatcher)
        binding!!.introducer.setOnCheckedChangeListener(null)
        binding!!.devicePause.setOnCheckedChangeListener(null)

        // Update views
        binding!!.id.setText(mDevice!!.deviceID)
        binding!!.name.setText(mDevice!!.name)
        binding!!.addresses.setText(displayableAddresses())
        binding!!.compressionValue.text = fromValue(this, mDevice!!.compression).getTitle(this)
        binding!!.introducer.setChecked(mDevice!!.introducer)
        binding!!.devicePause.setChecked(mDevice!!.paused)

        // Keep state updated
        binding!!.id.addTextChangedListener(mIdTextWatcher)
        binding!!.name.addTextChangedListener(mNameTextWatcher)
        binding!!.addresses.addTextChangedListener(mAddressesTextWatcher)
        binding!!.introducer.setOnCheckedChangeListener(mCheckedListener)
        binding!!.devicePause.setOnCheckedChangeListener(mCheckedListener)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.device_settings, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.create).isVisible = mIsCreateMode
        menu.findItem(R.id.share_device_id).isVisible = !mIsCreateMode
        menu.findItem(R.id.remove).isVisible = !mIsCreateMode
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.create -> {
                if (TextUtils.isEmpty(mDevice!!.deviceID)) {
                    Toast.makeText(this, R.string.device_id_required, Toast.LENGTH_LONG)
                        .show()
                    return true
                }
                api?.addDevice(
                    mDevice!!,
                    OnResultListener1 { error: String? ->
                        Toast.makeText(
                            this,
                            error,
                            Toast.LENGTH_LONG
                        ).show()
                    })
                finish()
                return true
            }

            R.id.share_device_id -> {
                shareDeviceId(this, mDevice!!.deviceID)
                return true
            }

            R.id.remove -> {
                showDeleteDialog()
                return true
            }

            android.R.id.home -> {
                onBackPressed()
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun showDeleteDialog() {
        mDeleteDialog = createDeleteDialog()
        mDeleteDialog!!.show()
    }

    private fun createDeleteDialog(): Dialog {
        return getAlertDialogBuilder(this)
            .setMessage(R.string.remove_device_confirm)
            .setPositiveButton(
                android.R.string.yes
            ) { _: DialogInterface?, _: Int ->
                api?.removeDevice(mDevice!!.deviceID)
                finish()
            }
            .setNegativeButton(android.R.string.no, null)
            .create()
    }

    /**
     * Receives value of scanned QR code and sets it as device ID.
     */
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (requestCode == QR_SCAN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                val scanResult = intent.getStringExtra(QRScannerActivity.QR_RESULT_ARG)
                if (scanResult != null) {
                    mDevice!!.deviceID = scanResult
                    binding!!.id.setText(mDevice!!.deviceID)
                }
            }
        }
    }

    private fun initDevice() {
        mDevice = Device()
        mDevice!!.name = intent.getStringExtra(EXTRA_DEVICE_NAME)!!
        mDevice!!.deviceID = intent.getStringExtra(EXTRA_DEVICE_ID)
        mDevice!!.addresses = DYNAMIC_ADDRESS
        mDevice!!.compression = Compression.METADATA.getValue(this)
        mDevice!!.introducer = false
        mDevice!!.paused = false
    }

    private fun prepareEditMode() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val dr = ContextCompat.getDrawable(this, R.drawable.ic_content_copy_24dp)
        binding!!.id.setCompoundDrawablesWithIntrinsicBounds(null, null, dr, null)
        binding!!.id.setEnabled(false)
        binding!!.qrButton.setVisibility(View.GONE)

        binding!!.idContainer.setOnClickListener(this)
    }

    /**
     * Sends the updated device info if in edit mode.
     */
    private fun updateDevice() {
        if (!mIsCreateMode && mDeviceNeedsToUpdate && mDevice != null) {
            api?.editDevice(mDevice!!)
        }
    }

    private fun persistableAddresses(userInput: CharSequence?): MutableList<String?>? {
        return (if (TextUtils.isEmpty(userInput))
            DYNAMIC_ADDRESS
        else
            listOf<String?>(
                *userInput.toString().split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray())) as MutableList<String?>?
    }

    private fun displayableAddresses(): String? {
        val list = if (DYNAMIC_ADDRESS == mDevice!!.addresses)
            DYNAMIC_ADDRESS
        else
            mDevice!!.addresses
        return TextUtils.join(" ", list!!)
    }

    override fun onClick(v: View) {
        when (v) {
            binding!!.compressionContainer -> {
                showCompressionDialog()
            }
            binding!!.qrButton -> {
                val qrIntent: Intent = QRScannerActivity.intent(this)
                startActivityForResult(qrIntent, QR_SCAN_REQUEST_CODE)
            }
            binding!!.idContainer -> {
                copyDeviceId(this, mDevice!!.deviceID)
            }
        }
    }

    private fun showCompressionDialog() {
        mCompressionDialog = createCompressionDialog()
        mCompressionDialog!!.show()
    }

    private fun createCompressionDialog(): Dialog {
        return getAlertDialogBuilder(this)
            .setTitle(R.string.compression)
            .setSingleChoiceItems(
                R.array.compress_entries,
                fromValue(this, mDevice!!.compression).index,
                mCompressionEntrySelectedListener
            )
            .create()
    }

    /**
     * Shares the given device ID via Intent. Must be called from an Activity.
     */
    private fun shareDeviceId(context: Context, id: String?) {
        val shareIntent = Intent()
        shareIntent.setAction(Intent.ACTION_SEND)
        shareIntent.setType("text/plain")
        shareIntent.putExtra(Intent.EXTRA_TEXT, id)
        context.startActivity(
            Intent.createChooser(
                shareIntent, context.getString(R.string.send_device_id_to)
            )
        )
    }

    override fun onBackPressed() {
        if (mIsCreateMode) {
            showDiscardDialog()
        } else {
            super.onBackPressed()
        }
    }

    private fun showDiscardDialog() {
        mDiscardDialog = createDiscardDialog()
        mDiscardDialog!!.show()
    }

    private fun createDiscardDialog(): Dialog {
        return getAlertDialogBuilder(this)
            .setMessage(R.string.dialog_discard_changes)
            .setPositiveButton(
                android.R.string.ok
            ) { _: DialogInterface?, _: Int -> finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID: String =
            "com.nutomic.syncthingandroid.activities.DeviceActivity.NOTIFICATION_ID"
        const val EXTRA_DEVICE_ID: String =
            "com.nutomic.syncthingandroid.activities.DeviceActivity.DEVICE_ID"
        const val EXTRA_DEVICE_NAME: String =
            "com.nutomic.syncthingandroid.activities.DeviceActivity.DEVICE_NAME"
        const val EXTRA_IS_CREATE: String =
            "com.nutomic.syncthingandroid.activities.DeviceActivity.IS_CREATE"

        private const val TAG = "DeviceSettingsFragment"
        private const val IS_SHOWING_DISCARD_DIALOG = "DISCARD_FOLDER_DIALOG_STATE"
        private const val IS_SHOWING_COMPRESSION_DIALOG = "COMPRESSION_FOLDER_DIALOG_STATE"
        private const val IS_SHOWING_DELETE_DIALOG = "DELETE_FOLDER_DIALOG_STATE"
        private const val QR_SCAN_REQUEST_CODE = 777

        private val DYNAMIC_ADDRESS = mutableListOf<String?>("dynamic")
    }
}
