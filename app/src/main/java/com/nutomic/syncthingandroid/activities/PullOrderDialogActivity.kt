package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import com.nutomic.syncthingandroid.databinding.ActivityPullorderDialogBinding

class PullOrderDialogActivity : ThemedAppCompatActivity() {
    private var selectedType: String? = null

    private var binding: ActivityPullorderDialogBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPullorderDialogBinding.inflate(layoutInflater)
        setContentView(binding!!.getRoot())
        if (savedInstanceState == null) {
            selectedType = intent.getStringExtra(EXTRA_PULL_ORDER)
        }
        initiateFinishBtn()
        initiateSpinner()
    }

    private fun initiateFinishBtn() {
        binding!!.finishBtn.setOnClickListener { _: View? ->
            saveConfiguration()
            finish()
        }
    }

    private fun saveConfiguration() {
        val intent = Intent()
        intent.putExtra(EXTRA_RESULT_PULL_ORDER, selectedType)
        setResult(RESULT_OK, intent)
    }

    private fun initiateSpinner() {
        binding!!.pullOrderTypeSpinner.setSelection(mTypes.indexOf(selectedType))
        binding!!.pullOrderTypeSpinner.onItemSelectedListener = object :
            AdapterView.OnItemSelectedListener {
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
        const val EXTRA_PULL_ORDER: String =
            "com.nutomic.syncthinandroid.activities.PullOrderDialogActivity.PULL_ORDER"
        const val EXTRA_RESULT_PULL_ORDER: String =
            "com.nutomic.syncthinandroid.activities.PullOrderDialogActivity.EXTRA_RESULT_PULL_ORDER"

        private val mTypes = mutableListOf<String?>(
            "random",
            "alphabetic",
            "smallestFirst",
            "largestFirst",
            "oldestFirst",
            "newestFirst"
        )
    }
}
