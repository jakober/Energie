package com.jakober.energie.core.alerts

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/** Welche Hinweise der Nutzer haben moechte, mit Schwellen. */
@Serializable
data class AlertSettings(
    /** Auto steht zu Hause und ist nicht abgeschlossen. */
    val carUnlocked: Boolean = true,
    val unlockedMinutes: Int = 10,
    /** Speicher voll, Einspeisung hoch, Auto steckt aber laedt nicht. */
    val surplusUnused: Boolean = true,
    val surplusW: Int = 1500,
    val batteryFullPercent: Int = 95,
    /** Rueckmeldung, wenn die Ladeautomatik pausiert oder fortsetzt. */
    val automation: Boolean = true,
    /** SENEC oder FRITZ!Box antworten seit laengerem nicht. */
    val sourceDown: Boolean = true,
    val sourceDownMinutes: Int = 60,
    /** Naechtliche Sicherung fehlgeschlagen (wird direkt vom Worker gemeldet). */
    val backupFailed: Boolean = true,
)

enum class AlertKind { CAR_UNLOCKED_HOME, SURPLUS_UNUSED, AUTOMATION_ACTED, SOURCE_DOWN, SOURCE_BACK, BACKUP_FAILED }

/** Ein Hinweis, wie er als Benachrichtigung erscheint. */
data class Alert(
    val kind: AlertKind,
    val title: String,
    val text: String,
    /** Mit Knopf "Jetzt laden". */
    val offerCharge: Boolean = false,
)

/** Was sich die Engine zwischen zwei Durchlaeufen merkt, damit nichts doppelt kommt. */
@Serializable
data class AlertState(
    /** Seit wann das Auto zu Hause unverschlossen steht (Unix-Sekunden), null = nicht. */
    val unlockedSince: Long? = null,
    val unlockedReported: Boolean = false,
    /** Letzter Ueberschuss-Hinweis (Unix-Sekunden). */
    val lastSurplusAt: Long = 0,
    val senecDownReported: Boolean = false,
    val fritzDownReported: Boolean = false,
)

/** Momentaufnahme fuer die Engine. `null` = unbekannt. */
data class AlertInput(
    val now: Instant,
    val batterySocPercent: Double?,
    /** Netzleistung in W, positiv Bezug, negativ Einspeisung. */
    val gridPowerW: Double?,
    val carPluggedIn: Boolean?,
    val carCharging: Boolean?,
    /** LOCKED, PARTLY_LOCKED, UNLOCKED oder null. */
    val carLockState: String?,
    val carDistanceHomeM: Double?,
    val chargeOverride: Boolean,
    val senecConfigured: Boolean,
    val fritzConfigured: Boolean,
    /** Letzte erfolgreiche Antwort je Quelle; null = noch nie. */
    val lastSenecOkAt: Instant?,
    val lastFritzOkAt: Instant?,
    /** Zeile, die die Ladeautomatik in diesem Durchlauf ins Protokoll geschrieben hat. */
    val automationLine: String?,
)

data class AlertResult(val alerts: List<Alert>, val state: AlertState)

/**
 * Leitet aus einer Momentaufnahme die faelligen Hinweise ab. Rein funktional:
 * gleicher Eingang plus gleicher Zustand ergibt immer dasselbe Ergebnis.
 */
object AlertEngine {
    /** Naeher als das gilt als "zu Hause". */
    const val HOME_RADIUS_M = 300.0
    val SURPLUS_REPEAT = 60.minutes

    fun evaluate(input: AlertInput, state: AlertState, settings: AlertSettings): AlertResult {
        val alerts = ArrayList<Alert>()
        var s = state
        val nowSec = input.now.epochSeconds

        // --- Auto zu Hause nicht abgeschlossen ---
        val atHome = input.carDistanceHomeM?.let { it <= HOME_RADIUS_M }
        val unlocked = when (input.carLockState) { "UNLOCKED", "PARTLY_LOCKED" -> true; "LOCKED" -> false; else -> null }
        when {
            atHome == true && unlocked == true -> {
                val since = s.unlockedSince ?: nowSec
                s = s.copy(unlockedSince = since)
                if (settings.carUnlocked && !s.unlockedReported && nowSec - since >= settings.unlockedMinutes * 60L) {
                    val minutes = (nowSec - since) / 60
                    alerts += Alert(
                        AlertKind.CAR_UNLOCKED_HOME, "Auto nicht abgeschlossen",
                        if (input.carLockState == "PARTLY_LOCKED") "Das Auto steht seit $minutes min zu Hause und ist nur teilweise verriegelt."
                        else "Das Auto steht seit $minutes min zu Hause und ist nicht abgeschlossen.",
                    )
                    s = s.copy(unlockedReported = true)
                }
            }
            // Abgeschlossen oder weggefahren: Parkvorgang beendet.
            unlocked == false || atHome == false -> s = s.copy(unlockedSince = null, unlockedReported = false)
            // Unbekannt: nichts aendern, sonst kaeme der Hinweis nach jeder Luecke erneut.
        }

        // --- Ueberschuss ungenutzt ---
        val soc = input.batterySocPercent
        val grid = input.gridPowerW
        if (settings.surplusUnused && soc != null && grid != null &&
            soc >= settings.batteryFullPercent && -grid >= settings.surplusW &&
            input.carPluggedIn == true && input.carCharging == false && !input.chargeOverride &&
            nowSec - s.lastSurplusAt >= SURPLUS_REPEAT.inWholeSeconds
        ) {
            alerts += Alert(
                AlertKind.SURPLUS_UNUSED, "Sonnenstrom ungenutzt",
                "Speicher ${soc.toInt()} % voll, ${(-grid).toInt()} W gehen ins Netz. Das Auto steckt, lädt aber nicht.",
                offerCharge = true,
            )
            s = s.copy(lastSurplusAt = nowSec)
        }

        // --- Rueckmeldung der Ladeautomatik ---
        if (settings.automation && !input.automationLine.isNullOrBlank()) {
            alerts += Alert(AlertKind.AUTOMATION_ACTED, "Ladeautomatik", input.automationLine)
        }

        // --- Quelle ausgefallen / wieder da ---
        if (settings.sourceDown) {
            val limit = settings.sourceDownMinutes.minutes
            fun check(configured: Boolean, lastOk: Instant?, reported: Boolean, name: String): Boolean {
                if (!configured || lastOk == null) return reported
                val down = input.now - lastOk >= limit
                if (down && !reported) {
                    alerts += Alert(AlertKind.SOURCE_DOWN, "$name antwortet nicht", "Seit ${(input.now - lastOk).inWholeMinutes} min keine Daten von $name.")
                    return true
                }
                if (!down && reported) {
                    alerts += Alert(AlertKind.SOURCE_BACK, "$name wieder da", "$name liefert wieder Daten.")
                    return false
                }
                return reported
            }
            s = s.copy(
                senecDownReported = check(input.senecConfigured, input.lastSenecOkAt, s.senecDownReported, "SENEC"),
                fritzDownReported = check(input.fritzConfigured, input.lastFritzOkAt, s.fritzDownReported, "FRITZ!Box"),
            )
        }

        return AlertResult(alerts, s)
    }
}
