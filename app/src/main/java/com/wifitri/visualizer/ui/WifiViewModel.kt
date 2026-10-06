package com.wifitri.visualizer.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.wifitri.visualizer.core.ApLocator
import com.wifitri.visualizer.wifi.ScanResultUi
import com.wifitri.visualizer.wifi.ScanThrottleController
import com.wifitri.visualizer.wifi.ThrottleStatus
import com.wifitri.visualizer.wifi.WifiScanner
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WifiViewModel(app: Application) : BaseTrackerViewModel(app, RadioKind.WIFI, mergeRadiusM = 0.0, { ApLocator() }) {
    private val throttle = ScanThrottleController(app, settings)
    private val scanner = WifiScanner(app)
    private val lastTimestampUs = HashMap<String, Long>()
    private var lastArrivalScanMs = 0L

    init {
        _state.update { it.copy(adbCommand = throttle.adbCommand) }
        viewModelScope.launch { scanner.results.collect { onScan(it) } }
        viewModelScope.launch { scanner.throttled.collect { t -> _state.update { it.copy(throttled = t) } } }
        viewModelScope.launch { scanner.lastResultAtMs.collect { t -> _state.update { it.copy(lastReadingMs = t) } } }
    }

    override fun start() {
        applyThrottleSetting()
        scanner.start(); headingProvider.start(); stepProvider.start()
    }

    override fun stop() {
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

    /** Re-checks permission state, e.g. after the user ran the adb command and came back. */
    fun refreshThrottleStatus() = applyThrottleSetting()

    override fun select(n: ScanResultUi?) {
        super.select(n)
        if (n != null) scanner.requestScan()
    }

    private fun onScan(list: List<ScanResultUi>) {
        val sel = _state.value.selected
        val match = sel?.let { s -> list.firstOrNull { it.bssid == s.bssid } }
        _state.update { it.copy(networks = list, selected = match ?: it.selected) }
        // Record EVERY network at the current position, not just the selected one.
        for (r in list) {
            if (lastTimestampUs[r.bssid] == r.timestampUs) continue // same scan result as last time
            lastTimestampUs[r.bssid] = r.timestampUs
            ingest(r.bssid, r.rssi.toDouble())
        }
    }

    /** When the user reaches the suggested spot, ask for a scan right away instead of waiting for the timer. */
    override fun onStepTaken() {
        val s = _state.value
        if (s.waypoint == null || s.waypointDistM >= ARRIVED_M) return
        val now = System.currentTimeMillis()
        if (now - lastArrivalScanMs < 8_000) return
        lastArrivalScanMs = now
        scanner.requestScan()
    }

    private companion object { const val FAST_INTERVAL_MS = 6_000L }
}
