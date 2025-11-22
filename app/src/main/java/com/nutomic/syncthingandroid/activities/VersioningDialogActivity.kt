package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import androidx.fragment.app.Fragment
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.databinding.ActivityVersioningDialogBinding
import com.nutomic.syncthingandroid.fragments.dialog.ExternalVersioningFragment
import com.nutomic.syncthingandroid.fragments.dialog.NoVersioningFragment
import com.nutomic.syncthingandroid.fragments.dialog.SimpleVersioningFragment
import com.nutomic.syncthingandroid.fragments.dialog.StaggeredVersioningFragment
import com.nutomic.syncthingandroid.fragments.dialog.TrashCanVersioningFragment

class VersioningDialogActivity : ThemedAppCompatActivity() {
    private var mCurrentFragment: Fragment? = null

    private var mArguments: Bundle? = null

    private var binding: ActivityVersioningDialogBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVersioningDialogBinding.inflate(layoutInflater)
        setContentView(binding!!.getRoot())

        mArguments = if (savedInstanceState != null) {
            savedInstanceState.getBundle("arguments")
        } else {
            intent.extras
        }

        updateFragmentView(mTypes.indexOf(intent.extras!!.getString("type")))
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
        intent.putExtras(mCurrentFragment!!.requireArguments())
        setResult(RESULT_OK, intent)
    }

    private fun initiateSpinner() {
        binding!!.versioningTypeSpinner.setSelection(
            mTypes.indexOf(
                intent.extras!!.getString("type")
            )
        )
        binding!!.versioningTypeSpinner.onItemSelectedListener = object :
            AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position != mTypes.indexOf(intent.extras!!.getString("type"))) {
                    updateVersioningType(position)
                    updateFragmentView(position)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    private fun updateVersioningType(position: Int) {
        mArguments!!.putString("type", mTypes[position])
    }

    private fun updateFragmentView(selection: Int) {
        if (mCurrentFragment != null) {
            mArguments = mCurrentFragment!!.arguments
        }

        mCurrentFragment = getFragment(selection)
        val transaction = supportFragmentManager.beginTransaction()

        //This Activtiy (VersioningDialogActivity) contains all the file versioning parameters that have been passed from the FolderActivity in the intent extras, so we simply
        // pass that to the currentfragment.
        mCurrentFragment!!.setArguments(mArguments)
        transaction.replace(R.id.versioningFragmentContainer, mCurrentFragment!!)
        transaction.commit()
    }

    private fun getFragment(selection: Int): Fragment? {
        var fragment: Fragment? = null

        when (selection) {
            0 -> fragment = NoVersioningFragment()
            1 -> fragment = TrashCanVersioningFragment()
            2 -> fragment = SimpleVersioningFragment()
            3 -> fragment = StaggeredVersioningFragment()
            4 -> fragment = ExternalVersioningFragment()
        }

        return fragment
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle("arguments", mCurrentFragment!!.arguments)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveConfiguration()
        super.onBackPressed()
    }

    companion object {
        private val mTypes =
            mutableListOf<String?>("none", "trashcan", "simple", "staggered", "external")
    }
}
