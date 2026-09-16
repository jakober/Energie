package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/**
 * Woher der Strom im Fahrakku stammt, in Wh. Der Akku ist ein Tank: Laden
 * fuellt einen Topf, Fahren entnimmt anteilig aus allen. So laesst sich jede
 * Fahrt in Sonnen-, Netz- und Fremdstrom aufteilen.
 */
@Serializable
data class BatteryMix(
    val solarWh: Double = 0.0,
    val gridWh: Double = 0.0,
    /** Unterwegs geladen (Saeule, Arbeit), Preis unbekannt, Nutzer traegt einen ein. */
    val publicWh: Double = 0.0,
    /** War schon im Akku, bevor die App mitzaehlte. */
    val unknownWh: Double = 0.0,
) {
    val totalWh: Double get() = solarWh + gridWh + publicWh + unknownWh

    /** Entnimmt `wh` anteilig; was der Tank nicht deckt, gilt als unbekannt. */
    fun draw(wh: Double): Pair<BatteryMix, BatteryMix> {
        val total = totalWh
        if (total <= 0) return this to BatteryMix(unknownWh = wh)
        val f = (wh / total).coerceAtMost(1.0)
        val taken = BatteryMix(solarWh * f, gridWh * f, publicWh * f, unknownWh * f)
        val missing = max(0.0, wh - total)
        return BatteryMix(solarWh - taken.solarWh, gridWh - taken.gridWh, publicWh - taken.publicWh, unknownWh - taken.unknownWh) to
            taken.copy(unknownWh = taken.unknownWh + missing)
    }

    operator fun plus(o: BatteryMix) = BatteryMix(solarWh + o.solarWh, gridWh + o.gridWh, publicWh + o.publicWh, unknownWh + o.unknownWh)

    /** Passt den Tank an den gemessenen Inhalt an: zu viel wird anteilig gekuerzt, zu wenig gilt als unbekannt. */
    fun fitTo(measuredWh: Double): BatteryMix {
        val total = totalWh
        return when {
            total > measuredWh + TOLERANCE_WH -> if (measuredWh <= 0) BatteryMix() else (measuredWh / total).let { f -> BatteryMix(solarWh * f, gridWh * f, publicWh * f, unknownWh * f) }
            total < measuredWh - TOLERANCE_WH -> copy(unknownWh = unknownWh + (measuredWh - total))
            else -> this
        }
    }

    companion object {
        const val TOLERANCE_WH = 500.0
    }
}

/** Ein Tag Fahren: Strecke, verbrauchte Energie und deren Herkunft. */
data class DriveDay(
    val date: LocalDate,
    val startKm: Double?,
    val endKm: Double?,
    val drivenKm: Double,
    /** Aus dem Akku entnommen (Fahren, Vorklimatisieren, Standverbrauch). */
    val used: BatteryMix,
    /** Zu Hause nachgeladen. */
    val chargedHomeWh: Double,
    /** Unterwegs nachgeladen. */
    val chargedPublicWh: Double,
) {
    val usedWh: Double get() = used.totalWh
    val solarShare: Double? get() = if (usedWh > 0) (used.solarWh / usedWh).coerceIn(0.0, 1.0) else null
    val unknownShare: Double? get() = if (usedWh > 0) (used.unknownWh / usedWh).coerceIn(0.0, 1.0) else null
    /** kWh je 100 km, erst ab einem Kilometer sinnvoll. */
    val kwhPer100Km: Double? get() = if (drivenKm >= 1.0) usedWh / 1000.0 / drivenKm * 100.0 else null
    /** Bezahlter Strom: Netz zum Haustarif, unterwegs zum Fremdpreis. */
    fun costEur(pricePerKwh: Double, publicPricePerKwh: Double): Double = used.gridWh / 1000.0 * pricePerKwh + used.publicWh / 1000.0 * publicPricePerKwh
    /** Wert des Sonnenstroms: die entgangene Einspeisung. */
    fun solarValueEur(feedInPerKwh: Double): Double = used.solarWh / 1000.0 * feedInPerKwh
    fun costPer100Km(pricePerKwh: Double, publicPricePerKwh: Double): Double? =
        if (drivenKm >= 1.0) costEur(pricePerKwh, publicPricePerKwh) / drivenKm * 100.0 else null

    companion object {
        fun sum(days: List<DriveDay>): DriveDay? {
            if (days.isEmpty()) return null
            val sorted = days.sortedBy { it.date }
            return DriveDay(
                date = sorted.first().date,
                startKm = sorted.firstOrNull { it.startKm != null }?.startKm,
                endKm = sorted.lastOrNull { it.endKm != null }?.endKm,
                drivenKm = days.sumOf { it.drivenKm },
                used = days.fold(BatteryMix()) { acc, d -> acc + d.used },
                chargedHomeWh = days.sumOf { it.chargedHomeWh },
                chargedPublicWh = days.sumOf { it.chargedPublicWh },
            )
        }
    }
}

/** Zwischenstand, damit Tage nacheinander verarbeitet werden koennen. */
@Serializable
data class DrivingState(
    val mix: BatteryMix = BatteryMix(),
    val last: EnergySample? = null,
    /** Kleine Energieaenderungen sammeln, bis sie ueber dem Rauschen liegen. */
    val pendingWh: Double = 0.0,
)

/**
 * Rekonstruiert Fahrtage aus Kilometerstand und Akku-Energie des Autos.
 * Zwischen zwei Messpunkten: mehr Kilometer = gefahren; weniger Energie =
 * verbraucht (aus dem Tank entnommen); mehr Energie = geladen, zu Hause mit dem
 * Netz-/Sonnenmix des Moments, sonst als Fremdstrom.
 */
object Driving {
    /** Unter dieser Aenderung ist es Messrauschen der Telemetrie. */
    const val MIN_STEP_WH = 100.0
    /** Ein Sprung darueber ist ein Fehler der Quelle, nicht gefahren. */
    const val MAX_KM_STEP = 2000.0

    private fun EnergySample.energyWh(): Double? = carEnergyKwh?.let { it * 1000.0 }
    private fun EnergySample.charging() = (carChargePowerW ?: 0.0) > 0

    fun of(samples: List<EnergySample>, state: DrivingState = DrivingState(), zone: TimeZone = TimeZone.currentSystemDefault()): Pair<List<DriveDay>, DrivingState> {
        val days = LinkedHashMap<LocalDate, Acc>()
        var mix = state.mix
        var last = state.last
        var pending = state.pendingWh
        fun acc(date: LocalDate) = days.getOrPut(date) { Acc(date) }

        for (b in samples.sortedBy { it.at }) {
            if (b.carOdometerKm == null && b.energyWh() == null) continue
            val date = b.at.toLocalDateTime(zone).date
            val a = last
            val day = acc(date)
            b.carOdometerKm?.let { odo ->
                if (day.startKm == null) day.startKm = a?.carOdometerKm ?: odo
                day.endKm = odo
                val prev = a?.carOdometerKm
                if (prev != null) {
                    val d = odo - prev
                    if (d > 0 && d < MAX_KM_STEP) day.drivenKm += d
                }
            }
            val eb = b.energyWh()
            val ea = a?.energyWh()
            if (eb != null) {
                if (ea == null) {
                    // Erster bekannter Inhalt: was drin ist, ist unbekannter Herkunft.
                    if (a == null || a.energyWh() == null) mix = mix.fitTo(eb)
                } else {
                    pending += eb - ea
                    if (abs(pending) >= MIN_STEP_WH) {
                        if (pending > 0) {
                            val home = a.charging() || b.charging()
                            if (home) {
                                val gridShare = gridShare(a, b)
                                val grid = pending * gridShare
                                mix = mix + BatteryMix(solarWh = pending - grid, gridWh = grid)
                                day.chargedHomeWh += pending
                            } else {
                                mix = mix + BatteryMix(publicWh = pending)
                                day.chargedPublicWh += pending
                            }
                        } else {
                            val (rest, taken) = mix.draw(-pending)
                            mix = rest
                            day.used = day.used + taken
                        }
                        pending = 0.0
                        mix = mix.fitTo(eb)
                    }
                }
            }
            last = b
        }
        return days.values.map { it.toDay() } to DrivingState(mix, last, pending)
    }

    /** Netzanteil des Hausverbrauchs zwischen zwei Messpunkten, wie in [EnergyTotals]. */
    private fun gridShare(a: EnergySample, b: EnergySample): Double {
        fun mean(x: Double?, y: Double?): Double? = if (x != null && y != null) (x + y) / 2 else x ?: y
        val cons = mean(a.consumptionW, b.consumptionW) ?: return 0.0
        val grid = mean(a.gridPowerW, b.gridPowerW)?.coerceAtLeast(0.0) ?: return 0.0
        return if (cons > 0) (grid / cons).coerceIn(0.0, 1.0) else 0.0
    }

    private class Acc(val date: LocalDate) {
        var startKm: Double? = null
        var endKm: Double? = null
        var drivenKm = 0.0
        var used = BatteryMix()
        var chargedHomeWh = 0.0
        var chargedPublicWh = 0.0
        fun toDay() = DriveDay(date, startKm, endKm, drivenKm, used, chargedHomeWh, chargedPublicWh)
    }
}
