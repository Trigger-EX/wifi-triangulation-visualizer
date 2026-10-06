package com.wifitri.visualizer.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estimates the direction of an AP from (position, RSSI) samples, starting from just two readings.
 *
 * 1) Weighted plane fit of RSSI over position; the gradient points toward the AP. Its covariance
 *    (noise / geometry) gives an honest bearing uncertainty. With a straight-line walk only the
 *    along-track component is observable, so the best possible claim is "somewhere in this half-plane" (about +-90 deg).
 * 2) Once the walk has width, a log-distance path-loss model is fitted (grid search + Gauss-Newton)
 *    to get an actual position and a spatial uncertainty.
 */
class ApLocator(private val pathLossN: Double = 2.5) {
    private var lastBearing: Double? = null

    fun reset() { lastBearing = null }

    private companion object {
        const val P0_PRIOR = -40.0
        const val PRIOR_WEIGHT = 0.3
        /** Typical scatter of a single RSSI reading (dB) from multipath and body shadowing. */
        const val NOISE_DB = 3.0
        const val MIN_BASELINE_M = 2.0
    }

    fun estimate(samples: List<Sample>, nowMs: Long = samples.lastOrNull()?.tMs ?: 0L): ApEstimate {
        if (samples.size < 2 || extentOf(samples) < MIN_BASELINE_M) return ApEstimate.NONE
        val cur = samples.last()
        val spread = spread(samples)

        val grad = gradient(samples.takeLast(40), nowMs)
        // A near-straight walk leaves a mirror ambiguity across the line, so only fit once the path has width.
        val fit = if (samples.size >= 8 && spread >= 3.0 && minorSpread(samples) >= 1.0) pathLossFit(samples, nowMs) else null

        val useFit = fit != null && (grad == null || fit.confidence >= 0.5 * grad.confidence) && fit.confidence > 0.1
        val raw: ApEstimate = when {
            useFit -> fit!!.copy(bearingWorldRad = atan2(fit.x - cur.x, fit.y - cur.y), distanceM = hypot(fit.x - cur.x, fit.y - cur.y))
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
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxr = 0.0; var syr = 0.0; var srr = 0.0
        for (i in s.indices) {
            val dx = s[i].x - mx; val dy = s[i].y - my; val dr = s[i].rssi - mr
            sxx += w[i] * dx * dx; sxy += w[i] * dx * dy; syy += w[i] * dy * dy
            sxr += w[i] * dx * dr; syr += w[i] * dy * dr; srr += w[i] * dr * dr
        }
        val trace = sxx + syy
        if (trace < 1e-9) return null
        val cur = s.last()
        val det = sxx * syy - sxy * sxy

        if (det < 1e-3 * trace * trace) { // (near-)collinear walk, e.g. exactly two readings
            val theta = 0.5 * atan2(2 * sxy, sxx - syy)
            val ex = cos(theta); val ey = sin(theta)
            var stt = 0.0; var str = 0.0
            for (i in s.indices) {
                val t = (s[i].x - mx) * ex + (s[i].y - my) * ey
                stt += w[i] * t * t; str += w[i] * t * (s[i].rssi - mr)
            }
            if (stt < 1e-9) return null
            val slope = str / stt
            val z = abs(slope) / (NOISE_DB / sqrt(stt)) // slope in units of its own standard error
            val sgn = if (slope >= 0) 1.0 else -1.0
            val bearing = atan2(sgn * ex, sgn * ey)
            // Along-track direction is known, but the sideways offset is not: best case is the +-90 deg half-plane.
            val sigma = if (z < 1.0) PI else (PI / 2) * (1 - 0.25 * min(1.0, (z - 1) / 3))
            val conf = if (z < 1.0) 0.0 else min(1.0, z / 4) * 0.4
            val dist = 10.0.pow((-40.0 - cur.rssi) / 25.0)
            return ApEstimate(cur.x + dist * sin(bearing), cur.y + dist * cos(bearing), bearing, dist, conf, Method.GRADIENT, sigma)
        }

        val gx = (sxr * syy - syr * sxy) / det
        val gy = (syr * sxx - sxr * sxy) / det
        val n = s.size
        val ssRes = srr - gx * sxr - gy * syr
        val resStd = if (n > 3) sqrt(max(ssRes, 0.0) / (sw * (n - 3) / n)) else 0.0
        val sn2 = max(NOISE_DB, resStd).pow(2)
        val c11 = sn2 * syy / det; val c22 = sn2 * sxx / det; val c12 = -sn2 * sxy / det
        val mag = hypot(gx, gy)
        val bearing = atan2(gx, gy)
        val sigma: Double
        if (mag < 1e-9) sigma = PI else {
            val ux = gx / mag; val uy = gy / mag; val px = -uy; val py = ux
            val varPar = c11 * ux * ux + 2 * c12 * ux * uy + c22 * uy * uy
            val varPerp = c11 * px * px + 2 * c12 * px * py + c22 * py * py
            sigma = if (mag < 2 * sqrt(max(varPar, 0.0))) PI else min(PI, atan2(2 * sqrt(max(varPerp, 0.0)), mag))
        }
        val r2 = if (srr > 1e-9) (1 - ssRes / srr).coerceIn(0.0, 1.0) else 0.0
        val conf = (mag / 1.5).coerceIn(0.0, 1.0) * r2 * (1 - sigma / PI)
        val dist = 10.0.pow((-40.0 - cur.rssi) / 25.0)
        return ApEstimate(cur.x + dist * sin(bearing), cur.y + dist * cos(bearing), bearing, dist, conf, Method.GRADIENT, sigma)
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
        val cells = ArrayList<Triple<Double, Double, Double>>() // x, y, cost
        var best = Double.MAX_VALUE; var bx = 0.0; var by = 0.0
        var x = minX
        while (x <= maxX) {
            var y = minY
            while (y <= maxY) {
                val c = cost(x, y).first
                cells.add(Triple(x, y, c))
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
        val near = cells.filter { it.third <= best * 1.1 + 1e-9 }
        val amb = near.size.toDouble() / cells.size
        val posRms = sqrt(near.sumOf { (it.first - X).pow(2) + (it.second - Y).pow(2) } / near.size)
        val conf = exp(-rmse / 8.0) * (spread(s) / 6.0).coerceIn(0.0, 1.0) * (1 - min(1.0, amb * 5))
        val cur = s.last()
        val dist = hypot(X - cur.x, Y - cur.y)
        // angular uncertainty from the spatial uncertainty of the plausible AP positions, seen from here (~95%)
        val sigma = min(PI / 2, atan2(2 * (posRms + 0.5), max(dist, 1.0)))
        return ApEstimate(X, Y, atan2(X - cur.x, Y - cur.y), dist, conf, Method.PATH_LOSS_FIT, sigma)
    }
}
