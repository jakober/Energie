package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/**
 * Alles, was eine Anzeige ueber einen Tag braucht, ohne dessen Messpunkte zu laden:
 * Tagesstatistik, Ladevorgaenge, Fahrtage, Akkukapazitaet und der letzte Zaehlerstand
 * (fuer die Zaehlerdifferenz des Folgetags). Die Zentrale schreibt je Tag eine Zeile
 * in die Cloud, die iOS-Anzeige liest Wochen, Monate und Jahre daraus.
 */
@Serializable
data class DaySummary(
    val date: LocalDate,
    val stats: DayStatistics,
    val sessions: List<ChargeSession> = emptyList(),
    val drive: List<DriveDay> = emptyList(),
    val capacity: CapacityPoint? = null,
    /** Letzter Messpunkt des Tages mit Zaehlerstand, als Startwert fuer den Folgetag. */
    val lastMeterAt: Instant? = null,
    val lastMeterImportWh: Long? = null,
    val lastMeterExportWh: Long? = null,
    /** Version des Formats, damit alte Zeilen erkannt und neu geschrieben werden koennen. */
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 1

        /** Baut die Zusammenfassung aus den Messpunkten des Tages; Fahrtage kommen von aussen, weil sie den Vortag brauchen. */
        fun of(
            date: LocalDate,
            samples: List<EnergySample>,
            zone: TimeZone = TimeZone.currentSystemDefault(),
            previous: EnergySample? = null,
            drive: List<DriveDay> = emptyList(),
        ): DaySummary {
            val sorted = samples.sortedBy { it.at }
            val lastMeter = sorted.lastOrNull { it.meterImportWh != null || it.meterExportWh != null }
            return DaySummary(
                date = date,
                stats = DayStatistics.of(date, sorted, zone, previous),
                sessions = ChargeSessions.of(sorted),
                drive = drive,
                capacity = CarBatteryHealth.dayPoint(date, sorted),
                lastMeterAt = lastMeter?.at,
                lastMeterImportWh = lastMeter?.meterImportWh,
                lastMeterExportWh = lastMeter?.meterExportWh,
            )
        }
    }
}
