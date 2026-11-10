package com.jakober.energie.core.alerts

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CloudWatchTest {
    private val t0 = Instant.parse("2026-11-07T01:30:00Z")
    private fun at(min: Int) = Instant.fromEpochSeconds(t0.epochSeconds + min * 60L)

    @Test
    fun `ein einzelner Fehlschlag meldet nichts, merkt sich aber den Beginn`() {
        val (alert, state) = CloudWatch.step(at(0), "HTTP 401", CloudWatchState(), 60, hub = true)
        assertNull(alert)
        assertEquals(t0.epochSeconds, state.failingSince)
    }

    @Test
    fun `nach der Frist kommt genau ein Hinweis mit der Meldung`() {
        var state = CloudWatchState()
        val alerts = (0..180 step 15).mapNotNull { m ->
            val (a, s) = CloudWatch.step(at(m), "HTTP 401 JWT issued at future", state, 60, hub = true)
            state = s; a
        }
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.CLOUD_DOWN, alerts[0].kind)
        assertEquals(true, alerts[0].text.contains("JWT issued at future"))
        assertEquals(true, alerts[0].text.contains("1 h 0 min"))
    }

    @Test
    fun `ein Erfolg dazwischen setzt die Frist zurueck`() {
        var state = CloudWatch.step(at(0), "Zeitueberschreitung", CloudWatchState(), 60, hub = false).second
        state = CloudWatch.step(at(45), null, state, 60, hub = false).second
        val (alert, _) = CloudWatch.step(at(70), "Zeitueberschreitung", state, 60, hub = false)
        assertNull(alert)
    }

    @Test
    fun `wieder da kommt nur, wenn vorher gemeldet wurde`() {
        assertNull(CloudWatch.step(at(5), null, CloudWatchState(failingSince = t0.epochSeconds), 60, hub = true).first)
        val (back, state) = CloudWatch.step(at(200), null, CloudWatchState(t0.epochSeconds, reported = true), 60, hub = true)
        assertNotNull(back)
        assertEquals(AlertKind.CLOUD_BACK, back.kind)
        assertEquals(CloudWatchState(), state)
    }
}
