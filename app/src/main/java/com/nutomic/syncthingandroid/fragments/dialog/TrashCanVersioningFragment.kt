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
 * Contains the configuration options for trashcan file versioning.
 */
class TrashCanVersioningFragment : Fragment() {
    private var mArguments: Bundle? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_trashcan_versioning, container, false)
        mArguments = arguments
        fillArguments()
        updateNumberPicker()
        return view
    }

    private fun fillArguments() {
        if (missingParameters()) {
            mArguments!!.putString("cleanoutDays", "0")
        }
    }

    private fun missingParameters(): Boolean {
        return !mArguments!!.containsKey("cleanoutDays")
    }

    //a NumberPickerFragment is nested in the fragment_trashcan_versioning layout, the values for it are update below.
    private fun updateNumberPicker() {
        val numberPicker =
            getChildFragmentManager().findFragmentByTag("numberpicker_trashcan_versioning") as NumberPickerFragment?
        numberPicker!!.updateNumberPicker(100, 0, this.cleanoutDays)
        numberPicker.setOnValueChangedListener { _: NumberPicker?, _: Int, newVal: Int ->
            updateCleanoutDays(
                (newVal.toString())
            )
        }
    }

    private val cleanoutDays: Int
        get() = mArguments!!.getString("cleanoutDays")!!.toInt()

    private fun updateCleanoutDays(newValue: String?) {
        mArguments!!.putString("cleanoutDays", newValue)
    }
}
