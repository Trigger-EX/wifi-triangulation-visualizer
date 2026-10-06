package com.wifitri.visualizer.core

/** 1-D Kalman filter that smooths jumpy RSSI readings. */
class RssiFilter(private val q: Double = 0.5, private val r: Double = 4.0) {
    private var x = 0.0
    private var p = 1.0
    private var init = false

    fun update(rssi: Double): Double {
        val z = rssi.coerceIn(-100.0, -20.0)
        if (!init) { x = z; p = 1.0; init = true; return x }
        p += q
        val k = p / (p + r)
        x += k * (z - x)
        p *= (1 - k)
        return x
    }

    fun reset() { init = false; p = 1.0 }
}
