package com.layers.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.layers.app.reminders.ReminderScheduler

class LayersApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderScheduler.ensureChannel(this)
        container.work.scheduleTrashPurge()

        // Re-lock locked notes (and the app, if app lock is on) whenever Layers leaves the screen.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                container.lockSession.onAppBackgrounded()
            }
        })
    }
}
