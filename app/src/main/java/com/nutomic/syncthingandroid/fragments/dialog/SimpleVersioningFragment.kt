package com.nutomic.syncthingandroid.fragments.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.fragment.app.Fragment
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.fragments.NumberPickerFragment

/**
 * Contains the configuration options for simple file versioning.
 */
class SimpleVersioningFragment : Fragment() {
    private var mArguments: Bundle? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_simple_versioning, container, false)
        mArguments = arguments
        fillArguments()
        updateNumberPicker()
        return view
    }

    private fun fillArguments() {
        if (missingParameters()) {
            mArguments!!.putString("keep", "5")
        }
    }

    private fun missingParameters(): Boolean {
        return !mArguments!!.containsKey("keep")
    }

    //a NumberPickerFragment is nested in the fragment_simple_versioning layout, the values for it are update below.
    private fun updateNumberPicker() {
        val numberPicker =
            getChildFragmentManager().findFragmentByTag("numberpicker_simple_versioning") as NumberPickerFragment?
        numberPicker!!.updateNumberPicker(100000, 1, this.keepVersions)
        numberPicker.setOnValueChangedListener { _: NumberPicker?, _: Int, newVal: Int ->
            updateKeepVersions(
                (newVal.toString())
            )
        }
    }

    private fun updateKeepVersions(newValue: String?) {
        mArguments!!.putString("keep", newValue)
    }

    private val keepVersions: Int
        get() = mArguments!!.getString("keep")!!.toInt()
}
