package com.jakober.energie.data

import com.jakober.energie.core.fritz.FritzBoxClient
import com.jakober.energie.core.fritz.FritzDevice
import com.jakober.energie.core.fritz.SmartMeterReading
import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.history.HistoryStore
import com.jakober.energie.core.model.EnergySample
import com.jakober.energie.core.senec.SenecConnectClient
import com.jakober.energie.core.senec.SenecSystem
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds

/** Der aktuelle Zustand, wie ihn die Oberflaeche zeigt. */
data class LiveState(
    val sample: EnergySample? = null,
    val senec: SenecSystem? = null,
    val meter: SmartMeterReading? = null,
    val fritzDevices: List<FritzDevice> = emptyList(),
    val senecError: String? = null,
    val fritzError: String? = null,
    val lastUpdate: Instant? = null,
    val refreshing: Boolean = false,
    /** Letzte Rohantwort von SENEC, fuer die Ansicht in den Einstellungen. */
    val senecRaw: String? = null,
)

/**
 * Holt die Werte beider Quellen, fuehrt sie zu einem Messpunkt zusammen und
 * legt ihn im Verlauf ab. Wird sowohl vom Bildschirm (regelmaessig, solange
 * die App offen ist) als auch vom Hintergrund-Worker aufgerufen.
 */
class EnergyRepository(
    private val settings: AppSettings,
    private val http: HttpClient,
    val history: HistoryStore,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(LiveState(sample = history.latest(), lastUpdate = history.latest()?.at))
    val state: StateFlow<LiveState> = _state

    private val lock = Mutex()
    private var fritz: FritzBoxClient? = null
    private var fritzKey: String? = null
    private var lastStored: Instant? = history.latest()?.at

    /** Fragt beide Quellen ab. Fehler einer Quelle blockieren die andere nicht. */
    suspend fun refresh(): LiveState = lock.withLock {
        val s = settings.current()
        _state.update { it.copy(refreshing = true) }
        val result = withContext(Dispatchers.IO) {
            coroutineScope {
                val senecJob = async { if (s.senecConfigured) runCatching { fetchSenec(s) } else null }
                val fritzJob = async { if (s.fritzConfigured) runCatching { fetchFritz(s) } else null }
                Pair(senecJob.await(), fritzJob.await())
            }
        }
        val (senecResult, fritzResult) = result
        val now = clock.now()
        val senec = senecResult?.getOrNull()
        val fritzData = fritzResult?.getOrNull()
        val meter = fritzData?.second

        val sample = EnergySample(
            at = now,
            batterySocPercent = senec?.system?.battery?.stateOfCharge,
            batteryPowerW = senec?.system?.battery?.power,
            batteryState = senec?.system?.battery?.state,
            productionW = senec?.system?.meter?.production,
            consumptionW = senec?.system?.meter?.consumption,
            senecGridPowerW = senec?.system?.meter?.gridPower,
            evseChargingPowerW = senec?.system?.evse?.firstOrNull()?.chargingPower,
            evConnected = senec?.system?.evse?.firstOrNull()?.evConnected,
            meterGridPowerW = meter?.gridPowerWatt,
            meterImportWh = meter?.importEnergyWh,
            meterExportWh = meter?.exportEnergyWh,
        )

        val gotSomething = sample.hasSenec || sample.hasMeter
        if (gotSomething && shouldStore(now)) {
            history.append(sample)
            lastStored = now
        }

        val newState = LiveState(
            sample = if (gotSomething) sample else _state.value.sample,
            senec = senec?.system ?: if (senecResult == null) null else _state.value.senec,
            meter = meter ?: if (fritzResult == null) null else _state.value.meter,
            fritzDevices = fritzData?.first ?: _state.value.fritzDevices,
            senecError = senecResult?.exceptionOrNull()?.let { it.message ?: it.toString() },
            fritzError = fritzResult?.exceptionOrNull()?.let { it.message ?: it.toString() },
            lastUpdate = if (gotSomething) now else _state.value.lastUpdate,
            refreshing = false,
            senecRaw = senec?.raw ?: _state.value.senecRaw,
        )
        _state.value = newState
        newState
    }

    /** Hoechstens ein gespeicherter Messpunkt je Minute, damit der Verlauf klein bleibt. */
    private fun shouldStore(now: Instant): Boolean {
        val last = lastStored ?: return true
        return now - last >= MIN_STORE_INTERVAL
    }

    private class SenecResult(val system: SenecSystem, val raw: String)

    private suspend fun fetchSenec(s: Settings): SenecResult {
        val client = SenecConnectClient(http, s.senecKey, s.senecBaseUrl)
        val raw = client.rawGeneral()
        val systems = client.parse(raw)
        val system = systems.firstOrNull() ?: throw IllegalStateException("SENEC liefert keine Anlage fuer dieses Konto.")
        return SenecResult(system, raw)
    }

    private suspend fun fetchFritz(s: Settings): Pair<List<FritzDevice>, SmartMeterReading?> {
        val key = "${s.fritzHost}|${s.fritzUser}|${s.fritzPassword}"
        val client = fritz?.takeIf { fritzKey == key } ?: FritzBoxClient(http, s.fritzHost, s.fritzUser, s.fritzPassword, clock).also {
            fritz = it
            fritzKey = key
        }
        val devices = client.deviceList()
        return devices to client.smartMeter(devices)
    }

    fun dayStatistics(date: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): DayStatistics =
        DayStatistics.of(date, history.day(date), zone)

    fun today(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = clock.now().toLocalDateTime(zone).date

    suspend fun prune() {
        val s = settings.current()
        withContext(Dispatchers.IO) { history.prune(today(), s.keepDays) }
    }

    companion object {
        val MIN_STORE_INTERVAL = 55.seconds
    }
}
