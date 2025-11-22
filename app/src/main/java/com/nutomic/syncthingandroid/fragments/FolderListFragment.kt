package com.nutomic.syncthingandroid.fragments

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import androidx.fragment.app.ListFragment
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingService.OnServiceStateChangeListener
import com.nutomic.syncthingandroid.views.FoldersAdapter
import java.util.Timer
import java.util.TimerTask

/**
 * Displays a list of all existing folders.
 */
class FolderListFragment : ListFragment(), OnServiceStateChangeListener, OnItemClickListener {
    private var mAdapter: FoldersAdapter? = null

    private var mTimer: Timer? = null

    override fun onPause() {
        super.onPause()
        if (mTimer != null) {
            mTimer!!.cancel()
        }
    }

    override fun onServiceStateChange(currentState: SyncthingService.State?) {
        if (currentState != SyncthingService.State.ACTIVE) return

        mTimer = Timer()
        mTimer!!.schedule(object : TimerTask() {
            override fun run() {
                if (activity == null) return

                activity!!.runOnUiThread { this@FolderListFragment.updateList() }
            }
        }, 0, Constants.GUI_UPDATE_INTERVAL)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setHasOptionsMenu(true)
        setEmptyText(getString(R.string.folder_list_empty))
        getListView().onItemClickListener = this
    }

    /**
     * Refreshes ListView by updating folders and info.
     *
     * Also creates adapter if it doesn't exist yet.
     */
    private fun updateList() {
        val activity = activity as SyncthingActivity?
        if (activity == null || view == null || activity.isFinishing) {
            return
        }
        val restApi = activity.api
        if (restApi == null || !restApi.isConfigLoaded) {
            return
        }
        val folders = restApi.folders
        if (mAdapter == null) {
            mAdapter = FoldersAdapter(activity)
            setListAdapter(mAdapter)
        }

        // Prevent scroll position reset due to list update from clear().
        mAdapter!!.setNotifyOnChange(false)
        mAdapter!!.clear()
        mAdapter!!.addAll(folders)
        mAdapter!!.updateFolderStatus(restApi)
        mAdapter!!.notifyDataSetChanged()
        setListShown(true)
    }

    override fun onItemClick(adapterView: AdapterView<*>?, view: View?, i: Int, l: Long) {
        val intent = Intent(activity, FolderActivity::class.java)
            .putExtra(FolderActivity.EXTRA_IS_CREATE, false)
            .putExtra(FolderActivity.EXTRA_FOLDER_ID, mAdapter!!.getItem(i)!!.id)
        startActivity(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.folder_list, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.add_folder -> {
                val intent = Intent(activity, FolderActivity::class.java)
                    .putExtra(FolderActivity.EXTRA_IS_CREATE, true)
                startActivity(intent)
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }
}
