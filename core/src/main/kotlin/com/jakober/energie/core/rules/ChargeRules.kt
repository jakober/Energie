package com.jakober.energie.core.rules

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/** Einstellungen der Ladeautomatik, wie der Nutzer sie vorgibt. */
@Serializable
data class ChargeRules(
    val enabled: Boolean = false,
    /** Laden erlaubt, wenn der Hausspeicher mindestens so voll ist (Prozent). */
    val batteryOnPercent: Int = 70,
    /** Laden pausieren, wenn der Hausspeicher darunter faellt (Prozent). Abstand = Hysterese. */
    val batteryOffPercent: Int = 50,
    /** Laden auch erlaubt, wenn so viel PV-Ueberschuss da ist (W). */
    val surplusOnW: Int = 2000,
    /** Nachtsperre von ... (Minuten seit Mitternacht). */
    val nightStartMinutes: Int = 0,
    /** ... bis. Gleich = keine Sperre; das ist der Standard, die Speicherregel reicht meist. */
    val nightEndMinutes: Int = 0,
    /** Unter dieser Autoladung wird immer geladen, egal was der Speicher sagt. */
    val carReservePercent: Int = 50,
    /** Mindestabstand zwischen zwei Befehlen, gegen Flattern. */
    val minCommandGapMinutes: Int = 15,
) {
    val nightEnabled: Boolean get() = nightStartMinutes != nightEndMinutes

    fun isNight(time: LocalTime): Boolean {
        if (!nightEnabled) return false
        val m = time.hour * 60 + time.minute
        return if (nightStartMinutes < nightEndMinutes) m >= nightStartMinutes && m < nightEndMinutes
        else m >= nightStartMinutes || m < nightEndMinutes
    }
}

/** Was die Automatik im Moment weiss. `null` = Wert fehlt. */
data class ChargeInput(
    val now: Instant,
    val localTime: LocalTime,
    val houseBatteryPercent: Double?,
    /** Netzleistung in W: positiv Bezug, negativ Einspeisung. */
    val gridPowerW: Double?,
    val carSocPercent: Double?,
    val carPluggedIn: Boolean?,
    val carCharging: Boolean?,
    /** Ladeleistung des Autos in W, wenn es gerade laedt. */
    val carChargePowerW: Double?,
    val lastCommandAt: Instant?,
    /** Handschalter "jetzt voll laden": Automatik setzt aus, bis das Auto abgesteckt wird. */
    val overrideFullCharge: Boolean,
)

enum class ChargeAction { NONE, PAUSE, RESUME }

data class ChargeDecision(val action: ChargeAction, val reason: String)

/**
 * Entscheidet, ob das Auto jetzt laden soll. Rein funktional, ohne
 * Nebenwirkungen - deshalb gut testbar. Vorrang von oben nach unten:
 * nicht eingesteckt, Handschalter, Reserve, Nachtsperre, Speicher und
 * Ueberschuss mit Hysterese, Wartezeit zwischen Befehlen.
 */
object ChargeRuleEngine {

    fun decide(rules: ChargeRules, input: ChargeInput): ChargeDecision {
        if (!rules.enabled) return ChargeDecision(ChargeAction.NONE, "Automatik aus")
        if (input.carPluggedIn != true) return ChargeDecision(ChargeAction.NONE, "Auto nicht angeschlossen")
        val charging = input.carCharging ?: return ChargeDecision(ChargeAction.NONE, "Ladestatus unbekannt")

        if (input.overrideFullCharge) {
            return if (!charging) ChargeDecision(ChargeAction.RESUME, "Handschalter: jetzt voll laden")
            else ChargeDecision(ChargeAction.NONE, "Handschalter aktiv, Auto laedt")
        }

        val carSoc = input.carSocPercent
        if (carSoc != null && carSoc < rules.carReservePercent) {
            return if (!charging) gated(rules, input, ChargeAction.RESUME, "Reserve: Auto ${carSoc.toInt()} % unter ${rules.carReservePercent} %")
            else ChargeDecision(ChargeAction.NONE, "Reserve wird geladen")
        }

        if (rules.isNight(input.localTime)) {
            return if (charging) gated(rules, input, ChargeAction.PAUSE, "Nachtsperre")
            else ChargeDecision(ChargeAction.NONE, "Nachtsperre, Auto pausiert")
        }

        val soc = input.houseBatteryPercent ?: return ChargeDecision(ChargeAction.NONE, "Speicherstand unbekannt")
        // Verfuegbarer Ueberschuss = was ohne das Auto ins Netz ginge: Einspeisung plus
        // die Ladeleistung, die das Auto gerade schluckt, minus Netzbezug. Sonst
        // pausiert die Automatik ihr eigenes Laden.
        val carDraw = if (charging) (input.carChargePowerW ?: 0.0) else 0.0
        val available = (carDraw - (input.gridPowerW ?: 0.0)).coerceAtLeast(0.0)

        val wantCharge = soc >= rules.batteryOnPercent || available >= rules.surplusOnW
        val wantPause = soc < rules.batteryOffPercent && available < rules.surplusOnW * 0.7

        return when {
            wantCharge && !charging -> gated(rules, input, ChargeAction.RESUME,
                if (soc >= rules.batteryOnPercent) "Speicher ${soc.toInt()} % >= ${rules.batteryOnPercent} %" else "Ueberschuss ${available.toInt()} W >= ${rules.surplusOnW} W")
            wantPause && charging -> gated(rules, input, ChargeAction.PAUSE,
                "Speicher ${soc.toInt()} % < ${rules.batteryOffPercent} %, Ueberschuss ${available.toInt()} W")
            charging -> ChargeDecision(ChargeAction.NONE, "Auto laedt, Speicher ${soc.toInt()} %")
            else -> ChargeDecision(ChargeAction.NONE, "Auto pausiert, Speicher ${soc.toInt()} %")
        }
    }

    private fun gated(rules: ChargeRules, input: ChargeInput, action: ChargeAction, reason: String): ChargeDecision {
        val last = input.lastCommandAt
        if (last != null && input.now - last < rules.minCommandGapMinutes.minutes) {
            val wait = rules.minCommandGapMinutes - (input.now - last).inWholeMinutes
            return ChargeDecision(ChargeAction.NONE, "$reason - Wartezeit noch $wait min")
        }
        return ChargeDecision(action, reason)
    }
}
