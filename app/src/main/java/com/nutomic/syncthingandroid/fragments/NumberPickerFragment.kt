package com.nutomic.syncthingandroid.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.NumberPicker.OnValueChangeListener
import androidx.fragment.app.Fragment
import com.nutomic.syncthingandroid.R

/**
 * Simply displays a numberpicker and allows easy access to configure it with the public functions.
 */
class NumberPickerFragment : Fragment() {
    private var mNumberPicker: NumberPicker? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        mNumberPicker =
            inflater.inflate(R.layout.numberpicker_fragment, container, false) as NumberPicker
        mNumberPicker!!.setWrapSelectorWheel(false)

        return mNumberPicker
    }

    fun setOnValueChangedListener(onValueChangeListener: OnValueChangeListener?) {
        mNumberPicker!!.setOnValueChangedListener(onValueChangeListener)
    }

    fun updateNumberPicker(maxValue: Int, minValue: Int, currentValue: Int) {
        mNumberPicker!!.setMaxValue(maxValue)
        mNumberPicker!!.setMinValue(minValue)
        mNumberPicker!!.value = currentValue
    }
}
