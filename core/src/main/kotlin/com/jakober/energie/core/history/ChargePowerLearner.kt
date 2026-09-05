package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample

/**
 * Lernt die tatsaechliche Ladeleistung des Autos aus dem Sprung im
 * Hausverbrauch beim Ladestart, statt einen festen Wert (Ladeziegel 2,2 kW)
 * anzunehmen. Nur mit dem geeichten Zaehler, sonst ist der Sprung zu unsicher.
 */
object ChargePowerLearner {
    /** Plausibler Bereich fuer Wallbox oder Ladeziegel. */
    const val MIN_W = 1000.0
    const val MAX_W = 11_000.0
    /** Vergleichspunkte duerfen hoechstens so alt sein. */
    const val MAX_AGE_SECONDS = 30 * 60
    /** Gewicht eines neuen Werts gegenueber dem bisherigen. */
    const val WEIGHT = 0.3

    /**
     * Schaetzt die Ladeleistung aus `first` (erster Messpunkt, an dem das Auto
     * laedt) gegen den Median des Haushalts der letzten Messpunkte davor.
     * `before` sind Messpunkte vor `first`, bei denen das Auto nicht lud.
     */
    fun estimate(before: List<EnergySample>, first: EnergySample): Double? {
        if (!first.hasMeter) return null
        val consumption = first.consumptionW ?: return null
        val recent = before
            .filter { it.at < first.at && (first.at - it.at).inWholeSeconds <= MAX_AGE_SECONDS && (it.carChargePowerW ?: 0.0) <= 0 && it.hasMeter }
            .sortedByDescending { it.at }
            .take(3)
            .mapNotNull { it.consumptionW }
        if (recent.size < 2) return null
        val baseline = recent.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val jump = consumption - baseline
        return jump.takeIf { it in MIN_W..MAX_W }
    }

    /** Neuer gelernter Wert: gleitender Mittelwert, der Ausreisser daempft. */
    fun blend(old: Double?, new: Double): Double = if (old == null) new else old * (1 - WEIGHT) + new * WEIGHT
}
