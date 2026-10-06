package com.wifitri.visualizer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifitri.visualizer.core.ApEstimate
import com.wifitri.visualizer.core.ApLocator
import com.wifitri.visualizer.core.LockTracker
import com.wifitri.visualizer.core.Navigator
import com.wifitri.visualizer.core.Pdr
import com.wifitri.visualizer.core.Phase
import com.wifitri.visualizer.core.Plan
import com.wifitri.visualizer.core.RssiFilter
import com.wifitri.visualizer.core.Sample
import com.wifitri.visualizer.core.Waypoint
import com.wifitri.visualizer.core.relativeBearing
import com.wifitri.visualizer.core.signalTrendDb
import com.wifitri.visualizer.data.AppSettings
import com.wifitri.visualizer.sensors.HeadingProvider
import com.wifitri.visualizer.sensors.HeadingSource
import com.wifitri.visualizer.sensors.StepProvider
import com.wifitri.visualizer.wifi.ScanResultUi
import com.wifitri.visualizer.wifi.ScanThrottleController
import com.wifitri.visualizer.wifi.ThrottleStatus
import com.wifitri.visualizer.wifi.WifiScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.hypot

data class UiState(
    val networks: List<ScanResultUi> = emptyList(),
    val selected: ScanResultUi? = null,
    val samples: List<Sample> = emptyList(),
    val estimate: ApEstimate = ApEstimate.NONE,
    val headingRad: Double = 0.0,
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val smoothedRssi: Double = -100.0,
    /** Estimated AP direction relative to where the phone points. */
    val relBearingRad: Double = 0.0,
    val phase: Phase = Phase.FIRST_READING,
    val locked: Boolean = false,
    /** Where the app wants the next reading taken (absolute, metres), and how to get there from here. */
    val waypoint: Waypoint? = null,
    val waypointRelRad: Double = 0.0,
    val waypointDistM: Double = 0.0,
    val trendDb: Double? = null,
    val lastReadingMs: Long = 0L,
    val scanIntervalMs: Long = WifiScanner.SCAN_INTERVAL_MS,
    val throttled: Boolean = false,
    val heightCm: Int = 170,
    val strideM: Double = 0.7,
    val radarSize: Float = 1f,
    val radarRangeM: Float = 0f,
    val steps: Int = 0,
    val headingSource: HeadingSource = HeadingSource.NONE,
    val autoDisableThrottle: Boolean = false,
    val compassEnabled: Boolean = true,
    val throttleStatus: ThrottleStatus = ThrottleStatus.NOT_REQUESTED,
    val adbCommand: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = AppSettings(app)
    private val throttle = ScanThrottleController(app, settings)
    private val scanner = WifiScanner(app)
    private val headingProvider = HeadingProvider(app)
    private val stepProvider = StepProvider(app)
    private val pdr = Pdr(Pdr.strideFromHeightM(settings.heightCm.toDouble()))
    private val filter = RssiFilter()
    private val locator = ApLocator()
    private val lockTracker = LockTracker()
    private val samples = ArrayList<Sample>()
    private var lastTimestampUs = -1L
    private var lastArrivalScanMs = 0L

    private val _state = MutableStateFlow(
        UiState(
            autoDisableThrottle = settings.autoDisableThrottle, compassEnabled = settings.compassEnabled, adbCommand = throttle.adbCommand,
            heightCm = settings.heightCm, strideM = pdr.stepLengthM, radarSize = settings.radarSize, radarRangeM = settings.radarRangeM,
        ),
    )
    val state: StateFlow<UiState> = _state

    init {
        headingProvider.compassAllowed = settings.compassEnabled
        viewModelScope.launch { headingProvider.source.collect { src -> _state.update { it.copy(headingSource = src) } } }
        viewModelScope.launch { scanner.results.collect { onScan(it) } }
        viewModelScope.launch { scanner.throttled.collect { t -> _state.update { it.copy(throttled = t) } } }
        viewModelScope.launch { scanner.lastResultAtMs.collect { t -> _state.update { it.copy(lastReadingMs = t) } } }
        viewModelScope.launch { headingProvider.heading.collect { h -> _state.update { refreshGuidance(it.copy(headingRad = h)) } } }
        viewModelScope.launch {
            stepProvider.steps.collect {
                pdr.onStep(headingProvider.heading.value)
                _state.update { refreshGuidance(it.copy(posX = pdr.x, posY = pdr.y, steps = it.steps + 1)) }
                requestScanIfArrived()
            }
        }
    }

    fun start() {
        applyThrottleSetting()
        scanner.start(); headingProvider.start(); stepProvider.start()
    }

    fun stop() {
        scanner.stop(); headingProvider.stop(); stepProvider.stop()
        throttle.restoreAsync() // leave the user's Developer options exactly as we found them
    }

    private fun applyThrottleSetting() {
        // May block on the Magisk Superuser prompt, so run it off the main thread.
        viewModelScope.launch {
            val status = throttle.sync(settings.autoDisableThrottle)
            val fast = status == ThrottleStatus.DISABLED_BY_APP || status == ThrottleStatus.ALREADY_OFF
            scanner.intervalMs = if (fast) FAST_INTERVAL_MS else WifiScanner.SCAN_INTERVAL_MS
            if (fast) scanner.clearThrottleWarning()
            _state.update { it.copy(throttleStatus = status, scanIntervalMs = scanner.intervalMs) }
        }
    }

    fun setAutoDisableThrottle(on: Boolean) {
        settings.autoDisableThrottle = on
        _state.update { it.copy(autoDisableThrottle = on) }
        applyThrottleSetting()
    }

    fun setCompassEnabled(on: Boolean) {
        settings.compassEnabled = on
        headingProvider.compassAllowed = on
        headingProvider.restart()
        _state.update { it.copy(compassEnabled = on) }
    }

    /** Re-checks permission state, e.g. after the user ran the adb command and came back. */
    fun refreshThrottleStatus() = applyThrottleSetting()

    fun select(n: ScanResultUi?) {
        resetTrail()
        _state.update { it.copy(selected = n, smoothedRssi = n?.rssi?.toDouble() ?: -100.0) }
        lastTimestampUs = -1L
        if (n != null) scanner.requestScan()
    }

    fun resetTrail() {
        samples.clear(); pdr.reset(); filter.reset(); locator.reset(); lockTracker.reset()
        _state.update {
            it.copy(
                samples = emptyList(), estimate = ApEstimate.NONE, posX = 0.0, posY = 0.0, steps = 0,
                phase = Phase.FIRST_READING, locked = false, waypoint = null, trendDb = null,
            )
        }
    }

    fun setHeightCm(cm: Int) {
        settings.heightCm = cm
        pdr.stepLengthM = Pdr.strideFromHeightM(cm.toDouble())
        _state.update { it.copy(heightCm = cm, strideM = pdr.stepLengthM) }
    }

    fun setRadarSize(f: Float) { settings.radarSize = f; _state.update { it.copy(radarSize = f) } }
    fun setRadarRange(m: Float) { settings.radarRangeM = m; _state.update { it.copy(radarRangeM = m) } }

    private fun onScan(list: List<ScanResultUi>) {
        val sel = _state.value.selected
        val match = sel?.let { s -> list.firstOrNull { it.bssid == s.bssid } }
        _state.update { it.copy(networks = list, selected = match ?: it.selected) }
        if (match == null || match.timestampUs == lastTimestampUs) return
        lastTimestampUs = match.timestampUs
        val smooth = filter.update(match.rssi.toDouble())
        samples.add(Sample(pdr.x, pdr.y, smooth, headingProvider.heading.value, System.currentTimeMillis()))
        if (samples.size > 300) samples.removeAt(0)
        val snapshot = samples.toList()
        viewModelScope.launch {
            val est = withContext(Dispatchers.Default) { locator.estimate(snapshot) }
            val locked = lockTracker.update(est, snapshot.size)
            val plan: Plan = Navigator.plan(snapshot, est, pdr.x, pdr.y, headingProvider.heading.value, locked)
            _state.update {
                refreshGuidance(
                    it.copy(
                        samples = snapshot, estimate = est, smoothedRssi = smooth, phase = plan.phase, locked = locked,
                        waypoint = plan.waypoint, trendDb = signalTrendDb(snapshot),
                    ),
                )
            }
        }
    }

    /** When the user reaches the suggested spot, ask for a scan right away instead of waiting for the timer. */
    private fun requestScanIfArrived() {
        val s = _state.value
        if (s.waypoint == null || s.waypointDistM >= ARRIVED_M) return
        val now = System.currentTimeMillis()
        if (now - lastArrivalScanMs < 8_000) return
        lastArrivalScanMs = now
        scanner.requestScan()
    }

    private fun refreshGuidance(s: UiState): UiState {
        val rel = relativeBearing(s.estimate.bearingWorldRad, s.headingRad)
        val wp = s.waypoint
        val wpRel = wp?.let { relativeBearing(atan2(it.x - s.posX, it.y - s.posY), s.headingRad) } ?: 0.0
        val wpDist = wp?.let { hypot(it.x - s.posX, it.y - s.posY) } ?: 0.0
        return s.copy(relBearingRad = rel, waypointRelRad = wpRel, waypointDistM = wpDist)
    }

    override fun onCleared() { stop() }

    companion object {
        private const val FAST_INTERVAL_MS = 6_000L
        const val ARRIVED_M = 1.0
    }
}
