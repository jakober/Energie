package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlin.math.max

/** Ein Ladevorgang des Autos zu Hause, aus den Messpunkten rekonstruiert. */
data class ChargeSession(
    val start: Instant,
    val end: Instant,
    val energyWh: Double,
    /** Anteil, der im selben Moment aus dem Netz kam (gleicher Mix wie das Haus). */
    val fromGridWh: Double,
    val socStart: Double?,
    val socEnd: Double?,
    /** Ob der Vorgang beim letzten Messpunkt noch lief. */
    val ongoing: Boolean,
) {
    val durationMinutes: Long get() = (end - start).inWholeMinutes
    val fromSolarWh: Double get() = max(0.0, energyWh - fromGridWh)
    val solarShare: Double? get() = if (energyWh > 0) (fromSolarWh / energyWh).coerceIn(0.0, 1.0) else null
    val avgPowerW: Double? get() = (end - start).inWholeSeconds.takeIf { it > 0 }?.let { energyWh / (it / 3600.0) }
    fun costPaid(pricePerKwh: Double): Double = fromGridWh / 1000 * pricePerKwh
    fun saved(pricePerKwh: Double): Double = fromSolarWh / 1000 * pricePerKwh
    fun forgoneFeedIn(feedInPerKwh: Double): Double = fromSolarWh / 1000 * feedInPerKwh
}

/**
 * Zerlegt die Messpunkte in Ladevorgaenge: zusammenhaengende Abschnitte mit
 * Ladeleistung, kurze Luecken (Pause der Automatik, verpasste Messung) bis
 * `MAX_GAP_SECONDS` zaehlen noch zum selben Vorgang.
 */
object ChargeSessions {
    // Im Hintergrund misst die App alle 15 Minuten; ein verpasster Punkt ergibt 30 Minuten Abstand.
    const val MAX_GAP_SECONDS = 30 * 60
    /** Kleinere Vorgaenge sind Messrauschen und werden verworfen. */
    const val MIN_ENERGY_WH = 50.0

    private fun EnergySample.charging() = (carChargePowerW ?: 0.0) > 0

    fun of(samples: List<EnergySample>): List<ChargeSession> {
        val sorted = samples.sortedBy { it.at }
        val out = ArrayList<ChargeSession>()
        var i = 0
        while (i < sorted.size) {
            if (!sorted[i].charging()) { i++; continue }
            // Vorgang beginnt bei i. Laeuft, solange Ladepunkte hoechstens MAX_GAP auseinander liegen.
            var lastCharging = i
            var j = i + 1
            while (j < sorted.size) {
                val s = sorted[j]
                if (s.charging()) {
                    if ((s.at - sorted[lastCharging].at).inWholeSeconds > MAX_GAP_SECONDS) break
                    lastCharging = j
                } else if ((s.at - sorted[lastCharging].at).inWholeSeconds > MAX_GAP_SECONDS) {
                    break
                }
                j++
            }
            // Randpunkt: der erste Nicht-Ladepunkt nach dem letzten Ladepunkt, wenn er nah genug liegt,
            // damit das letzte Intervall (Rampe auf null) mitgezaehlt wird.
            val boundary = sorted.getOrNull(lastCharging + 1)?.takeIf { !it.charging() && (it.at - sorted[lastCharging].at).inWholeSeconds <= MAX_GAP_SECONDS }
            val slice = sorted.subList(i, lastCharging + 1) + listOfNotNull(boundary)
            val totals = EnergyTotals.of(slice)
            val ongoing = boundary == null && lastCharging == sorted.lastIndex
            val energy = if (slice.size == 1) 0.0 else totals.carChargeWh
            if (energy >= MIN_ENERGY_WH || (ongoing && slice.size >= 1)) {
                out += ChargeSession(
                    start = sorted[i].at,
                    end = (boundary ?: sorted[lastCharging]).at,
                    energyWh = energy,
                    fromGridWh = totals.carFromGridWh,
                    socStart = slice.firstOrNull { it.carSocPercent != null }?.carSocPercent,
                    socEnd = slice.lastOrNull { it.carSocPercent != null }?.carSocPercent,
                    ongoing = ongoing,
                )
            }
            i = if (boundary != null) lastCharging + 2 else lastCharging + 1
        }
        return out
    }
}
