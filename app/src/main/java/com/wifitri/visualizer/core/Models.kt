package com.wifitri.visualizer.core

import kotlin.math.PI

/** One RSSI observation tagged with the user's dead-reckoned position (x = east, y = north, metres). */
data class Sample(val x: Double, val y: Double, val rssi: Double, val headingRad: Double, val tMs: Long)

enum class Method { NONE, GRADIENT, PATH_LOSS_FIT }

/**
 * Estimated access point position and bearing (world frame, 0 = north, clockwise).
 *
 * [bearingSigmaRad] is the half-width of an approximate 95% interval around [bearingWorldRad]:
 * PI means "direction undetermined" (the signal differences are within measurement noise).
 */
data class ApEstimate(
    val x: Double,
    val y: Double,
    val bearingWorldRad: Double,
    val distanceM: Double,
    val confidence: Double,
    val method: Method,
    val bearingSigmaRad: Double = PI,
) {
    val directionKnown: Boolean get() = method != Method.NONE && bearingSigmaRad < PI * 0.95

    companion object {
        val NONE = ApEstimate(0.0, 0.0, 0.0, 0.0, 0.0, Method.NONE, PI)
    }
}
