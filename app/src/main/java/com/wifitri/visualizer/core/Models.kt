package com.wifitri.visualizer.core

/** One RSSI observation tagged with the user's dead-reckoned position (x = east, y = north, metres). */
data class Sample(val x: Double, val y: Double, val rssi: Double, val headingRad: Double, val tMs: Long)

enum class Method { NONE, GRADIENT, PATH_LOSS_FIT }

enum class Hint { WALK_MORE, TURN_LEFT, TURN_RIGHT, AHEAD, HOTTER, COLDER, VERY_CLOSE }

/** Estimated access point position and bearing (world frame, 0 = north, clockwise). */
data class ApEstimate(
    val x: Double,
    val y: Double,
    val bearingWorldRad: Double,
    val distanceM: Double,
    val confidence: Double,
    val method: Method,
) {
    companion object {
        val NONE = ApEstimate(0.0, 0.0, 0.0, 0.0, 0.0, Method.NONE)
    }
}
