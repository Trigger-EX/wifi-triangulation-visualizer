package com.wifitri.visualizer.core

fun relativeBearing(worldBearing: Double, heading: Double): Double = wrapAngle(worldBearing - heading)

/** Maps RSSI to 0 (cold, -95 dBm) .. 1 (hot, -35 dBm). */
fun rssiToColor01(rssi: Double): Double = ((rssi + 95.0) / 60.0).coerceIn(0.0, 1.0)

fun hint(rel: Double, est: ApEstimate, trendDbPerStep: Double, rssi: Double): Hint = when {
    rssi > -40.0 -> Hint.VERY_CLOSE
    est.method == Method.NONE -> Hint.WALK_MORE
    trendDbPerStep > 0.5 -> Hint.HOTTER
    trendDbPerStep < -0.5 -> Hint.COLDER
    Math.abs(rel) < Math.toRadians(20.0) -> Hint.AHEAD
    rel > 0 -> Hint.TURN_RIGHT
    else -> Hint.TURN_LEFT
}
