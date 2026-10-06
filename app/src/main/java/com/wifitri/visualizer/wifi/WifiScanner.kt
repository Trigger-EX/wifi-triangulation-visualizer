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

class WifiScanner(context: Context) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val _results = MutableStateFlow<List<ScanResultUi>>(emptyList())
    val results: StateFlow<List<ScanResultUi>> = _results
    private val _throttled = MutableStateFlow(false)
    val throttled: StateFlow<Boolean> = _throttled
    private val _lastResultAtMs = MutableStateFlow(0L)
    /** Wall-clock time of the most recent scan-results broadcast (0 = none yet). */
    val lastResultAtMs: StateFlow<Long> = _lastResultAtMs
    private var running = false

    /** Time between automatic scan requests; shortened when throttling is off. */
    @Volatile var intervalMs = SCAN_INTERVAL_MS

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)) _throttled.value = true
            publish()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            requestScan()
            handler.postDelayed(this, intervalMs)
        }
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
        @Suppress("DEPRECATION")
        val ok = try { wm.startScan() } catch (_: SecurityException) { false }
        if (!ok) _throttled.value = true
    }

    fun start() {
        if (running) return
        running = true
        app.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        publish() // show cached results immediately
        handler.post(ticker)
    }

    /** Called once throttling has been turned off, so the "throttled" warning clears. */
    fun clearThrottleWarning() { _throttled.value = false }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(ticker)
        try { app.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
    }

    companion object { const val SCAN_INTERVAL_MS = 30_000L }
}
