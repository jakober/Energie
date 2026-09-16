package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** Ein Extremwert mit dem Zeitpunkt, an dem er auftrat. */
data class Peak(val at: Instant, val value: Double)

/** Energiemengen einer Stunde (0..23) in Wh. */
data class HourBucket(
    val hour: Int,
    val productionWh: Double,
    val consumptionWh: Double,
    val gridImportWh: Double,
    val gridExportWh: Double,
    val batteryChargeWh: Double,
    val batteryDischargeWh: Double,
    val carChargeWh: Double,
    /** Mittlerer Ladezustand in der Stunde in Prozent. */
    val batterySocPercent: Double?,
)

/**
 * Alles, was die App ueber einen Tag zu sagen hat: Summen, Spitzen mit
 * Uhrzeit, Stundenprofil, Zaehlerstaende. Rechnet ausschliesslich auf den
 * lokal gesammelten Messpunkten.
 */
data class DayStatistics(
    val date: LocalDate,
    val sampleCount: Int,
    val totals: EnergyTotals,
    val peakConsumption: Peak?,
    val peakProduction: Peak?,
    val peakGridImport: Peak?,
    val peakGridExport: Peak?,
    val peakBatteryCharge: Peak?,
    val peakBatteryDischarge: Peak?,
    val socMin: Peak?,
    val socMax: Peak?,
    /** Erster und letzter Ladezustand des Tages. */
    val socStart: Double?,
    val socEnd: Double?,
    /** Zaehlerstaende zu Tagesbeginn und -ende in Wh. */
    val meterImportStartWh: Long?,
    val meterImportEndWh: Long?,
    val meterExportStartWh: Long?,
    val meterExportEndWh: Long?,
    val hours: List<HourBucket>,
    /** Erster Messpunkt mit PV-Leistung ueber 10 W und letzter - grob Sonnenauf-/untergang. */
    val firstProduction: Instant?,
    val lastProduction: Instant?,
    /**
     * Minuten ohne Messung: von Mitternacht bis zum ersten Punkt und jede Luecke
     * ueber [EnergyTotals.MAX_GAP_SECONDS] dazwischen. In dieser Zeit fehlen
     * Erzeugung, Verbrauch und Speicher; Bezug und Einspeisung kommen vom Zaehler.
     */
    val gapMinutes: Long = 0,
    /** Verbrauch je Messstecker (Schluessel = Geraetekennung). */
    val plugs: Map<String, com.jakober.energie.core.plugs.PlugTotals> = emptyMap(),
) {
    /** Stunde mit dem hoechsten Verbrauch. */
    val heaviestHour: HourBucket? get() = hours.maxByOrNull { it.consumptionWh }?.takeIf { it.consumptionWh > 0 }

    /** Grundlast: der kleinste 15-Minuten-Mittelwert des Verbrauchs. */
    val baseLoadW: Double? get() = baseLoad

    // wird im Builder gesetzt
    internal var baseLoad: Double? = null

    companion object {
        /**
         * `previous` ist der letzte Messpunkt des Vortags mit Zaehlerstand. Liegt er nach
         * 12 Uhr des Vortags, beginnt die Zaehlerdifferenz dort, damit die Nacht nicht
         * verloren geht, wenn die App bis zum Morgen schlief.
         */
        fun of(date: LocalDate, samples: List<EnergySample>, zone: TimeZone = TimeZone.currentSystemDefault(), previous: EnergySample? = null): DayStatistics {
            val sorted = samples.sortedBy { it.at }
            val dayStart = date.atStartOfDayIn(zone)
            val usePrevious = previous != null && previous.at < dayStart && (dayStart - previous.at).inWholeHours < 12 &&
                (previous.meterImportWh != null || previous.meterExportWh != null)
            val importStart = (if (usePrevious) previous?.meterImportWh else null) ?: sorted.firstOrNull { it.meterImportWh != null }?.meterImportWh
            val exportStart = (if (usePrevious) previous?.meterExportWh else null) ?: sorted.firstOrNull { it.meterExportWh != null }?.meterExportWh
            val importEnd = sorted.lastOrNull { it.meterImportWh != null }?.meterImportWh
            val exportEnd = sorted.lastOrNull { it.meterExportWh != null }?.meterExportWh
            var totals = EnergyTotals.of(sorted)
            if (importStart != null && importEnd != null && exportStart != null && exportEnd != null && importEnd >= importStart && exportEnd >= exportStart) {
                totals = totals.copy(
                    gridImportWh = (importEnd - importStart).toDouble(), gridExportWh = (exportEnd - exportStart).toDouble(),
                    meterImportWh = importEnd - importStart, meterExportWh = exportEnd - exportStart, gridFromMeter = true,
                )
            }
            var gap = 0L
            sorted.firstOrNull()?.let { first ->
                val lead = (first.at - dayStart).inWholeSeconds
                if (lead > EnergyTotals.MAX_GAP_SECONDS) gap += lead / 60
            }
            for (i in 1 until sorted.size) {
                val dt = (sorted[i].at - sorted[i - 1].at).inWholeSeconds
                if (dt > EnergyTotals.MAX_GAP_SECONDS) gap += dt / 60
            }
            fun peakMax(sel: (EnergySample) -> Double?): Peak? =
                sorted.mapNotNull { s -> sel(s)?.let { Peak(s.at, it) } }.maxByOrNull { it.value }?.takeIf { it.value > 0 }
            fun peakMin(sel: (EnergySample) -> Double?): Peak? =
                sorted.mapNotNull { s -> sel(s)?.let { Peak(s.at, it) } }.minByOrNull { it.value }

            val grid: (EnergySample) -> Double? = { it.gridPowerW }
            val socs = sorted.mapNotNull { s -> s.batterySocPercent?.let { Peak(s.at, it) } }
            val producing = sorted.filter { (it.productionW ?: 0.0) > 10.0 }

            val stats = DayStatistics(
                date = date,
                sampleCount = sorted.size,
                totals = totals,
                peakConsumption = peakMax { it.consumptionW },
                peakProduction = peakMax { it.productionW },
                peakGridImport = peakMax { grid(it)?.coerceAtLeast(0.0) },
                peakGridExport = peakMax { grid(it)?.let { g -> (-g).coerceAtLeast(0.0) } },
                peakBatteryCharge = peakMax { it.batteryPowerW?.coerceAtLeast(0.0) },
                peakBatteryDischarge = peakMax { it.batteryPowerW?.let { p -> (-p).coerceAtLeast(0.0) } },
                socMin = socs.minByOrNull { it.value },
                socMax = socs.maxByOrNull { it.value },
                socStart = socs.firstOrNull()?.value,
                socEnd = socs.lastOrNull()?.value,
                meterImportStartWh = importStart,
                meterImportEndWh = importEnd,
                meterExportStartWh = exportStart,
                meterExportEndWh = exportEnd,
                hours = hourBuckets(sorted, zone),
                firstProduction = producing.firstOrNull()?.at,
                lastProduction = producing.lastOrNull()?.at,
                gapMinutes = gap,
                plugs = com.jakober.energie.core.plugs.PlugEnergy.of(sorted, previous?.takeIf { it.at < dayStart && (dayStart - it.at).inWholeHours < 12 }),
            )
            stats.baseLoad = baseLoad(sorted)
            return stats
        }

        private fun hourBuckets(sorted: List<EnergySample>, zone: TimeZone): List<HourBucket> {
            val byHour = sorted.groupBy { it.at.toLocalDateTime(zone).hour }
            return (0..23).map { h ->
                val inHour = byHour[h].orEmpty()
                // Jedes Intervall gehoert zu der Stunde, in der es beginnt. Deshalb den
                // ersten Punkt der Folgestunde mitnehmen, sonst fehlt das letzte Intervall.
                val next = sorted.firstOrNull { it.at.toLocalDateTime(zone).hour > h }
                val t = EnergyTotals.of(inHour + listOfNotNull(next))
                val soc = inHour.mapNotNull { it.batterySocPercent }
                HourBucket(
                    hour = h,
                    productionWh = t.productionWh,
                    consumptionWh = t.consumptionWh,
                    gridImportWh = t.gridImportWh,
                    gridExportWh = t.gridExportWh,
                    batteryChargeWh = t.batteryChargeWh,
                    batteryDischargeWh = t.batteryDischargeWh,
                    carChargeWh = t.carChargeWh,
                    batterySocPercent = if (soc.isEmpty()) null else soc.average(),
                )
            }
        }

        /** Kleinster Mittelwert des Verbrauchs ueber ein 15-Minuten-Fenster. */
        private fun baseLoad(sorted: List<EnergySample>): Double? {
            val pts = sorted.mapNotNull { s -> s.consumptionW?.let { s.at to it } }
            if (pts.size < 3) return pts.minOfOrNull { it.second }
            var best: Double? = null
            var i = 0
            for (j in pts.indices) {
                while ((pts[j].first - pts[i].first).inWholeSeconds > 15 * 60) i++
                if (j - i >= 2) {
                    val avg = pts.subList(i, j + 1).map { it.second }.average()
                    if (best == null || avg < best) best = avg
                }
            }
            return best ?: pts.minOfOrNull { it.second }
        }
    }
}
