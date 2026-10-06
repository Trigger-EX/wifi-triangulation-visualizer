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
import com.wifitri.visualizer.sensors.HeadingProvider
import com.wifitri.visualizer.sensors.StepProvider
import com.wifitri.visualizer.wifi.ScanResultUi
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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = WifiScanner(app)
    private val headingProvider = HeadingProvider(app)
    private val stepProvider = StepProvider(app)
    private val pdr = Pdr()
    private val filter = RssiFilter()
    private val locator = ApLocator()
    private val samples = ArrayList<Sample>()
    private var lastTimestampUs = -1L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
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

    fun start() { scanner.start(); headingProvider.start(); stepProvider.start() }
    fun stop() { scanner.stop(); headingProvider.stop(); stepProvider.stop() }

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
}
