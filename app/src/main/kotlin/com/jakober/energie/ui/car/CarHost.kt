package com.jakober.energie.ui.car

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakober.energie.ui.EnergieViewModel
import com.jakober.energie.ui.statistics.CarContent
import com.jakober.energie.ui.statistics.StatisticsActions
import com.jakober.energie.ui.statistics.StatisticsData

/**
 * Android-Huelle der Auto-Seite. Sie benutzt dieselben Datenfluesse wie die Statistik,
 * nur ohne die Karten, die nicht zum Auto gehoeren.
 */
@Composable
fun CarScreen(vm: EnergieViewModel, contentPadding: PaddingValues) {
    val range by vm.range.collectAsStateWithLifecycle()
    val date by vm.selectedDate.collectAsStateWithLifecycle()
    val day by vm.dayStats.collectAsStateWithLifecycle()
    val rangeStats by vm.rangeStats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lifetime by vm.lifetime.collectAsStateWithLifecycle()
    val sessions by vm.chargeSessions.collectAsStateWithLifecycle()
    val driving by vm.driving.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        StatisticsActions(setRange = vm::setRange, shift = vm::shift, goToday = vm::goToday)
    }
    CarContent(
        StatisticsData(
            range = range, date = date, isToday = date == vm.todayDate(), todayDate = vm.todayDate(),
            day = day, rangeStats = rangeStats, settings = settings, lifetime = lifetime, sessions = sessions,
            currentMonth = null, storedDays = 0, driving = driving, daySamples = emptyList(),
            upgrade = null, upgradeKwh = 0.0, upgradeCost = 0.0, live = live, gridMonths = emptyList(),
        ),
        actions, contentPadding,
    )
}
