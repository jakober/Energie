package com.jakober.energie.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jakober.energie.MainActivity
import com.jakober.energie.R
import com.jakober.energie.core.alerts.Alert
import com.jakober.energie.core.alerts.AlertKind

/**
 * Zeigt Hinweise als Benachrichtigung. Zwei Kanaele: "Auto" (wichtig, mit Ton)
 * fuer nicht abgeschlossen und ungenutzten Sonnenstrom, "Hinweise" (leise) fuer
 * Automatik-Rueckmeldungen, Ausfaelle und Sicherung.
 */
class Notifier(private val context: Context) {

    init {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CAR, "Auto", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Auto nicht abgeschlossen, Sonnenstrom ungenutzt"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INFO, "Hinweise", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Ladeautomatik, Ausfaelle, Sicherung"
                setSound(null, null)
            },
        )
    }

    fun show(alert: Alert) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val channel = when (alert.kind) {
            AlertKind.CAR_UNLOCKED_HOME, AlertKind.SURPLUS_UNUSED -> CHANNEL_CAR
            else -> CHANNEL_INFO
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(if (channel == CHANNEL_CAR) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
        if (alert.offerCharge) {
            val charge = PendingIntent.getBroadcast(
                context, 1, Intent(context, ChargeActionReceiver::class.java).setAction(ChargeActionReceiver.ACTION_CHARGE_NOW),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Jetzt laden", charge)
        }
        try {
            NotificationManagerCompat.from(context).notify(idFor(alert.kind), builder.build())
        } catch (_: SecurityException) {
            // Berechtigung fehlt (Android 13+): still bleiben, die Einstellungen weisen darauf hin.
        }
    }

    fun showAll(alerts: List<Alert>) = alerts.forEach(::show)

    /** Ein Hinweis je Art; ein neuer derselben Art ersetzt den alten. SOURCE_BACK raeumt SOURCE_DOWN ab. */
    private fun idFor(kind: AlertKind): Int = when (kind) {
        AlertKind.SOURCE_BACK -> AlertKind.SOURCE_DOWN.ordinal + 100
        else -> kind.ordinal + 100
    }

    companion object {
        const val CHANNEL_CAR = "auto"
        const val CHANNEL_INFO = "hinweise"
    }
}
