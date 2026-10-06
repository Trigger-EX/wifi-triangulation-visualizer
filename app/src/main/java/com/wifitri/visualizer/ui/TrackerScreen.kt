package com.wifitri.visualizer.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifitri.visualizer.core.Hint
import com.wifitri.visualizer.core.Method
import com.wifitri.visualizer.core.rssiToColor01
import com.wifitri.visualizer.core.wrapAngle
import com.wifitri.visualizer.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

@Composable
fun TrackerScreen(
    state: UiState,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onStepLength: (Double) -> Unit,
) {
    val sel = state.selected ?: return
    val heat = heatColor(rssiToColor01(state.smoothedRssi).toFloat())
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 28.sp, color = NeonCyan, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            Column {
                Text(sel.ssid, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("${state.smoothedRssi.toInt()} dBm · ${sel.band}", color = heat, fontWeight = FontWeight.Bold)
            }
        }
        HotColdBanner(state)

        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            PulsingRings(state.smoothedRssi, heat)
            ConfidenceRing(state.estimate.confidence.toFloat())
            CompassArrow(state, heat)
        }
        Text(
            when (state.estimate.method) {
                Method.NONE -> "Walk a few steps in an L-shape to calibrate"
                Method.GRADIENT -> "Direction from signal gradient · ~${state.estimate.distanceM.toInt()} m"
                Method.PATH_LOSS_FIT -> "Position fit · ~${state.estimate.distanceM.toInt()} m away"
            } + "  ·  confidence ${(state.estimate.confidence * 100).toInt()}%",
            color = Color.White.copy(0.75f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )

        Text("🛰 Radar (heads-up)", color = NeonCyan, fontWeight = FontWeight.Bold)
        RadarView(state)

        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(NavyCard).padding(14.dp)) {
            Text("Step length: ${"%.2f".format(state.stepLengthM)} m   ·   steps: ${state.steps}   ·   samples: ${state.samples.size}",
                color = Color.White.copy(0.8f), fontSize = 13.sp)
            Slider(
                value = state.stepLengthM.toFloat(), onValueChange = { onStepLength(it.toDouble()) }, valueRange = 0.4f..1.0f,
                colors = SliderDefaults.colors(thumbColor = NeonMagenta, activeTrackColor = NeonMagenta),
            )
            Text(
                "↺ Reset trail", color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(NeonMagenta, NeonOrange)))
                    .clickable(onClick = onReset).padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        if (state.throttled) ThrottleBanner()
    }
}

@Composable
private fun HotColdBanner(state: UiState) {
    val (text, color) = when (state.hint) {
        Hint.WALK_MORE -> "🚶 Walk a few steps to get a fix" to NeonCyan
        Hint.HOTTER -> "🔥 Hotter! Keep going" to HotRed
        Hint.COLDER -> "❄ Colder — turn around" to ColdBlue
        Hint.AHEAD -> "⬆ Straight ahead" to NeonLime
        Hint.TURN_LEFT -> "⬅ Turn left" to NeonMagenta
        Hint.TURN_RIGHT -> "Turn right ➡" to NeonMagenta
        Hint.VERY_CLOSE -> "🎯 You're right on top of it!" to NeonOrange
    }
    val c by animateColorAsState(color, tween(500), label = "hint")
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(c.copy(0.9f), c.copy(0.35f)))).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White) }
}

@Composable
private fun PulsingRings(rssi: Double, color: Color) {
    val speed = (3200 - 2200 * rssiToColor01(rssi)).toInt() // closer => faster
    val t by rememberInfiniteTransition(label = "rings").animateFloat(
        0f, 1f, infiniteRepeatable(tween(speed, easing = LinearEasing), RepeatMode.Restart), label = "r",
    )
    Canvas(Modifier.fillMaxSize()) {
        val maxR = size.minDimension / 2
        for (k in 0 until 3) {
            val p = (t + k / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.55f), radius = maxR * (0.35f + 0.65f * p), style = Stroke(width = 5f))
        }
    }
}

@Composable
private fun ConfidenceRing(confidence: Float) {
    val sweep by animateFloatAsState(confidence * 360f, tween(600), label = "conf")
    Canvas(Modifier.fillMaxSize().padding(10.dp)) {
        drawArc(Color.White.copy(0.08f), -90f, 360f, false, style = Stroke(16f))
        drawArc(
            Brush.sweepGradient(listOf(NeonMagenta, NeonOrange, NeonLime, NeonCyan, NeonMagenta)),
            -90f, sweep, false, style = Stroke(16f, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun CompassArrow(state: UiState, heat: Color) {
    val holder = remember { floatArrayOf(0f) }
    val target = Math.toDegrees(state.relBearingRad).toFloat()
    holder[0] += Math.toDegrees(wrapAngle(Math.toRadians((target - holder[0]).toDouble()))).toFloat()
    val deg by animateFloatAsState(holder[0], spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "arrow")
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        0.94f, 1.04f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "bobv",
    )
    val active = state.estimate.method != Method.NONE
    Canvas(Modifier.fillMaxSize().padding(48.dp)) {
        val c = center
        val r = size.minDimension / 2 * (if (active) bob else 1f)
        rotate(if (active) deg else 0f, c) {
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
            val col = if (active) heat else Color.White.copy(0.25f)
            drawPath(path, col.copy(0.25f), style = Stroke(width = 34f, cap = StrokeCap.Round)) // glow
            drawPath(path, Brush.verticalGradient(listOf(col, NeonMagenta), c.y - r, c.y + r))
            drawPath(path, Color.White, style = Stroke(width = 4f))
        }
        drawCircle(Navy, 14f, c); drawCircle(Color.White, 14f, c, style = Stroke(3f))
    }
}

@Composable
private fun RadarView(state: UiState) {
    val sweepT by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "sw",
    )
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color(0xFF050818))) {
        val c = center
        val half = size.minDimension / 2
        val est = state.estimate
        var maxD = 6.0
        state.samples.forEach { maxD = max(maxD, hypot(it.x - state.posX, it.y - state.posY)) }
        if (est.method != Method.NONE) maxD = max(maxD, hypot(est.x - state.posX, est.y - state.posY).coerceAtMost(40.0))
        val scale = (half * 0.9f / maxD).toFloat()
        val h = state.headingRad

        fun toScreen(wx: Double, wy: Double): Offset {
            val dx = wx - state.posX; val dy = wy - state.posY
            val rel = kotlin.math.atan2(dx, dy) - h
            val d = hypot(dx, dy) * scale
            return Offset(c.x + (d * sin(rel)).toFloat(), c.y - (d * cos(rel)).toFloat())
        }

        // grid rings every 2 m (4 m if zoomed out) and crosshair
        val ringStep = if (maxD > 16) 4.0 else 2.0
        var m = ringStep
        while (m * scale < half) {
            drawCircle(NeonCyan.copy(0.15f), (m * scale).toFloat(), c, style = Stroke(2f)); m += ringStep
        }
        drawLine(NeonCyan.copy(0.12f), Offset(c.x, 0f), Offset(c.x, size.height))
        drawLine(NeonCyan.copy(0.12f), Offset(0f, c.y), Offset(size.width, c.y))

        // rotating sweep
        rotate(sweepT, c) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, NeonCyan.copy(0.25f)), c), half, c)
        }

        // trail
        for (i in 1 until state.samples.size) {
            val a = state.samples[i - 1]; val b = state.samples[i]
            drawLine(Color.White.copy(0.18f), toScreen(a.x, a.y), toScreen(b.x, b.y), 3f)
        }
        state.samples.forEach {
            val col = heatColor(rssiToColor01(it.rssi).toFloat())
            val p = toScreen(it.x, it.y)
            drawCircle(col.copy(0.35f), 16f, p); drawCircle(col, 8f, p)
        }

        // estimated AP star + uncertainty
        if (est.method != Method.NONE) {
            val p = toScreen(est.x, est.y)
            val unc = ((1 - est.confidence) * 8.0 + 1.0) * scale
            drawCircle(NeonOrange.copy(0.12f), unc.toFloat(), p)
            drawCircle(NeonOrange.copy(0.5f), unc.toFloat(), p, style = Stroke(2f))
            star(p, 22f, NeonOrange.copy(0.35f), 1.7f)
            star(p, 22f, NeonOrange)
            star(p, 10f, Color.White)
            // pointer line from user to the estimate
            drawLine(NeonMagenta.copy(0.7f), c, p, 3f, cap = StrokeCap.Round)
        }

        // user marker (always pointing up)
        val tri = Path().apply {
            moveTo(c.x, c.y - 18f); lineTo(c.x + 12f, c.y + 12f); lineTo(c.x, c.y + 5f); lineTo(c.x - 12f, c.y + 12f); close()
        }
        drawPath(tri, NeonLime); drawPath(tri, Color.White, style = Stroke(2f))
        // north indicator
        val nPos = Offset(c.x + (half * 0.92f * sin(-h)).toFloat(), c.y - (half * 0.92f * cos(-h)).toFloat())
        drawCircle(HotRed, 9f, nPos)
    }
}

private fun DrawScope.star(center: Offset, r: Float, color: Color, glow: Float = 1f) {
    val path = Path()
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r * glow else r * 0.45f * glow
        val a = -PI / 2 + i * PI / 5
        val p = Offset(center.x + (rad * cos(a)).toFloat(), center.y + (rad * sin(a)).toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color)
}
