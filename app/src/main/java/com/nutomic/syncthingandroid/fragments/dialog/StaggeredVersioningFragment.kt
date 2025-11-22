package com.nutomic.syncthingandroid.fragments.dialog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.FolderPickerActivity
import com.nutomic.syncthingandroid.fragments.NumberPickerFragment
import java.util.concurrent.TimeUnit

/**
 * Contains the configuration options for Staggered file versioning.
 */
class StaggeredVersioningFragment : Fragment() {
    private var mView: View? = null

    private var mArguments: Bundle? = null

    private var mPathView: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        mView = inflater.inflate(R.layout.fragment_staggered_versioning, container, false)
        mArguments = arguments
        fillArguments()
        updateNumberPicker()
        initiateVersionsPathTextView()
        return mView
    }

    private fun fillArguments() {
        if (missingParameters()) {
            mArguments!!.putString("maxAge", "0")
            mArguments!!.putString("versionsPath", "")
        }
    }

    private fun missingParameters(): Boolean {
        return !mArguments!!.containsKey("maxAge")
    }

    //The maxAge parameter is displayed in days but stored in seconds since Syncthing needs it in seconds.
    //A NumberPickerFragment is nested in the fragment_staggered_versioning layout, the values for it are update below.
    private fun updateNumberPicker() {
        val numberPicker =
            getChildFragmentManager().findFragmentByTag("numberpicker_staggered_versioning") as NumberPickerFragment?
        numberPicker!!.updateNumberPicker(100, 0, this.maxAgeInDays)
        numberPicker.setOnValueChangedListener { _: NumberPicker?, _: Int, newVal: Int ->
            updatePreference(
                "maxAge",
                (TimeUnit.DAYS.toSeconds(newVal.toLong()).toString())
            )
        }
    }

    private fun initiateVersionsPathTextView() {
        mPathView = mView!!.findViewById(R.id.directoryTextView)
        val currentPath = this.versionsPath

        mPathView!!.text = currentPath
        mPathView!!.setOnClickListener { _: View? ->
            startActivityForResult(
                FolderPickerActivity.createIntent(context, currentPath, null),
                FolderPickerActivity.DIRECTORY_REQUEST_CODE
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && requestCode == FolderPickerActivity.DIRECTORY_REQUEST_CODE) {
            updatePath(data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY))
        }
    }

    private fun updatePath(directory: String?) {
        mPathView!!.text = directory
        updatePreference("versionsPath", directory)
    }

    private val versionsPath: String?
        get() = mArguments!!.getString("versionsPath")

    private fun updatePreference(key: String?, newValue: String?) {
        requireArguments().putString(key, newValue)
    }

    private val maxAgeInDays: Int
        get() = TimeUnit.SECONDS.toDays(
            mArguments!!.getString("maxAge")!!.toLong()
        ).toInt()
}
