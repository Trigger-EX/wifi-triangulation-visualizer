package com.wifitri.visualizer.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun model(x: Double, y: Double, ax: Double, ay: Double) =
        -40.0 - 25.0 * log10(maxOf(hypot(ax - x, ay - y), 0.5))

    @Test fun locatorFindsApAfterLWalk() {
        val rnd = Random(5)
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in 0..14) pts.add(0.0 to i * 0.7 * 1.0)
        for (i in 1..14) pts.add(i * 0.7 to 14 * 0.7)
        val s = pts.mapIndexed { i, p ->
            Sample(p.first, p.second, model(p.first, p.second, 10.0, 5.0) + rnd.nextGaussian() * 2, 0.0, i * 4000L)
        }
        val e = ApLocator().estimate(s)
        val cur = s.last()
        val truth = Math.atan2(10.0 - cur.x, 5.0 - cur.y)
        assertTrue("bearing ${e.bearingWorldRad} vs $truth", abs(wrapAngle(e.bearingWorldRad - truth)) < Math.toRadians(25.0))
        assertTrue(e.confidence > 0.0)
    }

    @Test fun singleSampleGivesNone() {
        val s = listOf(Sample(0.0, 0.0, -60.0, 0.0, 0L))
        assertEquals(Method.NONE, ApLocator().estimate(s).method)
    }

    @Test fun gradientPointsAlongLine() {
        val s = List(12) { Sample(0.0, it * 1.0, model(0.0, it * 1.0, 0.0, 30.0), 0.0, it * 1000L) }
        val e = ApLocator().estimate(s)
        assertTrue(abs(wrapAngle(e.bearingWorldRad)) < Math.toRadians(15.0))
    }

    @Test fun filterConverges() {
        val f = RssiFilter(); var v = 0.0
        repeat(50) { v = f.update(-60.0) }
        assertEquals(-60.0, v, 1e-6)
    }

    @Test fun pdrEast() {
        val p = Pdr(0.7); repeat(4) { p.onStep(PI / 2) }
        assertEquals(2.8, p.x, 1e-9); assertEquals(0.0, p.y, 1e-9)
    }

    @Test fun relativeBearingWraps() {
        assertEquals(Math.toRadians(-20.0), relativeBearing(Math.toRadians(350.0), Math.toRadians(10.0)), 1e-9)
    }

    @Test fun stepDetectorCountsSteps() {
        val d = StepDetectorLogic(); var n = 0
        for (i in 0 until 500) { // 100 Hz, 5 s, 2 Hz bounce
            val t = i * 10L
            if (d.onAccel(0.0, 0.0, 9.81 + 3 * sin(2 * PI * 2 * t / 1000.0), t)) n++
        }
        assertTrue("steps=$n", n in 8..12)
    }

    @Test fun gyroHeadingTracksClockwiseTurn() {
        val g = GyroHeading(); g.onAccel(0.0, 0.0, 9.81) // phone flat, up = +z
        var h = 0.0
        for (i in 0..100) h = g.onGyro(0.0, 0.0, -PI / 2, i * 10_000_000L) // clockwise from above, 90 deg/s for 1 s
        assertEquals(PI / 2, h, 0.02)
    }

    @Test fun gyroHeadingIgnoresTiltAxes() {
        val g = GyroHeading(); g.onAccel(0.0, 0.0, 9.81)
        var h = 0.0
        for (i in 0..100) h = g.onGyro(1.0, 1.0, 0.0, i * 10_000_000L)
        assertEquals(0.0, h, 1e-9)
    }

    @Test fun twoReadingsGiveTentativeButHonestGuess() {
        // 3 m walk toward an AP 12 m away changes RSSI by only ~3 dB: a guess is returned, but flagged as undetermined
        val s = listOf(
            Sample(0.0, 0.0, model(0.0, 0.0, 0.0, 12.0), 0.0, 0L),
            Sample(0.0, 3.0, model(0.0, 3.0, 0.0, 12.0), 0.0, 10_000L),
        )
        val e = ApLocator().estimate(s)
        assertEquals(Method.GRADIENT, e.method)
        assertTrue(abs(wrapAngle(e.bearingWorldRad)) < 0.01) // points along the walk, toward the stronger reading
        assertTrue(!e.directionKnown)
    }

    @Test fun twoReadingsWithBigChangeNarrowToHalfPlane() {
        val s = listOf(Sample(0.0, 0.0, -75.0, 0.0, 0L), Sample(0.0, 4.0, -60.0, 0.0, 10_000L))
        val e = ApLocator().estimate(s)
        assertTrue(e.directionKnown)
        // a straight line can never do better than the +-90 deg half-plane
        assertTrue(e.bearingSigmaRad > Math.toRadians(60.0))
    }

    @Test fun tooCloseReadingsGiveNothing() {
        val s = listOf(Sample(0.0, 0.0, -75.0, 0.0, 0L), Sample(0.0, 1.0, -60.0, 0.0, 10_000L))
        assertEquals(Method.NONE, ApLocator().estimate(s).method)
    }

    @Test fun uncertaintyShrinksWithBetterGeometry() {
        val rnd = Random(5)
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in 0..14) pts.add(0.0 to i * 0.7)
        for (i in 1..14) pts.add(i * 0.7 to 14 * 0.7)
        val all = pts.mapIndexed { i, p -> Sample(p.first, p.second, model(p.first, p.second, 10.0, 5.0) + rnd.nextGaussian() * 2, 0.0, i * 4000L) }
        val line = ApLocator().estimate(all.take(15))
        val full = ApLocator().estimate(all)
        assertTrue(full.bearingSigmaRad < line.bearingSigmaRad)
    }

    @Test fun plannerAsksForSidewaysLegAfterStraightWalk() {
        val s = listOf(Sample(0.0, 0.0, -60.0, 0.0, 0L), Sample(0.0, 3.0, -55.0, 0.0, 1000L))
        val plan = Navigator.plan(s, ApEstimate.NONE, 0.0, 3.0, 0.0, false)
        assertEquals(Phase.ANGLE, plan.phase)
        val w = plan.waypoint!!
        assertEquals(4.0, w.x, 1e-9) // right-hand side of a northbound walk is east
        assertEquals(3.0, w.y, 1e-9)
    }

    @Test fun plannerPhasesFollowReadingCount() {
        assertEquals(Phase.FIRST_READING, Navigator.plan(emptyList(), ApEstimate.NONE, 0.0, 0.0, 0.0, false).phase)
        val one = listOf(Sample(0.0, 0.0, -60.0, 0.0, 0L))
        val p = Navigator.plan(one, ApEstimate.NONE, 0.0, 0.0, PI / 2, false)
        assertEquals(Phase.BASELINE, p.phase)
        assertEquals(Navigator.BASELINE_M, p.waypoint!!.x, 1e-9) // heading east -> waypoint east
    }

    @Test fun lockTrackerNeedsTightEstimateAndHasHysteresis() {
        val t = LockTracker()
        val loose = ApEstimate(0.0, 0.0, 0.0, 5.0, 0.8, Method.PATH_LOSS_FIT, Math.toRadians(40.0))
        val tight = loose.copy(bearingSigmaRad = Math.toRadians(15.0))
        val mid = loose.copy(bearingSigmaRad = Math.toRadians(30.0))
        assertTrue(!t.update(loose, 20))
        assertTrue(t.update(tight, 20))
        assertTrue(t.update(mid, 20)) // stays locked inside the hysteresis band
        assertTrue(!t.update(loose, 20))
    }

    @Test fun strideFromHeight() {
        assertEquals(0.7055, Pdr.strideFromHeightM(170.0), 1e-4)
        assertEquals(0.6008, Pdr.strideFromHeightInches(57), 1e-3) // 4′ 9″
        assertTrue(Pdr.strideFromHeightInches(64) < Pdr.strideFromHeightInches(76)) // taller -> longer stride
    }

    @Test fun engineMergesNearbyReadingsWhenAsked() {
        val e = TrackerEngine({ ApLocator() }, 67, mergeRadiusM = 0.5)
        e.select("a")
        e.addReading("a", -60.0, 0.0, 0L); e.addReading("a", -58.0, 0.0, 100L)
        assertEquals(1, e.snapshot().size) // same spot: merged
        repeat(3) { e.pdr.onStep(0.0) } // walk ~2 m
        e.addReading("a", -55.0, 0.0, 200L)
        assertEquals(2, e.snapshot().size)
        val plain = TrackerEngine({ ApLocator() }, 67)
        plain.select("a")
        plain.addReading("a", -60.0, 0.0, 0L); plain.addReading("a", -58.0, 0.0, 100L)
        assertEquals(2, plain.snapshot().size) // WiFi mode: every scan is a sample
    }

    @Test fun engineRecordsEveryNetworkAndKeepsHistoryAcrossSelection() {
        val e = TrackerEngine({ ApLocator() }, 67)
        e.select("A")
        for (i in 0 until 6) {
            assertEquals(i >= 0, e.addReading("A", -60.0 - i, 0.0, i * 1000L)) // selected
            assertTrue(!e.addReading("B", -70.0 + i, 0.0, i * 1000L)) // background network
            repeat(3) { e.pdr.onStep(0.0) }
        }
        assertEquals(6, e.snapshot("A").size)
        e.select("B") // switching target must not need new samples
        assertEquals(6, e.snapshot().size)
        assertEquals(setOf("A", "B"), e.counts().keys)
        assertEquals(12, e.totalReadings())
        // the history carries real positions, so B can be estimated at once
        assertTrue(e.estimate(e.snapshot()).method != Method.NONE)
    }

    @Test fun resetClearsEverythingButKeepsSelection() {
        val e = TrackerEngine({ ApLocator() }, 67)
        e.select("A")
        e.addReading("A", -60.0, 0.0, 0L); e.addReading("B", -70.0, 0.0, 0L)
        repeat(4) { e.pdr.onStep(0.0) }
        e.resetAll()
        assertEquals(0, e.totalReadings())
        assertTrue(e.snapshot().isEmpty())
        assertEquals(0.0, e.pdr.y, 1e-9)
        assertTrue(e.addReading("A", -61.0, 0.0, 10L)) // still the selected target
    }

    @Test fun bleParametersShiftDistanceEstimate() {
        // same RSSI means a nearer device under the BLE prior (-60 dBm at 1 m) than under the WiFi prior (-40 dBm)
        val s = listOf(Sample(0.0, 0.0, -75.0, 0.0, 0L), Sample(0.0, 4.0, -60.0, 0.0, 10_000L))
        val wifi = ApLocator().estimate(s)
        val ble = ApLocator(2.2, -60.0, 3.5).estimate(s)
        assertTrue(ble.distanceM < wifi.distanceM)
    }
}
