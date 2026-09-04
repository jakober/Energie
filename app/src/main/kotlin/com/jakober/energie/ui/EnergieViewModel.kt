package com.jakober.energie.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jakober.energie.AppContainer
import com.jakober.energie.core.fritz.FritzBoxClient
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.history.EnergyTotals
import com.jakober.energie.core.senec.SenecConnectClient
import com.jakober.energie.core.smartcar.SmartcarClient
import com.jakober.energie.data.CarCommand
import com.jakober.energie.data.LiveState
import com.jakober.energie.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.seconds

enum class Range { DAY, WEEK, MONTH }

/** Zusammenfassung mehrerer Tage (Woche, Monat). */
data class RangeStatistics(
    val from: LocalDate,
    val to: LocalDate,
    val days: List<DayStatistics>,
) {
    val totals: EnergyTotals = EnergyTotals(
        productionWh = days.sumOf { it.totals.productionWh },
        consumptionWh = days.sumOf { it.totals.consumptionWh },
        gridImportWh = days.sumOf { it.totals.gridImportWh },
        gridExportWh = days.sumOf { it.totals.gridExportWh },
        batteryChargeWh = days.sumOf { it.totals.batteryChargeWh },
        batteryDischargeWh = days.sumOf { it.totals.batteryDischargeWh },
        carChargeWh = days.sumOf { it.totals.carChargeWh },
        carFromGridWh = days.sumOf { it.totals.carFromGridWh },
        meterImportWh = days.mapNotNull { it.totals.meterImportWh }.takeIf { it.isNotEmpty() }?.sum(),
        meterExportWh = days.mapNotNull { it.totals.meterExportWh }.takeIf { it.isNotEmpty() }?.sum(),
    )
    val daysWithData: List<DayStatistics> get() = days.filter { it.sampleCount > 0 }
    val bestProductionDay: DayStatistics? get() = daysWithData.maxByOrNull { it.totals.productionWh }?.takeIf { it.totals.productionWh > 0 }
    val heaviestConsumptionDay: DayStatistics? get() = daysWithData.maxByOrNull { it.totals.consumptionWh }?.takeIf { it.totals.consumptionWh > 0 }
    val averageConsumptionWh: Double? get() = daysWithData.takeIf { it.isNotEmpty() }?.let { d -> d.sumOf { it.totals.consumptionWh } / d.size }
    val peakConsumption: com.jakober.energie.core.history.Peak? get() = daysWithData.mapNotNull { it.peakConsumption }.maxByOrNull { it.value }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EnergieViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repository

    val live: StateFlow<LiveState> = repo.state
    val settings: StateFlow<Settings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _selectedDate = MutableStateFlow(repo.today())
    val selectedDate: StateFlow<LocalDate> = _selectedDate
    private val _range = MutableStateFlow(Range.DAY)
    val range: StateFlow<Range> = _range

    private val updates = live.map { it.lastUpdate }.distinctUntilChanged()

    val todayStats: StateFlow<DayStatistics?> = updates
        .mapLatest { withContext(Dispatchers.IO) { repo.dayStatistics(repo.today()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dayStats: StateFlow<DayStatistics?> = combine(_selectedDate, updates) { d, _ -> d }
        .mapLatest { d -> withContext(Dispatchers.IO) { repo.dayStatistics(d) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val rangeStats: StateFlow<RangeStatistics?> = combine(_selectedDate, _range, updates) { d, r, _ -> d to r }
        .mapLatest { (d, r) ->
            if (r == Range.DAY) return@mapLatest null
            val (from, to) = bounds(d, r)
            withContext(Dispatchers.IO) {
                val list = ArrayList<DayStatistics>()
                var day = from
                while (day <= to) {
                    list += repo.dayStatistics(day)
                    day = day.plus(1, DateTimeUnit.DAY)
                }
                RangeStatistics(from, to, list)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Alles seit dem ersten Messpunkt, fuer die Auto-Gesamtrechnung. */
    val lifetime: StateFlow<EnergyTotals?> = updates
        .mapLatest { withContext(Dispatchers.IO) { repo.lifetimeTotals() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val storedDays: StateFlow<Int> = updates
        .mapLatest { withContext(Dispatchers.IO) { container.history.days().size } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var polling: Job? = null

    /** Solange die App sichtbar ist, regelmaessig abfragen. */
    fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive) {
                if (settings.value.anythingConfigured) runCatching { repo.refresh() }
                delay(settings.value.pollSeconds.coerceAtLeast(20).seconds)
            }
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    fun refreshNow() {
        viewModelScope.launch { runCatching { repo.refresh() } }
    }

    fun selectDate(d: LocalDate) { _selectedDate.value = d }

    fun setRange(r: Range) { _range.value = r }

    /** Einen Schritt der aktuellen Ansicht vor oder zurueck (Tag, Woche, Monat). */
    fun shift(steps: Int) {
        val d = _selectedDate.value
        _selectedDate.value = when (_range.value) {
            Range.DAY -> d.plus(steps, DateTimeUnit.DAY)
            Range.WEEK -> d.plus(steps * 7, DateTimeUnit.DAY)
            Range.MONTH -> d.plus(steps, DateTimeUnit.MONTH)
        }
    }

    fun goToday() { _selectedDate.value = repo.today() }

    val isToday: Boolean get() = _selectedDate.value == repo.today()

    // --- Einstellungen ---

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult

    fun save(s: Settings) {
        viewModelScope.launch {
            container.settings.save(s)
            runCatching { repo.refresh() }
        }
    }

    fun testFritz(s: Settings) {
        viewModelScope.launch {
            _testResult.value = "FRITZ!Box wird geprueft …"
            _testResult.value = withContext(Dispatchers.IO) {
                runCatching {
                    val client = FritzBoxClient(container.http, s.fritzHost, s.fritzUser, s.fritzPassword)
                    val devices = client.deviceList()
                    val meter = client.smartMeter(devices)
                    buildString {
                        append("FRITZ!Box erreichbar, ${devices.size} Smart-Home-Geraete. ")
                        if (meter != null) append("Stromzaehler gefunden: ${Format.power(meter.gridPowerWatt, signed = true)}, Bezug ${Format.meterReading(meter.importEnergyWh)}.")
                        else append("Kein FRITZ!Smart Energy 250 gefunden - Geraet in der Box eingerichtet und Benutzer mit Smart-Home-Recht?")
                    }
                }.getOrElse { "Fehler: ${it.message ?: it}" }
            }
        }
    }

    fun testSenec(s: Settings) {
        viewModelScope.launch {
            _testResult.value = "SENEC wird geprueft …"
            _testResult.value = withContext(Dispatchers.IO) {
                runCatching {
                    val systems = SenecConnectClient(container.http, s.senecKey, s.senecBaseUrl).systems()
                    val sys = systems.firstOrNull()
                    if (sys == null) "SENEC antwortet, aber ohne Anlage."
                    else "SENEC ok: ${sys.bessNameplate?.model ?: sys.systemId}, Speicher ${Format.percentValue(sys.battery?.stateOfCharge)}, PV ${Format.power(sys.meter?.production)}."
                }.getOrElse { "Fehler: ${it.message ?: it}" }
            }
        }
    }

    fun clearTestResult() { _testResult.value = null }

    // --- Auto (Smartcar) ---

    private val _carResult = MutableStateFlow<String?>(null)
    val carResult: StateFlow<String?> = _carResult
    private val _carRaw = MutableStateFlow<String?>(null)
    val carRaw: StateFlow<String?> = _carRaw

    fun connectUrl(s: Settings): String = SmartcarClient.connectUrl(s.smartcarAppId)

    /** Sucht das verbundene Fahrzeug und merkt es sich. */
    fun carCheck() {
        val s = settings.value
        if (!s.smartcarConfigured) { _carResult.value = "Erst Client-ID und Secret eintragen und speichern."; return }
        viewModelScope.launch {
            _carResult.value = "Suche verbundene Fahrzeuge …"
            runCatching { repo.carConnections(s) }
                .onSuccess { list ->
                    _carRaw.value = list.firstOrNull()?.raw
                    if (list.isEmpty()) {
                        _carResult.value = "Smartcar erreichbar, aber noch kein Fahrzeug verbunden. Erst „Auto verbinden“ ausführen."
                    } else {
                        val c = list.first()
                        container.settings.saveCar(c.vehicleId, c.userId)
                        _carResult.value = "Fahrzeug verbunden: ${c.vehicleId}" + (if (list.size > 1) " (${list.size} Verbindungen, erstes gewählt)" else "")
                        carStatus()
                    }
                }
                .onFailure { _carResult.value = "Fehler: ${it.message ?: it}" }
        }
    }

    fun carStatus() {
        viewModelScope.launch {
            val s = container.settings.current()
            if (!s.carConnected) { _carResult.value = "Kein Fahrzeug verbunden."; return@launch }
            _carResult.value = "Lese Fahrzeugstatus …"
            runCatching { repo.carRefreshNow(s) }
                .onSuccess { c ->
                    _carRaw.value = c.raw.entries.joinToString("\n\n") { (k, v) -> "$k\n$v" }
                    _carResult.value = buildString {
                        append("Ladung ${Format.percentValue(c.socPercent)}")
                        c.rangeKm?.let { append(", Reichweite ${it.toInt()} km") }
                        append(", ")
                        append(
                            when {
                                c.isCharging == true -> "lädt"
                                c.isPluggedIn == true -> "steckt, lädt nicht"
                                c.isPluggedIn == false -> "nicht angeschlossen"
                                else -> "Status unbekannt"
                            },
                        )
                        c.chargeLimitPercent?.let { append(", Ladeziel ${Format.percentValue(it)}") }
                        c.chargingStatus?.let { append(" (") ; append(it); append(")") }
                    }
                }
                .onFailure { _carResult.value = "Fehler: ${it.message ?: it}" }
        }
    }

    fun carCommand(command: CarCommand) {
        viewModelScope.launch {
            val s = container.settings.current()
            if (!s.carConnected) { _carResult.value = "Kein Fahrzeug verbunden."; return@launch }
            _carResult.value = "${command.label} wird gesendet …"
            runCatching { repo.carCommand(s, command) }
                .onSuccess { r ->
                    _carRaw.value = r.body
                    _carResult.value = if (r.ok) "${command.label}: Smartcar hat den Befehl angenommen (HTTP ${r.status}). In 1–2 Minuten in der Ford-App prüfen."
                    else "${command.label}: abgelehnt mit HTTP ${r.status}. Rohantwort unten."
                }
                .onFailure { _carResult.value = "Fehler: ${it.message ?: it}" }
        }
    }

    companion object {
        fun bounds(d: LocalDate, r: Range): Pair<LocalDate, LocalDate> = when (r) {
            Range.DAY -> d to d
            Range.WEEK -> {
                val monday = d.minus(d.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)
                monday to monday.plus(6, DateTimeUnit.DAY)
            }
            Range.MONTH -> {
                val first = LocalDate(d.year, d.month, 1)
                first to first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
            }
        }
    }
}
