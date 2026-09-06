package com.jakober.energie.core.plugs

import kotlinx.serialization.Serializable

/** Welche Firmware auf dem Messstecker laeuft; bestimmt die Abfrage. */
@Serializable
enum class PlugKind { SHELLY, TASMOTA }

/** Ein Messstecker, wie der Nutzer ihn eingerichtet hat. */
@Serializable
data class PlugDevice(
    /** Stabile Kennung, bei Shelly die Geraete-ID (etwa shellyplugmg3-a1b2c3), sonst die Adresse. */
    val id: String,
    /** Name des Verbrauchers, den der Nutzer vergibt: "Kuehlschrank". */
    val name: String,
    /** IP-Adresse oder Hostname im Heimnetz. */
    val host: String,
    val kind: PlugKind = PlugKind.SHELLY,
)

/** Momentaufnahme eines Steckers. `energyWh` ist ein Zaehler, der nur waechst (ausser nach einem Reset). */
@Serializable
data class PlugReading(
    val powerW: Double? = null,
    val energyWh: Double? = null,
    val on: Boolean? = null,
)

/** Was ein Shelly ueber sich sagt. */
data class ShellyInfo(val id: String, val name: String?, val model: String?)
