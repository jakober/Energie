package com.jakober.energie.ui.dashboard

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jakober.energie.ui.AndroidPlatformHooks
import com.jakober.energie.ui.EnergieViewModel
import com.jakober.energie.ui.LocalPlatformHooks

/** Android-Huelle: sammelt die Datenfluesse des ViewModels und reicht sie an die gemeinsame Uebersicht. */
@Composable
fun DashboardScreen(vm: EnergieViewModel, onOpenSettings: () -> Unit, contentPadding: PaddingValues) {
    val live by vm.live.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val today by vm.todayStats.collectAsStateWithLifecycle()
    val fordResult by vm.fordResult.collectAsStateWithLifecycle()
    val yesterday by vm.yesterdayStats.collectAsStateWithLifecycle()
    val cooling by vm.coolingReports.collectAsStateWithLifecycle()
    val carHealth by vm.carBatteryHealth.collectAsStateWithLifecycle()
    val gridMonths by vm.gridMonths.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hooks = remember(context) { AndroidPlatformHooks(context.applicationContext) }
    val actions = remember(vm) {
        DashboardActions(
            refresh = vm::refreshNow,
            setChargeOverride = vm::setChargeOverride,
            fordCommand = vm::fordCommand,
            savePlaces = vm::savePlaces,
        )
    }
    CompositionLocalProvider(LocalPlatformHooks provides hooks) {
        DashboardContent(
            DashboardData(
                live = live, settings = settings, today = today, yesterday = yesterday, cooling = cooling,
                carHealth = carHealth, gridMonths = gridMonths, fordResult = fordResult, todayDate = vm.todayDate(),
            ),
            actions, onOpenSettings, contentPadding,
        )
    }
}
