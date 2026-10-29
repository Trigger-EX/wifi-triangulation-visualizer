package com.wifitri.visualizer.core

import kotlin.math.hypot

/**
 * The radio-agnostic tracking pipeline shared by the WiFi and Bluetooth screens.
 *
 * It records signal samples for EVERY network/device heard (not just the selected one), each tagged with the
 * user's dead-reckoned position. Selecting a different target therefore reuses the walking already done: its
 * history is simply replayed through the estimator.
 *
 * One engine serves both radios (series ids carry a radio prefix), so WiFi and Bluetooth samples share one position and path.
 * `mergeRadiusM` > 0 in [addReading] folds readings taken within that distance of a series' previous sample into it
 * (used for Bluetooth, where advertisements arrive many times per second while you stand still).
 */
class TrackerEngine(heightInches: Int) {
    val pdr = Pdr(Pdr.strideFromHeightInches(heightInches))

    private class Series {
        val filter = RssiFilter()
        val samples = ArrayList<Sample>()
        var smoothed = -100.0
        var lastMs = 0L
    }

    private val series = HashMap<String, Series>()
    private var locatorFactory: () -> ApLocator = { ApLocator() }
    private var locator = locatorFactory()
    private val lock = LockTracker()
    private var selectedId: String? = null

    data class Result(val locked: Boolean, val plan: Plan, val trendDb: Double?)

    private val path = ArrayList<PathPoint>().apply { add(PathPoint(0.0, 0.0)) }

    /** Advances the dead-reckoned position by one step and extends the walked path (used by the map). */
    fun onStep(headingRad: Double) {
        pdr.onStep(headingRad)
        path.add(PathPoint(pdr.x, pdr.y))
        if (path.size > MAX_PATH) path.removeAt(0)
    }

    /** The series with the most samples (used to pick what the map's heat layer shows by default). */
    fun topSeries(): String? = series.maxByOrNull { it.value.samples.size }?.key

    fun path(): List<PathPoint> = path.toList()

    /** Every network's samples, for the map. */
    fun allSeries(): Map<String, List<Sample>> = series.mapValues { it.value.samples.toList() }

    fun setHeightInches(inches: Int) { pdr.stepLengthM = Pdr.strideFromHeightInches(inches) }

    /** Records a reading for [id] at the current position. Returns true if [id] is the selected target. */
    fun addReading(id: String, rssi: Double, headingRad: Double, tMs: Long, mergeRadiusM: Double = 0.0): Boolean {
        val s = series.getOrPut(id) { Series() }
        s.smoothed = s.filter.update(rssi)
        s.lastMs = tMs
        val last = s.samples.lastOrNull()
        if (last != null && mergeRadiusM > 0 && hypot(pdr.x - last.x, pdr.y - last.y) < mergeRadiusM) {
            s.samples[s.samples.size - 1] = last.copy(rssi = s.smoothed, tMs = tMs, headingRad = headingRad, raw = rssi)
        } else {
            s.samples.add(Sample(pdr.x, pdr.y, s.smoothed, headingRad, tMs, rssi))
            if (s.samples.size > MAX_SAMPLES) s.samples.removeAt(0)
        }
        if (series.size > MAX_SERIES) prune()
        return id == selectedId
    }

    private fun prune() {
        series.entries.sortedBy { it.value.lastMs }.take(series.size - MAX_SERIES).forEach { if (it.key != selectedId) series.remove(it.key) }
    }

    /** Switches the target; its stored history is used immediately. */
    fun select(id: String?, factory: () -> ApLocator = locatorFactory) {
        selectedId = id
        locatorFactory = factory
        locator = locatorFactory()
        lock.reset()
    }

    fun snapshot(id: String? = selectedId): List<Sample> = id?.let { series[it]?.samples?.toList() } ?: emptyList()
    fun smoothedRssi(id: String? = selectedId): Double = id?.let { series[it]?.smoothed } ?: -100.0

    /** Samples per network, for showing how much has already been collected. */
    fun counts(): Map<String, Int> = series.mapValues { it.value.samples.size }
    fun totalReadings(): Int = series.values.sumOf { it.samples.size }

    /** CPU-heavy; call off the main thread. */
    fun estimate(snapshot: List<Sample>): ApEstimate = locator.estimate(snapshot)

    fun finish(est: ApEstimate, snapshot: List<Sample>, headingRad: Double): Result {
        val locked = lock.update(est, snapshot.size)
        return Result(locked, Navigator.plan(snapshot, est, pdr.x, pdr.y, headingRad, locked), signalTrendDb(snapshot))
    }

    /** Forgets every sample for every network and restarts the position origin here. Keeps the selection. */
    fun resetAll() {
        series.clear(); pdr.reset(); path.clear(); path.add(PathPoint(0.0, 0.0)); locator = locatorFactory(); lock.reset()
    }

    private companion object {
        const val MAX_SAMPLES = 300
        const val MAX_SERIES = 400
        const val MAX_PATH = 4000
    }
}
