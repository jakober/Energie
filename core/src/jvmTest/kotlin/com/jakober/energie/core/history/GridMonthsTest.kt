package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class GridMonthsTest {
    private val zone = TimeZone.UTC

    /** Ein Tag mit Zaehlerstaenden: Bezug waechst um [importWh], Einspeisung um [exportWh]. */
    private fun day(date: LocalDate, startImport: Long, importWh: Long, startExport: Long, exportWh: Long): DayStatistics {
        val t0 = Instant.parse("${date}T00:00:00Z")
        val samples = (0..23).map { h ->
            EnergySample(
                at = t0 + h.hours,
                meterGridPowerW = 300.0,
                consumptionW = 500.0,
                meterImportWh = startImport + importWh * h / 23,
                meterExportWh = startExport + exportWh * h / 23,
            )
        }
        val prev = EnergySample(at = t0 - 1.hours, meterImportWh = startImport, meterExportWh = startExport)
        return DayStatistics.of(date, samples, zone, prev)
    }

    @Test
    fun `monate aus zaehlerstaenden, bezug und kosten`() {
        val days = (1..3).map { d -> day(LocalDate(2026, 9, d), 100_000L + (d - 1) * 5_000L, 5_000L, 20_000L, 2_000L) }
        val months = GridMonths.of(days, today = LocalDate(2026, 9, 3))
        assertEquals(1, months.size)
        val m = months.single()
        assertEquals(15_000.0, m.importWh, 1.0)
        assertEquals(6_000.0, m.exportWh, 1.0)
        assertTrue(m.fromMeter)
        assertEquals(3, m.days)
        assertEquals(3, m.daysElapsed)
        assertTrue(m.complete)
        // 15 kWh x 0,28 EUR = 4,20 EUR, Verguetung 6 kWh x 0,08 = 0,48 EUR
        assertEquals(4.20, m.costEur(0.28), 0.01)
        assertEquals(0.48, m.feedInEur(0.08), 0.01)
        assertEquals(3.72, m.balanceEur(0.28, 0.08), 0.01)
        // Hochrechnung auf 30 Tage
        assertEquals(150_000.0, m.projectedImportWh(30)!!, 100.0)
    }

    @Test
    fun `luecke macht den monat unvollstaendig`() {
        val days = listOf(day(LocalDate(2026, 9, 1), 100_000L, 5_000L, 0L, 0L), day(LocalDate(2026, 9, 3), 110_000L, 5_000L, 0L, 0L))
        val m = GridMonths.of(days, today = LocalDate(2026, 9, 3)).single()
        assertEquals(2, m.days)
        assertEquals(3, m.daysElapsed)
        assertFalse(m.complete)
    }

    @Test
    fun `mehrere monate bleiben getrennt und sortiert`() {
        val days = listOf(
            day(LocalDate(2026, 8, 30), 90_000L, 4_000L, 0L, 0L),
            day(LocalDate(2026, 9, 1), 100_000L, 5_000L, 0L, 0L),
        )
        val months = GridMonths.of(days, today = LocalDate(2026, 9, 1))
        assertEquals(2, months.size)
        assertEquals(8, months[0].month)
        assertEquals(9, months[1].month)
        // August ist vorbei: 31 Tage erwartet, nur einer da.
        assertEquals(31, months[0].daysElapsed)
        assertEquals(1, months[1].daysElapsed)
    }

    @Test
    fun `jahre fassen die monate zusammen`() {
        val days = listOf(
            day(LocalDate(2026, 8, 30), 90_000L, 4_000L, 0L, 1_000L),
            day(LocalDate(2026, 9, 1), 100_000L, 5_000L, 0L, 2_000L),
        )
        val years = GridMonths.years(GridMonths.of(days, today = LocalDate(2026, 9, 1)))
        assertEquals(1, years.size)
        val y = years.single()
        assertEquals(2026, y.year)
        assertEquals(9_000.0, y.importWh, 1.0)
        assertEquals(3_000.0, y.exportWh, 1.0)
        assertEquals(2, y.months)
        assertEquals(2, y.days)
        assertFalse(y.complete)
        assertEquals(9.0 * 0.28, y.costEur(0.28), 0.01)
    }

    @Test
    fun `letzter tag und tage je monat`() {
        assertEquals(LocalDate(2026, 12, 31), GridMonths.lastOfMonth(LocalDate(2026, 12, 1)))
        assertEquals(28, GridMonths.daysInMonth(LocalDate(2026, 2, 1)))
        assertEquals(29, GridMonths.daysInMonth(LocalDate(2028, 2, 1)))
    }
}
