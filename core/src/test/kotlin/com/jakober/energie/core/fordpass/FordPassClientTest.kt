package com.jakober.energie.core.fordpass

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FordPassClientTest {

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_800_000_000)
    }

    private suspend fun bodyOf(req: io.ktor.client.request.HttpRequestData): String = when (val b = req.body) {
        is TextContent -> b.text
        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
        else -> ""
    }

    @Test
    fun loginUrlUndPkce() {
        val client = FordPassClient(HttpClient(MockEngine { respond("") }), null)
        val verifier = client.newCodeVerifier()
        assertEquals(128, verifier.length)
        val url = client.loginUrl(verifier)
        assertTrue(url.startsWith("https://login.ford.de/4566605f-43a7-400a-946e-89cc9fdb0bd7/B2C_1A_SignInSignUp_de-DE/oauth2/v2.0/authorize?redirect_uri=fordapp://userauthorized&response_type=code&max_age=3600&code_challenge="))
        assertTrue(url.contains("&code_challenge_method=S256&scope=%2009852200-05fd-41f6-8c21-d36d3497dc64%20openid&client_id=09852200-05fd-41f6-8c21-d36d3497dc64"))
        assertTrue(url.endsWith("&ui_locales=de-DE&language_code=de-DE&ford_application_id=667D773E-1BDC-4139-8AD0-2B16474E8DC7&country_code=DEU"))
    }

    @Test
    fun codeTauschFahrzeugeTelemetrieBefehle() = runTest {
        val saved = ArrayList<FordTokens>()
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val body = bodyOf(req)
            when {
                req.url.host == "login.ford.de" && path.endsWith("/oauth2/v2.0/token") -> {
                    assertTrue(body.contains("grant_type=authorization_code") && body.contains("code=abc123") && body.contains("code_verifier=ver"), body)
                    respond("""{"access_token":"idp-token","token_type":"Bearer"}""")
                }
                path.endsWith("/token/v2/cat-with-b2c-access-token") -> {
                    assertEquals("""{"idpToken":"idp-token"}""", body)
                    assertEquals("667D773E-1BDC-4139-8AD0-2B16474E8DC7", req.headers["Application-Id"])
                    respond("""{"access_token":"ford-access","refresh_token":"ford-refresh","expires_in":1800,"refresh_expires_in":15552000}""")
                }
                req.url.host == "accounts.autonomic.ai" -> {
                    assertTrue(body.contains("subject_token=ford-refresh") && body.contains("client_id=fordpass-prod"), body)
                    respond("""{"access_token":"auto-access","expires_in":3600}""")
                }
                path.endsWith("/user/garage") -> {
                    assertEquals("ford-access", req.headers["auth-token"])
                    assertEquals("DEU", req.headers["countryCode"])
                    respond("""[{"vin":"WF0XXXMACHE123456","profile":{"model":"Mustang Mach-E","year":"2021"},"nickName":"Mach-E"}]""")
                }
                path.endsWith("/telemetry/sources/fordpass/vehicles/WF0XXXMACHE123456") -> {
                    assertEquals("Bearer auto-access", req.headers["Authorization"])
                    respond("""{"metrics":{"xevBatteryStateOfCharge":{"value":62.5},"xevBatteryRange":{"value":268.0},"xevBatteryChargeDisplayStatus":{"value":"IN_PROGRESS"},"xevPlugChargerStatus":{"value":"CONNECTED"},"xevBatteryChargerVoltageOutput":{"value":346},"xevBatteryChargerCurrentOutput":{"value":5.4},"position":{"value":{"location":{"lat":48.137,"lon":11.575,"alt":520}}},"doorLockStatus":[{"value":"LOCKED","vehicleDoor":"UNSPECIFIED_FRONT"},{"value":"LOCKED","vehicleDoor":"ALL_DOORS"}]}}""")
                }
                path.endsWith("/command/vehicles/WF0XXXMACHE123456/commands") -> {
                    assertEquals("""{"tags":{},"type":"pauseGlobalChargeCommand","version":"1.0.1","wakeUp":true}""", body)
                    respond("""{"id":"cmd-1","status":"QUEUED"}""", HttpStatusCode.Accepted)
                }
                path.endsWith("/preferred-charge-times") -> {
                    assertEquals("WF0XXXMACHE123456", req.headers["vin"])
                    respond("""[{"vin":"WF0XXXMACHE123456","location":{"id":"loc-1","name":"Zuhause","type":"HOME","address":"Musterweg 1","latitude":48.1,"longitude":11.5},"chargeProfile":{"chargeMode":"CHARGE_NOW","schedules":[],"targetSoc":90}}]""")
                }
                path.endsWith("/preferred-charge-times/locations/loc-1") -> {
                    assertTrue(body.contains(""""targetSoc":60"""), body) // 65 -> 60 (unter 80 nur Zehner)
                    assertTrue(body.contains(""""chargeMode":"CHARGE_NOW"""") && body.contains(""""vin":"WF0XXXMACHE123456""""), body)
                    respond("""{"status":"ok"}""")
                }
                else -> respond("nicht gefunden: $path", HttpStatusCode.NotFound)
            }
        }
        val client = FordPassClient(HttpClient(engine), null, onTokens = { saved += it }, clock = FixedClock)
        val tokens = client.exchangeCode("fordapp://userauthorized/?code=abc123&state=x", "ver")
        assertEquals("ford-access", tokens.accessToken)
        assertEquals(1_800_000_000 + 1800 - 60, tokens.expiresAt)

        val vehicles = client.vehicles()
        assertEquals("WF0XXXMACHE123456", vehicles.single().vin)
        assertEquals("Mustang Mach-E", vehicles.single().model)

        val state = client.state("WF0XXXMACHE123456")
        assertEquals(62.5, state.socPercent)
        assertEquals(true, state.isCharging)
        assertEquals(true, state.isPluggedIn)
        assertEquals(346 * 5.4, state.chargePowerW!!, 1e-9)
        assertEquals(48.137, state.latitude)
        assertEquals(11.575, state.longitude)
        assertEquals("LOCKED", state.lockState)
        // Marienplatz -> Olympiapark rund 4,5 km
        val d = com.jakober.energie.core.smartcar.distanceMeters(48.137, 11.575, 48.175, 11.552)
        assertTrue(d > 4000 && d < 5000, "$d")
        assertTrue(saved.any { it.autoAccessToken == "auto-access" })

        assertTrue(client.pauseCharge("WF0XXXMACHE123456").accepted)

        val locations = client.chargeLocations("WF0XXXMACHE123456")
        assertEquals("Zuhause", locations.single().name)
        assertEquals(90, locations.single().targetSoc)
        assertTrue(client.setTargetSoc("WF0XXXMACHE123456", locations.single(), 65).accepted)
    }

    @Test
    fun abgelaufenesTokenWirdErneuert() = runTest {
        var refreshCalls = 0
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/cat-with-refresh-token") -> {
                    refreshCalls++
                    assertEquals("""{"refresh_token":"old-refresh"}""", bodyOf(req))
                    respond("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":1800}""")
                }
                req.url.encodedPath.endsWith("/user/garage") -> {
                    assertEquals("new-access", req.headers["auth-token"])
                    respond("[]")
                }
                else -> respond("?", HttpStatusCode.NotFound)
            }
        }
        val expired = FordTokens("old-access", "old-refresh", expiresAt = 1_700_000_000)
        val client = FordPassClient(HttpClient(engine), expired, clock = FixedClock)
        assertTrue(client.vehicles().isEmpty())
        assertEquals(1, refreshCalls)
    }
}
