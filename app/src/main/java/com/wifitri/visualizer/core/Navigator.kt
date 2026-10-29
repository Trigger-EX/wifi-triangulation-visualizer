package com.wifitri.visualizer.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/** Where we are in the search. [step] is shown to the user as "step N of 5". */
enum class Phase(val step: Int) {
    FIRST_READING(1), BASELINE(2), ANGLE(3), REFINING(4), LOCKED(5);

    val locking: Boolean get() = this != LOCKED
}

data class Waypoint(val x: Double, val y: Double)

/** Where the app would like the next reading to be taken from ([waypoint] null = stay put or head for the AP). */
data class Plan(val phase: Phase, val waypoint: Waypoint?)

/**
 * "Locked on" = the direction is trustworthy: a tight, confident estimate. Uses hysteresis so the
 * state doesn't flicker as new readings arrive.
 */
class LockTracker(
    private val lockSigmaRad: Double = Math.toRadians(20.0),
    private val unlockSigmaRad: Double = Math.toRadians(35.0),
) {
    var locked = false; private set

    fun reset() { locked = false }

    fun update(est: ApEstimate, sampleCount: Int): Boolean {
        val usable = est.method == Method.PATH_LOSS_FIT || (est.method == Method.GRADIENT && sampleCount >= 8)
        locked = if (locked) usable && est.bearingSigmaRad <= unlockSigmaRad
        else usable && est.confidence >= 0.45 && est.bearingSigmaRad <= lockSigmaRad
        return locked
    }
}

object Navigator {
    const val BASELINE_M = 3.0
    const val ANGLE_M = 4.0

    /**
     * Chooses the phase and the next place to take a reading from.
     *  - no reading yet: stand still;  one: walk straight ~3 m;  a straight line only: step sideways ~4 m;
     *  - otherwise (refining): the candidate point that adds the most information about the AP position
     *    (D-optimal design on the path-loss model's Jacobian), with a mild penalty for walking far.
     */
    fun plan(samples: List<Sample>, est: ApEstimate, posX: Double, posY: Double, headingRad: Double, locked: Boolean): Plan {
        val n = samples.size
        if (n == 0) return Plan(Phase.FIRST_READING, null)
        if (n == 1) return Plan(Phase.BASELINE, Waypoint(posX + BASELINE_M * sin(headingRad), posY + BASELINE_M * cos(headingRad)))
        if (n == 2 || minorSpread(samples) < 1.0) {
            val a = samples.first(); val b = samples.last()
            val len = hypot(b.x - a.x, b.y - a.y)
            // unit vector along the walk; if the walk is degenerate use the current heading
            val ex = if (len > 0.5) (b.x - a.x) / len else sin(headingRad)
            val ey = if (len > 0.5) (b.y - a.y) / len else cos(headingRad)
            // right-hand perpendicular of the direction of travel
            return Plan(Phase.ANGLE, Waypoint(posX + ANGLE_M * ey, posY - ANGLE_M * ex))
        }
        if (locked) return Plan(Phase.LOCKED, null)
        return Plan(Phase.REFINING, bestCandidate(samples, est, posX, posY))
    }

    private fun row(px: Double, py: Double, ax: Double, ay: Double): DoubleArray {
        val dx = ax - px; val dy = ay - py
        val d = max(hypot(dx, dy), 1.0)
        val c = -25.0 / (d * d * ln(10.0))
        return doubleArrayOf(c * dx, c * dy, 1.0)
    }

    private fun bestCandidate(samples: List<Sample>, est: ApEstimate, posX: Double, posY: Double): Waypoint {
        val f = Array(3) { DoubleArray(3) }
        for (i in 0..2) f[i][i] = 1e-3
        for (s in samples) {
            val j = row(s.x, s.y, est.x, est.y)
            for (r in 0..2) for (c in 0..2) f[r][c] += j[r] * j[c]
        }
        var best = Waypoint(posX, posY); var bestScore = -Double.MAX_VALUE
        for (r in doubleArrayOf(2.5, 5.0)) for (k in 0 until 16) {
            val a = 2 * PI * k / 16
            val cx = posX + r * sin(a); val cy = posY + r * cos(a)
            val j = row(cx, cy, est.x, est.y)
            val g = Array(3) { rr -> DoubleArray(3) { cc -> f[rr][cc] + j[rr] * j[cc] } }
            val score = ln(abs(det3(g)) + 1e-12) - 0.03 * r
            if (score > bestScore) { bestScore = score; best = Waypoint(cx, cy) }
        }
        return best
    }
}
