package com.jakober.energie.notify

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jakober.energie.BuildConfig
import com.jakober.energie.EnergieApp
import com.jakober.energie.core.alerts.Alert
import com.jakober.energie.core.alerts.AlertKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Firebase-Push ohne google-services.json: die Kenndaten kommen aus dem Build.
 * Fehlt der API-Schluessel, bleibt Push aus und die Anzeige holt Hinweise
 * weiter alle 15 Minuten ab.
 */
object Push {
    val available: Boolean get() = BuildConfig.FIREBASE_API_KEY.isNotBlank()

    fun init(context: Context) {
        if (!available) return
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }
            // Token einmal holen; Aenderungen kommen ueber onNewToken.
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> storeToken(context, token) }
        }.onFailure { Log.w("Energie", "Firebase-Start fehlgeschlagen: ${it.message}") }
    }

    fun storeToken(context: Context, token: String) {
        val app = context.applicationContext as? EnergieApp ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                app.container.settings.savePushToken(token)
                val s = app.container.settings.current()
                if (s.cloudConfigured) app.container.cloud.registerDeviceIfNeeded(s)
            }
        }
    }
}

/** Empfaengt Daten-Nachrichten der Edge Function "push" und zeigt sie als Hinweis. */
class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        Push.storeToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val title = d["title"] ?: return
        val body = d["body"] ?: ""
        val kind = runCatching { AlertKind.valueOf(d["kind"] ?: "") }.getOrDefault(AlertKind.AUTOMATION_ACTED)
        val offer = d["offer_charge"] == "true"
        val app = applicationContext as? EnergieApp ?: return
        app.container.notifier.show(Alert(kind, title, body, offer))
        // Als zugestellt markieren, damit das Abholen ihn nicht noch einmal bringt.
        d["id"]?.toLongOrNull()?.let { id ->
            scope.launch {
                runCatching {
                    val s = app.container.settings.current()
                    if (s.cloudConfigured) app.container.cloud.markDelivered(s, id)
                }
            }
        }
    }
}
