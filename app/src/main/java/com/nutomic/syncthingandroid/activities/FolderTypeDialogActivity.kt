package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.Spinner
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants

class FolderTypeDialogActivity : ThemedAppCompatActivity() {
    private var selectedType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_foldertype_dialog)
        if (savedInstanceState == null) {
            selectedType = intent.getStringExtra(EXTRA_FOLDER_TYPE)
        }
        initiateFinishBtn()
        initiateSpinner()
    }

    private fun initiateFinishBtn() {
        val finishBtn = findViewById<Button>(R.id.finish_btn)
        finishBtn.setOnClickListener { _: View? ->
            saveConfiguration()
            finish()
        }
    }

    private fun saveConfiguration() {
        val intent = Intent()
        intent.putExtra(EXTRA_RESULT_FOLDER_TYPE, selectedType)
        setResult(RESULT_OK, intent)
    }

    private fun initiateSpinner() {
        val folderTypeSpinner = findViewById<Spinner>(R.id.folderTypeSpinner)
        folderTypeSpinner.setSelection(mTypes.indexOf(selectedType))
        folderTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position != mTypes.indexOf(selectedType)) {
                    selectedType = mTypes[position]
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // This is not allowed.
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveConfiguration()
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_FOLDER_TYPE: String =
            "com.nutomic.syncthinandroid.activities.FolderTypeDialogActivity.FOLDER_TYPE"
        const val EXTRA_RESULT_FOLDER_TYPE: String =
            "com.nutomic.syncthinandroid.activities.FolderTypeDialogActivity.EXTRA_RESULT_FOLDER_TYPE"

        private val mTypes: List<String> = listOf<String>(
            Constants.FOLDER_TYPE_SEND_RECEIVE,
            Constants.FOLDER_TYPE_SEND_ONLY,
            Constants.FOLDER_TYPE_RECEIVE_ONLY
        )
    }
}
