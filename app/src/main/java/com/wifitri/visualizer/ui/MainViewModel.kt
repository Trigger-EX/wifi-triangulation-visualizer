package com.wifitri.visualizer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifitri.visualizer.core.ApEstimate
import com.wifitri.visualizer.core.ApLocator
import com.wifitri.visualizer.core.Hint
import com.wifitri.visualizer.core.Pdr
import com.wifitri.visualizer.core.RssiFilter
import com.wifitri.visualizer.core.Sample
import com.wifitri.visualizer.core.hint
import com.wifitri.visualizer.core.relativeBearing
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

data class UiState(
    val networks: List<ScanResultUi> = emptyList(),
    val selected: ScanResultUi? = null,
    val samples: List<Sample> = emptyList(),
    val estimate: ApEstimate = ApEstimate.NONE,
    val headingRad: Double = 0.0,
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val smoothedRssi: Double = -100.0,
    val relBearingRad: Double = 0.0,
    val hint: Hint = Hint.WALK_MORE,
    val throttled: Boolean = false,
    val stepLengthM: Double = 0.7,
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
    private val pdr = Pdr()
    private val filter = RssiFilter()
    private val locator = ApLocator()
    private val samples = ArrayList<Sample>()
    private var lastTimestampUs = -1L

    private val _state = MutableStateFlow(
        UiState(autoDisableThrottle = settings.autoDisableThrottle, compassEnabled = settings.compassEnabled, adbCommand = throttle.adbCommand),
    )
    val state: StateFlow<UiState> = _state

    init {
        headingProvider.compassAllowed = settings.compassEnabled
        viewModelScope.launch { headingProvider.source.collect { src -> _state.update { it.copy(headingSource = src) } } }
        viewModelScope.launch { scanner.results.collect { onScan(it) } }
        viewModelScope.launch { scanner.throttled.collect { t -> _state.update { it.copy(throttled = t) } } }
        viewModelScope.launch { headingProvider.heading.collect { h -> _state.update { refreshGuidance(it.copy(headingRad = h)) } } }
        viewModelScope.launch {
            stepProvider.steps.collect {
                pdr.onStep(headingProvider.heading.value)
                _state.update { it.copy(posX = pdr.x, posY = pdr.y, steps = it.steps + 1) }
            }
        }
    }

    fun start() {
        applyThrottleSetting()
        scanner.start(); headingProvider.start(); stepProvider.start()
    }

    fun stop() {
        scanner.stop(); headingProvider.stop(); stepProvider.stop()
        throttle.restore() // leave the user's Developer options exactly as we found them
    }

    private fun applyThrottleSetting() {
        val status = throttle.sync(settings.autoDisableThrottle)
        val fast = status == ThrottleStatus.DISABLED_BY_APP || status == ThrottleStatus.ALREADY_OFF
        scanner.intervalMs = if (fast) FAST_INTERVAL_MS else WifiScanner.SCAN_INTERVAL_MS
        if (fast) scanner.clearThrottleWarning()
        _state.update { it.copy(throttleStatus = status) }
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
        samples.clear(); pdr.reset(); filter.reset(); locator.reset()
        _state.update { it.copy(samples = emptyList(), estimate = ApEstimate.NONE, posX = 0.0, posY = 0.0, steps = 0, hint = Hint.WALK_MORE) }
    }

    fun setStepLength(m: Double) { pdr.stepLengthM = m; _state.update { it.copy(stepLengthM = m) } }

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
            _state.update { refreshGuidance(it.copy(samples = snapshot, estimate = est, smoothedRssi = smooth)) }
        }
    }

    private fun refreshGuidance(s: UiState): UiState {
        val rel = relativeBearing(s.estimate.bearingWorldRad, s.headingRad)
        val n = s.samples.size
        val trend = if (n >= 3) (s.samples[n - 1].rssi - s.samples[n - 3].rssi) / 2.0 else 0.0
        return s.copy(relBearingRad = rel, hint = hint(rel, s.estimate, trend, s.smoothedRssi))
    }

    override fun onCleared() { stop() }

    private companion object { const val FAST_INTERVAL_MS = 6_000L }
}
