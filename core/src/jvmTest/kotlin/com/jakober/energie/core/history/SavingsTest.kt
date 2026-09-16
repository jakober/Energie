package com.jakober.energie.core.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavingsTest {
    private val totals = EnergyTotals(
        productionWh = 20_000.0, consumptionWh = 15_000.0, gridImportWh = 5_000.0, gridExportWh = 10_000.0,
        batteryChargeWh = 0.0, batteryDischargeWh = 0.0, meterImportWh = null, meterExportWh = null,
    )

    @Test
    fun `ersparnis aus Eigenverbrauch, Verguetung und Bezug`() {
        val s = Savings.of(totals, pricePerKwh = 0.30, feedInPerKwh = 0.08)
        assertEquals(3.0, s.selfConsumptionSavedEur, 1e-9) // 10 kWh Eigenverbrauch
        assertEquals(0.8, s.feedInRevenueEur, 1e-9)
        assertEquals(1.5, s.gridCostEur, 1e-9)
        assertEquals(3.8, s.benefitEur, 1e-9)
        assertEquals(0.7, s.billEur, 1e-9)
    }

    @Test
    fun `amortisation`() {
        assertEquals(0.25, Savings.amortisationShare(5000.0, 20_000.0))
        assertNull(Savings.amortisationShare(5000.0, null))
        assertNull(Savings.amortisationShare(5000.0, 0.0))
        // 3,8 EUR je Tag -> ~1388 EUR je Jahr -> 20000 / 1388 = 14,4 Jahre
        assertEquals(14.4, Savings.yearsToAmortise(3.8, 1, 20_000.0)!!, 0.1)
        assertNull(Savings.yearsToAmortise(0.0, 10, 20_000.0))
    }

    @Test
    fun `monatsprognose skaliert linear auf die Tage mit Daten`() {
        val f = MonthForecast.of(totals, daysWithData = 5, daysInMonth = 30, pricePerKwh = 0.30, feedInPerKwh = 0.08)!!
        assertEquals(9.0, f.gridCostEur, 1e-9)
        assertEquals(4.8, f.feedInRevenueEur, 1e-9)
        assertEquals(90_000.0, f.consumptionWh, 1e-6)
        assertEquals(4.2, f.billEur, 1e-9)
        assertNull(MonthForecast.of(totals, 0, 30, 0.3, 0.08))
    }
}
