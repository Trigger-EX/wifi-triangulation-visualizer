package com.wifitri.visualizer.core

import kotlin.math.sqrt

/** Accelerometer-based step detector, used when TYPE_STEP_DETECTOR is unavailable. */
class StepDetectorLogic(private val minIntervalMs: Long = 300, private val threshold: Double = 1.2) {
    private var lp = 9.81
    private var mean = 9.81
    private var prevAbove = false
    private var lastStep = Long.MIN_VALUE / 2

    fun onAccel(ax: Double, ay: Double, az: Double, tMs: Long): Boolean {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        lp += 0.2 * (mag - lp)
        mean += 0.02 * (mag - mean)
        val above = lp > mean + threshold
        val step = above && !prevAbove && tMs - lastStep >= minIntervalMs
        prevAbove = above
        if (step) lastStep = tMs
        return step
    }
}
