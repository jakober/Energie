package com.jakober.energie

import android.app.Application
import androidx.work.Configuration
import com.jakober.energie.work.BackupWorker
import com.jakober.energie.work.PollWorker

class EnergieApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        com.jakober.energie.notify.Push.init(this)
        PollWorker.schedule(this)
        BackupWorker.schedule(this) // prueft selbst, ob eine Sicherung eingerichtet ist
        // Zentrale: Vordergrund-Dienst, damit jede Minute gemessen wird.
        if (kotlinx.coroutines.runBlocking { container.settings.current().cloudRole } == com.jakober.energie.data.CloudRole.HUB) {
            com.jakober.energie.hub.HubService.start(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
