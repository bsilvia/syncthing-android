package com.nutomic.syncthingandroid

import com.nutomic.syncthingandroid.activities.FirstStartActivity
import com.nutomic.syncthingandroid.activities.FolderPickerActivity
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.activities.SettingsActivity.SettingsFragment
import com.nutomic.syncthingandroid.activities.ShareActivity
import com.nutomic.syncthingandroid.activities.ThemedAppCompatActivity
import com.nutomic.syncthingandroid.receiver.AppConfigReceiver
import com.nutomic.syncthingandroid.service.EventProcessor
import com.nutomic.syncthingandroid.service.NotificationHandler
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncthingRunnable
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.Languages
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [SyncthingModule::class])
interface DaggerComponent {
    fun inject(app: SyncthingApp?)
    fun inject(activity: MainActivity?)
    fun inject(activity: FirstStartActivity?)
    fun inject(activity: FolderPickerActivity?)
    fun inject(languages: Languages?)
    fun inject(service: SyncthingService?)
    fun inject(runConditionMonitor: RunConditionMonitor?)
    fun inject(eventProcessor: EventProcessor?)
    fun inject(syncthingRunnable: SyncthingRunnable?)
    fun inject(notificationHandler: NotificationHandler?)
    fun inject(appConfigReceiver: AppConfigReceiver?)
    fun inject(restApi: RestApi?)
    fun inject(fragment: SettingsFragment?)
    fun inject(activity: ShareActivity?)
    fun inject(activity: ThemedAppCompatActivity?)
}
