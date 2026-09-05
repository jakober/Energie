package com.jakober.energie.widget

import android.content.Context
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.core.smartcar.CarState
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Das Wenige, was das Homescreen-Widget zeigt. Die App schreibt es nach jeder
 * Messung in eine kleine Datei; das Widget liest nur diese Datei und macht
 * selbst keine Netzwerkzugriffe.
 */
@Serializable
data class WidgetState(
    val at: Instant,
    val batterySocPercent: Double? = null,
    val batteryPowerW: Double? = null,
    val productionW: Double? = null,
    val gridPowerW: Double? = null,
    val householdW: Double? = null,
    val carSocPercent: Double? = null,
    /** "lädt 2,1 kW", "steckt", "unterwegs", "nicht angeschlossen" oder null ohne Auto. */
    val carLabel: String? = null,
    val carCharging: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private fun file(context: Context) = File(context.filesDir, "widget.json")

        fun of(sample: EnergySample, car: CarState?, placeName: String?, carPowerLabel: (Double) -> String): WidgetState {
            val carLabel = when {
                car == null -> null
                car.isCharging == true -> "lädt" + (sample.carChargePowerW?.let { " ${carPowerLabel(it)}" } ?: "")
                car.isPluggedIn == true -> "steckt, pausiert"
                placeName != null && (car.distanceHomeM ?: 0.0) > 300 -> "bei $placeName"
                (car.distanceHomeM ?: 0.0) > 300 -> "unterwegs"
                car.isPluggedIn == false -> "nicht angeschlossen"
                else -> null
            }
            return WidgetState(
                at = sample.at,
                batterySocPercent = sample.batterySocPercent,
                batteryPowerW = sample.batteryPowerW,
                productionW = sample.productionW,
                gridPowerW = sample.gridPowerW,
                householdW = sample.householdW,
                carSocPercent = car?.socPercent,
                carLabel = carLabel,
                carCharging = car?.isCharging == true,
            )
        }

        fun save(context: Context, state: WidgetState) {
            runCatching { file(context).writeText(json.encodeToString(serializer(), state)) }
        }

        fun load(context: Context): WidgetState? =
            runCatching { file(context).takeIf { it.exists() }?.readText()?.let { json.decodeFromString(serializer(), it) } }.getOrNull()
    }
}
