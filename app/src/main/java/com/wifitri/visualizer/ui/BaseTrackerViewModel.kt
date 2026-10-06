package com.wifitri.visualizer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifitri.visualizer.core.ApEstimate
import com.wifitri.visualizer.core.Phase
import com.wifitri.visualizer.core.TrackerEngine
import com.wifitri.visualizer.core.relativeBearing
import com.wifitri.visualizer.data.AppSettings
import com.wifitri.visualizer.sensors.HeadingProvider
import com.wifitri.visualizer.sensors.StepProvider
import com.wifitri.visualizer.wifi.ScanResultUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Everything the WiFi and Bluetooth trackers share: heading + step sensors, the [TrackerEngine],
 * UI state and the display settings. Subclasses feed it radio readings via [ingest].
 */
abstract class BaseTrackerViewModel(app: Application, kind: RadioKind, mergeRadiusM: Double, locatorFactory: () -> com.wifitri.visualizer.core.ApLocator) :
    AndroidViewModel(app) {
    protected val settings = AppSettings(app)
    protected val headingProvider = HeadingProvider(app)
    protected val stepProvider = StepProvider(app)
    protected val engine = TrackerEngine(locatorFactory, settings.heightIn, mergeRadiusM)

    protected val _state = MutableStateFlow(
        UiState(
            kind = kind, compassEnabled = settings.compassEnabled, autoDisableThrottle = settings.autoDisableThrottle,
            heightIn = settings.heightIn, strideM = engine.pdr.stepLengthM, radarSize = settings.radarSize, radarRangeM = settings.radarRangeM,
        ),
    )
    val state: StateFlow<UiState> = _state

    private var busy = false
    private var dirty = false

    init {
        headingProvider.compassAllowed = settings.compassEnabled
        viewModelScope.launch { headingProvider.source.collect { src -> _state.update { it.copy(headingSource = src) } } }
        viewModelScope.launch { headingProvider.heading.collect { h -> _state.update { refreshGuidance(it.copy(headingRad = h)) } } }
        viewModelScope.launch {
            stepProvider.steps.collect {
                engine.onStep(headingProvider.heading.value)
                _state.update { refreshGuidance(it.copy(posX = engine.pdr.x, posY = engine.pdr.y, steps = it.steps + 1)) }
                onStepTaken()
            }
        }
    }

    /** Stops/starts the radio scan (the list freezes and no samples are recorded while paused; steps still count). */
    protected abstract fun applyPaused(paused: Boolean)

    fun setScanPaused(p: Boolean) {
        _state.update { it.copy(scanPaused = p) }
        applyPaused(p)
    }

    abstract fun start()
    abstract fun stop()
    protected open fun onStepTaken() {}

    /**
     * Picks the target. Nothing is cleared: samples for every network were being recorded all along, so a
     * network you already walked around is estimated straight away.
     */
    open fun select(n: ScanResultUi?) {
        engine.select(n?.bssid)
        val snap = engine.snapshot()
        _state.update {
            it.copy(
                selected = n, smoothedRssi = if (n == null) -100.0 else if (snap.isEmpty()) n.rssi.toDouble() else engine.smoothedRssi(),
                samples = snap, estimate = ApEstimate.NONE, phase = Phase.FIRST_READING, locked = false, waypoint = null, trendDb = null,
            )
        }
        if (n != null && snap.isNotEmpty()) recompute(snap)
    }

    /** Clears all collected samples for all networks and restarts the position origin where the user stands now. */
    fun resetAllSamples() {
        engine.resetAll()
        _state.update {
            it.copy(
                samples = emptyList(), estimate = ApEstimate.NONE, posX = 0.0, posY = 0.0, steps = 0,
                phase = Phase.FIRST_READING, locked = false, waypoint = null, trendDb = null,
                sampleCounts = emptyMap(), totalReadings = 0, smoothedRssi = it.selected?.rssi?.toDouble() ?: -100.0,
            )
        }
    }

    fun setCompassEnabled(on: Boolean) {
        settings.compassEnabled = on
        headingProvider.compassAllowed = on
        headingProvider.restart()
        _state.update { it.copy(compassEnabled = on) }
    }

    fun setHeightInches(inches: Int) {
        settings.heightIn = inches
        engine.setHeightInches(inches)
        _state.update { it.copy(heightIn = inches, strideM = engine.pdr.stepLengthM) }
    }

    fun setRadarSize(f: Float) { settings.radarSize = f; _state.update { it.copy(radarSize = f) } }
    fun setRadarRange(m: Float) { settings.radarRangeM = m; _state.update { it.copy(radarRangeM = m) } }

    private var lastCountsMs = 0L

    /** Records one RSSI reading for [id] at the current position; only the selected target triggers a re-estimate. */
    protected fun ingest(id: String, rssi: Double) {
        val now = System.currentTimeMillis()
        val isSelected = engine.addReading(id, rssi, headingProvider.heading.value, now)
        if (now - lastCountsMs > 500) {
            lastCountsMs = now
            _state.update { it.copy(sampleCounts = engine.counts(), totalReadings = engine.totalReadings()) }
        }
        if (isSelected) recompute(engine.snapshot())
    }

    private fun recompute(snapshot: List<com.wifitri.visualizer.core.Sample>) {
        if (busy) { dirty = true; return }
        busy = true
        viewModelScope.launch {
            val est = withContext(Dispatchers.Default) { engine.estimate(snapshot) }
            val heading = headingProvider.heading.value
            val r = engine.finish(est, snapshot, heading)
            _state.update {
                refreshGuidance(
                    it.copy(
                        samples = snapshot, estimate = est, smoothedRssi = engine.smoothedRssi(), phase = r.plan.phase, locked = r.locked,
                        waypoint = r.plan.waypoint, trendDb = r.trendDb,
                    ),
                )
            }
            busy = false
            if (dirty) { dirty = false; recompute(engine.snapshot()) }
        }
    }

    /** Rebuilds the experimental map from everything collected so far (cheap enough to call every couple of seconds). */
    fun refreshMap(heatId: String?) {
        val series = engine.allSeries(); val path = engine.path()
        viewModelScope.launch {
            val m = withContext(Dispatchers.Default) { com.wifitri.visualizer.core.MapBuilder.build(series, path, heatId) }
            _state.update { it.copy(map = m) }
        }
    }

    protected fun refreshGuidance(s: UiState): UiState {
        val rel = relativeBearing(s.estimate.bearingWorldRad, s.headingRad)
        val wp = s.waypoint
        val wpRel = wp?.let { relativeBearing(atan2(it.x - s.posX, it.y - s.posY), s.headingRad) } ?: 0.0
        val wpDist = wp?.let { hypot(it.x - s.posX, it.y - s.posY) } ?: 0.0
        return s.copy(relBearingRad = rel, waypointRelRad = wpRel, waypointDistM = wpDist)
    }

    override fun onCleared() { stop() }

    companion object { const val ARRIVED_M = 1.0 }
}
