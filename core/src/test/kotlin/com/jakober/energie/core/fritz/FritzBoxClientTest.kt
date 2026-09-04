package com.jakober.energie.core.fritz

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FritzBoxClientTest {

    private fun session(sid: String, challenge: String = "2$10000$5A1711$2000$5A1722", block: Int = 0) =
        """<SessionInfo><SID>$sid</SID><Challenge>$challenge</Challenge><BlockTime>$block</BlockTime></SessionInfo>"""

    @Test
    fun anmeldungUndGeraeteliste() = runTest {
        val calls = ArrayList<Url>()
        val engine = MockEngine { req ->
            calls += req.url
            val p = req.url.parameters
            when {
                req.url.encodedPath == "/login_sid.lua" && p["response"] == null -> respond(session(FritzBoxClient.NO_SID))
                req.url.encodedPath == "/login_sid.lua" -> {
                    assertEquals("admin", p["username"])
                    assertEquals("5A1722$1798a1672bca7c6463d6b245f82b53703b0f50813401b03e4045a5861e689adb", p["response"])
                    respond(session("abcdef0123456789"))
                }
                req.url.encodedPath == "/webservices/homeautoswitch.lua" -> {
                    assertEquals("abcdef0123456789", p["sid"])
                    assertEquals("getdevicelistinfos", p["switchcmd"])
                    respond(FritzXmlTest.SMART_ENERGY_250)
                }
                else -> respond("nicht gefunden", HttpStatusCode.NotFound)
            }
        }
        val client = FritzBoxClient(HttpClient(engine), "http://fritz.box", "admin", "1example!")
        val reading = client.smartMeter()!!
        assertEquals(523.45, reading.gridPowerWatt, 1e-9)
        assertEquals(3, calls.size)
    }

    @Test
    fun abgelaufeneSitzungWirdErneuert() = runTest {
        var logins = 0
        val engine = MockEngine { req ->
            val p = req.url.parameters
            when {
                req.url.encodedPath == "/login_sid.lua" && p["response"] == null -> respond(session(FritzBoxClient.NO_SID))
                req.url.encodedPath == "/login_sid.lua" -> { logins++; respond(session("sid$logins")) }
                p["sid"] == "sid1" -> respond("", HttpStatusCode.Forbidden)
                else -> respond(FritzXmlTest.DECT_200)
            }
        }
        val client = FritzBoxClient(HttpClient(engine), "fritz.box", "admin", "x")
        assertEquals(1, client.deviceList().size)
        assertEquals(2, logins)
    }

    @Test
    fun falschesPasswort() = runTest {
        val engine = MockEngine { respond(session(FritzBoxClient.NO_SID)) }
        val client = FritzBoxClient(HttpClient(engine), "fritz.box", "admin", "falsch")
        assertFailsWith<FritzBoxException> { client.login() }
    }

    @Test
    fun sperrzeitWirdGemeldet() = runTest {
        val engine = MockEngine { respond(session(FritzBoxClient.NO_SID, block = 64)) }
        val client = FritzBoxClient(HttpClient(engine), "fritz.box", "admin", "x")
        val e = assertFailsWith<FritzBoxException> { client.login() }
        assertEquals(true, e.message!!.contains("64 s"))
    }
}
