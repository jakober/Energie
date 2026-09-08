package com.jakober.energie.core.plugs

import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/** Wie ein Kuehlgeraet gerade dasteht. */
enum class CoolingVerdict { LEARNING, OK, HIGH, LOW }

/** Auswertung eines Kuehlgeraets ueber die letzten Tage. */
data class CoolingReport(
    val deviceId: String,
    val verdict: CoolingVerdict,
    /** Volle Tage, die in den Normalwert eingeflossen sind. */
    val daysLearned: Int,
    /** Normalwert kWh je Tag (Median), null in der Lernphase. */
    val baselineKwhPerDay: Double?,
    /** Normalwert Laufanteil 0..1. */
    val baselineDuty: Double?,
    /** Median der letzten vollen Tage (bis zu 3). */
    val recentKwhPerDay: Double?,
    val recentDuty: Double?,
    val recentCycles: Double?,
    /** Abweichung des Verbrauchs vom Normalwert in Prozent, positiv = mehr. */
    val deviationPercent: Int?,
    /** Herstellerangabe je Tag, aus dem Jahreswert. */
    val labelKwhPerDay: Double?,
    /** Verbrauch der letzten Tage in Prozent der Herstellerangabe. */
    val labelPercent: Int?,
    /** Kurze Einordnung fuer die Anzeige. */
    val summary: String,
    /** Fuer den Hinweis: was vermutlich los ist, null wenn alles normal. */
    val warning: String?,
)

/**
 * Erkennt, ob ein Kuehlgeraet mehr arbeitet als sonst. Rein aus den Tageswerten
 * (kWh, Laufanteil, Zyklen), die [PlugEnergy] je Tag liefert.
 *
 * Normalwert: Median der vollen Tage vor den letzten drei (mindestens [MIN_DAYS]).
 * Verglichen wird der Median der letzten drei vollen Tage. So faellt ein einzelner
 * heisser Tag nicht auf, ein Trend ueber mindestens zwei von drei Tagen schon.
 */
object CoolingHealth {
    const val MIN_DAYS = 5
    const val RECENT_DAYS = 3
    const val BASELINE_DAYS = 14
    /** Ab dieser Abweichung nach oben gilt das Geraet als auffaellig. */
    const val HIGH_PERCENT = 30
    /** Und der Laufanteil muss mindestens so viele Prozentpunkte hoeher liegen (sonst war es z. B. nur eine Abtauheizung). */
    const val HIGH_DUTY_POINTS = 8
    /** Verbrauch ueber diesem Anteil der Herstellerangabe ist verdaechtig, auch ohne eigenen Normalwert. */
    const val LABEL_HIGH_PERCENT = 150
    /** Ein Tag zaehlt nur als voll, wenn so viele Minuten gemessen wurden. */
    const val FULL_DAY_MINUTES = 20 * 60

    fun of(device: PlugDevice, days: List<Pair<LocalDate, PlugTotals>>): CoolingReport {
        val full = days.filter { it.second.measuredMinutes >= FULL_DAY_MINUTES }.sortedBy { it.first }
        val recent = full.takeLast(RECENT_DAYS)
        val base = full.dropLast(RECENT_DAYS).takeLast(BASELINE_DAYS)
        val labelPerDay = device.labelKwhPerYear?.takeIf { it > 0 }?.let { it / 365.0 }

        val recentKwh = median(recent.map { it.second.energyWh / 1000 })
        val recentDuty = median(recent.mapNotNull { it.second.dutyShare })
        val recentCycles = recent.map { it.second.cycles.toDouble() }.average().takeIf { recent.isNotEmpty() }
        val baseKwh = if (base.size >= MIN_DAYS) median(base.map { it.second.energyWh / 1000 }) else null
        val baseDuty = if (base.size >= MIN_DAYS) median(base.mapNotNull { it.second.dutyShare }) else null
        val deviation = if (baseKwh != null && baseKwh > 0 && recentKwh != null) ((recentKwh / baseKwh - 1) * 100).roundToInt() else null
        val labelPercent = if (labelPerDay != null && recentKwh != null) (recentKwh / labelPerDay * 100).roundToInt() else null
        val dutyPoints = if (baseDuty != null && recentDuty != null) ((recentDuty - baseDuty) * 100).roundToInt() else null

        val (verdict, warning) = when {
            recentKwh == null -> CoolingVerdict.LEARNING to null
            deviation != null && deviation >= HIGH_PERCENT && (dutyPoints == null || dutyPoints >= HIGH_DUTY_POINTS) ->
                CoolingVerdict.HIGH to "${device.name} braucht seit $RECENT_DAYS Tagen $deviation % mehr als üblich" +
                    (dutyPoints?.let { ", der Kompressor läuft ${(recentDuty!! * 100).roundToInt()} % der Zeit statt ${(baseDuty!! * 100).roundToInt()} %" } ?: "") +
                    ". Mögliche Ursachen: vereist, Tür undicht oder länger offen, Kondensator verstaubt, zu kalt eingestellt."
            deviation != null && deviation <= -HIGH_PERCENT ->
                CoolingVerdict.LOW to null
            baseKwh == null && labelPercent != null && recent.size >= RECENT_DAYS && labelPercent >= LABEL_HIGH_PERCENT ->
                CoolingVerdict.HIGH to "${device.name} braucht $labelPercent % vom Herstellerwert (${fmt(recentKwh)} statt ${fmt(labelPerDay!!)} kWh am Tag). Möglicherweise vereist, undicht oder zu kalt eingestellt."
            baseKwh == null -> CoolingVerdict.LEARNING to null
            labelPercent != null && labelPercent >= LABEL_HIGH_PERCENT && recent.size >= RECENT_DAYS ->
                CoolingVerdict.HIGH to "${device.name} liegt dauerhaft bei $labelPercent % vom Herstellerwert (${fmt(recentKwh)} statt ${fmt(labelPerDay!!)} kWh am Tag), auch wenn das seit Beginn so ist. Einstellung und Dichtungen prüfen."
            else -> CoolingVerdict.OK to null
        }

        val summary = buildString {
            when (verdict) {
                CoolingVerdict.LEARNING -> {
                    append("lernt noch (${full.size} von $MIN_DAYS Tagen)")
                    if (labelPercent != null) append(" · $labelPercent % vom Herstellerwert")
                }
                CoolingVerdict.OK -> append("normal")
                CoolingVerdict.HIGH -> append("auffällig")
                CoolingVerdict.LOW -> append("weniger als sonst")
            }
            if (recentKwh != null) append(" · Ø ${fmt(recentKwh)} kWh/Tag")
            if (deviation != null && verdict != CoolingVerdict.LEARNING) append(" (${if (deviation >= 0) "+" else ""}$deviation %)")
            if (recentDuty != null) append(" · läuft ${(recentDuty * 100).roundToInt()} % der Zeit")
            if (recentCycles != null) append(" · ${recentCycles.roundToInt()} Zyklen/Tag")
            if (labelPercent != null && verdict != CoolingVerdict.LEARNING) append(" · $labelPercent % vom Label")
        }

        return CoolingReport(
            device.id, verdict, base.size, baseKwh, baseDuty, recentKwh, recentDuty, recentCycles,
            deviation, labelPerDay, labelPercent, summary, warning,
        )
    }

    private fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun fmt(kwh: Double) = String.format(java.util.Locale.GERMANY, "%.2f", kwh)
}
