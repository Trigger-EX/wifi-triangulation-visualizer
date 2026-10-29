package com.wifitri.visualizer.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ScanResultUi(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val freqMhz: Int,
    val timestampUs: Long,
) {
    val isBle: Boolean get() = freqMhz == 0

    val band: String get() = when {
        isBle -> "Bluetooth LE"
        freqMhz >= 5925 -> "6 GHz"
        freqMhz >= 4900 -> "5 GHz"
        else -> "2.4 GHz"
    }
}

/** How scan requests have fared; shown in Settings so the effect of "disable throttling" is measured, not assumed. */
data class ScanStats(val accepted: Int = 0, val refused: Int = 0, val currentIntervalMs: Long = WifiScanner.SCAN_INTERVAL_MS)

class WifiScanner(context: Context) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val _results = MutableStateFlow<List<ScanResultUi>>(emptyList())
    val results: StateFlow<List<ScanResultUi>> = _results
    private val _throttled = MutableStateFlow(false)
    /** True while Android has refused a scan request recently (within the last ~2 minutes). */
    val throttled: StateFlow<Boolean> = _throttled
    private val _stats = MutableStateFlow(ScanStats())
    val stats: StateFlow<ScanStats> = _stats
    private val _lastResultAtMs = MutableStateFlow(0L)
    /** Wall-clock time of the most recent scan-results broadcast (0 = none yet). */
    val lastResultAtMs: StateFlow<Long> = _lastResultAtMs
    private var running = false
    private var paused = false
    private var lastRefusedMs = 0L

    /**
     * When set, scans are requested every [FAST_INTERVAL_MS]. Android may still refuse them (foreground apps get about
     * 4 per 2 minutes while throttling is on), so after a refusal we back off for [BACKOFF_MS] and then probe again.
     * That way it works whether or not the phone's throttling setting could be read or changed.
     */
    @Volatile var fastMode = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)) noteRefused()
            if (!paused) publish()
        }
    }

    private fun backingOff() = lastRefusedMs != 0L && System.currentTimeMillis() - lastRefusedMs < BACKOFF_MS
    private fun interval() = if (fastMode && !backingOff()) FAST_INTERVAL_MS else SCAN_INTERVAL_MS

    private val ticker = object : Runnable {
        override fun run() {
            if (!running || paused) return
            requestScan()
            val iv = interval()
            _stats.update { it.copy(currentIntervalMs = iv) }
            _throttled.value = backingOff()
            handler.postDelayed(this, iv)
        }
    }

    private fun noteRefused() {
        lastRefusedMs = System.currentTimeMillis()
        _stats.update { it.copy(refused = it.refused + 1) }
        _throttled.value = true
    }

    @SuppressLint("MissingPermission")
    private fun publish() {
        _lastResultAtMs.value = System.currentTimeMillis()
        val list = try { wm.scanResults } catch (_: SecurityException) { emptyList() }
        _results.value = list.map {
            val ssid = if (Build.VERSION.SDK_INT >= 33) it.wifiSsid?.toString()?.trim('"').orEmpty() else @Suppress("DEPRECATION") it.SSID
            ScanResultUi(it.BSSID, ssid.ifBlank { "(hidden)" }, it.level, it.frequency, it.timestamp)
        }.sortedByDescending { it.rssi }
    }

    @SuppressLint("MissingPermission")
    fun requestScan() {
        if (paused) return
        @Suppress("DEPRECATION")
        val ok = try { wm.startScan() } catch (_: SecurityException) { false }
        if (ok) _stats.update { it.copy(accepted = it.accepted + 1) } else noteRefused()
    }

    fun start() {
        if (running) return
        running = true
        app.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        if (!paused) { publish(); handler.post(ticker) } // show cached results immediately
    }

    /** Pausing stops scan requests and freezes the list; resuming scans again at once. */
    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        handler.removeCallbacks(ticker)
        if (!p && running) { publish(); handler.post(ticker) }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(ticker)
        try { app.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
    }

    companion object {
        const val SCAN_INTERVAL_MS = 30_000L
        const val FAST_INTERVAL_MS = 6_000L
        const val BACKOFF_MS = 130_000L
    }
}
