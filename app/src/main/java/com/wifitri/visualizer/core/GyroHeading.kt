package com.wifitri.visualizer.core

import kotlin.math.sqrt

/**
 * Compass-free heading: integrates the gyroscope's rotation about the gravity axis.
 * The heading starts at 0 (wherever the phone points when tracking starts), which is all the
 * relative-position frame needs. Drifts slowly (~1-2 deg/min on typical MEMS gyros).
 * Convention: increasing heading = clockwise seen from above, matching a compass.
 */
class GyroHeading {
    var heading = 0.0; private set
    private var ux = 0.0
    private var uy = 0.0
    private var uz = 1.0
    private var haveUp = false
    private var lastNs = -1L

    fun reset() { heading = 0.0; lastNs = -1L }

    /** Accelerometer at rest reads +g along "up", so the low-passed vector gives the up axis. */
    fun onAccel(ax: Double, ay: Double, az: Double) {
        if (!haveUp) { ux = ax; uy = ay; uz = az; haveUp = true; return }
        ux += 0.1 * (ax - ux); uy += 0.1 * (ay - uy); uz += 0.1 * (az - uz)
    }

    fun onGyro(wx: Double, wy: Double, wz: Double, tNs: Long): Double {
        val n = sqrt(ux * ux + uy * uy + uz * uz)
        if (lastNs >= 0 && haveUp && n > 1e-6) {
            val dt = (tNs - lastNs) * 1e-9
            if (dt > 0 && dt < 0.5) {
                val rate = (wx * ux + wy * uy + wz * uz) / n // rad/s, counter-clockwise positive
                heading = wrapAngle(heading - rate * dt)
            }
        }
        lastNs = tNs
        return heading
    }
}
