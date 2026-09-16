package com.jakober.energie.data

import com.jakober.energie.core.alerts.AlertSettings
import com.jakober.energie.core.alerts.AlertState
import com.jakober.energie.core.forecast.PvForecast
import com.jakober.energie.core.places.NamedPlace
import com.jakober.energie.core.rules.ChargeRules
import com.jakober.energie.core.senec.SenecConnectClient

/** Rolle dieses Geraets im Verbund mit Supabase. */
enum class CloudRole(val label: String) {
    /** Misst selbst, nutzt keine Cloud. */
    STANDALONE("Eigenständig"),
    /** Misst zu Hause und schreibt alles in die Cloud. */
    HUB("Zentrale"),
    /** Misst nicht, liest alles aus der Cloud. */
    VIEWER("Anzeige"),
}

/** Alles, was der Nutzer in der App eintraegt. */
data class Settings(
    val fritzHost: String = "fritz.box",
    val fritzUser: String = "",
    val fritzPassword: String = "",
    val senecKey: String = "",
    val senecBaseUrl: String = SenecConnectClient.DEFAULT_BASE_URL,
    /** Abfrageabstand im Vordergrund in Sekunden. */
    val pollSeconds: Int = 60,
    /** Strompreis in Euro je kWh, fuer die Kostenschaetzung. */
    val pricePerKwh: Double = 0.32,
    /** Einspeiseverguetung in Euro je kWh. */
    val feedInPerKwh: Double = 0.08,
    /** So viele Tage Verlauf bleiben gespeichert. */
    val keepDays: Int = 400,
    // Smartcar: Application ID oeffnet Connect, Client-ID und Secret holen das API-Token.
    val smartcarAppId: String = "",
    val smartcarClientId: String = "",
    val smartcarClientSecret: String = "",
    /** Nach dem Verbinden gemerkt, damit nicht jede Abfrage die Verbindungen listet. */
    val smartcarVehicleId: String = "",
    val smartcarUserId: String = "",
    /** Ladeleistung in W, wenn Smartcar keine liefert (Ladeziegel: 2200). */
    val carFallbackPowerW: Int = 2200,
    /** Aus dem Verbrauchssprung beim Ladestart gelernte Ladeleistung in W, 0 = noch nichts gelernt. */
    val carLearnedPowerW: Double = 0.0,
    /** Anschaffungskosten der Anlage in Euro fuer die Amortisation, 0 = nicht angegeben. */
    val systemCostEur: Double = 0.0,
    /** Preis fuer unterwegs geladenen Strom in Euro je kWh (Saeule), fuer die Fahrtkosten. */
    val carPublicPricePerKwh: Double = 0.59,
    /** Nutzbare Akkukapazitaet des Autos im Neuzustand in kWh, 0 = hoechster gemessener Wert als Bezug. */
    val carBatteryNominalKwh: Double = 0.0,
    /** FordPass (inoffiziell): Tokens als JSON, Fahrzeug und bevorzugter Ladeort. */
    val fordTokensJson: String = "",
    val fordVin: String = "",
    val fordLocationId: String = "",
    /** Koordinaten des Ladeorts Zuhause (aus FordPass), 0 = unbekannt. */
    val homeLat: Double = 0.0,
    val homeLon: Double = 0.0,
    /** Ladeautomatik. */
    val chargeRules: ChargeRules = ChargeRules(),
    /** Zeitpunkt des letzten Befehls der Automatik, Unix-Sekunden, 0 = nie. */
    val chargeLastCommandAt: Long = 0,
    /** Handschalter "jetzt voll laden" bis zum Abstecken. */
    val chargeOverride: Boolean = false,
    /** Letzte Entscheidungen der Automatik, neueste zuerst, eine je Zeile. */
    val chargeLog: String = "",
    /** Sicherung: Zielordner (SAF-Baum-URI), Passwort fuer die Zugangsdaten, letztes Ergebnis. */
    val backupTreeUri: String = "",
    val backupPassword: String = "",
    val backupLastAt: Long = 0,
    val backupLastResult: String = "",
    /** Benachrichtigungen und der Merkzustand der Hinweis-Engine. */
    val alerts: AlertSettings = AlertSettings(),
    val alertState: AlertState = AlertState(),
    /** Die Anzeige hat gemeldet, dass die Zentrale schweigt (damit es nur einmal kommt). */
    val hubSilentReported: Boolean = false,
    /** Vom Nutzer benannte Orte (Arbeit, Oma, ...), an denen das Auto erkannt wird. */
    val places: List<NamedPlace> = emptyList(),
    /** Messstecker im Heimnetz (Shelly, Tasmota). */
    val plugs: List<com.jakober.energie.core.plugs.PlugDevice> = emptyList(),
    // Cloud (Supabase): Zentrale misst zu Hause und schreibt, Anzeige liest unterwegs.
    val cloudUrl: String = "",
    val cloudAnonKey: String = "",
    val cloudEmail: String = "",
    val cloudPassword: String = "",
    val cloudRole: CloudRole = CloudRole.STANDALONE,
    /** Von der App gepflegt: Anmeldung, letzter hochgeladener bzw. geholter Messpunkt (Unix-Sekunden). */
    val cloudSessionJson: String = "",
    val cloudUploadedAt: Long = 0,
    val cloudSyncedAt: Long = 0,
    /** Wann die Anzeige zuletzt Einstellungen der Zentrale uebernommen hat (Unix-Sekunden). */
    val cloudSettingsAppliedAt: Long = 0,
    /** Firebase-Token dieses Geraets und das zuletzt in der Cloud eingetragene. */
    val pushToken: String = "",
    val pushRegisteredToken: String = "",
    /** PV-Prognose: Anlagenleistung in kWp (0 = aus dem Verlauf schaetzen), Neigung, Azimut (0 = Sued, -90 = Ost, 90 = West). */
    val pvPeakKw: Double = 0.0,
    val pvTiltDeg: Int = 30,
    val pvAzimuthDeg: Int = 0,
    /** Zweite Dachseite (Ost-West-Dach), 0 kWp = keine. */
    val pvPeakKw2: Double = 0.0,
    val pvTiltDeg2: Int = 30,
    val pvAzimuthDeg2: Int = 0,
    /** Gelernter Faktor echter Ertrag / Prognose, 1,0 = unkorrigiert. */
    val pvCalibration: Double = 1.0,
    /** Letzte Prognose von Open-Meteo, gespeichert damit nicht jede Messung abfragt. */
    val pvForecast: PvForecast? = null,
    /** Einstrahlungs-Prognose je Tag (ISO-Datum -> Wh/m²) der letzten Tage, fuer die Kalibrierung. */
    val pvForecastHistory: Map<String, Double> = emptyMap(),
) {
    val backupConfigured: Boolean get() = backupTreeUri.isNotBlank() && backupPassword.length >= 8
    val fordConnected: Boolean get() = fordTokensJson.isNotBlank() && fordVin.isNotBlank()
    val fritzConfigured: Boolean get() = fritzHost.isNotBlank() && fritzPassword.isNotBlank()
    val senecConfigured: Boolean get() = senecKey.isNotBlank()
    val smartcarConfigured: Boolean get() = smartcarClientId.isNotBlank() && smartcarClientSecret.isNotBlank()
    val carConnected: Boolean get() = smartcarConfigured && smartcarVehicleId.isNotBlank()
    val anythingConfigured: Boolean get() = fritzConfigured || senecConfigured || (cloudRole == CloudRole.VIEWER && cloudConfigured)
    val cloudConfigured: Boolean get() = cloudUrl.isNotBlank() && cloudAnonKey.isNotBlank() && cloudEmail.isNotBlank() && cloudPassword.isNotBlank()
    /** Ladeleistung, mit der gerechnet wird: gelernt, sonst der Annahmewert. */
    val carAssumedPowerW: Double get() = if (carLearnedPowerW > 0) carLearnedPowerW else carFallbackPowerW.toDouble()
}
