package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class DayStatisticsTest {
    private val zone = TimeZone.of("Europe/Berlin")
    private val t0 = Instant.parse("2026-09-04T04:00:00Z") // 06:00 Ortszeit

    private fun sample(minutes: Int, cons: Double, prod: Double, soc: Double, grid: Double = cons - prod) =
        EnergySample(
            at = t0 + minutes.minutes, consumptionW = cons, productionW = prod,
            batterySocPercent = soc, senecGridPowerW = grid, batteryPowerW = 0.0,
            meterImportWh = 5000L + minutes, meterExportWh = 9000L,
        )

    @Test
    fun spitzenMitUhrzeit() {
        val samples = listOf(
            sample(0, 300.0, 0.0, 40.0),
            sample(60, 350.0, 500.0, 45.0),
            sample(120, 2800.0, 1500.0, 55.0), // 08:00 Kochen
            sample(180, 400.0, 3000.0, 70.0),
            sample(240, 380.0, 2900.0, 85.0),
        )
        val s = DayStatistics.of(LocalDate(2026, 9, 4), samples, zone)
        assertEquals(5, s.sampleCount)
        assertEquals(Peak(t0 + 120.minutes, 2800.0), s.peakConsumption)
        assertEquals(Peak(t0 + 180.minutes, 3000.0), s.peakProduction)
        assertEquals(Peak(t0 + 120.minutes, 1300.0), s.peakGridImport)
        assertEquals(Peak(t0 + 180.minutes, 2600.0), s.peakGridExport)
        assertEquals(40.0, s.socStart)
        assertEquals(85.0, s.socEnd)
        assertEquals(85.0, assertNotNull(s.socMax).value)
        assertEquals(5000L, s.meterImportStartWh)
        assertEquals(5240L, s.meterImportEndWh)
        assertEquals(t0 + 60.minutes, s.firstProduction)
        assertEquals(t0 + 240.minutes, s.lastProduction)
        assertNull(s.peakBatteryCharge)
        assertEquals(24, s.hours.size)
        // Stunde 7 (Ortszeit) umfasst das Intervall 07:00-08:00 mit Mittelwert (350+2800)/2
        assertEquals(1575.0, s.hours[7].consumptionWh, 1e-6)
        assertEquals(8, assertNotNull(s.heaviestHour).hour) // (2800+400)/2 > (350+2800)/2
        assertEquals(45.0, s.hours[7].batterySocPercent)
        assertEquals(325.0, s.hours[6].consumptionWh, 1e-6)
    }

    @Test
    fun grundlastIstKleinstesViertelstundenmittel() {
        val samples = (0 until 60).map { m ->
            val cons = if (m in 20..40) 150.0 else 600.0
            sample(m, cons, 0.0, 50.0)
        }
        val s = DayStatistics.of(LocalDate(2026, 9, 4), samples, zone)
        assertEquals(150.0, assertNotNull(s.baseLoadW), 1e-6)
    }

    @Test
    fun leererTag() {
        val s = DayStatistics.of(LocalDate(2026, 9, 4), emptyList(), zone)
        assertEquals(0, s.sampleCount)
        assertNull(s.peakConsumption)
        assertNull(s.heaviestHour)
        assertNull(s.baseLoadW)
    }
}
