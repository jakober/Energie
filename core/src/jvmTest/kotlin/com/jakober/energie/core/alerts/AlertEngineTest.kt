package com.jakober.energie.core.alerts

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class AlertEngineTest {
    private val t0 = Instant.parse("2026-09-05T10:00:00Z")
    private val settings = AlertSettings()

    private fun input(
        now: Instant = t0,
        soc: Double? = 50.0, grid: Double? = 0.0,
        plugged: Boolean? = false, charging: Boolean? = false,
        lock: String? = "LOCKED", distance: Double? = 20.0,
        override: Boolean = false,
        senecOk: Instant? = now, fritzOk: Instant? = now,
        line: String? = null, carSoc: Double? = null, lockAt: Instant? = null,
    ) = AlertInput(
        now = now, batterySocPercent = soc, gridPowerW = grid, carPluggedIn = plugged, carCharging = charging,
        carLockState = lock, carDistanceHomeM = distance, chargeOverride = override,
        senecConfigured = true, fritzConfigured = true, lastSenecOkAt = senecOk, lastFritzOkAt = fritzOk, automationLine = line,
        carSocPercent = carSoc, carLockUpdatedAt = lockAt,
    )

    @Test
    fun `alte Verriegelungsmeldung von vor der Ankunft zaehlt nicht als offen`() {
        // Unterwegs, dann zu Hause: Ford meldet "offen" mit einem Zeitstempel von vor der Ankunft.
        var r = AlertEngine.evaluate(input(lock = "UNLOCKED", distance = 5000.0, lockAt = t0 - 3.hours), AlertState(), settings)
        assertNull(r.state.homeSince)
        val arrived = t0 + 10.minutes
        r = AlertEngine.evaluate(input(now = arrived, lock = "UNLOCKED", distance = 20.0, lockAt = t0 - 3.hours), r.state, settings)
        assertEquals(arrived.epochSeconds, r.state.homeSince)
        assertNull(r.state.unlockedSince, "Meldung von vor der Ankunft sagt nichts ueber das Parken")
        r = AlertEngine.evaluate(input(now = arrived + 30.minutes, lock = "UNLOCKED", distance = 20.0, lockAt = t0 - 3.hours), r.state, settings)
        assertTrue(r.alerts.isEmpty())
        // Frische Meldung nach der Ankunft: jetzt zaehlt es.
        r = AlertEngine.evaluate(input(now = arrived + 31.minutes, lock = "UNLOCKED", distance = 20.0, lockAt = arrived + 1.minutes), r.state, settings)
        assertEquals((arrived + 31.minutes).epochSeconds, r.state.unlockedSince)
        r = AlertEngine.evaluate(input(now = arrived + 42.minutes, lock = "UNLOCKED", distance = 20.0, lockAt = arrived + 1.minutes), r.state, settings)
        assertEquals(listOf(AlertKind.CAR_UNLOCKED_HOME), r.alerts.map { it.kind })
    }

    @Test
    fun `ladestart und ladeende melden nur den Wechsel`() {
        // Erster Durchlauf: Zustand lernen, keine Meldung.
        var r = AlertEngine.evaluate(input(charging = false, carSoc = 60.0), AlertState(), settings)
        assertTrue(r.alerts.isEmpty())
        assertEquals(false, r.state.lastCharging)
        // Laedt jetzt: eine Meldung mit Akkustand.
        r = AlertEngine.evaluate(input(plugged = true, charging = true, carSoc = 61.0), r.state, settings)
        assertEquals(listOf(AlertKind.CHARGE_STARTED), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].text.contains("61 %"))
        // Laedt weiter: nichts.
        r = AlertEngine.evaluate(input(plugged = true, charging = true, carSoc = 70.0), r.state, settings)
        assertTrue(r.alerts.isEmpty())
        // Ende: Meldung mit von-bis, Auto steckt noch.
        r = AlertEngine.evaluate(input(plugged = true, charging = false, carSoc = 80.0), r.state, settings)
        assertEquals(listOf(AlertKind.CHARGE_STOPPED), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].text.contains("61 % → 80 %"), r.alerts[0].text)
        assertTrue(r.alerts[0].text.contains("steckt noch"))
        // Abgeschaltet: keine Meldung, Zustand bleibt gemerkt.
        r = AlertEngine.evaluate(input(plugged = true, charging = true, carSoc = 80.0), r.state, settings.copy(chargeStartStop = false))
        assertTrue(r.alerts.isEmpty())
        assertEquals(true, r.state.lastCharging)
    }

    @Test
    fun `nichts zu melden im Normalfall`() {
        val r = AlertEngine.evaluate(input(), AlertState(), settings)
        assertTrue(r.alerts.isEmpty())
        assertNull(r.state.unlockedSince)
    }

    @Test
    fun `auto unverschlossen zu Hause meldet nach Wartezeit genau einmal`() {
        var state = AlertState()
        var r = AlertEngine.evaluate(input(lock = "UNLOCKED"), state, settings)
        assertTrue(r.alerts.isEmpty(), "Erst merken, noch nicht melden")
        assertEquals(t0.epochSeconds, r.state.unlockedSince)
        state = r.state

        r = AlertEngine.evaluate(input(now = t0 + 5.minutes, lock = "UNLOCKED"), state, settings)
        assertTrue(r.alerts.isEmpty(), "5 min sind unter der Schwelle")
        state = r.state

        r = AlertEngine.evaluate(input(now = t0 + 10.minutes, lock = "UNLOCKED"), state, settings)
        assertEquals(listOf(AlertKind.CAR_UNLOCKED_HOME), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].text.contains("10 min"))
        state = r.state

        r = AlertEngine.evaluate(input(now = t0 + 20.minutes, lock = "UNLOCKED"), state, settings)
        assertTrue(r.alerts.isEmpty(), "nicht wiederholen")
        state = r.state

        // Abgeschlossen: Zustand zurueck, naechstes Mal wieder melden.
        r = AlertEngine.evaluate(input(now = t0 + 30.minutes, lock = "LOCKED"), state, settings)
        assertNull(r.state.unlockedSince)
        assertEquals(false, r.state.unlockedReported)
    }

    @Test
    fun `unbekannter Schliesszustand aendert nichts`() {
        val state = AlertState(unlockedSince = t0.epochSeconds, unlockedReported = true, lastCharging = false)
        val r = AlertEngine.evaluate(input(now = t0 + 60.minutes, lock = null), state, settings)
        assertEquals(state.copy(homeSince = (t0 + 60.minutes).epochSeconds), r.state)
        assertTrue(r.alerts.isEmpty())
    }

    @Test
    fun `unverschlossen unterwegs ist kein Thema`() {
        val r = AlertEngine.evaluate(input(now = t0 + 60.minutes, lock = "UNLOCKED", distance = 5000.0), AlertState(unlockedSince = t0.epochSeconds), settings)
        assertTrue(r.alerts.isEmpty())
        assertNull(r.state.unlockedSince)
    }

    @Test
    fun `ueberschuss ungenutzt mit Ladeknopf, hoechstens einmal je Stunde`() {
        var r = AlertEngine.evaluate(input(soc = 97.0, grid = -2100.0, plugged = true, charging = false), AlertState(), settings)
        assertEquals(listOf(AlertKind.SURPLUS_UNUSED), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].offerCharge)
        assertTrue(r.alerts[0].text.contains("2100 W"))
        val state = r.state

        r = AlertEngine.evaluate(input(now = t0 + 30.minutes, soc = 97.0, grid = -2100.0, plugged = true, charging = false), state, settings)
        assertTrue(r.alerts.isEmpty())

        r = AlertEngine.evaluate(input(now = t0 + 61.minutes, soc = 97.0, grid = -2100.0, plugged = true, charging = false), state, settings)
        assertEquals(1, r.alerts.size)
    }

    @Test
    fun `kein Ueberschuss-Hinweis wenn Auto laedt, Handschalter an oder Speicher nicht voll`() {
        assertTrue(AlertEngine.evaluate(input(soc = 97.0, grid = -2100.0, plugged = true, charging = true), AlertState(), settings).alerts.isEmpty())
        assertTrue(AlertEngine.evaluate(input(soc = 97.0, grid = -2100.0, plugged = true, charging = false, override = true), AlertState(), settings).alerts.isEmpty())
        assertTrue(AlertEngine.evaluate(input(soc = 80.0, grid = -2100.0, plugged = true, charging = false), AlertState(), settings).alerts.isEmpty())
        assertTrue(AlertEngine.evaluate(input(soc = 97.0, grid = -900.0, plugged = true, charging = false), AlertState(), settings).alerts.isEmpty())
    }

    @Test
    fun `automatik-zeile wird durchgereicht`() {
        val r = AlertEngine.evaluate(input(line = "10:00 Pausiert: Speicher 40 %"), AlertState(), settings)
        assertEquals(listOf(AlertKind.AUTOMATION_ACTED), r.alerts.map { it.kind })
        assertEquals("10:00 Pausiert: Speicher 40 %", r.alerts[0].text)
        assertTrue(AlertEngine.evaluate(input(line = "x"), AlertState(), settings.copy(automation = false)).alerts.isEmpty())
    }

    @Test
    fun `quelle ausgefallen einmal melden und Rueckkehr melden`() {
        var r = AlertEngine.evaluate(input(now = t0 + 61.minutes, senecOk = t0), AlertState(), settings)
        assertEquals(listOf(AlertKind.SOURCE_DOWN), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].title.contains("SENEC"))
        assertTrue(r.state.senecDownReported)

        r = AlertEngine.evaluate(input(now = t0 + 90.minutes, senecOk = t0), r.state, settings)
        assertTrue(r.alerts.isEmpty())

        r = AlertEngine.evaluate(input(now = t0 + 95.minutes, senecOk = t0 + 95.minutes), r.state, settings)
        assertEquals(listOf(AlertKind.SOURCE_BACK), r.alerts.map { it.kind })
        assertEquals(false, r.state.senecDownReported)
    }

    @Test
    fun `quelle die noch nie geantwortet hat wird nicht gemeldet`() {
        val r = AlertEngine.evaluate(input(now = t0 + 600.minutes, senecOk = null, fritzOk = null), AlertState(), settings)
        assertTrue(r.alerts.isEmpty())
    }

    @Test
    fun `leeres Auto zu Hause ohne Stecker meldet einmal je Parkvorgang`() {
        var r = AlertEngine.evaluate(input(carSoc = 40.0), AlertState(), settings)
        assertEquals(listOf(AlertKind.CAR_LOW_UNPLUGGED), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].text.contains("40 %"))
        // Gleiche Lage eine Stunde spaeter: nicht noch einmal.
        r = AlertEngine.evaluate(input(now = t0 + 1.hours, carSoc = 40.0), r.state, settings)
        assertTrue(r.alerts.isEmpty())
        // Angesteckt: Parkvorgang gilt als erledigt.
        r = AlertEngine.evaluate(input(now = t0 + 2.hours, plugged = true, carSoc = 40.0), r.state, settings)
        assertEquals(false, r.state.carLowReported)
        // Wieder abgezogen: darf erneut melden.
        r = AlertEngine.evaluate(input(now = t0 + 3.hours, carSoc = 40.0), r.state, settings)
        assertEquals(listOf(AlertKind.CAR_LOW_UNPLUGGED), r.alerts.map { it.kind })
    }

    @Test
    fun `volles Auto, unterwegs oder unbekannter Stecker melden nicht`() {
        assertTrue(AlertEngine.evaluate(input(carSoc = 80.0), AlertState(), settings).alerts.isEmpty())
        assertTrue(AlertEngine.evaluate(input(carSoc = 40.0, distance = 5000.0), AlertState(), settings).alerts.isEmpty())
        assertTrue(AlertEngine.evaluate(input(carSoc = 40.0, plugged = null), AlertState(), settings).alerts.isEmpty())
        assertTrue(AlertEngine.evaluate(input(carSoc = 40.0, distance = null), AlertState(), settings).alerts.isEmpty())
    }

    @Test
    fun `Strom da und Auto steckt nicht meldet hoechstens stuendlich`() {
        // Einspeisung ueber der Schwelle.
        var r = AlertEngine.evaluate(input(grid = -2000.0, carSoc = 70.0), AlertState(), settings)
        assertEquals(listOf(AlertKind.CAR_SURPLUS_UNPLUGGED), r.alerts.map { it.kind })
        assertTrue(r.alerts[0].text.contains("2000 W"))
        r = AlertEngine.evaluate(input(now = t0 + 30.minutes, grid = -2000.0, carSoc = 70.0), r.state, settings)
        assertTrue(r.alerts.isEmpty())
        r = AlertEngine.evaluate(input(now = t0 + 61.minutes, grid = -2000.0, carSoc = 70.0), r.state, settings)
        assertEquals(listOf(AlertKind.CAR_SURPLUS_UNPLUGGED), r.alerts.map { it.kind })
        // Voller Hausspeicher allein reicht auch.
        val s2 = AlertEngine.evaluate(input(soc = 85.0, carSoc = 70.0), AlertState(), settings)
        assertEquals(listOf(AlertKind.CAR_SURPLUS_UNPLUGGED), s2.alerts.map { it.kind })
        assertTrue(s2.alerts[0].text.contains("85 %"))
        // Weder Sonne noch voller Speicher: nichts.
        assertTrue(AlertEngine.evaluate(input(soc = 40.0, grid = 300.0, carSoc = 70.0), AlertState(), settings).alerts.isEmpty())
    }

    @Test
    fun `beide Hinweise lassen sich einzeln abschalten`() {
        val ohneLeer = settings.copy(carLowUnplugged = false)
        assertTrue(AlertEngine.evaluate(input(carSoc = 30.0), AlertState(), ohneLeer).alerts.isEmpty())
        val ohneStrom = settings.copy(carSurplusUnplugged = false)
        assertTrue(AlertEngine.evaluate(input(grid = -3000.0, carSoc = 70.0), AlertState(), ohneStrom).alerts.isEmpty())
    }
}
