package com.jakober.energie.core.fordpass

import com.jakober.energie.core.model.CarExtras
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Alle Zugangsdaten nach dem Login; die App speichert sie als JSON. */
@Serializable
data class FordTokens(
    val accessToken: String,
    val refreshToken: String,
    /** Ablauf des Ford-Tokens, Unix-Sekunden. */
    val expiresAt: Long,
    /** Token fuer die Autonomic-Dienste (Telemetrie, Befehle), optional. */
    val autoAccessToken: String? = null,
    val autoExpiresAt: Long = 0,
)

/** Ein Fahrzeug aus der FordPass-Garage. */
data class FordVehicle(val vin: String, val model: String?, val year: String?, val nickname: String?)

/**
 * Zustand des Autos aus der Autonomic-Telemetrie. Ford liefert Rohstrings wie
 * `IN_PROGRESS`, `PAUSED`, `CONNECTED`; sie stehen unveraendert in den Feldern,
 * die Bool-Ableitungen sind fuer die Oberflaeche.
 */
data class FordCarState(
    val at: Instant,
    val vin: String,
    val socPercent: Double?,
    val rangeKm: Double?,
    /** xevBatteryChargeDisplayStatus: NOT_READY, SCHEDULED, PAUSED, IN_PROGRESS, STOPPED, FAULT ... */
    val chargeStatus: String?,
    /** xevPlugChargerStatus: CONNECTED, DISCONNECTED, CHARGING, CHARGINGAC ... */
    val plugStatus: String?,
    val chargerVoltage: Double?,
    val chargerCurrent: Double?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** LOCKED, PARTLY_LOCKED, UNLOCKED oder null, wenn Ford nichts liefert. */
    val lockState: String? = null,
    /** Reifendruck, Kilometerstand, 12-V-Batterie, Tueren, Fenster ... */
    val extra: CarExtras? = null,
    val raw: String,
) {
    val isCharging: Boolean? get() = chargeStatus?.let { it.uppercase() == "IN_PROGRESS" } ?: plugStatus?.let { it.uppercase().startsWith("CHARGING") }
    val isPluggedIn: Boolean? get() = plugStatus?.let { it.uppercase() != "DISCONNECTED" }
    val isPaused: Boolean get() = chargeStatus?.uppercase() == "PAUSED"
    /** Leistung an der Batterie in W, falls Ford Spannung und Strom liefert. */
    val chargePowerW: Double? get() = if (chargerVoltage != null && chargerCurrent != null && chargerVoltage > 0 && chargerCurrent > 0) chargerVoltage * chargerCurrent else null
}

/** Ein Ladeort mit Ladeprofil, wie Ford ihn unter "preferred charge times" fuehrt. */
data class FordChargeLocation(
    val id: String,
    val name: String?,
    val type: String?,
    val targetSoc: Int?,
    val chargeMode: String?,
    /** Das komplette Objekt, das beim Setzen unveraendert zurueckgeschickt werden muss. */
    val raw: kotlinx.serialization.json.JsonObject,
)

data class FordCommandResult(val status: Int, val body: String) {
    val accepted: Boolean get() = status in 200..205
}
