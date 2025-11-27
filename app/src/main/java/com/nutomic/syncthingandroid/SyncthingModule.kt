package com.nutomic.syncthingandroid

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.service.NotificationHandler
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class SyncthingModule(private val mApp: SyncthingApp) {
    @get:Singleton
    @get:Provides
    val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(mApp)

    @get:Singleton
    @get:Provides
    val notificationHandler: NotificationHandler
        get() = NotificationHandler(mApp)
}
