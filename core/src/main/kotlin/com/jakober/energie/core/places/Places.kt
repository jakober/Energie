package com.jakober.energie.core.places

import com.jakober.energie.core.smartcar.distanceMeters
import kotlinx.serialization.Serializable

/** Ein vom Nutzer benannter Ort, etwa "Arbeit" oder "Oma". */
@Serializable
data class NamedPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** Zuordnung einer Position zu den gemerkten Orten. */
object Places {
    /** Innerhalb dieses Umkreises gilt das Auto als "an diesem Ort". */
    const val RADIUS_M = 200.0

    /** Der naechste gemerkte Ort im Umkreis, sonst null. */
    fun match(places: List<NamedPlace>, lat: Double, lon: Double, radiusM: Double = RADIUS_M): NamedPlace? =
        places.map { it to distanceMeters(lat, lon, it.latitude, it.longitude) }
            .filter { it.second <= radiusM }
            .minByOrNull { it.second }?.first

    /** Fuegt einen Ort hinzu; ein Ort gleichen Namens wird ersetzt. */
    fun upsert(places: List<NamedPlace>, place: NamedPlace): List<NamedPlace> =
        places.filterNot { it.name.equals(place.name.trim(), ignoreCase = true) } + place.copy(name = place.name.trim())

    fun remove(places: List<NamedPlace>, place: NamedPlace): List<NamedPlace> =
        places.filterNot { it.name == place.name && it.latitude == place.latitude && it.longitude == place.longitude }
}
