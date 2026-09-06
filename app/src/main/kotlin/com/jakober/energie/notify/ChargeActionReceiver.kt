package com.jakober.energie.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.jakober.energie.EnergieApp
import com.jakober.energie.core.alerts.AlertKind
import com.jakober.energie.data.FordCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Knopf "Jetzt laden" in der Benachrichtigung: Handschalter setzen und das
 * Laden ueber FordPass anstossen, ohne die App zu oeffnen.
 */
class ChargeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHARGE_NOW) return
        val app = context.applicationContext as EnergieApp
        val pending = goAsync()
        NotificationManagerCompat.from(context).cancel(AlertKind.SURPLUS_UNUSED.ordinal + 100)
        scope.launch {
            try {
                val settings = app.container.settings
                settings.saveChargeOverride(true)
                val s = settings.current()
                if (s.cloudRole == com.jakober.energie.data.CloudRole.VIEWER) {
                    runCatching { app.container.cloud.sendCommand(s, com.jakober.energie.data.CloudSync.CMD_OVERRIDE, kotlinx.serialization.json.buildJsonObject { put("on", kotlinx.serialization.json.JsonPrimitive(true)) }) }
                } else {
                    if (s.fordConnected) runCatching { app.container.repository.fordCommand(s, FordCommand.RESUME) }
                    app.container.repository.forceCarOnNextRefresh()
                }
                runCatching { app.container.repository.refresh() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHARGE_NOW = "com.jakober.energie.CHARGE_NOW"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
