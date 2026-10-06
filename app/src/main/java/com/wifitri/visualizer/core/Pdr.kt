package com.wifitri.visualizer.core

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Pedestrian dead reckoning: x = east, y = north. */
class Pdr(var stepLengthM: Double = 0.7) {
    var x = 0.0; private set
    var y = 0.0; private set

    fun onStep(headingRad: Double) {
        x += stepLengthM * sin(headingRad)
        y += stepLengthM * cos(headingRad)
    }

    fun reset() { x = 0.0; y = 0.0 }

    companion object {
        /** Typical walking stride is about 41.5% of body height. */
        fun strideFromHeightM(heightCm: Double): Double = 0.415 * heightCm / 100.0

        /** Weinberg step-length model from accel peak-to-peak. */
        fun stepLengthFromAccel(peakToPeak: Double): Double =
            (0.45 * peakToPeak.coerceAtLeast(0.0).pow(0.25)).coerceIn(0.4, 1.0)
    }
}
