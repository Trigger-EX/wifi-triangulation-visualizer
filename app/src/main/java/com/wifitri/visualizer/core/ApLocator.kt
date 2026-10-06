package com.wifitri.visualizer.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Estimates the direction of an AP from (position, RSSI) samples.
 * 1) weighted plane fit of RSSI over position -> gradient points toward the AP
 * 2) log-distance path-loss grid search + Gauss-Newton refinement for an actual position
 */
class ApLocator(private val pathLossN: Double = 2.5) {
    private var lastBearing: Double? = null

    fun reset() { lastBearing = null }

    private companion object {
        const val P0_PRIOR = -40.0
        const val PRIOR_WEIGHT = 0.3
    }

    fun estimate(samples: List<Sample>, nowMs: Long = samples.lastOrNull()?.tMs ?: 0L): ApEstimate {
        if (samples.size < 6) return ApEstimate.NONE
        val spread = spread(samples)
        if (spread < 1.5) return ApEstimate.NONE
        val cur = samples.last()

        val recent = samples.takeLast(40)
        val grad = gradient(recent, nowMs)
        // A near-straight walk leaves a mirror ambiguity across the line, so only fit once the path has width.
        val fit = if (samples.size >= 12 && spread >= 3.0 && minorSpread(samples) >= 1.0) pathLossFit(samples, nowMs) else null

        val useFit = fit != null && (grad == null || fit.confidence >= 0.5 * grad.confidence) && fit.confidence > 0.1
        val raw: ApEstimate = when {
            useFit -> {
                val f = fit!!
                f.copy(bearingWorldRad = atan2(f.x - cur.x, f.y - cur.y), distanceM = hypot(f.x - cur.x, f.y - cur.y))
            }
            grad != null -> grad
            else -> return ApEstimate.NONE
        }
        val bearing = lastBearing?.let { blendAngle(it, raw.bearingWorldRad, 0.3) } ?: raw.bearingWorldRad
        lastBearing = bearing
        return raw.copy(bearingWorldRad = bearing)
    }

    private fun spread(s: List<Sample>): Double {
        val mx = s.sumOf { it.x } / s.size
        val my = s.sumOf { it.y } / s.size
        return sqrt(s.sumOf { (it.x - mx).pow(2) + (it.y - my).pow(2) } / s.size)
    }

    private fun gradient(s: List<Sample>, now: Long): ApEstimate? {
        val w = s.map { exp(-(now - it.tMs).coerceAtLeast(0) / 1000.0 / 60.0) }
        val sw = w.sum()
        val mx = s.indices.sumOf { w[it] * s[it].x } / sw
        val my = s.indices.sumOf { w[it] * s[it].y } / sw
        val mr = s.indices.sumOf { w[it] * s[it].rssi } / sw
        // centred normal equations for rssi ~ gx*x + gy*y
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxr = 0.0; var syr = 0.0; var srr = 0.0
        for (i in s.indices) {
            val dx = s[i].x - mx; val dy = s[i].y - my; val dr = s[i].rssi - mr
            sxx += w[i] * dx * dx; sxy += w[i] * dx * dy; syy += w[i] * dy * dy
            sxr += w[i] * dx * dr; syr += w[i] * dy * dr; srr += w[i] * dr * dr
        }
        val det = sxx * syy - sxy * sxy
        if (abs(det) < 1e-9) return null
        val gx = (sxr * syy - syr * sxy) / det
        val gy = (syr * sxx - sxr * sxy) / det
        val ssRes = srr - gx * sxr - gy * syr
        val r2 = if (srr > 1e-9) (1 - ssRes / srr).coerceIn(0.0, 1.0) else 0.0
        val mag = hypot(gx, gy)
        val conf = (mag / 1.5).coerceIn(0.0, 1.0) * r2
        val cur = s.last()
        val dist = 10.0.pow((-40.0 - cur.rssi) / 25.0)
        val bearing = atan2(gx, gy)
        return ApEstimate(cur.x + dist * kotlin.math.sin(bearing), cur.y + dist * kotlin.math.cos(bearing),
            bearing, dist, conf, Method.GRADIENT)
    }

    private fun pathLossFit(s: List<Sample>, now: Long): ApEstimate? {
        val w = s.map { exp(-(now - it.tMs).coerceAtLeast(0) / 1000.0 / 120.0) + 0.05 }
        val k = 10.0 * pathLossN
        val minX = s.minOf { it.x } - 15; val maxX = s.maxOf { it.x } + 15
        val minY = s.minOf { it.y } - 15; val maxY = s.maxOf { it.y } + 15
        fun cost(px: Double, py: Double): Pair<Double, Double> {
            var sw = 0.0; var sp = 0.0
            val l = DoubleArray(s.size)
            for (i in s.indices) {
                l[i] = k * log10(max(hypot(s[i].x - px, s[i].y - py), 0.5))
                sp += w[i] * (s[i].rssi + l[i]); sw += w[i]
            }
            // soft prior: typical RSSI at 1 m is about -40 dBm, which stops far-away fits from absorbing noise into P0
            val lambda = PRIOR_WEIGHT * sw
            val p0 = (sp + lambda * P0_PRIOR) / (sw + lambda)
            var sse = lambda * (p0 - P0_PRIOR).pow(2)
            for (i in s.indices) { val e = s[i].rssi - (p0 - l[i]); sse += w[i] * e * e }
            return sse to p0
        }
        val costs = ArrayList<Double>()
        var best = Double.MAX_VALUE; var bx = 0.0; var by = 0.0
        var x = minX
        while (x <= maxX) {
            var y = minY
            while (y <= maxY) {
                val c = cost(x, y).first
                costs.add(c)
                if (c < best) { best = c; bx = x; by = y }
                y += 1.0
            }
            x += 1.0
        }
        // Gauss-Newton refine on (X, Y, P0)
        var X = bx; var Y = by; var P0 = cost(bx, by).second
        for (it in 0 until 20) {
            val a = Array(3) { DoubleArray(3) }; val b = DoubleArray(3)
            for (i in s.indices) {
                val dx = X - s[i].x; val dy = Y - s[i].y
                val d = max(hypot(dx, dy), 0.5)
                val res = s[i].rssi - (P0 - k * log10(d))
                val c = -k / (d * d * kotlin.math.ln(10.0))
                val j = doubleArrayOf(c * dx, c * dy, 1.0) // d(model)/d(X,Y,P0)
                for (r in 0..2) { b[r] += w[i] * j[r] * res; for (cc in 0..2) a[r][cc] += w[i] * j[r] * j[cc] }
            }
            for (r in 0..2) a[r][r] += 1e-3
            val step = solve3x3(a, b) ?: break
            if (hypot(step[0], step[1]) > 5.0) break // diverging: keep last good estimate
            X += step[0]; Y += step[1]; P0 += step[2]
            if (hypot(step[0], step[1]) < 0.01) break
        }
        val (sse, _) = cost(X, Y)
        val rmse = sqrt(sse / w.sum())
        val amb = costs.count { it <= best * 1.1 + 1e-9 }.toDouble() / costs.size
        val conf = exp(-rmse / 8.0) * (spread(s) / 6.0).coerceIn(0.0, 1.0) * (1 - min(1.0, amb * 5))
        val cur = s.last()
        return ApEstimate(X, Y, atan2(X - cur.x, Y - cur.y), hypot(X - cur.x, Y - cur.y), conf, Method.PATH_LOSS_FIT)
    }

    /** Std-dev of the walk along its narrow axis (smaller eigenvalue of the position covariance). */
    private fun minorSpread(s: List<Sample>): Double {
        val mx = s.sumOf { it.x } / s.size
        val my = s.sumOf { it.y } / s.size
        val a = s.sumOf { (it.x - mx).pow(2) } / s.size
        val c = s.sumOf { (it.y - my).pow(2) } / s.size
        val b = s.sumOf { (it.x - mx) * (it.y - my) } / s.size
        val l = (a + c) / 2 - sqrt(((a - c) / 2).pow(2) + b * b)
        return sqrt(l.coerceAtLeast(0.0))
    }
}
