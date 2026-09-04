package com.jakober.energie.core.fritz

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FritzXmlTest {

    @Test
    fun smartEnergy250ErscheintAlsLesekopfPlusZweiUnterzaehler() {
        val devices = FritzXml.parseDeviceList(SMART_ENERGY_250)
        assertEquals(3, devices.size)

        val head = devices[0]
        assertEquals("13979 0123456", head.ain)
        assertNull(head.powerMeter)
        assertNull(head.meterRole)

        val import = devices.first { it.meterRole == MeterRole.GRID_IMPORT }
        assertEquals("139790123456-1", import.ainCompact)
        assertEquals(523.45, import.powerMeter!!.powerWatt, 1e-9)
        assertEquals(12_345_678L, import.powerMeter!!.energyWh)
        assertEquals(230.1, import.powerMeter!!.voltage!!, 1e-9)

        val export = devices.first { it.meterRole == MeterRole.GRID_EXPORT }
        assertEquals("139790123456-2", export.ainCompact)
        assertEquals(0.0, export.powerMeter!!.powerWatt)
        assertEquals(6_543_210L, export.powerMeter!!.energyWh)
        assertNull(export.powerMeter!!.voltage)
    }

    @Test
    fun zusammenfassungBezug() {
        val reading = summarizeSmartMeter(FritzXml.parseDeviceList(SMART_ENERGY_250), FixedClock.now())
        assertNotNull(reading)
        assertEquals(523.45, reading.gridPowerWatt, 1e-9)
        assertEquals(12_345_678L, reading.importEnergyWh)
        assertEquals(6_543_210L, reading.exportEnergyWh)
        assertEquals(FixedClock.now(), reading.at)
    }

    @Test
    fun zusammenfassungEinspeisungWirdNegativ() {
        val xml = SMART_ENERGY_250
            .replace("<power>523450</power>", "<power>0</power>")
            .replace("<power>0</power>\n        <energy>6543210</energy>", "<power>1800000</power>\n        <energy>6543210</energy>")
        val reading = summarizeSmartMeter(FritzXml.parseDeviceList(xml), FixedClock.now())!!
        assertEquals(-1800.0, reading.gridPowerWatt, 1e-9)
    }

    @Test
    fun dect200MitSchalterUndTemperatur() {
        val devices = FritzXml.parseDeviceList(DECT_200)
        val d = devices.single()
        assertEquals(true, d.switchOn)
        assertEquals(21.5, d.temperatureCelsius)
        assertEquals(12.3, d.powerMeter!!.powerWatt, 1e-9)
        assertNull(d.meterRole)
    }

    @Test
    fun statistikenSkaliertUndGeordnet() {
        val stats = FritzXml.parseDeviceStats(STATS)
        val days = stats.energyPerDayWh!!
        assertEquals(86_400, days.gridSeconds)
        assertEquals(listOf(1200.0, 3400.0, null), days.values)
        assertEquals(Instant.fromEpochSeconds(1_756_900_000), days.newestAt)
        assertEquals(2_678_400, stats.energyPerMonthWh!!.gridSeconds)
        // Leistung kommt in 0,01 W
        assertEquals(listOf(5.0, 6.5), stats.power.single().values)
        assertEquals(listOf(230.0), stats.voltage.single().values)
        assertTrue(stats.temperature.isEmpty())
    }

    @Test
    fun sitzungsinfo() {
        val info = FritzXml.parseSessionInfo(
            """<?xml version="1.0" encoding="utf-8"?><SessionInfo><SID>0000000000000000</SID><Challenge>2$10000$5A1711$2000$5A1722</Challenge><BlockTime>0</BlockTime><Rights></Rights></SessionInfo>""",
        )
        assertEquals(FritzBoxClient.NO_SID, info.sid)
        assertEquals("2$10000$5A1711$2000$5A1722", info.challenge)
        assertEquals(0, info.blockTimeSeconds)
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_756_950_000)
    }

    companion object {
        val SMART_ENERGY_250 = """
<devicelist version="1" fwversion="8.20">
  <device identifier="13979 0123456" id="16" functionbitmask="1" fwversion="04.26" manufacturer="AVM" productname="FRITZ!Smart Energy 250">
    <present>1</present>
    <txbusy>0</txbusy>
    <name>Stromzaehler</name>
    <battery>90</battery>
    <batterylow>0</batterylow>
  </device>
  <device identifier="13979 0123456-1" id="2000" functionbitmask="8322" fwversion="04.26" manufacturer="AVM" productname="FRITZ!Smart Energy 250">
    <present>1</present>
    <txbusy>0</txbusy>
    <name>Stromzaehler: Strombezug</name>
    <powermeter>
        <voltage>230100</voltage>
        <power>523450</power>
        <energy>12345678</energy>
    </powermeter>
  </device>
  <device identifier="13979 0123456-2" id="2001" functionbitmask="8322" fwversion="04.26" manufacturer="AVM" productname="FRITZ!Smart Energy 250">
    <present>1</present>
    <txbusy>0</txbusy>
    <name>Stromzaehler: Einspeisung</name>
    <powermeter>
        <voltage></voltage>
        <power>0</power>
        <energy>6543210</energy>
    </powermeter>
  </device>
</devicelist>
""".trim()

        val DECT_200 = """
<devicelist version="1">
  <device identifier="08761 0000434" id="17" functionbitmask="35712" fwversion="04.26" manufacturer="AVM" productname="FRITZ!DECT 200">
    <present>1</present>
    <name>Steckdose</name>
    <switch><state>1</state><mode>manuell</mode><lock>0</lock><devicelock>0</devicelock></switch>
    <powermeter><voltage>229800</voltage><power>12300</power><energy>4567</energy></powermeter>
    <temperature><celsius>215</celsius><offset>0</offset></temperature>
  </device>
</devicelist>
""".trim()

        val STATS = """
<devicestats>
  <voltage><stats count="1" grid="10" datatime="1756900000">230000</stats></voltage>
  <power><stats count="2" grid="10" datatime="1756900000">500,650</stats></power>
  <energy>
    <stats count="3" grid="2678400" datatime="1756900000">55000,61000,-</stats>
    <stats count="3" grid="86400" datatime="1756900000">1200,3400,-</stats>
  </energy>
</devicestats>
""".trim()
    }
}
