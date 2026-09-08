package com.jakober.energie.core.plugs

import com.jakober.energie.core.history.EnergyTotals
import com.jakober.energie.core.model.EnergySample

/** Energie eines Steckers in einem Zeitraum. */
data class PlugTotals(
    val energyWh: Double,
    val maxPowerW: Double?,
    /** Zahl der Messpunkte mit diesem Stecker. */
    val samples: Int,
    /** True, wenn die Energie aus dem Zaehler des Steckers stammt, sonst aus der Leistung integriert. */
    val fromCounter: Boolean,
    /** Minuten, in denen der Verbraucher lief (Leistung ab [PlugEnergy.ON_W]). */
    val onMinutes: Double = 0.0,
    /** Minuten mit Messung, als Bezug fuer den Laufanteil. */
    val measuredMinutes: Double = 0.0,
    /** Wie oft der Verbraucher angesprungen ist (aus → an). */
    val cycles: Int = 0,
    /** Laengster Lauf am Stueck in Minuten. */
    val longestRunMinutes: Double = 0.0,
) {
    /** Anteil der Laufzeit an der gemessenen Zeit, null ohne Messung. */
    val dutyShare: Double? get() = if (measuredMinutes > 0) onMinutes / measuredMinutes else null

    operator fun plus(o: PlugTotals) = PlugTotals(
        energyWh + o.energyWh, listOfNotNull(maxPowerW, o.maxPowerW).maxOrNull(), samples + o.samples, fromCounter && o.fromCounter,
        onMinutes + o.onMinutes, measuredMinutes + o.measuredMinutes, cycles + o.cycles, maxOf(longestRunMinutes, o.longestRunMinutes),
    )
}

/**
 * Verbrauch je Stecker aus den Messpunkten. Bevorzugt die Zaehlerdifferenz
 * (exakt, auch bei Messluecken); faellt der Zaehler zurueck (Neustart des
 * Steckers), zaehlt der neue Stand ab null. Ohne Zaehler wird die Leistung
 * wie beim Haus integriert.
 */
object PlugEnergy {
    /** Ab dieser Leistung gilt der Verbraucher als "laeuft" (Kompressor an); Standby liegt bei 0 bis 3 W. */
    const val ON_W = 20.0

    fun of(samples: List<EnergySample>, previous: EnergySample? = null): Map<String, PlugTotals> {
        val sorted = samples.sortedBy { it.at }
        val ids = sorted.flatMap { it.plugs.keys }.toSet()
        if (ids.isEmpty()) return emptyMap()
        val chain = listOfNotNull(previous) + sorted
        return ids.associateWith { id -> totalsFor(id, chain, sorted) }
    }

    private fun totalsFor(id: String, chain: List<EnergySample>, own: List<EnergySample>): PlugTotals {
        val readings = chain.mapNotNull { s -> s.plugs[id]?.let { s.at to it } }
        val counters = readings.mapNotNull { (at, r) -> r.energyWh?.let { at to it } }
        val maxPower = own.mapNotNull { it.plugs[id]?.powerW }.maxOrNull()
        val count = own.count { it.plugs.containsKey(id) }
        val runs = runs(own.mapNotNull { s -> s.plugs[id]?.powerW?.let { s.at to it } })
        if (counters.size >= 2) {
            var sum = 0.0
            for (i in 1 until counters.size) {
                val d = counters[i].second - counters[i - 1].second
                sum += if (d >= 0) d else counters[i].second
            }
            return PlugTotals(sum, maxPower, count, fromCounter = true, runs.onMinutes, runs.measuredMinutes, runs.cycles, runs.longestRunMinutes)
        }
        // Rueckfall: Leistung integrieren, Luecken wie bei den Hauswerten ueberspringen.
        var wh = 0.0
        for (i in 1 until readings.size) {
            val (ta, a) = readings[i - 1]
            val (tb, b) = readings[i]
            val dt = (tb - ta).inWholeSeconds
            if (dt <= 0 || dt > EnergyTotals.MAX_GAP_SECONDS) continue
            val pa = a.powerW; val pb = b.powerW
            val p = if (pa != null && pb != null) (pa + pb) / 2 else pa ?: pb ?: continue
            wh += p * dt / 3600.0
        }
        return PlugTotals(wh, maxPower, count, fromCounter = false, runs.onMinutes, runs.measuredMinutes, runs.cycles, runs.longestRunMinutes)
    }

    private class Runs(val onMinutes: Double, val measuredMinutes: Double, val cycles: Int, val longestRunMinutes: Double)

    /**
     * Laufzeiten aus der Leistungsfolge: jede Minute zwischen zwei Messpunkten zaehlt als
     * "laeuft", wenn der vordere Messpunkt ueber [ON_W] lag. Luecken werden uebersprungen.
     */
    private fun runs(points: List<Pair<kotlinx.datetime.Instant, Double>>): Runs {
        var on = 0.0; var measured = 0.0; var cycles = 0; var longest = 0.0; var current = 0.0
        var wasOn: Boolean? = null
        for (i in points.indices) {
            val isOn = points[i].second >= ON_W
            if (isOn && wasOn == false) cycles++
            if (i > 0) {
                val dt = (points[i].first - points[i - 1].first).inWholeSeconds
                if (dt in 1..EnergyTotals.MAX_GAP_SECONDS.toLong()) {
                    val min = dt / 60.0
                    measured += min
                    if (points[i - 1].second >= ON_W) { on += min; current += min; longest = maxOf(longest, current) }
                    else current = 0.0
                } else current = 0.0
            }
            wasOn = isOn
        }
        return Runs(on, measured, cycles, longest)
    }
}
