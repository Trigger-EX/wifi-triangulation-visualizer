package com.wifitri.visualizer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifitri.visualizer.WifiCompassApp
import com.wifitri.visualizer.core.ApEstimate
import com.wifitri.visualizer.core.ApLocator
import com.wifitri.visualizer.core.MapBuilder
import com.wifitri.visualizer.core.Phase
import com.wifitri.visualizer.core.Sample
import com.wifitri.visualizer.core.relativeBearing
import com.wifitri.visualizer.data.AppSettings
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
 * What the WiFi and Bluetooth screens share: UI state on top of the app-wide [com.wifitri.visualizer.tracking.TrackingHub]
 * (sensors, position, samples). Subclasses own one radio and feed its readings in via [ingest].
 * Series ids are prefixed per radio ([prefix]) so both radios can live in one sample store.
 */
abstract class BaseTrackerViewModel(
    app: Application,
    kind: RadioKind,
    private val prefix: String,
    private val mergeRadiusM: Double,
    private val locatorFactory: () -> ApLocator,
) : AndroidViewModel(app) {
    protected val hub = (app as WifiCompassApp).hub
    protected val settings: AppSettings = hub.settings
    protected val engine = hub.engine
    private var acquired = false

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
        viewModelScope.launch { hub.heading.source.collect { src -> _state.update { it.copy(headingSource = src) } } }
        viewModelScope.launch { hub.heading.heading.collect { h -> _state.update { refreshGuidance(it.copy(headingRad = h)) } } }
        viewModelScope.launch {
            hub.position.collect { p ->
                val stepped = p.steps != _state.value.steps
                _state.update { refreshGuidance(it.copy(posX = p.x, posY = p.y, steps = p.steps)) }
                if (stepped) onStepTaken()
            }
        }
        viewModelScope.launch {
            hub.resets.collect {
                _state.update {
                    it.copy(
                        samples = emptyList(), estimate = ApEstimate.NONE, phase = Phase.FIRST_READING, locked = false, waypoint = null,
                        trendDb = null, sampleCounts = emptyMap(), totalReadings = 0, map = com.wifitri.visualizer.core.MapModel.EMPTY,
                        smoothedRssi = it.selected?.rssi?.toDouble() ?: -100.0,
                    )
                }
            }
        }
    }

    /** Stops/starts the radio scan (the list freezes and no samples are recorded while paused; steps still count). */
    protected abstract fun applyPaused(paused: Boolean)

    fun setScanPaused(p: Boolean) {
        _state.update { it.copy(scanPaused = p) }
        applyPaused(p)
    }

    /** Starts the shared sensors (reference-counted) then the radio. */
    open fun start() { if (!acquired) { acquired = true; hub.acquire() } }
    open fun stop() { if (acquired) { acquired = false; hub.release() } }
    protected open fun onStepTaken() {}

    /**
     * Picks the target. Nothing is cleared: samples for every network were being recorded all along, so a
     * network you already walked around is estimated straight away.
     */
    open fun select(n: ScanResultUi?) {
        engine.select(n?.let { prefix + it.bssid }, locatorFactory)
        val snap = engine.snapshot()
        _state.update {
            it.copy(
                selected = n, smoothedRssi = if (n == null) -100.0 else if (snap.isEmpty()) n.rssi.toDouble() else engine.smoothedRssi(),
                samples = snap, estimate = ApEstimate.NONE, phase = Phase.FIRST_READING, locked = false, waypoint = null, trendDb = null,
            )
        }
        if (n != null && snap.isNotEmpty()) recompute(snap)
    }

    /** The shared engine has one selection; call this when returning to this screen so it points at ours again. */
    fun reassertSelection() { _state.value.selected?.let { select(it) } }

    /** Clears all collected samples for BOTH radios and restarts the position origin where the user stands now. */
    fun resetAllSamples() = hub.resetAll()

    fun setCompassEnabled(on: Boolean) {
        hub.setCompassEnabled(on)
        _state.update { it.copy(compassEnabled = on) }
    }

    fun setHeightInches(inches: Int) {
        hub.setHeightInches(inches)
        _state.update { it.copy(heightIn = inches, strideM = engine.pdr.stepLengthM) }
    }

    fun setRadarSize(f: Float) { settings.radarSize = f; _state.update { it.copy(radarSize = f) } }
    fun setRadarRange(m: Float) { settings.radarRangeM = m; _state.update { it.copy(radarRangeM = m) } }

    private var lastCountsMs = 0L

    private fun ownCounts() = engine.counts().filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

    /** Records one RSSI reading for [id] at the current position; only the selected target triggers a re-estimate. */
    protected fun ingest(id: String, rssi: Double) {
        val now = System.currentTimeMillis()
        val isSelected = engine.addReading(prefix + id, rssi, hub.heading.heading.value, now, mergeRadiusM)
        if (now - lastCountsMs > 500) {
            lastCountsMs = now
            _state.update { it.copy(sampleCounts = ownCounts(), totalReadings = engine.totalReadings()) }
        }
        if (isSelected) recompute(engine.snapshot())
    }

    private fun recompute(snapshot: List<Sample>) {
        if (busy) { dirty = true; return }
        busy = true
        viewModelScope.launch {
            val est = withContext(Dispatchers.Default) { engine.estimate(snapshot) }
            val heading = hub.heading.heading.value
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

    /** Rebuilds the experimental map from everything collected so far, from BOTH radios. */
    fun refreshMap(heatId: String?) {
        val series = engine.allSeries(); val path = engine.path()
        viewModelScope.launch {
            val m = withContext(Dispatchers.Default) { MapBuilder.build(series, path, heatId) }
            _state.update { it.copy(map = m, totalReadings = engine.totalReadings()) }
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
