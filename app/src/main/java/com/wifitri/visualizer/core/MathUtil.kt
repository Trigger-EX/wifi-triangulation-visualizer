package com.wifitri.visualizer.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Wraps an angle to (-PI, PI]. */
fun wrapAngle(a: Double): Double {
    var r = a % (2 * PI)
    if (r > PI) r -= 2 * PI
    if (r <= -PI) r += 2 * PI
    return r
}

/** Blends two angles along the shortest arc. alpha = weight of [next]. */
fun blendAngle(prev: Double, next: Double, alpha: Double): Double =
    wrapAngle(prev + alpha * wrapAngle(next - prev))

fun circularMean(angles: List<Double>): Double =
    atan2(angles.sumOf { sin(it) }, angles.sumOf { cos(it) })

/** Solves a 3x3 linear system with Cramer's rule. Returns null if singular. */
fun solve3x3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    fun det(a: Array<DoubleArray>) =
        a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
    val d = det(m)
    if (abs(d) < 1e-12) return null
    return DoubleArray(3) { c ->
        val t = Array(3) { r -> DoubleArray(3) { k -> if (k == c) b[r] else m[r][k] } }
        det(t) / d
    }
}
