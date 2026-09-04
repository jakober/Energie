package com.jakober.energie.core.senec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Antwort von `GET /systems/device-data/general` der SENEC.Connect-API.
 * Die API liefert immer alle Anlagen des Kontos als Liste, auch bei nur einer.
 *
 * Alle Felder sind optional, weil SENEC die Schnittstelle noch ausbaut und
 * ein fehlendes Feld die App nicht zum Absturz bringen soll.
 */
@Serializable
data class SenecSystem(
    val battery: SenecBattery? = null,
    val bessNameplate: SenecNameplate? = null,
    val meter: SenecMeter? = null,
    val evse: List<SenecEvse> = emptyList(),
) {
    val systemId: String get() = bessNameplate?.systemId ?: bessNameplate?.serialNumber ?: "unbekannt"
}

@Serializable
data class SenecBattery(
    val state: String? = null,
    /** Ladezustand in Prozent. */
    @SerialName("state_of_charge") val stateOfCharge: Double? = null,
    /** Leistung in W: positiv laedt, negativ entlaedt. */
    val power: Double? = null,
    val voltage: Double? = null,
    val current: Double? = null,
)

@Serializable
data class SenecNameplate(
    val manufacturer: String? = null,
    val model: String? = null,
    @SerialName("serial_number") val serialNumber: String? = null,
    @SerialName("system_id") val systemId: String? = null,
    /** Nennkapazitaet in Wh. */
    @SerialName("design_capacity") val designCapacityWh: Double? = null,
    @SerialName("active_charge_power") val activeChargePowerW: Double? = null,
    @SerialName("active_discharge_power") val activeDischargePowerW: Double? = null,
)

@Serializable
data class SenecMeter(
    /** Netzleistung in W: positiv Bezug, negativ Einspeisung. */
    @SerialName("grid_power") val gridPower: Double? = null,
    /** Hausverbrauch in W. */
    val consumption: Double? = null,
    /** PV-Erzeugung in W. */
    val production: Double? = null,
)

@Serializable
data class SenecEvse(
    val id: String? = null,
    @SerialName("ev_connected") val evConnected: Boolean? = null,
    @SerialName("ev_charging") val evCharging: Boolean? = null,
    @SerialName("charging_power") val chargingPower: Double? = null,
)
