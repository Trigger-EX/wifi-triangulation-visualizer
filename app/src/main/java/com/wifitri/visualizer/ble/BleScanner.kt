package com.wifitri.visualizer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.wifitri.visualizer.wifi.ScanResultUi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class BleStatus { OK, BLUETOOTH_OFF, UNSUPPORTED, NO_PERMISSION, SCAN_FAILED }

/** One advertisement heard from a device. */
data class BleReading(val address: String, val rssi: Int, val tMs: Long)

/**
 * Continuous low-latency BLE scan. Unlike WiFi, advertisements arrive several times per second and are not
 * throttled in the same way, so the radar gets far denser data.
 */
class BleScanner(context: Context) {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private class Seen(var name: String, var rssi: Int, var lastSeenMs: Long, var tsUs: Long)
    private val seen = HashMap<String, Seen>()

    private val _devices = MutableStateFlow<List<ScanResultUi>>(emptyList())
    val devices: StateFlow<List<ScanResultUi>> = _devices
    private val _status = MutableStateFlow(BleStatus.OK)
    val status: StateFlow<BleStatus> = _status
    private val _readings = MutableSharedFlow<BleReading>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val readings: SharedFlow<BleReading> = _readings

    private var running = false
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) = handle(r)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) { _status.value = BleStatus.SCAN_FAILED; scanning = false }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { restartScan() }
    }

    private val publisher = object : Runnable {
        override fun run() {
            if (!running) return
            publish()
            handler.postDelayed(this, 500)
        }
    }

    private fun handle(r: ScanResult) {
        val now = System.currentTimeMillis()
        val addr = r.device.address
        val name = r.scanRecord?.deviceName.orEmpty() // device.name would need BLUETOOTH_CONNECT on Android 12+
        val e = seen.getOrPut(addr) { Seen(name, r.rssi, now, r.timestampNanos / 1000) }
        if (name.isNotEmpty()) e.name = name
        e.rssi = r.rssi; e.lastSeenMs = now; e.tsUs = r.timestampNanos / 1000
        _readings.tryEmit(BleReading(addr, r.rssi, now))
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value.lastSeenMs > STALE_MS }
        _devices.value = seen.map { (addr, e) ->
            ScanResultUi(addr, e.name.ifBlank { "Unnamed device" }, e.rssi, 0, e.tsUs)
        }.sortedByDescending { it.rssi }
    }

    @SuppressLint("MissingPermission")
    private fun restartScan() {
        stopScan()
        val a = adapter
        when {
            a == null -> _status.value = BleStatus.UNSUPPORTED
            !a.isEnabled -> _status.value = BleStatus.BLUETOOTH_OFF
            else -> try {
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                a.bluetoothLeScanner?.startScan(null, settings, callback)
                scanning = true
                _status.value = BleStatus.OK
            } catch (_: SecurityException) { _status.value = BleStatus.NO_PERMISSION }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) { }
        scanning = false
    }

    fun start() {
        if (running) return
        running = true
        app.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        restartScan()
        handler.post(publisher)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(publisher)
        stopScan()
        try { app.unregisterReceiver(stateReceiver) } catch (_: IllegalArgumentException) { }
    }

    private companion object { const val STALE_MS = 20_000L }
}
