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
            path.endsWith("/signals/tractionbattery-stateofcharge") -> respond("""{"data":{"id":"tractionbattery-stateofcharge","type":"signal","attributes":{"code":"tractionbattery-stateofcharge","status":{"value":"SUCCESS"},"body":{"value":50,"unit":"percent"}}},"included":{"vehicle":{"id":"veh-1","type":"vehicle"}}}""")
            path.endsWith("/signals/tractionbattery-range") -> respond("""{"data":{"id":"tractionbattery-range","type":"signal","attributes":{"code":"tractionbattery-range","status":{"value":"SUCCESS"},"body":{"value":219,"type":"DEFAULT","additionalValues":[],"unit":"km"}}},"included":{"vehicle":{"id":"veh-1","type":"vehicle"}}}""")
            path.endsWith("/signals/charge-ischarging") -> respond("""{"data":{"id":"charge-ischarging","type":"signal","attributes":{"code":"charge-ischarging","status":{"value":"SUCCESS"},"body":{"value":true}}},"included":{"vehicle":{"id":"veh-1","type":"vehicle"}}}""")
            path.endsWith("/signals/charge-ischargingcableconnected") -> respond("""{"data":{"id":"charge-ischargingcableconnected","type":"signal","attributes":{"code":"charge-ischargingcableconnected","status":{"value":"SUCCESS"},"body":{"value":true}}},"included":{"vehicle":{"id":"veh-1","type":"vehicle"}}}""")
            path.endsWith("/signals/charge-chargelimits") -> respond("""{"data":{"id":"charge-chargelimits","type":"signal","attributes":{"code":"charge-chargelimits","status":{"value":"SUCCESS"},"body":{"value":[{"limit":90,"isActive":true,"location":"HOME"},{"limit":100,"isActive":false}]}}},"included":{"vehicle":{"id":"veh-1","type":"vehicle"}}}""")
            path.endsWith("/signals/charge-detailedchargingstatus") -> respond("""{"data":{"id":"charge-detailedchargingstatus","type":"signal","attributes":{"code":"charge-detailedchargingstatus","status":{"value":"SUCCESS"},"body":{"value":"CHARGING"}}},"included":{"vehicle":{"id":"veh-1","type":"vehicle"}}}""")
            path.endsWith("/signals/charge-voltage") -> respond("""{"data":{"id":"charge-voltage","type":"signal","attributes":{"code":"charge-voltage","status":{"value":"SUCCESS"},"body":{"value":346,"unit":"volts"}}},"included":{"vehicle":{"id":"veh-1","type":"vehicle"}}}""")
            path.endsWith("/signals/charge-amperage") -> respond("""{"data":{"id":"charge-amperage","type":"signal","attributes":{"code":"charge-amperage","status":{"value":"SUCCESS"},"body":{"value":5.4,"unit":"ampere"}}},"included":{"vehicle":{"id":"veh-1","type":"vehicle"}}}""")
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
        val conns = client.connections().connections
        assertEquals(listOf("veh-1"), conns.map { it.vehicleId })
        assertEquals("usr-9", conns.single().userId)

        val state = client.state("veh-1", "usr-9")
        assertEquals(50.0, state.socPercent)
        assertEquals(219.0, state.rangeKm)
        assertEquals(true, state.isCharging)
        assertEquals(true, state.isPluggedIn)
        assertEquals(90.0, state.chargeLimitPercent)
        assertEquals("CHARGING", state.chargingStatus)
        assertEquals(346 * 5.4 / 0.88, state.chargePowerW!!, 1e-6)
        assertEquals(8, state.raw.size)

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
    fun andereSchreibweisenDerVerbindungsliste() = runTest {
        suspend fun parse(body: String): List<SmartcarConnection> {
            val engine = MockEngine { req ->
                when {
                    req.url.host == "iam.smartcar.com" -> respond("""{"access_token":"t","expires_in":3600}""")
                    req.url.encodedPath == "/v3/connections" -> respond(body)
                    else -> respond("""{"data":[]}""")
                }
            }
            return SmartcarClient(HttpClient(engine), "x", "y").connections().connections
        }
        assertEquals("v-snake", parse("""{"data":[{"vehicle_id":"v-snake","user_id":"u1"}]}""").single().vehicleId)
        val nested = parse("""{"data":[{"vehicle":{"id":"v-nested","make":"FORD"},"user":{"id":"u2"}}]}""").single()
        assertEquals("v-nested", nested.vehicleId)
        assertEquals("u2", nested.userId)
        assertEquals("v-jsonapi", parse("""{"data":[{"type":"vehicle","id":"v-jsonapi","attributes":{}}]}""").single().vehicleId)
        assertEquals(listOf("a", "b"), parse("""{"vehicles":["a","b"],"paging":{"count":2}}""").map { it.vehicleId })
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
    fun fehlerhafteAnmeldungNenntBeideVersuche() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("""{"error":"invalid_client"}""", HttpStatusCode.Unauthorized) }
        val client = SmartcarClient(HttpClient(engine), "x", "y")
        try {
            client.connections()
            error("erwartete Ausnahme")
        } catch (e: SmartcarException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!.contains("Formular"), e.message)
            assertTrue(e.message!!.contains("Basic-Auth"), e.message)
            assertTrue(e.message!!.contains("invalid_client"), e.message)
        }
        // Zwei Anmeldeversuche je Endpunkt, zwei Endpunkte (/connections, /vehicles)
        assertEquals(4, calls)
    }

    @Test
    fun basicAuthAlsZweiterVersuch() = runTest {
        val engine = MockEngine { req ->
            val auth = req.headers["Authorization"]
            when {
                req.url.host == "iam.smartcar.com" && auth == null -> respond("""{"error":"invalid_client"}""", HttpStatusCode.Unauthorized)
                req.url.host == "iam.smartcar.com" -> {
                    assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("x:y".toByteArray()), auth)
                    respond("""{"access_token":"tok-basic","expires_in":3600}""")
                }
                else -> respond("""{"data":[]}""")
            }
        }
        val client = SmartcarClient(HttpClient(engine), "x", "y")
        assertEquals("tok-basic", client.accessToken())
        val result = client.connections()
        assertTrue(result.connections.isEmpty())
        assertTrue(result.raw.contains("GET /connections") && result.raw.contains("GET /vehicles"), result.raw)
    }
}
