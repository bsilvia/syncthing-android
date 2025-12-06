package com.nutomic.syncthingandroid.fragments.dialog

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.nutomic.syncthingandroid.R

/**
 * Contains the configuration options for external file versioning.
 */
class ExternalVersioningFragment : Fragment() {
    private var mView: View? = null

    private var mArguments: Bundle? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        mView = inflater.inflate(R.layout.fragment_external_versioning, container, false)
        mArguments = arguments
        fillArguments()
        initiateTextView()
        return mView
    }

    private fun fillArguments() {
        if (missingParameters()) {
            mArguments!!.putString("command", "")
        }
    }

    private fun missingParameters(): Boolean {
        return !mArguments!!.containsKey("command")
    }

    private fun initiateTextView() {
        val commandTextView = mView!!.findViewById<TextView>(R.id.commandTextView)

        commandTextView.text = this.command
        commandTextView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(command: CharSequence, start: Int, before: Int, count: Int) {
                updateCommand(command.toString())
            }

            override fun afterTextChanged(s: Editable?) {
            }
        })
    }

    private fun updateCommand(command: String?) {
        mArguments!!.putString("command", command)
    }

    private val command: String?
        get() = if (mArguments!!.containsKey("command")) mArguments!!.getString("command") else ""
}
