package com.jakober.energie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.material3.dynamicLightColorScheme
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
        // Erst den Absturzbericht lesen, dann den Container holen: scheitert der Start selbst,
        // ist die Meldung trotzdem zu sehen.
        val crashFile = java.io.File(filesDir, EnergieApp.CRASH_FILE)
        val crashText = runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull()
        val container = runCatching { (application as EnergieApp).container }.getOrNull()
        setContent {
            // Android 12+: Systemfarben fuer das helle Schema, wie bisher.
            val dynamicLight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(this) else null
            EnergieTheme(lightScheme = dynamicLight) {
                var crash by remember { mutableStateOf(crashText) }
                val text = crash
                when {
                    text != null -> CrashScreen(text) { crashFile.delete(); crash = null }
                    container == null -> CrashScreen("Der Start der App ist fehlgeschlagen, es liegt aber kein Bericht vor.") {}
                    else -> EnergieRoot(container, connectReturns.intValue)
                }
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

/** Letzter Absturz in voller Laenge, zum Abfotografieren. Erste Ansicht nach einem Absturz. */
@Composable
private fun CrashScreen(text: String, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Letzter Absturz", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Text(
            "Bitte abfotografieren und schicken. Danach unten weiter.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text.lines().take(45).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
        )
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Verstanden, App starten") }
        Spacer(Modifier.height(32.dp))
    }
}
