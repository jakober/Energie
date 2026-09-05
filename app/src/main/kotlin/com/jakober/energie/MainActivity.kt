package com.jakober.energie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jakober.energie.ui.EnergieViewModel
import com.jakober.energie.ui.dashboard.DashboardScreen
import com.jakober.energie.ui.settings.SettingsScreen
import com.jakober.energie.ui.statistics.StatisticsScreen
import com.jakober.energie.ui.theme.EnergieTheme

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("uebersicht", "Übersicht", Icons.Rounded.Dashboard),
    STATISTICS("statistik", "Statistik", Icons.Rounded.Insights),
    SETTINGS("einstellungen", "Einstellungen", Icons.Rounded.Settings),
}

class MainActivity : ComponentActivity() {
    // Zaehlt, wie oft Smartcar Connect zurueck in die App gesprungen ist.
    private val connectReturns = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        noteConnectReturn(intent)
        val container = (application as EnergieApp).container
        setContent {
            EnergieTheme {
                EnergieRoot(container, connectReturns.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noteConnectReturn(intent)
    }

    private fun noteConnectReturn(intent: Intent?) {
        if (intent?.data?.scheme?.startsWith("sc") == true) connectReturns.intValue++
    }
}

@Composable
private fun EnergieRoot(container: AppContainer, connectReturns: Int) {
    val vm: EnergieViewModel = viewModel { EnergieViewModel(container) }

    // Zurueck aus Smartcar Connect: gleich nachsehen, ob das Auto jetzt verbunden ist.
    LaunchedEffect(connectReturns) {
        if (connectReturns > 0) vm.carCheck()
    }
    // Android 13+: Benachrichtigungen brauchen eine Erlaubnis; einmal beim Start fragen.
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    // Abfragen nur, solange die App sichtbar ist.
    LifecycleStartEffect(Unit) {
        vm.startPolling()
        onStopOrDispose { vm.stopPolling() }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding: PaddingValues ->
        NavHost(nav, startDestination = Tab.DASHBOARD.route) {
            composable(Tab.DASHBOARD.route) {
                DashboardScreen(vm, onOpenSettings = { nav.navigate(Tab.SETTINGS.route) { launchSingleTop = true } }, contentPadding = padding)
            }
            composable(Tab.STATISTICS.route) { StatisticsScreen(vm, contentPadding = padding) }
            composable(Tab.SETTINGS.route) { SettingsScreen(vm, contentPadding = padding) }
        }
    }
}
