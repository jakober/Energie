package com.jakober.energie.viewer

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ElectricCar
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jakober.energie.ui.EnergieCard
import com.jakober.energie.ui.Format
import com.jakober.energie.ui.dashboard.DashboardActions
import com.jakober.energie.ui.dashboard.DashboardContent
import com.jakober.energie.ui.settings.ChargeRulesCard
import com.jakober.energie.ui.statistics.StatisticsActions
import com.jakober.energie.ui.statistics.StatisticsContent
import com.jakober.energie.ui.theme.EnergieTheme
import kotlinx.coroutines.delay

private enum class ViewerTab(val label: String) { OVERVIEW("Übersicht"), STATISTICS("Statistik"), AUTOMATION("Automatik") }

/** Die ganze Anzeige-App: Anmeldung, drei Reiter, dieselben Karten wie auf Android. */
@Composable
fun ViewerApp(store: ViewerStore) {
    EnergieTheme {
        val state by store.state.collectAsState()
        // Ein Absturz aus dem letzten Start kommt zuerst: sonst ist er nicht zu sehen.
        var crash by remember { mutableStateOf(store.lastCrash()) }
        val text = crash
        when {
            text != null -> CrashScreen(text) { store.clearCrash(); crash = null }
            !state.loggedIn -> LoginScreen(state, store::login)
            else -> ViewerScaffold(store, state)
        }
    }
}

@Composable
private fun LoginScreen(state: ViewerState, onLogin: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf(state.email) }
    var password by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Energie", style = MaterialTheme.typography.displaySmall)
        Text("Anzeige für Zuhause. Anmeldung mit dem Konto der Zentrale.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("E-Mail") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Passwort") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onLogin(email, password) }, enabled = !state.busy && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (state.busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp) else Text("Anmelden")
        }
        state.loginError?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ViewerScaffold(store: ViewerStore, state: ViewerState) {
    var tab by rememberSaveable { mutableStateOf(ViewerTab.OVERVIEW) }
    LaunchedEffect(Unit) { store.start() }
    // Rueckmeldungen zu Auftraegen kurz einblenden.
    LaunchedEffect(state.message) { if (state.message != null) { delay(6000); store.clearMessage() } }

    val dashboardActions = remember(store) {
        DashboardActions(refresh = store::refreshNow, setChargeOverride = store::setChargeOverride, fordCommand = store::fordCommand, savePlaces = store::savePlaces)
    }
    val statisticsActions = remember(store) {
        StatisticsActions(setRange = store::setRange, shift = store::shift, goToday = store::goToday, setUpgradeModuleKwh = store::setUpgradeModuleKwh, setUpgradeCost = store::setUpgradeCost)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                ViewerTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t }, label = { Text(t.label) },
                        icon = { Icon(when (t) { ViewerTab.OVERVIEW -> Icons.Rounded.Dashboard; ViewerTab.STATISTICS -> Icons.Rounded.Insights; ViewerTab.AUTOMATION -> Icons.Rounded.ElectricCar }, t.label) },
                    )
                }
            }
        },
        snackbarHost = { state.message?.let { Snackbar(Modifier.padding(12.dp)) { Text(it) } } },
    ) { padding ->
        when (tab) {
            ViewerTab.OVERVIEW -> {
                val data = remember(state) { store.dashboardData(state) }
                DashboardContent(data, dashboardActions, onOpenSettings = { tab = ViewerTab.AUTOMATION }, contentPadding = padding)
            }
            ViewerTab.STATISTICS -> {
                val data = remember(state) { store.statisticsData(state) }
                StatisticsContent(data, statisticsActions, contentPadding = padding)
            }
            ViewerTab.AUTOMATION -> AutomationScreen(store, state, padding)
        }
    }
}

@Composable
private fun AutomationScreen(store: ViewerStore, state: ViewerState, contentPadding: PaddingValues) {
    val override = store.chargeOverride(state)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Automatik", style = MaterialTheme.typography.displaySmall) }
        item {
            EnergieCard(title = "Handschalter") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Jetzt voll laden", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (override) "Aktiv: Das Auto lädt bis voll, die Regeln pausieren. Endet beim Abstecken." else "Lädt das Auto bis voll, unabhängig vom Speicherstand. Endet beim Abstecken.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = override, onCheckedChange = { store.setChargeOverride(it) })
                }
                state.live.automationStatus?.let { Text("Zuletzt: $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item {
            ChargeRulesCard(
                saved = state.settings.chargeRules,
                fordConnected = state.settings.fordVin.isNotBlank(),
                status = state.live.automationStatus,
                log = state.settings.chargeLog,
                onSave = store::saveRules,
            )
        }
        item {
            EnergieCard(title = "Aufträge an die Zentrale") {
                if (state.commands.isEmpty()) Text("Noch keine Aufträge.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.commands.forEachIndexed { i, (c, result) ->
                    if (i > 0) HorizontalDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(commandLabel(c.kind, c.payload.toString()), style = MaterialTheme.typography.titleSmall)
                        Text(
                            (c.createdAt.takeIf { it.isNotBlank() }?.let { runCatching { Format.dateTime(kotlinx.datetime.Instant.parse(it)) }.getOrNull() } ?: "") +
                                " · " + (result ?: "wartet auf die Zentrale …"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            EnergieCard(title = "Hinweise") {
                if (state.alerts.isEmpty()) Text("Keine Hinweise.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.alerts.forEachIndexed { i, (a, at) ->
                    if (i > 0) HorizontalDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(Format.dateTime(at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(a.title, style = MaterialTheme.typography.titleSmall)
                        Text(a.body, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            EnergieCard(title = "Konto") {
                Text("Angemeldet als ${state.email}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.summariesAvailable) {
                    Text(
                        "Die Zentrale hat noch keine Tageszusammenfassungen geschrieben. Wochen, Monate und Jahre füllen sich, sobald sie mit Build 96 oder neuer läuft.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = store::logout, modifier = Modifier.fillMaxWidth()) { Text("Abmelden") }
            }
        }
    }
}

private fun commandLabel(kind: String, payload: String): String = when (kind) {
    "CHARGE_OVERRIDE" -> if (payload.contains("false")) "Handschalter aus" else "Handschalter ein"
    "SET_SETTINGS" -> "Einstellungen"
    "FORD" -> "Ford-Befehl"
    "REFRESH" -> "Auto neu abfragen"
    else -> kind
}

/** Letzter Absturz in voller Laenge, zum Abfotografieren. */
@Composable
private fun CrashScreen(text: String, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Letzter Absturz", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Text(
            "Bitte abfotografieren und schicken. Danach unten weiter.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text.lines().take(45).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Verstanden, App starten") }
        Spacer(Modifier.height(32.dp))
    }
}
