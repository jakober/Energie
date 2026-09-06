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
) {
    operator fun plus(o: PlugTotals) = PlugTotals(
        energyWh + o.energyWh, listOfNotNull(maxPowerW, o.maxPowerW).maxOrNull(), samples + o.samples, fromCounter && o.fromCounter,
    )
}

/**
 * Verbrauch je Stecker aus den Messpunkten. Bevorzugt die Zaehlerdifferenz
 * (exakt, auch bei Messluecken); faellt der Zaehler zurueck (Neustart des
 * Steckers), zaehlt der neue Stand ab null. Ohne Zaehler wird die Leistung
 * wie beim Haus integriert.
 */
object PlugEnergy {
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
        if (counters.size >= 2) {
            var sum = 0.0
            for (i in 1 until counters.size) {
                val d = counters[i].second - counters[i - 1].second
                sum += if (d >= 0) d else counters[i].second
            }
            return PlugTotals(sum, maxPower, count, fromCounter = true)
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
        return PlugTotals(wh, maxPower, count, fromCounter = false)
    }
}
