package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class ChargePowerLearnerTest {
    private val t0 = Instant.parse("2026-09-05T20:00:00Z")

    private fun s(min: Int, cons: Double, car: Double? = null, meter: Boolean = true) = EnergySample(
        at = t0 + min.minutes, consumptionW = cons, carChargePowerW = car, meterGridPowerW = if (meter) 100.0 else null,
    )

    @Test
    fun `sprung im Verbrauch beim Ladestart`() {
        val before = listOf(s(-3, 420.0), s(-2, 380.0), s(-1, 400.0))
        assertEquals(2050.0, ChargePowerLearner.estimate(before, s(0, 2450.0, car = 2200.0))!!, 0.1)
    }

    @Test
    fun `median ignoriert einen Ausreisser`() {
        val before = listOf(s(-3, 400.0), s(-2, 3000.0), s(-1, 420.0))
        assertEquals(2030.0, ChargePowerLearner.estimate(before, s(0, 2450.0, car = 2200.0))!!, 0.1)
    }

    @Test
    fun `ohne Zaehler, mit zu alten oder zu wenigen Vergleichspunkten nichts`() {
        assertNull(ChargePowerLearner.estimate(listOf(s(-2, 400.0), s(-1, 400.0)), s(0, 2450.0, meter = false)))
        assertNull(ChargePowerLearner.estimate(listOf(s(-60, 400.0), s(-40, 400.0)), s(0, 2450.0)))
        assertNull(ChargePowerLearner.estimate(listOf(s(-1, 400.0)), s(0, 2450.0)))
    }

    @Test
    fun `unplausible Spruenge werden verworfen`() {
        val before = listOf(s(-2, 400.0), s(-1, 400.0))
        assertNull(ChargePowerLearner.estimate(before, s(0, 900.0)))
        assertNull(ChargePowerLearner.estimate(before, s(0, 15_000.0)))
    }

    @Test
    fun `blend glaettet`() {
        assertEquals(2000.0, ChargePowerLearner.blend(null, 2000.0))
        assertEquals(2060.0, ChargePowerLearner.blend(2000.0, 2200.0), 0.1)
    }
}
