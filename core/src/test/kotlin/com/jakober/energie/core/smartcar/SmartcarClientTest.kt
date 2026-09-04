package com.jakober.energie.core.smartcar

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartcarClientTest {

    private var tokenCalls = 0
    private val requests = ArrayList<HttpRequestData>()

    private fun engine() = MockEngine { req ->
        requests += req
        val path = req.url.encodedPath
        when {
            req.url.host == "iam.smartcar.com" -> {
                tokenCalls++
                val body = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                assertTrue(body.contains("grant_type=client_credentials"), body)
                assertTrue(body.contains("client_id=client_01X"), body)
                respond("""{"access_token":"tok-123","token_type":"Bearer","expires_in":3600}""")
            }
            path == "/v3/connections" -> respond(
                """{"data":[{"type":"connection","attributes":{"vehicleId":"veh-1","userId":"usr-9","connectedAt":"2026-09-04T18:00:00Z"}}],"meta":{"page":{"number":1}}}""",
            )
            path.endsWith("/signals/tractionbattery-stateofcharge") -> respond("""{"data":{"code":"tractionbattery-stateofcharge","attributes":{"stateOfCharge":0.63,"unit":"%"}}}""")
            path.endsWith("/signals/tractionbattery-range") -> respond("""{"data":{"attributes":{"range":212.5}}}""")
            path.endsWith("/signals/charge-ischarging") -> respond("""{"data":{"attributes":{"isCharging":true}}}""")
            path.endsWith("/signals/charge-ischargingcableconnected") -> respond("""{"data":{"attributes":{"isChargingCableConnected":true}}}""")
            path.endsWith("/signals/charge-activelimit") -> respond("""{"data":{"attributes":{"activeLimit":90}}}""")
            path.endsWith("/signals/charge-detailedchargingstatus") -> respond("""{"data":{"attributes":{"detailedChargingStatus":"CHARGING"}}}""")
            path.endsWith("/signals/charge-wattage") -> respond("""{"errors":[{"status":"404"}]}""", HttpStatusCode.NotFound)
            path.endsWith("/signals/charge-voltage") -> respond("""{"data":{"attributes":{"voltage":230}}}""")
            path.endsWith("/signals/charge-amperage") -> respond("""{"data":{"attributes":{"amperage":10}}}""")
            path.endsWith("/commands/charge/set-limit") -> {
                assertEquals(HttpMethod.Post, req.method)
                assertEquals("""{"data":{"attributes":{"percent":50}}}""", (req.body as TextContent).text)
                respond("""{"data":{"status":"PENDING"}}""", HttpStatusCode.Accepted)
            }
            path.endsWith("/commands/charge/stop") -> respond("""{"data":{"status":"SUCCESS"}}""")
            else -> respond("nicht gefunden: $path", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun verbindungenZustandUndBefehle() = runTest {
        val client = SmartcarClient(HttpClient(engine()), "client_01X", "geheim")
        val conns = client.connections()
        assertEquals(listOf("veh-1"), conns.map { it.vehicleId })
        assertEquals("usr-9", conns.single().userId)

        val state = client.state("veh-1", "usr-9")
        assertEquals(63.0, state.socPercent)
        assertEquals(212.5, state.rangeKm)
        assertEquals(true, state.isCharging)
        assertEquals(true, state.isPluggedIn)
        assertEquals(90.0, state.chargeLimitPercent)
        assertEquals("CHARGING", state.chargingStatus)
        assertEquals(2300.0, state.chargePowerW)
        assertEquals(9, state.raw.size)

        val limit = client.setChargeLimit("veh-1", "usr-9", 30) // wird auf 50 begrenzt
        assertTrue(limit.ok)
        assertTrue(client.stopCharge("veh-1", "usr-9").ok)

        // Ein Token fuer alles, Nutzer-Header ueberall ausser bei /connections
        assertEquals(1, tokenCalls)
        val apiCalls = requests.filter { it.url.host == "vehicle.api.smartcar.com" }
        assertTrue(apiCalls.all { it.headers["Authorization"] == "Bearer tok-123" })
        assertTrue(apiCalls.filter { it.url.encodedPath != "/v3/connections" }.all { it.headers["sc-user-id"] == "usr-9" })
    }

    @Test
    fun connectAdresse() {
        val url = SmartcarClient.connectUrl("cf56d752-8533-426a-8fec-a45c9fe81eb9")
        assertTrue(url.startsWith("https://connect.smartcar.com/oauth/authorize?response_type=none&client_id=cf56d752-8533-426a-8fec-a45c9fe81eb9&"))
        assertTrue(url.contains("scope=read_vehicle_info%20read_battery%20read_charge%20control_charge"))
        assertTrue(url.contains("mode=live"))
        assertTrue(url.contains("redirect_uri=sccf56d752-8533-426a-8fec-a45c9fe81eb9%3A%2F%2Fexchange"))
    }

    @Test
    fun fehlerhafteAnmeldung() = runTest {
        val engine = MockEngine { respond("""{"error":"invalid_client"}""", HttpStatusCode.Unauthorized) }
        val client = SmartcarClient(HttpClient(engine), "x", "y")
        try {
            client.connections()
            error("erwartete Ausnahme")
        } catch (e: SmartcarException) {
            assertEquals(401, e.status)
        }
    }
}
