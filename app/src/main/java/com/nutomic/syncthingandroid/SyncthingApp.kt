package com.nutomic.syncthingandroid

import android.app.Application
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import com.google.android.material.color.DynamicColors
import com.nutomic.syncthingandroid.util.Languages
import javax.inject.Inject

class SyncthingApp : Application() {
    @Inject
    var mComponent: DaggerComponent? = null

    override fun onCreate() {
        DynamicColors.applyToActivitiesIfAvailable(this)

        super.onCreate()

        DaggerDaggerComponent.builder()
            .syncthingModule(SyncthingModule(this))
            .build()
            .inject(this)

        Languages(this).setLanguage(this)

        // The main point here is to use a VM policy without
        // `detectFileUriExposure`, as that leads to exceptions when e.g.
        // opening the ignores file. And it's enabled by default.
        // We might want to disable `detectAll` and `penaltyLog` on release (non-RC) builds too.
        val policy = VmPolicy.Builder()
            .detectAll()
            .penaltyLog()
            .build()
        StrictMode.setVmPolicy(policy)
    }

    fun component(): DaggerComponent? {
        return mComponent
    }
}
