package com.jakober.energie.data

import com.jakober.energie.core.fritz.FritzDevice
import com.jakober.energie.core.fritz.SmartMeterReading
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.core.senec.SenecSystem
import com.jakober.energie.core.smartcar.CarState
import kotlinx.datetime.Instant

/** Der aktuelle Zustand, wie ihn die Oberflaeche zeigt. */
data class LiveState(
    val sample: EnergySample? = null,
    val senec: SenecSystem? = null,
    val meter: SmartMeterReading? = null,
    val fritzDevices: List<FritzDevice> = emptyList(),
    val senecError: String? = null,
    val fritzError: String? = null,
    val car: CarState? = null,
    val carError: String? = null,
    val lastUpdate: Instant? = null,
    val refreshing: Boolean = false,
    /** Letzte Rohantwort von SENEC, fuer die Ansicht in den Einstellungen. */
    val senecRaw: String? = null,
    /** Letzte Entscheidung der Ladeautomatik in Worten. */
    val automationStatus: String? = null,
    /** Aus dem Verlauf geschaetzte Anlagenleistung in kWp, wenn der Nutzer keine eingetragen hat. */
    val pvPeakEstimateKw: Double? = null,
    /** Letzter Fehler beim Abruf der PV-Prognose. */
    val forecastError: String? = null,
    /** Rohantwort von Open-Meteo, fuer die Fehlersuche. */
    val forecastRaw: String? = null,
    /** Fehler je Messstecker (Kennung -> Text) aus der letzten Abfrage; leer = alle erreichbar. */
    val plugErrors: Map<String, String> = emptyMap(),
    /** Anzeige: wann sich die Zentrale zuletzt in der Cloud gemeldet hat. */
    val hubSeenAt: Instant? = null,
    /** Letzter Fehler bzw. letzte Meldung des Cloud-Abgleichs. */
    val cloudError: String? = null,
    val cloudInfo: String? = null,
)
