package com.nutomic.syncthingandroid.activities

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.nutomic.syncthingandroid.SyncthingApp
import javax.inject.Inject

/**
 * Provides a themed instance of AppCompatActivity.
 */
open class ThemedAppCompatActivity : AppCompatActivity() {
    @JvmField
    @Inject
    var mPreferences: SharedPreferences? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as SyncthingApp).component()!!.inject(this)
        // Load theme.
        //For api level below 28, Follow system fall backs to light mode
        val prefAppTheme = mPreferences!!.getString(
            com.nutomic.syncthingandroid.service.Constants.PREF_APP_THEME,
            FOLLOW_SYSTEM
        )!!.toInt()
        AppCompatDelegate.setDefaultNightMode(prefAppTheme)
        super.onCreate(savedInstanceState)
    }

    companion object {
        private const val FOLLOW_SYSTEM = "-1"
    }
}
