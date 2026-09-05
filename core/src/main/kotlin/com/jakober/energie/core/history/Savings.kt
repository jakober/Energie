package com.jakober.energie.core.history

/** Was die Anlage in einem Zeitraum in Euro gebracht hat. */
data class Savings(
    /** Eigenverbrauch, der sonst aus dem Netz gekauft worden waere. */
    val selfConsumptionSavedEur: Double,
    val feedInRevenueEur: Double,
    val gridCostEur: Double,
) {
    /** Ersparnis plus Verguetung: der Nutzen der Anlage. */
    val benefitEur: Double get() = selfConsumptionSavedEur + feedInRevenueEur
    /** Was am Ende auf der Rechnung steht: Bezug minus Verguetung (positiv = zahlen). */
    val billEur: Double get() = gridCostEur - feedInRevenueEur

    companion object {
        fun of(t: EnergyTotals, pricePerKwh: Double, feedInPerKwh: Double): Savings = Savings(
            selfConsumptionSavedEur = t.selfConsumptionWh / 1000 * pricePerKwh,
            feedInRevenueEur = t.gridExportWh / 1000 * feedInPerKwh,
            gridCostEur = t.gridImportWh / 1000 * pricePerKwh,
        )

        /** Anteil der Anlagenkosten, der schon verdient ist (0..1+), null ohne Kosten. */
        fun amortisationShare(benefitEur: Double, systemCostEur: Double?): Double? =
            systemCostEur?.takeIf { it > 0 }?.let { (benefitEur / it).coerceAtLeast(0.0) }

        /** Voraussichtliche Jahre bis zur Amortisation bei gleichem Tempo, null ohne Daten. */
        fun yearsToAmortise(benefitEur: Double, days: Int, systemCostEur: Double?): Double? {
            val cost = systemCostEur?.takeIf { it > 0 } ?: return null
            if (days <= 0 || benefitEur <= 0) return null
            val perYear = benefitEur / days * 365.25
            return cost / perYear
        }
    }
}

/** Hochrechnung des laufenden Monats aus den Tagen, die schon Daten haben. */
data class MonthForecast(
    val daysWithData: Int,
    val daysInMonth: Int,
    val gridCostEur: Double,
    val feedInRevenueEur: Double,
    val consumptionWh: Double,
    val productionWh: Double,
    val gridImportWh: Double,
) {
    val billEur: Double get() = gridCostEur - feedInRevenueEur

    companion object {
        fun of(soFar: EnergyTotals, daysWithData: Int, daysInMonth: Int, pricePerKwh: Double, feedInPerKwh: Double): MonthForecast? {
            if (daysWithData <= 0 || daysInMonth <= 0) return null
            val f = daysInMonth.toDouble() / daysWithData
            return MonthForecast(
                daysWithData = daysWithData,
                daysInMonth = daysInMonth,
                gridCostEur = soFar.gridImportWh * f / 1000 * pricePerKwh,
                feedInRevenueEur = soFar.gridExportWh * f / 1000 * feedInPerKwh,
                consumptionWh = soFar.consumptionWh * f,
                productionWh = soFar.productionWh * f,
                gridImportWh = soFar.gridImportWh * f,
            )
        }
    }
}
