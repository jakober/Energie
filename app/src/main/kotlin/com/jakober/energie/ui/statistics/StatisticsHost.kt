package com.jakober.energie.ui.statistics

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakober.energie.ui.EnergieViewModel

/*
 * Die Datei heisst bewusst nicht wie die gemeinsame Oberflaeche: gleicher Dateiname im
 * gleichen Paket ergaebe zwei Klassen namens ...ScreenKt, und zur Laufzeit gewaenne die
 * falsche (NoSuchMethodError).
 */

/** Android-Huelle: sammelt die Datenfluesse des ViewModels und reicht sie an die gemeinsame Statistik. */
@Composable
fun StatisticsScreen(vm: EnergieViewModel, contentPadding: PaddingValues) {
    val range by vm.range.collectAsStateWithLifecycle()
    val date by vm.selectedDate.collectAsStateWithLifecycle()
    val day by vm.dayStats.collectAsStateWithLifecycle()
    val rangeStats by vm.rangeStats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lifetime by vm.lifetime.collectAsStateWithLifecycle()
    val sessions by vm.chargeSessions.collectAsStateWithLifecycle()
    val currentMonth by vm.currentMonth.collectAsStateWithLifecycle()
    val storedDays by vm.storedDays.collectAsStateWithLifecycle()
    val driving by vm.driving.collectAsStateWithLifecycle()
    val daySamples by vm.daySamples.collectAsStateWithLifecycle()
    val upgrade by vm.storageUpgrade.collectAsStateWithLifecycle()
    val upgradeKwh by vm.upgradeModuleKwh.collectAsStateWithLifecycle()
    val upgradeCost by vm.upgradeCostEur.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val gridMonths by vm.gridMonths.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        StatisticsActions(
            setRange = vm::setRange,
            shift = vm::shift,
            goToday = vm::goToday,
            setUpgradeModuleKwh = { vm.upgradeModuleKwh.value = it },
            setUpgradeCost = { vm.upgradeCostEur.value = it },
        )
    }
    StatisticsContent(
        StatisticsData(
            range = range, date = date, isToday = date == vm.todayDate(), todayDate = vm.todayDate(),
            day = day, rangeStats = rangeStats, settings = settings, lifetime = lifetime, sessions = sessions,
            currentMonth = currentMonth, storedDays = storedDays, driving = driving, daySamples = daySamples,
            upgrade = upgrade, upgradeKwh = upgradeKwh, upgradeCost = upgradeCost, live = live, gridMonths = gridMonths,
        ),
        actions, contentPadding,
    )
}
