package com.jakober.energie.viewer

import com.jakober.energie.core.alerts.AlertSettings
import com.jakober.energie.core.places.NamedPlace
import com.jakober.energie.core.plugs.PlugDevice
import com.jakober.energie.core.rules.ChargeRules
import com.jakober.energie.data.Settings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Einstellungen der Zentrale aus der Cloud (flache Textwerte, wie plainForBackup sie
 * schreibt) auf ein [Settings]-Objekt anwenden. Nur die Werte, die die Anzeige braucht;
 * Geheimnisse liegen ohnehin nicht in der Cloud.
 */
object SettingsPlain {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun apply(base: Settings, plain: Map<String, String>): Settings {
        fun str(k: String) = plain[k]
        fun dbl(k: String) = plain[k]?.toDoubleOrNull()
        fun int(k: String) = plain[k]?.toIntOrNull()
        fun lng(k: String) = plain[k]?.toLongOrNull()
        inline fun <T> parse(k: String, block: (String) -> T): T? = plain[k]?.let { runCatching { block(it) }.getOrNull() }
        return base.copy(
            pollSeconds = int("pollSeconds") ?: base.pollSeconds,
            pricePerKwh = dbl("pricePerKwh") ?: base.pricePerKwh,
            feedInPerKwh = dbl("feedInPerKwh") ?: base.feedInPerKwh,
            keepDays = int("keepDays") ?: base.keepDays,
            smartcarVehicleId = str("smartcarVehicleId") ?: base.smartcarVehicleId,
            carFallbackPowerW = int("carFallbackPowerW") ?: base.carFallbackPowerW,
            carLearnedPowerW = dbl("carLearnedPowerW") ?: base.carLearnedPowerW,
            systemCostEur = dbl("systemCostEur") ?: base.systemCostEur,
            carPublicPricePerKwh = dbl("carPublicPricePerKwh") ?: base.carPublicPricePerKwh,
            carBatteryNominalKwh = dbl("carBatteryNominalKwh") ?: base.carBatteryNominalKwh,
            fordVin = str("fordVin") ?: base.fordVin,
            fordLocationId = str("fordLocationId") ?: base.fordLocationId,
            homeLat = dbl("homeLat") ?: base.homeLat,
            homeLon = dbl("homeLon") ?: base.homeLon,
            chargeRules = parse("chargeRules") { json.decodeFromString(ChargeRules.serializer(), it) } ?: base.chargeRules,
            chargeLastCommandAt = lng("chargeLastCommandAt") ?: base.chargeLastCommandAt,
            chargeLog = str("chargeLog") ?: base.chargeLog,
            alerts = parse("alerts") { json.decodeFromString(AlertSettings.serializer(), it) } ?: base.alerts,
            places = parse("places") { json.decodeFromString(ListSerializer(NamedPlace.serializer()), it) } ?: base.places,
            plugs = parse("plugs") { json.decodeFromString(ListSerializer(PlugDevice.serializer()), it) } ?: base.plugs,
            pvPeakKw = dbl("pvPeakKw") ?: base.pvPeakKw,
            pvTiltDeg = int("pvTiltDeg") ?: base.pvTiltDeg,
            pvAzimuthDeg = int("pvAzimuthDeg") ?: base.pvAzimuthDeg,
            pvCalibration = dbl("pvCalibration") ?: base.pvCalibration,
            pvPeakKw2 = dbl("pvPeakKw2") ?: base.pvPeakKw2,
            pvTiltDeg2 = int("pvTiltDeg2") ?: base.pvTiltDeg2,
            pvAzimuthDeg2 = int("pvAzimuthDeg2") ?: base.pvAzimuthDeg2,
        )
    }

    fun rulesJson(rules: ChargeRules): String = json.encodeToString(ChargeRules.serializer(), rules)
    fun placesJson(places: List<NamedPlace>): String = json.encodeToString(ListSerializer(NamedPlace.serializer()), places)
}
