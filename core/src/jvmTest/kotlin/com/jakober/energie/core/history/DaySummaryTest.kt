package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class DaySummaryTest {
    private val zone = TimeZone.of("Europe/Berlin")
    private val t0 = Instant.parse("2026-09-10T06:00:00Z")

    private fun samples(): List<EnergySample> = (0 until 120).map { i ->
        EnergySample(
            at = t0 + i.minutes, batterySocPercent = 40.0 + i / 4.0, batteryPowerW = 500.0, productionW = 2000.0, consumptionW = 900.0,
            meterGridPowerW = -600.0, meterImportWh = 100_000L + i, meterExportWh = 200_000L + i * 10,
            carSocPercent = 60.0, carEnergyKwh = 54.0, carChargePowerW = if (i in 30..90) 2000.0 else null, carOdometerKm = 41_000.0,
        )
    }

    @Test
    fun `Zusammenfassung ueberlebt den Weg durch JSON`() {
        val date = LocalDate(2026, 9, 10)
        val summary = DaySummary.of(date, samples(), zone)
        assertEquals(120, summary.stats.sampleCount)
        assertEquals(1, summary.sessions.size, "ein Ladevorgang")
        assertEquals(200_000L + 119 * 10, summary.lastMeterExportWh)
        assertTrue(summary.capacity != null && summary.capacity!!.capacityKwh > 80)

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val text = json.encodeToString(DaySummary.serializer(), summary)
        val back = json.decodeFromString(DaySummary.serializer(), text)
        assertEquals(summary.stats.totals, back.stats.totals)
        assertEquals(summary.stats.hours.size, back.stats.hours.size)
        assertEquals(summary.sessions, back.sessions)
        assertEquals(summary.capacity, back.capacity)
        assertEquals(summary.stats.baseLoadW, back.stats.baseLoadW, "Grundlast steckt nicht im Konstruktor und muss trotzdem mit")
    }
}
