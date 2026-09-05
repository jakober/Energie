package com.jakober.energie.core.forecast

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Open-Meteo (open-meteo.com): freie Wettervorhersage ohne Schluessel.
 * Wir holen die stuendliche Einstrahlung auf die geneigte Modulflaeche
 * (`global_tilted_irradiance`, W/m²) fuer drei Tage und summieren sie je Tag.
 * Azimut wie bei Open-Meteo: 0 = Sueden, -90 = Osten, 90 = Westen.
 */
class OpenMeteoClient(
    private val http: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun forecast(lat: Double, lon: Double, tiltDeg: Int, azimuthDeg: Int, days: Int = 3): List<PvForecastDay> {
        val response = http.get("$baseUrl/v1/forecast") {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("hourly", "global_tilted_irradiance")
            parameter("daily", "weather_code,sunshine_duration")
            parameter("tilt", tiltDeg)
            parameter("azimuth", azimuthDeg)
            parameter("timezone", "auto")
            parameter("forecast_days", days)
        }
        val text = response.bodyAsText()
        if (response.status.value != 200) throw IllegalStateException("Open-Meteo antwortet mit ${response.status.value}: ${text.take(200)}")
        return parse(text)
    }

    /**
     * Zwei Dachseiten (etwa Ost und West): zwei Abfragen, je Tag zusammengefuehrt.
     * Die zweite Seite landet in `irradiance2WhPerM2`.
     */
    suspend fun forecastTwoSides(
        lat: Double, lon: Double,
        tilt1: Int, azimuth1: Int,
        tilt2: Int, azimuth2: Int,
        days: Int = 3,
    ): List<PvForecastDay> {
        val first = forecast(lat, lon, tilt1, azimuth1, days)
        val second = forecast(lat, lon, tilt2, azimuth2, days).associateBy { it.date }
        return first.map { d -> d.copy(irradiance2WhPerM2 = second[d.date]?.irradianceWhPerM2 ?: d.irradianceWhPerM2) }
    }

    fun parse(text: String): List<PvForecastDay> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val hourly = root["hourly"] as? JsonObject
        val times = (hourly?.get("time") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val gti = (hourly?.get("global_tilted_irradiance") as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 } ?: emptyList()
        val perDay = LinkedHashMap<LocalDate, Double>()
        for (i in times.indices) {
            val date = runCatching { LocalDate.parse(times[i].substringBefore('T')) }.getOrNull() ?: continue
            perDay[date] = (perDay[date] ?: 0.0) + (gti.getOrNull(i) ?: 0.0)
        }
        val daily = root["daily"] as? JsonObject
        val dailyDates = (daily?.get("time") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() } } ?: emptyList()
        val codes = (daily?.get("weather_code") as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
        val sunshine = (daily?.get("sunshine_duration") as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()
        return perDay.map { (date, wh) ->
            val idx = dailyDates.indexOf(date)
            PvForecastDay(
                date = date,
                irradianceWhPerM2 = wh,
                sunshineHours = if (idx >= 0) sunshine.getOrNull(idx)?.let { it / 3600.0 } else null,
                weatherCode = if (idx >= 0) codes.getOrNull(idx) else null,
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.open-meteo.com"
    }
}
