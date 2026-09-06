package com.jakober.energie.core.history

import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Wie lange der Hausspeicher beim aktuellen Fluss noch reicht (Entladen) oder
 * bis er voll ist (Laden). Bewusst nur der Moment: Schaltet der Nutzer die
 * Klimaanlage aus, aendert sich die Zahl mit der naechsten Messung.
 */
object BatteryRuntime {
    /** Darunter ist der Speicher praktisch in Ruhe; eine Zeit waere Rauschen. */
    const val MIN_POWER_W = 50.0
    /** Laenger als das ist keine Aussage mehr, nur noch "reicht lange". */
    val MAX_DURATION: Duration = (48 * 60).minutes

    data class Estimate(val charging: Boolean, val duration: Duration, val beyondHorizon: Boolean)

    fun estimate(socPercent: Double?, powerW: Double?, capacityWh: Double?): Estimate? {
        if (socPercent == null || powerW == null || capacityWh == null || capacityWh <= 0) return null
        if (abs(powerW) < MIN_POWER_W) return null
        val charging = powerW > 0
        val soc = socPercent.coerceIn(0.0, 100.0)
        val remainingWh = if (charging) capacityWh * (100 - soc) / 100 else capacityWh * soc / 100
        val minutes = remainingWh / abs(powerW) * 60.0
        val d = minutes.minutes
        return if (d > MAX_DURATION) Estimate(charging, MAX_DURATION, beyondHorizon = true) else Estimate(charging, d, beyondHorizon = false)
    }
}
