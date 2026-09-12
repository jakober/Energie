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
    /** Das Auto hat zu laden begonnen oder aufgehoert (laut Fahrzeug). */
    val chargeStartStop: Boolean = true,
    /** SENEC oder FRITZ!Box antworten seit laengerem nicht. */
    val sourceDown: Boolean = true,
    val sourceDownMinutes: Int = 60,
    /** Naechtliche Sicherung fehlgeschlagen (wird direkt vom Worker gemeldet). */
    val backupFailed: Boolean = true,
    /** Die Anzeige meldet, wenn die Zentrale nichts mehr liefert. */
    val hubSilent: Boolean = true,
    val hubSilentMinutes: Int = 30,
    /** Kuehlgeraete an Messsteckern: Dauerlauf, Stillstand, Mehrverbrauch. */
    val cooling: Boolean = true,
    val coolingStuckHours: Int = 3,
    val coolingSilentHours: Int = 6,
)

enum class AlertKind {
    CAR_UNLOCKED_HOME, SURPLUS_UNUSED, AUTOMATION_ACTED, SOURCE_DOWN, SOURCE_BACK, BACKUP_FAILED, CHARGE_STARTED, CHARGE_STOPPED,
    COOLING_STUCK_ON, COOLING_SILENT, COOLING_BACK, COOLING_OVERLOAD, COOLING_TREND,
    HUB_SILENT, HUB_BACK,
}

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
    /** Ladestatus beim letzten Durchlauf, null = noch nie gesehen. */
    val lastCharging: Boolean? = null,
    /** Autoladung beim Ladestart, fuer die Meldung am Ende. */
    val chargeStartSoc: Double? = null,
    /** Kuehlgeraete: seit wann der Kompressor ununterbrochen laeuft (Unix-Sekunden), je Stecker-ID. */
    val plugOnSince: Map<String, Long> = emptyMap(),
    /** Wann der Kompressor zuletzt lief. */
    val plugLastOnAt: Map<String, Long> = emptyMap(),
    /** Seit wann die Leistung ueber der Nennleistung liegt. */
    val plugHighSince: Map<String, Long> = emptyMap(),
    val plugStuckReported: List<String> = emptyList(),
    val plugSilentReported: List<String> = emptyList(),
    val plugOverloadReported: List<String> = emptyList(),
    /** Letzte Trendmeldung je Stecker (Unix-Sekunden). */
    val coolingWarnedAt: Map<String, Long> = emptyMap(),
)

/** Ein Kuehlgeraet im aktuellen Durchlauf. `powerW` null = Stecker nicht erreichbar. */
data class CoolingLive(val id: String, val name: String, val powerW: Double?, val ratedPowerW: Double? = null)

/** Ergebnis der Tagesauswertung, wenn sie etwas zu melden hat. */
data class CoolingWarning(val id: String, val name: String, val text: String)

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
    val carSocPercent: Double? = null,
    /** Ob das Auto zu Hause laedt (Ladeleistung im Messpunkt), fuer den Text. */
    val carChargingAtHome: Boolean = false,
    /** Kuehlgeraete mit aktueller Leistung. */
    val coolingPlugs: List<CoolingLive> = emptyList(),
    /** Auffaellige Kuehlgeraete laut Tagesauswertung. */
    val coolingWarnings: List<CoolingWarning> = emptyList(),
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
    /** Ab dieser Leistung laeuft der Kompressor. */
    const val COOLING_ON_W = 20.0
    /** Leistung ueber diesem Vielfachen der Nennleistung ... */
    const val OVERLOAD_FACTOR = 1.3
    /** ... laenger als so lange ist ein Fehler (Abtauheizungen laufen 20 bis 30 min). */
    val OVERLOAD_MIN = 45.minutes
    /** Trendmeldung je Geraet hoechstens alle 7 Tage. */
    val TREND_REPEAT = (7 * 24 * 60).minutes

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

        // --- Ladestart / Ladeende laut Fahrzeug ---
        val charging = input.carCharging
        if (charging != null) {
            val before = s.lastCharging
            if (before != null && before != charging && settings.chargeStartStop) {
                val soc = input.carSocPercent
                if (charging) {
                    alerts += Alert(
                        AlertKind.CHARGE_STARTED, "Auto lädt",
                        buildString {
                            append(if (input.carChargingAtHome) "Das Auto lädt jetzt zu Hause" else "Das Auto lädt jetzt")
                            if (soc != null) append(", Akku ${soc.toInt()} %")
                            append(".")
                        },
                    )
                } else {
                    val from = s.chargeStartSoc
                    alerts += Alert(
                        AlertKind.CHARGE_STOPPED, "Laden beendet",
                        buildString {
                            append("Das Auto lädt nicht mehr")
                            if (soc != null) append(if (from != null && from < soc) ", Akku ${from.toInt()} % → ${soc.toInt()} %" else ", Akku ${soc.toInt()} %")
                            append(if (input.carPluggedIn == true) ". Es steckt noch." else ".")
                        },
                    )
                }
            }
            if (before != charging) s = s.copy(lastCharging = charging, chargeStartSoc = if (charging) input.carSocPercent else s.chargeStartSoc)
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

        // --- Kuehlgeraete ---
        if (settings.cooling) s = cooling(input, s, settings, alerts)

        return AlertResult(alerts, s)
    }

    private fun fmtDuration(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    private fun cooling(input: AlertInput, state: AlertState, settings: AlertSettings, alerts: MutableList<Alert>): AlertState {
        val nowSec = input.now.epochSeconds
        val onSince = state.plugOnSince.toMutableMap()
        val lastOn = state.plugLastOnAt.toMutableMap()
        val highSince = state.plugHighSince.toMutableMap()
        val stuck = state.plugStuckReported.toMutableSet()
        val silent = state.plugSilentReported.toMutableSet()
        val overload = state.plugOverloadReported.toMutableSet()
        val warned = state.coolingWarnedAt.toMutableMap()
        val ids = input.coolingPlugs.map { it.id }.toSet()

        for (p in input.coolingPlugs) {
            val w = p.powerW ?: continue // nicht erreichbar: Zustand einfrieren
            val running = w >= COOLING_ON_W
            if (running) {
                val since = onSince.getOrPut(p.id) { nowSec }
                lastOn[p.id] = nowSec
                if (p.id in silent) {
                    alerts += Alert(AlertKind.COOLING_BACK, "${p.name} läuft wieder", "Der Kompressor von ${p.name} ist wieder angesprungen.")
                    silent -= p.id
                }
                val limit = settings.coolingStuckHours * 3600L
                if (nowSec - since >= limit && p.id !in stuck) {
                    alerts += Alert(
                        AlertKind.COOLING_STUCK_ON, "${p.name} läuft ohne Pause",
                        "Der Kompressor von ${p.name} läuft seit ${fmtDuration(nowSec - since)} durch (${w.toInt()} W). " +
                            "Tür offen, stark vereist, Thermostat defekt oder gerade viel Neues eingelagert?",
                    )
                    stuck += p.id
                }
            } else {
                onSince -= p.id
                stuck -= p.id
                val last = lastOn[p.id]
                if (last == null) lastOn[p.id] = nowSec // zum ersten Mal gesehen: ab jetzt zaehlen
                else if (nowSec - last >= settings.coolingSilentHours * 3600L && p.id !in silent) {
                    alerts += Alert(
                        AlertKind.COOLING_SILENT, "${p.name} steht still",
                        "Seit ${fmtDuration(nowSec - last)} kein Kompressorlauf bei ${p.name} (jetzt ${w.toInt()} W). " +
                            "Stecker gezogen, Sicherung aus oder Gerät defekt? Bitte nachsehen.",
                    )
                    silent += p.id
                }
            }
            // Ueber der Nennleistung, laenger als eine Abtauheizung braucht.
            val rated = p.ratedPowerW
            if (rated != null && rated > 0 && w >= rated * OVERLOAD_FACTOR) {
                val since = highSince.getOrPut(p.id) { nowSec }
                if (nowSec - since >= OVERLOAD_MIN.inWholeSeconds && p.id !in overload) {
                    alerts += Alert(
                        AlertKind.COOLING_OVERLOAD, "${p.name} zieht zu viel",
                        "${p.name} nimmt seit ${fmtDuration(nowSec - since)} ${w.toInt()} W auf, Nennleistung ${rated.toInt()} W. " +
                            "Kompressor schwergängig oder Heizung hängt? Bitte prüfen.",
                    )
                    overload += p.id
                }
            } else {
                highSince -= p.id
                overload -= p.id
            }
        }
        for (wng in input.coolingWarnings) {
            val last = warned[wng.id] ?: 0
            if (nowSec - last >= TREND_REPEAT.inWholeSeconds) {
                alerts += Alert(AlertKind.COOLING_TREND, "${wng.name} braucht mehr Strom", wng.text)
                warned[wng.id] = nowSec
            }
        }
        // Entfernte Stecker vergessen.
        fun <V> Map<String, V>.keep() = filterKeys { it in ids }
        return state.copy(
            plugOnSince = onSince.keep(), plugLastOnAt = lastOn.keep(), plugHighSince = highSince.keep(),
            plugStuckReported = stuck.filter { it in ids }, plugSilentReported = silent.filter { it in ids },
            plugOverloadReported = overload.filter { it in ids }, coolingWarnedAt = warned.keep(),
        )
    }
}
