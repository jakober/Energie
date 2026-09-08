package com.jakober.energie.core.plugs

import kotlinx.serialization.Serializable

/** Welche Firmware auf dem Messstecker laeuft; bestimmt die Abfrage. */
@Serializable
enum class PlugKind { SHELLY, TASMOTA, SHELLY_S0 }

/** Was fuer ein Verbraucher am Stecker haengt; bestimmt, welche Auswertung die App macht. */
@Serializable
enum class PlugType { OTHER, COOLING }

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
    /** Nur SHELLY_S0: Nummer des Eingangs am Shelly Plus Uni (0 = Eingang 1). */
    val input: Int = 0,
    /** Nur SHELLY_S0: Impulse je kWh laut Zaehler (tecget: 1000). */
    val impulsesPerKwh: Int = 1000,
    /** Nur SHELLY_S0: Zaehlerstand in Wh beim Anschluss, damit die App denselben Stand zeigt wie das Display. */
    val offsetWh: Double = 0.0,
    /** Kuehlgeraet: Kompressorzyklen werden ueberwacht. */
    val type: PlugType = PlugType.OTHER,
    /** Nennleistung laut Typenschild in W, optional. */
    val ratedPowerW: Double? = null,
    /** Jahresverbrauch laut Energielabel in kWh, optional. */
    val labelKwhPerYear: Double? = null,
) {
    val isCooling: Boolean get() = type == PlugType.COOLING
}

/** Momentaufnahme eines Steckers. `energyWh` ist ein Zaehler, der nur waechst (ausser nach einem Reset). */
@Serializable
data class PlugReading(
    val powerW: Double? = null,
    val energyWh: Double? = null,
    val on: Boolean? = null,
)

/** Was ein Shelly ueber sich sagt. */
data class ShellyInfo(val id: String, val name: String?, val model: String?)
