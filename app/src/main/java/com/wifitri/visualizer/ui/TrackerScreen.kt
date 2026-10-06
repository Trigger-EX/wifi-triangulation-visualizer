package com.wifitri.visualizer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifitri.visualizer.core.Method
import com.wifitri.visualizer.core.Phase
import com.wifitri.visualizer.core.rssiToColor01
import com.wifitri.visualizer.sensors.HeadingSource
import com.wifitri.visualizer.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class TrackerActions(
    val onBack: () -> Unit,
    val onReset: () -> Unit,
    val onSettings: () -> Unit,
    val onHeightIn: (Int) -> Unit,
    val onRadarSize: (Float) -> Unit,
    val onRadarRange: (Float) -> Unit,
)

private fun deg(rad: Double) = Math.toDegrees(rad)

@Composable
fun TrackerScreen(state: UiState, actions: TrackerActions) {
    val sel = state.selected ?: return
    val heat = heatColor(rssiToColor01(state.smoothedRssi).toFloat())
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    val guide = guideFor(state)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 28.sp, color = NeonCyan, modifier = Modifier.clickable(onClick = actions.onBack).padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(sel.ssid, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                Text("${sel.bssid} · ${sel.band}", color = Color.White.copy(0.55f), fontSize = 11.sp)
            }
            Text("⚙", fontSize = 26.sp, color = Color.White, modifier = Modifier.clickable(onClick = actions.onSettings).padding(8.dp))
        }

        GuidanceCard(state, guide, heat, now)

        Compass(state, heat)
        EstimateReadout(state)
        HeadingChip(state)

        Label("RADAR · HEADS-UP")
        Radar(state)

        SettingsCard(state, actions)
        if (state.throttled && state.kind == RadioKind.WIFI) ThrottleBanner()
    }
}

// ---------- guidance text ----------

private class Guide(val title: String, val body: String)

private fun guideFor(s: UiState): Guide {
    val stride = s.strideM
    fun stepsFor(m: Double) = max(1, (m / stride).roundToInt())
    val ble = s.kind == RadioKind.BLUETOOTH
    val slow = !ble && s.scanIntervalMs >= 20_000
    val cadence = when {
        ble -> "continuously, several times a second"
        slow -> "about every 30 seconds (Android limits WiFi scans)"
        else -> "every few seconds"
    }
    val pause = if (ble) "pause for 2–3 seconds so the signal averages out" else "stand still until READINGS increases"
    val sigma = deg(s.estimate.bearingSigmaRad).roundToInt()
    val arrived = s.waypoint != null && s.waypointDistM < BaseTrackerViewModel.ARRIVED_M

    if (arrived && s.phase != Phase.LOCKED) {
        return Guide(
            "Hold still: waiting for a reading",
            if (ble) "You are at the suggested spot. Stand still for 2–3 seconds with the phone level and pointing forward, so the Bluetooth signal averages out. " +
                "Readings arrive $cadence and are merged while you stay put; a new reading is added once you move about half a metre."
            else "You are at the suggested spot. Stand still and keep the phone level and pointing forward until the READINGS counter goes up " +
                "(scans arrive $cadence). Each reading is tied to where you are standing when it arrives, so moving during a scan blurs the data." +
                if (slow) " For faster scans, enable “Disable WiFi scan throttling” in Settings (⚙)." else "",
        )
    }
    return when (s.phase) {
        Phase.FIRST_READING -> Guide(
            "Take a starting reading",
            "Stand still, hold the phone flat in front of you and point it the way you intend to walk first. " +
                (if (ble) "The app is listening to the device’s Bluetooth advertisements; the first reading appears within a second or two. "
                else "The app is waiting for the first WiFi scan to finish (scans arrive $cadence, and the first can take up to 30 seconds). ") +
                "Don’t move until the READINGS counter shows 1.",
        )
        Phase.BASELINE -> Guide(
            "Walk straight ahead about ${"%.0f".format(com.wifitri.visualizer.core.Navigator.BASELINE_M)} m",
            "Follow the arrow and walk in a straight line for roughly ${stepsFor(com.wifitri.visualizer.core.Navigator.BASELINE_M)} steps, then stop and $pause. " +
                "Comparing signal strength at two places gives the first (rough) direction guess. " +
                "Keep the phone flat and pointed forward while you walk, because steps are converted into distance using your height.",
        )
        Phase.ANGLE -> Guide(
            "Step sideways about ${"%.0f".format(com.wifitri.visualizer.core.Navigator.ANGLE_M)} m to cross-check",
            "A straight walk only shows whether the signal is rising or falling along your path; it can’t tell which side of the path the access point is on. " +
                "Turn and walk about ${stepsFor(com.wifitri.visualizer.core.Navigator.ANGLE_M)} steps toward the arrow (to the right of your previous path), then $pause. " +
                "A second angle lets the app triangulate. The wedge on the compass ring shows how unsure the guess is.",
        )
        Phase.REFINING -> Guide(
            "Refining: go to the marker",
            "The arrow points to the spot (the ring marker on the radar) where one more reading would improve the estimate the most. " +
                "Walk ${"%.1f".format(s.waypointDistM)} m (about ${stepsFor(s.waypointDistM)} steps), $pause, and repeat. " +
                "Lock-on happens when the direction is known to within ±20°" +
                if (s.estimate.directionKnown) " (currently ±$sigma°)." else ".",
        )
        Phase.LOCKED -> Guide(
            "Locked on: follow the arrow",
            "The arrow now points at the access point itself, with an uncertainty of ±$sigma°. Walk toward it and check back every few readings; " +
                "the signal should strengthen. Distance is only a rough guide indoors, because walls and people change signal strength a lot. " +
                "If readings stop making sense after a long walk, tap Reset samples to start over.",
        )
    }
}

@Composable
private fun Label(text: String) =
    Text(text, color = Color.White.copy(0.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)

@Composable
private fun GuidanceCard(s: UiState, g: Guide, heat: Color, now: Long) {
    val accent = if (s.phase == Phase.LOCKED) NeonLime else NeonCyan
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NavyCard).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..5) Box(
                Modifier.weight(1f).height(4.dp).clip(CircleShape)
                    .background(if (i <= s.phase.step) accent else Color.White.copy(0.12f)),
            )
        }
        Label((if (s.phase == Phase.LOCKED) "LOCKED ON" else "LOCK-ON · STEP ${s.phase.step} OF 5"))
        Text(g.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(g.body, fontSize = 13.sp, color = Color.White.copy(0.78f), lineHeight = 19.sp)

        val nextText = when {
            s.lastReadingMs == 0L -> "waiting"
            else -> {
                val left = ((s.lastReadingMs + s.scanIntervalMs - now) / 1000).toInt()
                if (left > 0) "~${left}s" else "due"
            }
        }
        val trend = s.trendDb
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("READINGS", "${s.samples.size}", Color.White)
            Stat("SIGNAL", "${s.smoothedRssi.roundToInt()} dBm", heat)
            Stat(
                "TREND",
                when {
                    trend == null -> "—"
                    abs(trend) < 1.0 -> "steady"
                    trend > 0 -> "↑ +${"%.1f".format(trend)} dB"
                    else -> "↓ ${"%.1f".format(trend)} dB"
                },
                Color.White,
            )
            if (s.kind == RadioKind.BLUETOOTH) Stat("SAMPLING", "live", Color.White) else Stat("NEXT SCAN", nextText, Color.White)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 10.sp, letterSpacing = 1.sp, color = Color.White.copy(0.5f), fontWeight = FontWeight.Bold)
        Text(value, fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HeadingChip(state: UiState) {
    val src = state.headingSource
    val col = if (src == HeadingSource.COMPASS) NeonLime else if (src == HeadingSource.NONE) NeonOrange else NeonCyan
    Text(
        "HEADING SOURCE · ${src.label.uppercase()}" + if (src == HeadingSource.COMPASS) "" else " · ${src.detail}",
        color = col, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(0.12f)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------- estimate readout ----------

private fun relText(relRad: Double): String {
    val d = deg(relRad)
    val a = abs(d).roundToInt()
    return when {
        a <= 5 -> "straight ahead"
        a >= 175 -> "directly behind you"
        d > 0 -> "$a° to your right"
        else -> "$a° to your left"
    }
}

@Composable
private fun EstimateReadout(s: UiState) {
    val e = s.estimate
    val (headline, detail, col) = when {
        e.method == Method.NONE -> Triple(
            "No direction estimate yet",
            "Needs at least two readings taken 2 m or more apart.", Color.White.copy(0.7f),
        )
        !e.directionKnown -> Triple(
            "Tentative: ${relText(s.relBearingRad)}",
            "Not reliable yet. The signal changed less than its normal ±3 dB fluctuation along your path, so the direction could be anywhere. " +
                "Follow the lock-on instructions to gather better data.", NeonOrange,
        )
        else -> Triple(
            "Access point: ${relText(s.relBearingRad)}  ±${deg(e.bearingSigmaRad).roundToInt()}°",
            "±° is an approximate 95% range. Confidence ${(e.confidence * 100).roundToInt()}%" +
                (if (e.method == Method.PATH_LOSS_FIT) " · distance ≈ ${e.distanceM.roundToInt()} m (rough)" else " · from signal gradient only") +
                if (s.locked) "" else " · not locked yet", if (s.locked) NeonLime else NeonCyan,
        )
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NavyCard).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Label("ESTIMATE")
        Text(headline, color = col, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(detail, color = Color.White.copy(0.7f), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

// ---------- compass ----------

@Composable
private fun animatedAngle(targetDeg: Float): Float {
    val holder = remember { floatArrayOf(targetDeg) }
    var d = (targetDeg - holder[0]) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    holder[0] += d
    val v by animateFloatAsState(holder[0], spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), label = "angle")
    return v
}

@Composable
private fun Compass(state: UiState, heat: Color) {
    val e = state.estimate
    val locking = state.phase.locking
    val hold = state.phase == Phase.FIRST_READING || (state.waypoint != null && state.waypointDistM < BaseTrackerViewModel.ARRIVED_M && locking)
    val showArrow = !hold
    val arrowTarget = (if (locking) deg(state.waypointRelRad) else deg(state.relBearingRad)).toFloat()
    val arrowDeg = animatedAngle(arrowTarget)
    val apDeg = animatedAngle(deg(state.relBearingRad).toFloat())
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        0.95f, 1.03f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "bobv",
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "pulsev",
    )
    val accent = if (state.phase == Phase.LOCKED) heat else NeonCyan
    val sigmaDeg = deg(e.bearingSigmaRad).toFloat()

    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val c = center
            val ringR = size.minDimension / 2 - 8f
            // base ring
            drawCircle(Color.White.copy(0.08f), ringR, c, style = Stroke(14f))
            // estimated AP direction + uncertainty
            if (e.method != Method.NONE) {
                if (e.directionKnown) {
                    val sweep = max(2f * sigmaDeg, 4f)
                    drawArc(
                        (if (state.locked) NeonLime else NeonCyan).copy(0.85f), apDeg - sweep / 2 - 90f, sweep, false,
                        topLeft = Offset(c.x - ringR, c.y - ringR), size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                        style = Stroke(14f, cap = StrokeCap.Butt),
                    )
                } else {
                    // direction undetermined: dashed ring + hollow tentative marker
                    drawCircle(NeonOrange.copy(0.55f), ringR, c, style = Stroke(6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 16f))))
                }
                val a = Math.toRadians(apDeg.toDouble())
                val p = Offset(c.x + ringR * sin(a).toFloat(), c.y - ringR * cos(a).toFloat())
                if (e.directionKnown) drawCircle(Color.White, 8f, p)
                else drawCircle(NeonOrange, 10f, p, style = Stroke(4f))
            }
            // soft pulse
            drawCircle(accent.copy(alpha = (1f - pulse) * 0.35f), ringR * (0.35f + 0.5f * pulse), c, style = Stroke(4f))
        }
        Canvas(Modifier.fillMaxSize().padding(46.dp)) {
            val c = center
            if (showArrow) {
                val r = size.minDimension / 2 * (if (locking) 1f else bob)
                rotate(arrowDeg, c) { arrow(c, r, locking, accent) }
            } else {
                drawCircle(NeonCyan.copy(0.15f), size.minDimension / 2 * 0.55f, c)
                drawCircle(NeonCyan, size.minDimension / 2 * 0.55f, c, style = Stroke(5f))
                val bar = size.minDimension * 0.07f
                drawRect(NeonCyan, Offset(c.x - bar * 1.6f, c.y - bar * 2f), androidx.compose.ui.geometry.Size(bar, bar * 4))
                drawRect(NeonCyan, Offset(c.x + bar * 0.6f, c.y - bar * 2f), androidx.compose.ui.geometry.Size(bar, bar * 4))
            }
            drawCircle(Navy, 12f, c); drawCircle(Color.White, 12f, c, style = Stroke(3f))
        }
        val caption = when {
            hold -> "HOLD STILL"
            locking -> "WALK ${"%.1f".format(state.waypointDistM)} m · ≈${max(1, (state.waypointDistM / state.strideM).roundToInt())} STEPS"
            else -> "ACCESS POINT" + if (e.method == Method.PATH_LOSS_FIT) " · ≈${e.distanceM.roundToInt()} m" else ""
        }
        Text(
            caption, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(50)).background(Navy.copy(0.85f)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

private fun DrawScope.arrow(c: Offset, r: Float, guide: Boolean, color: Color) {
    val path = Path().apply {
        moveTo(c.x, c.y - r)
        lineTo(c.x + r * 0.62f, c.y + r * 0.2f)
        lineTo(c.x + r * 0.2f, c.y + r * 0.05f)
        lineTo(c.x + r * 0.2f, c.y + r * 0.85f)
        lineTo(c.x - r * 0.2f, c.y + r * 0.85f)
        lineTo(c.x - r * 0.2f, c.y + r * 0.05f)
        lineTo(c.x - r * 0.62f, c.y + r * 0.2f)
        close()
    }
    drawPath(path, color.copy(0.2f), style = Stroke(width = 30f, cap = StrokeCap.Round))
    val second = if (guide) ColdBlue else NeonMagenta
    drawPath(path, Brush.verticalGradient(listOf(color, second), c.y - r, c.y + r))
    drawPath(path, Color.White.copy(0.9f), style = Stroke(width = 3f))
}

// ---------- radar ----------

private fun niceStep(range: Double): Double {
    val target = range / 4
    return listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0).firstOrNull { it >= target } ?: 50.0
}

private fun autoRange(s: UiState): Double {
    var m = 4.0
    s.samples.forEach { m = max(m, hypot(it.x - s.posX, it.y - s.posY) * 1.15) }
    s.waypoint?.let { m = max(m, hypot(it.x - s.posX, it.y - s.posY) * 1.25) }
    if (s.estimate.method == Method.PATH_LOSS_FIT) m = max(m, hypot(s.estimate.x - s.posX, s.estimate.y - s.posY).coerceAtMost(40.0) * 1.1)
    return m
}

@Composable
private fun Radar(state: UiState) {
    val range = if (state.radarRangeM > 0f) state.radarRangeM.toDouble() else autoRange(state)
    val lo = state.samples.minOfOrNull { it.rssi } ?: -100.0
    val hi = max(state.samples.maxOfOrNull { it.rssi } ?: -40.0, lo + 6.0) // colour scale spans this walk's own min..max
    val strongest = state.samples.maxByOrNull { it.rssi }
    val tm = rememberTextMeasurer()
    val ringStep = niceStep(range)
    val sweepT by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "sw",
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth(state.radarSize).aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF050818))) {
            val c = center
            val half = size.minDimension / 2
            val est = state.estimate
            val scale = (half * 0.92f / range).toFloat()
            val h = state.headingRad

            fun toScreen(wx: Double, wy: Double): Offset {
                val dx = wx - state.posX; val dy = wy - state.posY
                val rel = atan2(dx, dy) - h
                val d = hypot(dx, dy) * scale
                return Offset(c.x + (d * sin(rel)).toFloat(), c.y - (d * cos(rel)).toFloat())
            }

            var m = ringStep
            while (m * scale < half) { drawCircle(NeonCyan.copy(0.15f), (m * scale).toFloat(), c, style = Stroke(2f)); m += ringStep }
            drawLine(NeonCyan.copy(0.12f), Offset(c.x, 0f), Offset(c.x, size.height))
            drawLine(NeonCyan.copy(0.12f), Offset(0f, c.y), Offset(size.width, c.y))

            rotate(sweepT, c) { drawCircle(Brush.sweepGradient(listOf(Color.Transparent, NeonCyan.copy(0.18f)), c), half, c) }

            // AP direction wedge from the user (width = uncertainty)
            if (est.directionKnown) {
                val rel = deg(state.relBearingRad).toFloat()
                val sig = max(deg(est.bearingSigmaRad).toFloat(), 2f)
                drawArc(
                    (if (state.locked) NeonLime else NeonCyan).copy(0.16f), rel - sig - 90f, 2 * sig, true,
                    topLeft = Offset(c.x - half * 0.92f, c.y - half * 0.92f), size = androidx.compose.ui.geometry.Size(half * 1.84f, half * 1.84f),
                )
            }

            for (i in 1 until state.samples.size) {
                val a = state.samples[i - 1]; val b = state.samples[i]
                drawLine(Color.White.copy(0.18f), toScreen(a.x, a.y), toScreen(b.x, b.y), 3f)
            }
            state.samples.forEach {
                val col = ragColor(((it.rssi - lo) / (hi - lo)).toFloat())
                val p = toScreen(it.x, it.y)
                drawCircle(col.copy(0.35f), 16f, p); drawCircle(col, 8f, p)
            }

            // strongest reading: gold star in a ring, with a label
            if (strongest != null && state.samples.size >= 2) {
                val p = toScreen(strongest.x, strongest.y)
                val gold = Color(0xFFFFD700)
                drawCircle(gold.copy(0.18f), 34f, p)
                drawCircle(gold, 28f, p, style = Stroke(3f))
                star(p, 15f, gold)
                val layout = tm.measure("STRONGEST ${strongest.rssi.roundToInt()} dBm", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold))
                val lx = (p.x - layout.size.width / 2f).coerceIn(4f, size.width - layout.size.width - 4f)
                val ly = if (p.y - 44f - layout.size.height > 4f) p.y - 40f - layout.size.height else p.y + 40f
                drawRect(Color(0xCC050818), Offset(lx - 4f, ly - 2f), androidx.compose.ui.geometry.Size(layout.size.width + 8f, layout.size.height + 4f))
                drawText(layout, gold, Offset(lx, ly))
            }

            // where the app wants the next reading from
            state.waypoint?.let { w ->
                val p = toScreen(w.x, w.y)
                drawCircle(NeonCyan.copy(0.12f), 26f, p)
                drawCircle(NeonCyan, 26f, p, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                drawLine(NeonCyan, Offset(p.x - 12f, p.y), Offset(p.x + 12f, p.y), 3f)
                drawLine(NeonCyan, Offset(p.x, p.y - 12f), Offset(p.x, p.y + 12f), 3f)
                drawLine(NeonCyan.copy(0.5f), c, p, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f)))
            }

            // estimated AP (only once there is an actual position fit)
            if (est.method == Method.PATH_LOSS_FIT) {
                val p = toScreen(est.x, est.y)
                val unc = ((deg(est.bearingSigmaRad) / 90.0) * 6.0 + 0.8) * scale
                drawCircle(NeonOrange.copy(0.12f), unc.toFloat(), p)
                drawCircle(NeonOrange.copy(0.5f), unc.toFloat(), p, style = Stroke(2f))
                star(p, 20f, NeonOrange)
                star(p, 9f, Color.White)
            }

            val tri = Path().apply {
                moveTo(c.x, c.y - 18f); lineTo(c.x + 12f, c.y + 12f); lineTo(c.x, c.y + 5f); lineTo(c.x - 12f, c.y + 12f); close()
            }
            drawPath(tri, NeonLime); drawPath(tri, Color.White, style = Stroke(2f))
            val nPos = Offset(c.x + (half * 0.94f * sin(-h)).toFloat(), c.y - (half * 0.94f * cos(-h)).toFloat())
            drawCircle(HotRed, 9f, nPos)
        }
    }
    Text(
        "Rings every ${if (ringStep < 1) "%.1f".format(ringStep) else ringStep.roundToInt()} m · range ${range.roundToInt()} m · " +
            "cyan dashed ring = suggested next reading spot · red dot = start-up north",
        color = Color.White.copy(0.55f), fontSize = 11.sp, lineHeight = 15.sp,
    )
    if (state.samples.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${lo.roundToInt()} dBm", color = Color.White.copy(0.7f), fontSize = 11.sp)
            Box(
                Modifier.weight(1f).height(8.dp).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(ragColor(0f), ragColor(0.5f), ragColor(1f)))),
            )
            Text("${hi.roundToInt()} dBm", color = Color.White.copy(0.7f), fontSize = 11.sp)
        }
        Text(
            "Dots go from red (weakest reading on this walk) to green (strongest); the gold star marks the single strongest reading, " +
                "which is a good place to start looking.",
            color = Color.White.copy(0.55f), fontSize = 11.sp, lineHeight = 15.sp,
        )
    }
}

private fun DrawScope.star(center: Offset, r: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else r * 0.45f
        val a = -PI / 2 + i * PI / 5
        val p = Offset(center.x + (rad * cos(a)).toFloat(), center.y + (rad * sin(a)).toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color)
}

// ---------- calibration & display ----------

@Composable
private fun SettingsCard(state: UiState, a: TrackerActions) {
    val ft = state.heightIn / 12
    val inch = state.heightIn % 12
    val sliderColors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NavyCard).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label("CALIBRATION")
        Text("Your height: $ft′ $inch″", color = Color.White, fontWeight = FontWeight.SemiBold)
        Slider(
            value = state.heightIn.toFloat(), onValueChange = { a.onHeightIn(it.roundToInt()) },
            valueRange = 55f..83f, steps = 27, colors = sliderColors, // 4′ 7″ to 6′ 11″ in one-inch steps
        )
        Text(
            "Estimated stride: ${"%.2f".format(state.strideM)} m (a walking stride is about 41.5% of height). The app counts your steps and multiplies " +
                "by this to work out how far you moved, so a wrong height scales the whole map without changing directions much.",
            color = Color.White.copy(0.65f), fontSize = 12.sp, lineHeight = 17.sp,
        )

        Label("RADAR DISPLAY")
        Text("Radar size: ${(state.radarSize * 100).roundToInt()}% of screen width", color = Color.White, fontWeight = FontWeight.SemiBold)
        Slider(value = state.radarSize, onValueChange = a.onRadarSize, valueRange = 0.4f..1f, colors = sliderColors)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.radarRangeM > 0f) "Radar zoom: ${state.radarRangeM.roundToInt()} m radius" else "Radar zoom: automatic",
                color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Text("Auto", color = Color.White.copy(0.7f), fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            Switch(
                checked = state.radarRangeM <= 0f, onCheckedChange = { auto -> a.onRadarRange(if (auto) 0f else 10f) },
                colors = SwitchDefaults.colors(checkedTrackColor = NeonCyan, checkedThumbColor = Color.White),
            )
        }
        if (state.radarRangeM > 0f) {
            Slider(value = state.radarRangeM, onValueChange = a.onRadarRange, valueRange = 2f..60f, colors = sliderColors)
            Text("Smaller radius = everything looks bigger. Lower it to see short walks in detail.", color = Color.White.copy(0.65f), fontSize = 12.sp)
        }

        ConfirmReset(state, a.onReset) { open ->
            Text(
                "Reset samples", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(NeonMagenta, NeonOrange)))
                    .clickable(onClick = open).padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        Text(
            "${state.steps} steps counted · samples are collected from every ${if (state.kind == RadioKind.WIFI) "network" else "device"} in the background " +
                "(${state.totalReadings} so far), so switching targets reuses the walking you've already done. Position drifts over time, so reset if the trail stops matching reality.",
            color = Color.White.copy(0.55f), fontSize = 11.sp,
        )
    }
}
