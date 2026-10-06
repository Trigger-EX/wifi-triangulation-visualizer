package com.wifitri.visualizer.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class PathPoint(val x: Double, val y: Double)

/** A place where several networks changed more than free-space distance explains: probably a wall was crossed. */
data class WallTick(val x: Double, val y: Double, val dirX: Double, val dirY: Double, val score: Double, val networks: Int)

data class Wall(val ax: Double, val ay: Double, val bx: Double, val by: Double, val score: Double, val ticks: Int)
data class Doorway(val x: Double, val y: Double)
data class HeatCell(val x: Double, val y: Double, val rssi: Double)

data class MapModel(
    val path: List<PathPoint>,
    val heat: List<HeatCell>,
    val ticks: List<WallTick>,
    val walls: List<Wall>,
    val doorways: List<Doorway>,
    /** How many networks had enough data to take part in wall detection. */
    val networksUsed: Int,
) {
    companion object { val EMPTY = MapModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0) }
}

/**
 * EXPERIMENTAL. Builds a rough sketch from where you walked and how every network's signal changed along the way.
 *
 * What it can and can't do:
 *  - Walls are only inferred where you CROSSED them: between two consecutive readings, several networks all change by
 *    more than distance alone explains (a wall adds roughly 5-15 dB). Thin interior walls (about 3-5 dB) hide in normal
 *    signal fading and won't be seen.
 *  - A wall tick is placed perpendicular to your direction of travel, because that is all one crossing reveals.
 *    Aligned ticks from different crossings are joined into longer walls.
 *  - A doorway is a place where your path crossed an inferred wall line between two ticks with no signal jump.
 *  - Dead-reckoning drift (about 1-2% of distance) and signal fading (several dB) mean positions are approximate.
 */
object MapBuilder {
    private const val MIN_SEG_M = 0.8
    private const val MAX_SEG_M = 8.0
    private const val MAX_GAP_MS = 60_000L
    private const val CLUSTER_M = 1.6
    private const val EVENT_DB = 2.0
    private const val MIN_FRACTION = 0.3

    private class Seg(val mx: Double, val my: Double, val ux: Double, val uy: Double, val excess: Double, val id: String)
    private class Cluster(var cx: Double, var cy: Double, val segs: ArrayList<Seg> = ArrayList())

    fun build(series: Map<String, List<Sample>>, path: List<PathPoint>, heatId: String?): MapModel {
        val ticks = findTicks(series)
        val (walls, doors) = linkTicks(ticks, path)
        val heat = heatId?.let { series[it] }?.let { idwHeat(it) } ?: emptyList()
        return MapModel(path, heat, ticks, walls, doors, series.count { it.value.size >= 2 })
    }

    // ---- 1. wall-crossing evidence ----

    internal fun findTicks(series: Map<String, List<Sample>>): List<WallTick> {
        val segs = ArrayList<Seg>()
        for ((id, s) in series) {
            for (i in 1 until s.size) {
                val a = s[i - 1]; val b = s[i]
                val d = hypot(b.x - a.x, b.y - a.y)
                val dt = b.tMs - a.tMs
                if (d < MIN_SEG_M || d > MAX_SEG_M || dt <= 0 || dt > MAX_GAP_MS) continue
                // free space explains roughly 0.5 dB per metre; allow ~3 dB for fading on top
                val excess = max(0.0, abs(b.raw - a.raw) - (0.5 * d + 3.0))
                segs += Seg((a.x + b.x) / 2, (a.y + b.y) / 2, (b.x - a.x) / d, (b.y - a.y) / d, excess, id)
            }
        }
        // group segments that share a place (all networks of one scan share the same positions)
        val clusters = ArrayList<Cluster>()
        for (sg in segs) {
            val c = clusters.firstOrNull { hypot(it.cx - sg.mx, it.cy - sg.my) <= CLUSTER_M }
                ?: Cluster(sg.mx, sg.my).also { clusters += it }
            c.segs += sg
            c.cx = c.segs.sumOf { it.mx } / c.segs.size; c.cy = c.segs.sumOf { it.my } / c.segs.size
        }
        val out = ArrayList<WallTick>()
        for (c in clusters) {
            val perNet = HashMap<String, Double>()
            for (sg in c.segs) perNet[sg.id] = max(perNet[sg.id] ?: 0.0, sg.excess)
            val participating = perNet.size
            val events = perNet.values.count { it >= EVENT_DB }
            val strongest = perNet.values.maxOrNull() ?: 0.0
            val ok = (events >= 2 && events.toDouble() / participating >= MIN_FRACTION) || (participating <= 2 && strongest >= 8.0)
            if (!ok) continue
            // direction of travel through this place, sign-aligned
            val ref = c.segs.first()
            var ux = 0.0; var uy = 0.0
            for (sg in c.segs) { val sgn = if (sg.ux * ref.ux + sg.uy * ref.uy >= 0) 1.0 else -1.0; ux += sgn * sg.ux; uy += sgn * sg.uy }
            val n = hypot(ux, uy).takeIf { it > 1e-9 } ?: continue
            out += WallTick(c.cx, c.cy, ux / n, uy / n, perNet.values.filter { it >= EVENT_DB }.sum(), events)
        }
        return out
    }

    // ---- 2. join ticks into walls; find doorways ----

    private class Group(var cx: Double, var cy: Double, var nx: Double, var ny: Double, val ticks: ArrayList<WallTick> = ArrayList())

    internal fun linkTicks(ticks: List<WallTick>, path: List<PathPoint>): Pair<List<Wall>, List<Doorway>> {
        val groups = ArrayList<Group>()
        for (t in ticks.sortedByDescending { it.score }) {
            val g = groups.firstOrNull { g ->
                // same orientation (normals parallel or anti-parallel) and the tick lies on the group's line
                abs(g.nx * t.dirX + g.ny * t.dirY) >= cos(Math.toRadians(25.0)) &&
                    abs((t.x - g.cx) * g.nx + (t.y - g.cy) * g.ny) <= 1.2
            } ?: Group(t.x, t.y, t.dirX, t.dirY).also { groups += it }
            g.ticks += t
            val sgn = if (g.nx * t.dirX + g.ny * t.dirY >= 0) 1.0 else -1.0
            val m = hypot(g.nx * (g.ticks.size - 1) + sgn * t.dirX, g.ny * (g.ticks.size - 1) + sgn * t.dirY)
            if (m > 1e-9) { g.nx = (g.nx * (g.ticks.size - 1) + sgn * t.dirX) / m; g.ny = (g.ny * (g.ticks.size - 1) + sgn * t.dirY) / m }
            g.cx = g.ticks.sumOf { it.x } / g.ticks.size; g.cy = g.ticks.sumOf { it.y } / g.ticks.size
        }
        val walls = ArrayList<Wall>(); val doors = ArrayList<Doorway>()
        for (g in groups) {
            val wx = -g.ny; val wy = g.nx // direction along the wall
            val sorted = g.ticks.sortedBy { (it.x - g.cx) * wx + (it.y - g.cy) * wy }
            if (sorted.size == 1) {
                val t = sorted[0]
                walls += Wall(t.x - wx * 1.2, t.y - wy * 1.2, t.x + wx * 1.2, t.y + wy * 1.2, t.score, 1)
                continue
            }
            var start = sorted[0]; var count = 1; var score = sorted[0].score
            fun flush(end: WallTick) {
                walls += Wall(start.x - wx * 0.6 * (if (count == 1) 2 else 0), start.y - wy * 0.6 * (if (count == 1) 2 else 0),
                    end.x + wx * 0.6 * (if (count == 1) 2 else 0), end.y + wy * 0.6 * (if (count == 1) 2 else 0), score, count)
            }
            for (i in 1 until sorted.size) {
                val a = sorted[i - 1]; val b = sorted[i]
                val gap = hypot(b.x - a.x, b.y - a.y)
                val door = if (gap in 0.8..5.0) crossing(path, a.x, a.y, b.x, b.y) else null
                if (gap > 5.0 || door != null) {
                    flush(a)
                    if (door != null) doors += Doorway(door.first, door.second)
                    start = b; count = 1; score = b.score
                } else { count++; score += b.score }
            }
            flush(sorted.last())
        }
        return walls to doors
    }

    /** First point where the walked path crosses the segment (ax,ay)-(bx,by), if any. */
    private fun crossing(path: List<PathPoint>, ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double>? {
        for (i in 1 until path.size) {
            val p = path[i - 1]; val q = path[i]
            val r = segIntersect(p.x, p.y, q.x, q.y, ax, ay, bx, by)
            if (r != null) return r
        }
        return null
    }

    private fun segIntersect(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double, x4: Double, y4: Double): Pair<Double, Double>? {
        val d = (x2 - x1) * (y4 - y3) - (y2 - y1) * (x4 - x3)
        if (abs(d) < 1e-9) return null
        val t = ((x3 - x1) * (y4 - y3) - (y3 - y1) * (x4 - x3)) / d
        val u = ((x3 - x1) * (y2 - y1) - (y3 - y1) * (x2 - x1)) / d
        return if (t in 0.0..1.0 && u in 0.0..1.0) (x1 + t * (x2 - x1)) to (y1 + t * (y2 - y1)) else null
    }

    // ---- 3. signal heat map (inverse-distance weighting, no extrapolation) ----

    fun idwHeat(samples: List<Sample>, radiusM: Double = 3.0): List<HeatCell> {
        if (samples.isEmpty()) return emptyList()
        val minX = samples.minOf { it.x } - radiusM; val maxX = samples.maxOf { it.x } + radiusM
        val minY = samples.minOf { it.y } - radiusM; val maxY = samples.maxOf { it.y } + radiusM
        var cell = 0.5
        while (((maxX - minX) / cell) * ((maxY - minY) / cell) > 6000) cell *= 1.5
        val out = ArrayList<HeatCell>()
        var x = minX
        while (x <= maxX) {
            var y = minY
            while (y <= maxY) {
                var sw = 0.0; var sv = 0.0; var near = false
                for (s in samples) {
                    val d = hypot(s.x - x, s.y - y)
                    if (d > radiusM) continue
                    near = true
                    val w = 1.0 / (d * d + 0.25)
                    sw += w; sv += w * s.rssi
                }
                if (near) out += HeatCell(x, y, sv / sw)
                y += cell
            }
            x += cell
        }
        return out
    }
}
