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
    /** Leistung des Hausspeichers in W: positiv laedt, negativ gibt ab. */
    val houseBatteryPowerW: Double? = null,
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
    /**
     * Faellt der Speicher so viele Punkte unter die Pausiergrenze, pausiert die
     * Automatik sofort und wartet nicht. Die Wartezeit soll Flattern im
     * Grenzbereich verhindern; laeuft der Speicher dagegen deutlich leer, kostet
     * jede Minute Warten Speicherinhalt, den spaeter das Netz ersetzen muss.
     */
    const val URGENT_MARGIN_PERCENT = 5

    /**
     * Auch im Eilfall bleibt ein Mindestabstand zwischen zwei Befehlen. Ohne ihn
     * wuerde die Automatik im Minutentakt Befehle an Ford schicken, solange das
     * Auto nicht reagiert, und dabei nichts gewinnen.
     */
    const val URGENT_GAP_MINUTES = 5

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
        // die Ladeleistung, die das Auto gerade schluckt, minus Netzbezug und minus
        // das, was der Hausspeicher gerade abgibt. Ohne den Speicherabzug haelt die
        // Automatik ihr eigenes Laden aus dem Speicher fuer PV-Ueberschuss und
        // pausiert nachts nie.
        val carDraw = if (charging) (input.carChargePowerW ?: 0.0) else 0.0
        val discharge = (-(input.houseBatteryPowerW ?: 0.0)).coerceAtLeast(0.0)
        val available = (carDraw - (input.gridPowerW ?: 0.0) - discharge).coerceAtLeast(0.0)

        val wantCharge = soc >= rules.batteryOnPercent || available >= rules.surplusOnW
        val wantPause = soc < rules.batteryOffPercent && available < rules.surplusOnW * 0.7

        return when {
            wantCharge && !charging -> gated(rules, input, ChargeAction.RESUME,
                if (soc >= rules.batteryOnPercent) "Speicher ${soc.toInt()} % >= ${rules.batteryOnPercent} %" else "Ueberschuss ${available.toInt()} W >= ${rules.surplusOnW} W")
            wantPause && charging -> {
                val reason = "Speicher ${soc.toInt()} % < ${rules.batteryOffPercent} %, Ueberschuss ${available.toInt()} W" +
                    (if (discharge > 0) ", Speicher gibt ${discharge.toInt()} W ab" else "")
                // Deutlich unter der Grenze: nicht auf die Wartezeit warten.
                if (soc <= rules.batteryOffPercent - URGENT_MARGIN_PERCENT)
                    gated(rules, input, ChargeAction.PAUSE, "$reason - deutlich unter der Grenze", URGENT_GAP_MINUTES)
                else gated(rules, input, ChargeAction.PAUSE, reason)
            }
            charging -> ChargeDecision(ChargeAction.NONE, "Auto laedt, Speicher ${soc.toInt()} %")
            else -> ChargeDecision(ChargeAction.NONE, "Auto pausiert, Speicher ${soc.toInt()} %")
        }
    }

    /** [gapMinutes] ueberschreibt die eingestellte Wartezeit, etwa im Eilfall. */
    private fun gated(rules: ChargeRules, input: ChargeInput, action: ChargeAction, reason: String, gapMinutes: Int? = null): ChargeDecision {
        val gap = gapMinutes ?: rules.minCommandGapMinutes
        val last = input.lastCommandAt
        if (last != null && input.now - last < gap.minutes) {
            val wait = gap - (input.now - last).inWholeMinutes
            return ChargeDecision(ChargeAction.NONE, "$reason - Wartezeit noch $wait min")
        }
        return ChargeDecision(action, reason)
    }
}
