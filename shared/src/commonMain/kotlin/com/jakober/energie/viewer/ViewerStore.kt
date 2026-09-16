package com.jakober.energie.viewer

import com.jakober.energie.core.cloud.CloudAlert
import com.jakober.energie.core.cloud.CloudCommand
import com.jakober.energie.core.cloud.CloudException
import com.jakober.energie.core.cloud.CloudSession
import com.jakober.energie.core.cloud.SupabaseClient
import com.jakober.energie.core.forecast.OpenMeteoClient
import com.jakober.energie.core.forecast.PvForecast
import com.jakober.energie.core.history.CarBatteryHealth
import com.jakober.energie.core.history.ChargeSession
import com.jakober.energie.core.history.ChargeSessions
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.history.DaySummary
import com.jakober.energie.core.history.DriveDay
import com.jakober.energie.core.history.EnergyTotals
import com.jakober.energie.core.history.GridMonth
import com.jakober.energie.core.history.GridMonths
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.core.plugs.CoolingHealth
import com.jakober.energie.core.plugs.CoolingReport
import com.jakober.energie.core.senec.SenecSystem
import com.jakober.energie.core.smartcar.CarState
import com.jakober.energie.data.CloudRole
import com.jakober.energie.data.FordCommand
import com.jakober.energie.data.LiveState
import com.jakober.energie.data.Settings
import com.jakober.energie.ui.Range
import com.jakober.energie.ui.RangeStatistics
import com.jakober.energie.ui.dashboard.DashboardData
import com.jakober.energie.ui.statistics.StatisticsData
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Alles, was die Anzeige gerade weiss. Eine Momentaufnahme, die die Oberflaeche in Karten uebersetzt. */
data class ViewerState(
    val loggedIn: Boolean = false,
    val email: String = "",
    val live: LiveState = LiveState(),
    val settings: Settings = Settings(cloudRole = CloudRole.VIEWER, cloudUrl = CloudDefaults.URL, cloudAnonKey = CloudDefaults.ANON_KEY),
    /** Messpunkte je Tag, soweit geladen (heute immer, andere Tage auf Anfrage). */
    val samples: Map<LocalDate, List<EnergySample>> = emptyMap(),
    /** Tageszusammenfassungen der Zentrale (vergangene Tage). */
    val summaries: Map<LocalDate, DaySummary> = emptyMap(),
    val alerts: List<Pair<CloudAlert, Instant?>> = emptyList(),
    val commands: List<Pair<CloudCommand, String?>> = emptyList(),
    val selectedDate: LocalDate = LocalDate(2000, 1, 1),
    val range: Range = Range.DAY,
    val upgradeModuleKwh: Double = 3.55,
    val upgradeCostEur: Double = 1500.0,
    /** Rueckmeldung zum letzten Auftrag (Handschalter, Ford-Befehl, Regeln). */
    val message: String? = null,
    val loginError: String? = null,
    val busy: Boolean = false,
    /** Ob die Zentrale ueberhaupt schon Tageszusammenfassungen schreibt. */
    val summariesAvailable: Boolean = true,
)

/**
 * Datenschicht der reinen Anzeige (iOS): liest Status, Einstellungen, Messpunkte,
 * Tageszusammenfassungen und Hinweise aus Supabase und stellt Auftraege ein. Kein
 * eigener Verlauf auf dem Geraet - der heutige Tag kommt komplett aus der Cloud,
 * aeltere Tage als Zusammenfassung der Zentrale.
 */
class ViewerStore(
    http: HttpClient,
    private val kv: KeyValueStore,
    private val scope: CoroutineScope,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val client = SupabaseClient(http, CloudDefaults.URL, CloudDefaults.ANON_KEY)
    private val meteo = OpenMeteoClient(http)
    private val lock = Mutex()
    private var session: CloudSession? = kv.get(KEY_SESSION)?.let { runCatching { json.decodeFromString(CloudSession.serializer(), it) }.getOrNull() }
    private var lastForecastAt: Instant? = null
    private var summariesLoadedAt: Instant? = null
    private var polling: Job? = null

    private val _state = MutableStateFlow(
        ViewerState(
            loggedIn = session != null,
            email = kv.get(KEY_EMAIL) ?: "",
            selectedDate = today(),
            settings = Settings(cloudRole = CloudRole.VIEWER, cloudUrl = CloudDefaults.URL, cloudAnonKey = CloudDefaults.ANON_KEY, cloudEmail = kv.get(KEY_EMAIL) ?: "")
                .let { s -> kv.get(KEY_FORECAST)?.let { f -> runCatching { s.copy(pvForecast = json.decodeFromString(PvForecast.serializer(), f)) }.getOrNull() } ?: s },
        ),
    )
    val state: StateFlow<ViewerState> = _state

    fun today(): LocalDate = clock.now().toLocalDateTime(zone).date

    /** Letzter Absturz, vom Fehler-Haken der Plattform abgelegt; null = keiner. */
    fun lastCrash(): String? = kv.get(KEY_CRASH)
    fun clearCrash() = kv.put(KEY_CRASH, null)

    // ------------------------------------------------------------ Anmeldung

    fun login(email: String, password: String) {
        scope.launch {
            _state.update { it.copy(busy = true, loginError = null) }
            try {
                val s = client.signIn(email.trim(), password)
                saveSession(s)
                kv.put(KEY_EMAIL, email.trim())
                _state.update { it.copy(loggedIn = true, email = email.trim(), settings = it.settings.copy(cloudEmail = email.trim()), busy = false) }
                start()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, loginError = e.message ?: "Anmeldung fehlgeschlagen") }
            }
        }
    }

    fun logout() {
        stop()
        session = null
        kv.put(KEY_SESSION, null)
        _state.update { ViewerState(email = it.email, selectedDate = today()) }
    }

    private fun saveSession(s: CloudSession) {
        session = s
        kv.put(KEY_SESSION, json.encodeToString(CloudSession.serializer(), s))
    }

    /** Gueltige Sitzung; erneuert das Token rechtzeitig. Ohne Sitzung: abgemeldet. */
    private suspend fun session(): CloudSession {
        val s = session ?: throw CloudException("Nicht angemeldet", 401)
        if (!s.expiresSoon(clock.now())) return s
        val fresh = client.refresh(s)
        saveSession(fresh)
        return fresh
    }

    private suspend fun <T> withSession(block: suspend (CloudSession) -> T): T {
        return try {
            block(session())
        } catch (e: CloudException) {
            if (!e.unauthorized) throw e
            // Einmal mit erneuertem Token wiederholen, sonst ist die Sitzung wirklich abgelaufen.
            val s = session ?: throw e
            val fresh = runCatching { client.refresh(s) }.getOrElse { logout(); throw e }
            saveSession(fresh)
            block(fresh)
        }
    }

    // ------------------------------------------------------------ Laufender Abgleich

    /** Solange die App sichtbar ist, jede Minute abgleichen. */
    fun start() {
        if (polling?.isActive == true || session == null) return
        polling = scope.launch {
            while (isActive) {
                runCatching { refresh() }
                delay(REFRESH_INTERVAL)
            }
        }
    }

    fun stop() { polling?.cancel(); polling = null }

    fun refreshNow() {
        scope.launch {
            // Die Zentrale soll das Auto beim naechsten Lauf frisch fragen, nicht erst nach fuenf Minuten.
            runCatching { withSession { client.addCommand(it, CMD_REFRESH) } }
            runCatching { refresh() }
            delay(75.seconds)
            runCatching { refresh() }
        }
    }

    suspend fun refresh() = lock.withLock {
        if (session == null) return
        _state.update { it.copy(live = it.live.copy(refreshing = true)) }
        val today = today()
        try {
            coroutineScope {
                val status = async { runCatching { withSession { client.getStatus(it) } }.getOrNull() }
                val settings = async { runCatching { withSession { client.getSettings(it) } }.getOrNull() }
                val todaySamples = async { runCatching { loadDaySamples(today) }.getOrNull() }
                val alerts = async { runCatching { withSession { client.recentAlerts(it, 20) } }.getOrDefault(emptyList()) }
                val commands = async { runCatching { withSession { client.recentCommands(it, 8) } }.getOrDefault(emptyList()) }
                val summaries = async { runCatching { loadSummaries(today) }.getOrNull() }

                val st = status.await()
                val se = settings.await()
                val ts = todaySamples.await()
                val sm = summaries.await()
                val al = alerts.await()
                val cm = commands.await()
                _state.update { old ->
                    var live = old.live.copy(refreshing = false, cloudError = null)
                    st?.let { (obj, seen) -> live = applyStatus(live, obj).copy(hubSeenAt = seen) }
                    ts?.let { s -> live = live.copy(sample = s.lastOrNull() ?: live.sample, lastUpdate = s.lastOrNull()?.at ?: live.lastUpdate) }
                    // Ford-Knoepfe der Auto-Karte haengen an fordConnected; die Anzeige schickt Befehle ueber die Cloud.
                    val newSettings = (se?.let { (plain, _) -> SettingsPlain.apply(old.settings, plain) } ?: old.settings)
                        .let { ns -> if (ns.fordVin.isNotBlank()) ns.copy(fordTokensJson = "cloud") else ns }
                    old.copy(
                        live = live,
                        settings = newSettings,
                        samples = if (ts != null) old.samples + (today to ts) else old.samples,
                        summaries = sm ?: old.summaries,
                        alerts = al,
                        commands = cm,
                        summariesAvailable = sm?.isNotEmpty() ?: old.summariesAvailable,
                    )
                }
            }
            refreshForecast()
        } catch (e: Exception) {
            _state.update { it.copy(live = it.live.copy(refreshing = false, cloudError = e.message ?: e.toString())) }
        }
    }

    private fun applyStatus(live: LiveState, o: JsonObject): LiveState {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        (o["chargeOverride"] as? JsonPrimitive)?.booleanOrNull?.let { overrideFromStatus = it }
        return live.copy(
            car = (o["car"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(CarState.serializer(), it) }.getOrNull() } ?: live.car,
            senec = (o["senec"] as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(SenecSystem.serializer(), it) }.getOrNull() } ?: live.senec,
            automationStatus = str("automationStatus") ?: live.automationStatus,
            senecError = str("senecError"),
            fritzError = str("fritzError"),
            carError = str("carError"),
            pvPeakEstimateKw = (o["pvPeakEstimateKw"] as? JsonPrimitive)?.doubleOrNull ?: live.pvPeakEstimateKw,
            plugErrors = (o["plugErrors"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap(),
        )
    }

    /** Handschalter laut Zentrale; aeltere Zentralen melden ihn nicht, dann aus dem Automatik-Text. */
    fun chargeOverride(s: ViewerState): Boolean = overrideFromStatus ?: (s.live.automationStatus?.contains("Handschalter") == true)
    private var overrideFromStatus: Boolean? = null

    private suspend fun loadDaySamples(date: LocalDate): List<EnergySample> {
        val from = date.atStartOfDayIn(zone)
        val to = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        return withSession { client.samplesBetween(it, from, to) }
    }

    /**
     * Tageszusammenfassungen: beim ersten Mal alle (bis 400 Tage zurueck), danach nur die
     * letzten zwei Tage neu, weil die Zentrale gestern noch nachtraegt.
     */
    private suspend fun loadSummaries(today: LocalDate): Map<LocalDate, DaySummary> {
        val known = _state.value.summaries
        val now = clock.now()
        val full = known.isEmpty() || summariesLoadedAt == null || now - summariesLoadedAt!! > SUMMARIES_FULL_INTERVAL
        val from = if (full) today.minus(SUMMARY_DAYS, DateTimeUnit.DAY) else today.minus(2, DateTimeUnit.DAY)
        val rows = withSession { client.days(it, from, today.minus(1, DateTimeUnit.DAY)) }
        if (full) summariesLoadedAt = now
        return (if (full) emptyMap() else known) + rows.associateBy { it.date }
    }

    /** Fuer die Statistik: Messpunkte eines anderen Tages nachladen, wenn sie fehlen. */
    fun ensureDay(date: LocalDate) {
        if (date == today() || _state.value.samples.containsKey(date)) return
        scope.launch {
            val s = runCatching { loadDaySamples(date) }.getOrNull() ?: return@launch
            _state.update { it.copy(samples = it.samples + (date to s)) }
        }
    }

    private suspend fun refreshForecast() {
        val s = _state.value.settings
        if (s.homeLat == 0.0 && s.homeLon == 0.0) return
        val now = clock.now()
        val current = s.pvForecast
        val today = today()
        val fresh = current != null && now.epochSeconds - current.fetchedAtEpochSeconds < FORECAST_INTERVAL.inWholeSeconds &&
            current.day(today) != null && current.days.count { it.date >= today } >= 5
        if (fresh) return
        lastForecastAt?.let { if (now - it < 10.minutes) return }
        lastForecastAt = now
        val days = runCatching {
            if (s.pvPeakKw2 > 0) meteo.forecastTwoSides(s.homeLat, s.homeLon, s.pvTiltDeg, s.pvAzimuthDeg, s.pvTiltDeg2, s.pvAzimuthDeg2)
            else meteo.forecast(s.homeLat, s.homeLon, s.pvTiltDeg, s.pvAzimuthDeg)
        }.getOrElse { e -> _state.update { it.copy(live = it.live.copy(forecastError = e.message)) }; return }
        val forecast = PvForecast(now.epochSeconds, days)
        kv.put(KEY_FORECAST, json.encodeToString(PvForecast.serializer(), forecast))
        _state.update { it.copy(settings = it.settings.copy(pvForecast = forecast), live = it.live.copy(forecastError = null)) }
    }

    // ------------------------------------------------------------ Auftraege an die Zentrale

    fun setChargeOverride(on: Boolean) = command(if (on) "Handschalter ein" else "Handschalter aus") {
        client.addCommand(it, CMD_OVERRIDE, buildJsonObject { put("on", on) })
        overrideFromStatus = on
    }

    fun fordCommand(command: FordCommand) = command(command.label) {
        client.addCommand(it, CMD_FORD, buildJsonObject { put("command", command.name) })
    }

    fun saveRules(rules: com.jakober.energie.core.rules.ChargeRules) {
        val fixed = if (rules.batteryOffPercent >= rules.batteryOnPercent) rules.copy(batteryOffPercent = (rules.batteryOnPercent - 10).coerceAtLeast(0)) else rules
        _state.update { it.copy(settings = it.settings.copy(chargeRules = fixed)) }
        command("Laderegeln") {
            client.addCommand(it, CMD_SETTINGS, buildJsonObject { put("plain", buildJsonObject { put("chargeRules", SettingsPlain.rulesJson(fixed)) }) })
        }
    }

    fun savePlaces(places: List<com.jakober.energie.core.places.NamedPlace>) {
        _state.update { it.copy(settings = it.settings.copy(places = places)) }
        command("Orte") {
            client.addCommand(it, CMD_SETTINGS, buildJsonObject { put("plain", buildJsonObject { put("places", SettingsPlain.placesJson(places)) }) })
        }
    }

    private fun command(label: String, block: suspend (CloudSession) -> Unit) {
        scope.launch {
            try {
                withSession(block)
                _state.update { it.copy(message = "$label: an die Zentrale übergeben, greift mit ihrem nächsten Lauf (bis zu einer Minute).") }
                delay(70.seconds)
                runCatching { refresh() }
            } catch (e: Exception) {
                _state.update { it.copy(message = "$label: nicht übergeben (${e.message ?: e})") }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------ Zeitraum der Statistik

    fun setRange(r: Range) { _state.update { it.copy(range = r) }; ensureSelected() }
    fun shift(steps: Int) { _state.update { it.copy(selectedDate = Range.shift(it.selectedDate, it.range, steps)) }; ensureSelected() }
    fun goToday() { _state.update { it.copy(selectedDate = today()) } }
    fun setUpgradeModuleKwh(v: Double) = _state.update { it.copy(upgradeModuleKwh = v) }
    fun setUpgradeCost(v: Double) = _state.update { it.copy(upgradeCostEur = v) }
    private fun ensureSelected() { val s = _state.value; if (s.range == Range.DAY) ensureDay(s.selectedDate) }

    // ------------------------------------------------------------ Ableitungen fuer die Karten

    /** Letzter Zaehlerstand des Vortags als Messpunkt, damit die Zaehlerdifferenz die Nacht einschliesst. */
    private fun previousMeterSample(date: LocalDate, s: ViewerState): EnergySample? {
        val prev = date.minus(1, DateTimeUnit.DAY)
        s.samples[prev]?.lastOrNull { it.meterImportWh != null || it.meterExportWh != null }?.let { return it }
        val sum = s.summaries[prev] ?: return null
        val at = sum.lastMeterAt ?: return null
        return EnergySample(at = at, meterImportWh = sum.lastMeterImportWh, meterExportWh = sum.lastMeterExportWh)
    }

    fun dayStatistics(date: LocalDate, s: ViewerState): DayStatistics? {
        s.samples[date]?.let { return DayStatistics.of(date, it, zone, previousMeterSample(date, s)) }
        return s.summaries[date]?.stats
    }

    private fun emptyDay(date: LocalDate): DayStatistics = DayStatistics.of(date, emptyList(), zone)

    fun rangeStatistics(date: LocalDate, range: Range, s: ViewerState, today: LocalDate): RangeStatistics {
        val (from, to0) = Range.bounds(date, range)
        val to = if (to0 > today) today else to0
        val days = ArrayList<DayStatistics>()
        var d = from
        while (d <= to) { days += dayStatistics(d, s) ?: emptyDay(d); d = d.plus(1, DateTimeUnit.DAY) }
        return RangeStatistics(from, to0, days)
    }

    fun sessions(date: LocalDate, range: Range, s: ViewerState, today: LocalDate): List<ChargeSession> {
        val (from, to) = Range.bounds(date, range)
        val out = ArrayList<ChargeSession>()
        var d = from
        while (d <= to && d <= today) {
            s.samples[d]?.let { out += ChargeSessions.of(it) } ?: s.summaries[d]?.let { out += it.sessions }
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return out
    }

    fun dashboardData(s: ViewerState): DashboardData {
        val today = today()
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val todayStats = dayStatistics(today, s)
        val past = s.summaries.values.filter { it.date < today }.sortedBy { it.date }
        val cooling = s.settings.plugs.filter { it.isCooling }.associate { d ->
            val days = past.takeLast(CoolingHealth.BASELINE_DAYS + CoolingHealth.RECENT_DAYS + 7).mapNotNull { sum -> sum.stats.plugs[d.id]?.let { sum.date to it } }
            d.id to CoolingHealth.of(d, days)
        }
        val points = past.mapNotNull { it.capacity } + listOfNotNull(s.samples[today]?.let { CarBatteryHealth.dayPoint(today, it) })
        val health = if (points.isEmpty()) null else CarBatteryHealth.of(points, s.settings.carBatteryNominalKwh.takeIf { it > 0 })
        val gridMonths = GridMonths.of(past.map { it.stats } + listOfNotNull(todayStats), today)
        return DashboardData(
            live = s.live, settings = s.settings, today = todayStats, yesterday = dayStatistics(yesterday, s),
            cooling = cooling, carHealth = health, gridMonths = gridMonths, fordResult = s.message, todayDate = today,
        )
    }

    fun statisticsData(s: ViewerState): StatisticsData {
        val today = today()
        val past = s.summaries.values.filter { it.date < today }.sortedBy { it.date }
        val todayStats = dayStatistics(today, s)
        val all = past.map { it.stats } + listOfNotNull(todayStats)
        val lifetime = EnergyTotals(
            productionWh = all.sumOf { it.totals.productionWh }, consumptionWh = all.sumOf { it.totals.consumptionWh },
            gridImportWh = all.sumOf { it.totals.gridImportWh }, gridExportWh = all.sumOf { it.totals.gridExportWh },
            batteryChargeWh = all.sumOf { it.totals.batteryChargeWh }, batteryDischargeWh = all.sumOf { it.totals.batteryDischargeWh },
            carChargeWh = all.sumOf { it.totals.carChargeWh }, carFromGridWh = all.sumOf { it.totals.carFromGridWh },
            meterImportWh = null, meterExportWh = null,
        )
        val driving: List<DriveDay> = past.flatMap { it.drive }
        return StatisticsData(
            range = s.range, date = s.selectedDate, isToday = s.selectedDate == today, todayDate = today,
            day = dayStatistics(s.selectedDate, s), rangeStats = if (s.range == Range.DAY) null else rangeStatistics(s.selectedDate, s.range, s, today),
            settings = s.settings, lifetime = if (all.isEmpty()) null else lifetime,
            sessions = sessions(s.selectedDate, s.range, s, today),
            currentMonth = rangeStatistics(today, Range.MONTH, s, today),
            storedDays = all.size, driving = driving, daySamples = s.samples[s.selectedDate] ?: emptyList(),
            upgrade = null, upgradeKwh = s.upgradeModuleKwh, upgradeCost = s.upgradeCostEur, live = s.live,
            gridMonths = GridMonths.of(all, today),
        )
    }

    companion object {
        val REFRESH_INTERVAL = 60.seconds
        val FORECAST_INTERVAL = 3.hours
        /** Alle Zusammenfassungen so oft komplett neu laden, sonst nur die letzten Tage. */
        val SUMMARIES_FULL_INTERVAL = 6.hours
        const val SUMMARY_DAYS = 400
        const val KEY_SESSION = "session"
        const val KEY_EMAIL = "email"
        const val KEY_FORECAST = "forecast"
        const val KEY_CRASH = "crash"
        const val CMD_FORD = "FORD"
        const val CMD_OVERRIDE = "CHARGE_OVERRIDE"
        const val CMD_SETTINGS = "SET_SETTINGS"
        const val CMD_REFRESH = "REFRESH"
    }
}
