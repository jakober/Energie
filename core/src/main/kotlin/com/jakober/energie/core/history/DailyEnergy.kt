package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlin.math.max

/**
 * Energiemengen eines Zeitraums, aus Momentaufnahmen aufintegriert. Aus zwei
 * Leistungswerten im Abstand dt ergibt sich Energie = Mittelwert * dt.
 * Grob, aber ehrlich: Die SENEC-API liefert keine Zaehlerstaende, nur die
 * Netzwerte des Lesekopfs sind echte Zaehlerstaende.
 */
data class EnergyTotals(
    val productionWh: Double,
    val consumptionWh: Double,
    val gridImportWh: Double,
    val gridExportWh: Double,
    val batteryChargeWh: Double,
    val batteryDischargeWh: Double,
    /** Ins Auto geladen, aus der Ladeleistung integriert. */
    val carChargeWh: Double = 0.0,
    /** Aus Zaehlerstaenden: Differenz erster zu letzter Wert, falls vorhanden. */
    val meterImportWh: Long?,
    val meterExportWh: Long?,
) {
    val selfConsumptionWh: Double get() = max(0.0, productionWh - gridExportWh)
    val selfSufficiency: Double? get() = if (consumptionWh > 0) ((consumptionWh - gridImportWh) / consumptionWh).coerceIn(0.0, 1.0) else null
    val selfConsumptionShare: Double? get() = if (productionWh > 0) (selfConsumptionWh / productionWh).coerceIn(0.0, 1.0) else null

    companion object {
        /**
         * Luecken groesser als das werden nicht integriert (App war aus, kein Netz).
         * Im Hintergrund fragt die App alle 15 Minuten ab; Android darf das um
         * einiges verzoegern, daher grosszuegig bemessen.
         */
        const val MAX_GAP_SECONDS = 90 * 60

        fun of(samples: List<EnergySample>): EnergyTotals {
            var prod = 0.0; var cons = 0.0; var imp = 0.0; var exp = 0.0; var chg = 0.0; var dis = 0.0; var car = 0.0
            val sorted = samples.sortedBy { it.at }
            for (i in 1 until sorted.size) {
                val a = sorted[i - 1]; val b = sorted[i]
                val dt = (b.at - a.at).inWholeSeconds
                if (dt <= 0 || dt > MAX_GAP_SECONDS) continue
                val h = dt / 3600.0
                fun mean(x: Double?, y: Double?): Double? = if (x != null && y != null) (x + y) / 2 else x ?: y
                mean(a.productionW, b.productionW)?.let { prod += it * h }
                mean(a.consumptionW, b.consumptionW)?.let { cons += it * h }
                mean(a.senecGridPowerW ?: a.meterGridPowerW, b.senecGridPowerW ?: b.meterGridPowerW)?.let {
                    if (it >= 0) imp += it * h else exp += -it * h
                }
                mean(a.batteryPowerW, b.batteryPowerW)?.let {
                    if (it >= 0) chg += it * h else dis += -it * h
                }
                mean(a.carChargePowerW, b.carChargePowerW)?.let { car += it * h }
            }
            val firstImp = sorted.firstOrNull { it.meterImportWh != null }?.meterImportWh
            val lastImp = sorted.lastOrNull { it.meterImportWh != null }?.meterImportWh
            val firstExp = sorted.firstOrNull { it.meterExportWh != null }?.meterExportWh
            val lastExp = sorted.lastOrNull { it.meterExportWh != null }?.meterExportWh
            return EnergyTotals(
                productionWh = prod, consumptionWh = cons, gridImportWh = imp, gridExportWh = exp,
                batteryChargeWh = chg, batteryDischargeWh = dis, carChargeWh = car,
                meterImportWh = if (firstImp != null && lastImp != null) lastImp - firstImp else null,
                meterExportWh = if (firstExp != null && lastExp != null) lastExp - firstExp else null,
            )
        }
    }
}
