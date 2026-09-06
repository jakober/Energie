package com.jakober.energie.core.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class BatteryRuntimeTest {
    @Test
    fun `entladen bis leer, laden bis voll`() {
        // 10 kWh Speicher, 40 % = 4 kWh, 2000 W Abgabe -> 2 h
        val e = BatteryRuntime.estimate(40.0, -2000.0, 10_000.0)!!
        assertEquals(false, e.charging)
        assertEquals(2.hours, e.duration)
        // 60 % fehlen = 6 kWh bei 800 W -> 7,5 h
        val c = BatteryRuntime.estimate(40.0, 800.0, 10_000.0)!!
        assertEquals(true, c.charging)
        assertEquals(450.minutes, c.duration)
    }

    @Test
    fun `ruhe, fehlende Werte und ferner Horizont`() {
        assertNull(BatteryRuntime.estimate(40.0, 20.0, 10_000.0))
        assertNull(BatteryRuntime.estimate(null, -500.0, 10_000.0))
        assertNull(BatteryRuntime.estimate(40.0, -500.0, null))
        val far = BatteryRuntime.estimate(100.0, -60.0, 10_000.0)!!
        assertTrue(far.beyondHorizon)
        assertEquals(BatteryRuntime.MAX_DURATION, far.duration)
    }
}
