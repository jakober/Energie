package com.jakober.energie.core.senec

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SenecConnectClientTest {

    private val body = """
[
  {
    "battery": {"state": "CHARGING", "state_of_charge": 87.5, "power": 1234.0, "voltage": 402.1, "current": 3.07},
    "bessNameplate": {"manufacturer": "SENEC GmbH", "model": "SENEC.Home 4 hybrid / 11.8", "serial_number": "S4H1-0001", "system_id": "S4H1-XYZ", "design_capacity": 11800, "active_charge_power": 5000, "active_discharge_power": 5000},
    "meter": {"grid_power": -850.0, "consumption": 410.0, "production": 2494.0},
    "evse": [{"id": "wb1", "ev_connected": true, "ev_charging": false, "charging_power": 0}],
    "neuesFeld": {"x": 1}
  }
]
""".trim()

    @Test
    fun schluesselImHeaderUndParsen() = runTest {
        val engine = MockEngine { req ->
            assertEquals("https://apim-eds-gwc-prod.azure-api.net/senec-connect/v1/systems/device-data/general", req.url.toString())
            assertEquals("mein-key", req.headers[SenecConnectClient.KEY_HEADER])
            respond(body)
        }
        val systems = SenecConnectClient(HttpClient(engine), "mein-key").systems()
        val s = systems.single()
        assertEquals("S4H1-XYZ", s.systemId)
        assertEquals(87.5, s.battery!!.stateOfCharge)
        assertEquals(-850.0, s.meter!!.gridPower)
        assertEquals(2494.0, s.meter!!.production)
        assertEquals(true, s.evse.single().evConnected)
    }

    @Test
    fun einzelnesObjektWirdAuchVerstanden() {
        val client = SenecConnectClient(HttpClient(MockEngine { respond("") }), "k")
        val systems = client.parse("""{"meter": {"consumption": 5}}""")
        assertEquals(5.0, systems.single().meter!!.consumption)
        assertEquals("unbekannt", systems.single().systemId)
    }

    @Test
    fun falscherSchluessel() = runTest {
        val engine = MockEngine { respond("""{"statusCode":401}""", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<SenecConnectException> { SenecConnectClient(HttpClient(engine), "k").systems() }
        assertEquals(401, e.status)
    }
}
