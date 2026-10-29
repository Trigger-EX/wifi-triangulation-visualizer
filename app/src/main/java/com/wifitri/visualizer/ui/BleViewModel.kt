package com.wifitri.visualizer.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.wifitri.visualizer.ble.BleScanner
import com.wifitri.visualizer.core.ApLocator
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bluetooth LE variant. Advertisements arrive continuously, so the median of the last few is taken (BLE RSSI is noisy)
 * and readings within 0.5 m of the previous sample are merged into it rather than piling up.
 */
class BleViewModel(app: Application) : BaseTrackerViewModel(
    app, RadioKind.BLUETOOTH, prefix = "b:", mergeRadiusM = 0.5,
    // BLE advertisers: typical RSSI at 1 m is about -60 dBm, free-space-like exponent, noisier readings.
    locatorFactory = { ApLocator(pathLossN = 2.2, p0Prior = -60.0, noiseDb = 3.5) },
) {
    private val scanner = BleScanner(app)
    private val recent = HashMap<String, ArrayDeque<Int>>()
    private val lastIngestMs = HashMap<String, Long>()
    private var allDevices = emptyList<com.wifitri.visualizer.wifi.ScanResultUi>()

    init {
        // Bluetooth scanning stays OFF until the user taps Start (it is not a purely passive mode on Android).
        _state.update { it.copy(scanPaused = true) }
        scanner.setPaused(true)
        viewModelScope.launch { scanner.devices.collect { allDevices = it; publishDevices() } }
        viewModelScope.launch { scanner.status.collect { st -> _state.update { it.copy(bleStatus = st) } } }
        viewModelScope.launch {
            scanner.readings.collect { r ->
                // every device is recorded in the background, not just the selected one
                val buf = recent.getOrPut(r.address) { ArrayDeque() }
                buf.addLast(r.rssi)
                while (buf.size > 5) buf.removeFirst()
                if (r.address == _state.value.selected?.bssid) _state.update { it.copy(lastReadingMs = r.tMs) }
                if (r.tMs - (lastIngestMs[r.address] ?: 0L) < INGEST_INTERVAL_MS || buf.size < 3) return@collect
                lastIngestMs[r.address] = r.tMs
                ingest(r.address, buf.sorted()[buf.size / 2].toDouble()) // median
                if (recent.size > 600) { recent.clear(); lastIngestMs.clear() }
            }
        }
    }

    private fun publishDevices() {
        val hide = _state.value.hideUnnamed
        val list = if (hide) allDevices.filter { it.ssid != "Unnamed device" } else allDevices
        val sel = _state.value.selected
        _state.update { it.copy(networks = list, selected = allDevices.firstOrNull { d -> d.bssid == sel?.bssid } ?: sel) }
    }

    fun setHideUnnamed(on: Boolean) { _state.update { it.copy(hideUnnamed = on) }; publishDevices() }

    override fun applyPaused(paused: Boolean) = scanner.setPaused(paused)

    override fun start() { super.start(); scanner.start() }
    override fun stop() { scanner.stop(); super.stop() }

    private companion object { const val INGEST_INTERVAL_MS = 300L }
}
