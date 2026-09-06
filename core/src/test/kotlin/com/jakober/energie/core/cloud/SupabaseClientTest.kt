package com.jakober.energie.core.cloud

import com.jakober.energie.core.model.EnergySample
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SupabaseClientTest {
    private val t0 = Instant.parse("2026-09-07T10:00:00Z")

    @Test
    fun `anmelden, schreiben und lesen ueber PostgREST`() = runBlocking {
        val seen = ArrayList<String>()
        val engine = MockEngine { req ->
            seen += "${req.method.value} ${req.url.encodedPath}?${req.url.encodedQuery}"
            when {
                req.url.encodedPath == "/auth/v1/token" -> {
                    assertEquals("anon", req.headers["apikey"])
                    respond("""{"access_token":"tok","refresh_token":"ref","expires_in":3600,"expires_at":1800000000,"user":{"id":"u1"}}""")
                }
                req.url.encodedPath == "/rest/v1/samples" && req.method == HttpMethod.Post -> {
                    assertEquals("Bearer tok", req.headers["Authorization"])
                    assertTrue(req.headers["Prefer"]!!.contains("merge-duplicates"))
                    val body = String(req.body.toByteArray())
                    assertTrue(body.contains("\"at\":\"2026-09-07T10:00:00Z\""), body)
                    assertTrue(body.contains("\"productionW\":1200.0"), body)
                    respond("", HttpStatusCode.Created)
                }
                req.url.encodedPath == "/rest/v1/samples" -> {
                    assertEquals("gt.2026-09-07T09:00:00Z", req.url.parameters["at"])
                    respond("""[{"data":{"at":"2026-09-07T10:00:00Z","productionW":1200.0}},{"data":{"at":"2026-09-07T10:01:00Z"}}]""")
                }
                req.url.encodedPath == "/rest/v1/commands" && req.method == HttpMethod.Get ->
                    respond("""[{"id":7,"kind":"FORD_PAUSE","payload":{},"created_at":"2026-09-07T10:00:00Z"}]""")
                req.url.encodedPath == "/rest/v1/commands" && req.method == HttpMethod.Patch -> {
                    assertEquals("eq.7", req.url.parameters["id"]); respond("", HttpStatusCode.NoContent)
                }
                else -> respond("""{"message":"unbekannt"}""", HttpStatusCode.NotFound)
            }
        }
        val client = SupabaseClient(HttpClient(engine), "https://xyz.supabase.co/", "anon")
        val session = client.signIn("mat@example.org", "geheim")
        assertEquals("tok", session.accessToken)
        assertEquals("u1", session.userId)
        client.upsertSamples(session, listOf(EnergySample(at = t0, productionW = 1200.0)))
        val got = client.samplesAfter(session, t0 - kotlin.time.Duration.parse("1h"))
        assertEquals(2, got.size)
        assertEquals(1200.0, got[0].productionW)
        val open = client.openCommands(session)
        assertEquals("FORD_PAUSE", open.single().kind)
        client.finishCommand(session, 7, "ok")
        assertTrue(seen.any { it.startsWith("PATCH /rest/v1/commands") })
    }

    @Test
    fun `fehler tragen Status und Text`() = runBlocking {
        val engine = MockEngine { respond("""{"message":"JWT expired"}""", HttpStatusCode.Unauthorized) }
        val client = SupabaseClient(HttpClient(engine), "https://xyz.supabase.co", "anon")
        val e = assertFailsWith<CloudException> { client.latestSampleAt(CloudSession("x", "y", 0, "u")) }
        assertTrue(e.unauthorized)
        assertTrue(e.message!!.contains("JWT expired"))
    }
}
