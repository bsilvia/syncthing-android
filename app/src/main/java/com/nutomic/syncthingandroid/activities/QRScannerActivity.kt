package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.nutomic.syncthingandroid.databinding.ActivityQrScannerBinding

class QRScannerActivity : ThemedAppCompatActivity(), BarcodeCallback {
    // endregion
    private val RC_HANDLE_CAMERA_PERM = 888

    private var binding: ActivityQrScannerBinding? = null

    // region === Activity Lifecycle ===
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding!!.getRoot())

        binding!!.cancelButton.setOnClickListener { _: View? ->
            finishScanning()
        }

        checkPermissionAndStartScanner()
    }

    override fun onStop() {
        super.onStop()
        finishScanning()
    }

    // endregion
    // region === Permissions Callback ===
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_HANDLE_CAMERA_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanner()
            } else {
                finish()
            }
        }
    }

    // endregion
    // region === BarcodeCallback ===
    override fun barcodeResult(result: BarcodeResult) {
        val code = result.text
        val intent = Intent()
        intent.putExtra(QR_RESULT_ARG, code)
        setResult(RESULT_OK, intent)
        finishScanning()
    }

    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint?>?) {
        // Unused
    }

    // endregion
    // region === Private Methods ===
    private fun checkPermissionAndStartScanner() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val permissions = arrayOf<String?>(Manifest.permission.CAMERA)
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM)
        } else {
            startScanner()
        }
    }

    private fun startScanner() {
        binding!!.barCodeScannerView.resume()
        binding!!.barCodeScannerView.decodeSingle(this)
    }

    private fun finishScanning() {
        binding!!.barCodeScannerView.pause()
        finish()
    } // endregion

    companion object {
        // region === Static ===
        const val QR_RESULT_ARG: String = "QR_CODE"
        fun intent(context: Context?): Intent {
            return Intent(context, QRScannerActivity::class.java)
        }
    }
}
