package com.wifitri.visualizer.core

fun relativeBearing(worldBearing: Double, heading: Double): Double = wrapAngle(worldBearing - heading)

/** Maps RSSI to 0 (weak, -95 dBm) .. 1 (strong, -35 dBm). */
fun rssiToColor01(rssi: Double): Double = ((rssi + 95.0) / 60.0).coerceIn(0.0, 1.0)

/** Change in smoothed RSSI over the last three readings (dB), or null if there are fewer than three. */
fun signalTrendDb(samples: List<Sample>): Double? {
    val n = samples.size
    return if (n >= 3) samples[n - 1].rssi - samples[n - 3].rssi else null
}
