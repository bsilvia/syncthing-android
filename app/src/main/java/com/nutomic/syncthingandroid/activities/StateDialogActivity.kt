package com.nutomic.syncthingandroid.activities

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.databinding.DataBindingUtil
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.databinding.DialogLoadingBinding
import com.nutomic.syncthingandroid.model.RunConditionCheckResult
import com.nutomic.syncthingandroid.model.RunConditionCheckResult.BlockerReason
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingService.OnRunConditionCheckResultListener
import com.nutomic.syncthingandroid.service.SyncthingService.OnServiceStateChangeListener
import com.nutomic.syncthingandroid.util.Util.dismissDialogSafe
import com.nutomic.syncthingandroid.util.Util.getAlertDialogBuilder
import java.util.concurrent.TimeUnit

/**
 * Handles loading/disabled dialogs.
 */
abstract class StateDialogActivity : SyncthingActivity() {
    private var mServiceState = SyncthingService.State.INIT
    private var mLoadingDialog: AlertDialog? = null
    private var mDisabledDialog: AlertDialog? = null
    private var mIsPaused = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerOnServiceConnectedListener {
            service!!.registerOnServiceStateChangeListener { currentState: SyncthingService.State? ->
                this.onServiceStateChange(
                    currentState!!
                )
            }
            service!!.registerOnRunConditionCheckResultChange { _: RunConditionCheckResult? ->
                this.onRunConditionCheckResultChange(
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mIsPaused = false
        when (mServiceState) {
            SyncthingService.State.DISABLED -> showDisabledDialog()
            else -> {}
        }
    }

    override fun onPause() {
        super.onPause()
        mIsPaused = true
        dismissDisabledDialog()
        dismissLoadingDialog()
    }

    override fun onDestroy() {
        super.onDestroy()

        val onRunConditionCheckResultListener = OnRunConditionCheckResultListener { _: RunConditionCheckResult? ->
            this.onRunConditionCheckResultChange(
            )
        }
        val onServiceStateChangeListener = OnServiceStateChangeListener { currentState: SyncthingService.State? ->
            this.onServiceStateChange(
                currentState!!
            )
        }


        if (service != null) {
            service!!.unregisterOnServiceStateChangeListener(onServiceStateChangeListener)
            service!!.unregisterOnRunConditionCheckResultChange(onRunConditionCheckResultListener)
        }
        dismissDisabledDialog()
    }

    private fun onServiceStateChange(currentState: SyncthingService.State) {
        mServiceState = currentState
        when (mServiceState) {
            SyncthingService.State.INIT, SyncthingService.State.STARTING -> {
                dismissDisabledDialog()
                showLoadingDialog()
            }

            SyncthingService.State.ACTIVE -> {
                dismissDisabledDialog()
                dismissLoadingDialog()
            }

            SyncthingService.State.DISABLED -> if (!mIsPaused) {
                showDisabledDialog()
            }

            SyncthingService.State.ERROR -> {}
        }
    }

    private fun onRunConditionCheckResultChange() {
        if (mDisabledDialog != null && mDisabledDialog!!.isShowing) {
            mDisabledDialog!!.setMessage(this.disabledDialogMessage)
        }
    }

    private fun showDisabledDialog() {
        if (this.isFinishing && (mDisabledDialog != null)) {
            return
        }

        mDisabledDialog = getAlertDialogBuilder(this)
            .setTitle(R.string.syncthing_disabled_title)
            .setMessage(this.disabledDialogMessage)
            .setPositiveButton(
                R.string.syncthing_disabled_change_settings
            ) { _: DialogInterface?, _: Int ->
                val intent = Intent(this, SettingsActivity::class.java)
                intent.putExtra(
                    SettingsActivity.EXTRA_OPEN_SUB_PREF_SCREEN,
                    "category_run_conditions"
                )
                startActivity(intent)
            }
            .setNegativeButton(
                R.string.exit
            ) { _: DialogInterface?, _: Int ->
                ActivityCompat.finishAffinity(
                    this
                )
            }
            .setCancelable(false)
            .show()
    }

    private val disabledDialogMessage: StringBuilder
        get() {
            val message = java.lang.StringBuilder()
            message.append(this.getResources().getString(R.string.syncthing_disabled_message))
            val reasons: MutableCollection<BlockerReason?> =
                service?.currentRunConditionCheckResult!!.blockReasons
            if (!reasons.isEmpty()) {
                message.append("\n")
                message.append("\n")
                message.append(
                    this.getResources().getString(R.string.syncthing_disabled_reason_heading)
                )
                var count = 0
                for (reason in reasons) {
                    count++
                    message.append("\n")
                    if (reasons.size > 1) message.append("$count. ")
                    message.append(this.getString(reason!!.resId))
                }
            }
            return message
        }

    private fun dismissDisabledDialog() {
        dismissDialogSafe(mDisabledDialog, this)
        mDisabledDialog = null
    }

    /**
     * Shows the loading dialog with the correct text ("creating keys" or "loading").
     */
    private fun showLoadingDialog() {
        if (mIsPaused || mLoadingDialog != null) return

        val binding = DataBindingUtil.inflate<DialogLoadingBinding>(
            layoutInflater, R.layout.dialog_loading, null, false
        )
        val isGeneratingKeys = intent.getBooleanExtra(
            EXTRA_KEY_GENERATION_IN_PROGRESS,
            false
        )
        binding.loadingText.setText(
            if (isGeneratingKeys)
                R.string.web_gui_creating_key
            else
                R.string.api_loading
        )

        mLoadingDialog = getAlertDialogBuilder(this)
            .setCancelable(false)
            .setView(binding.root)
            .show()

        if (!isGeneratingKeys) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (this.isFinishing || mLoadingDialog == null) return@postDelayed
                binding.loadingSlowMessage.visibility = View.VISIBLE
                binding.viewLogs.setOnClickListener { _: View? ->
                    startActivity(
                        Intent(this, LogActivity::class.java)
                    )
                }
            }, SLOW_LOADING_TIME)
        }
    }

    private fun dismissLoadingDialog() {
        dismissDialogSafe(mLoadingDialog, this)
        mLoadingDialog = null
    }

    companion object {
        private val SLOW_LOADING_TIME = TimeUnit.SECONDS.toMillis(30)
    }
}
