package com.jakober.energie.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jakober.energie.EnergieApp
import com.jakober.energie.data.CloudRole
import kotlinx.coroutines.runBlocking

/** Nach dem Neustart des Geraets die Zentrale wieder starten. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as EnergieApp
        val role = runBlocking { app.container.settings.current().cloudRole }
        if (role == CloudRole.HUB) HubService.start(context)
    }
}
