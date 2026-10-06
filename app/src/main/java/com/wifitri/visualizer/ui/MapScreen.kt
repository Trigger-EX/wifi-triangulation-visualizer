package com.wifitri.visualizer.ui

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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifitri.visualizer.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Experimental floor-plan sketch: walked path, interpolated signal heat map, inferred wall segments and doorways. */
@Composable
fun MapScreen(
    state: UiState,
    heatId: String?,
    heatName: String,
    onRefresh: (String?) -> Unit,
    modeTabs: @Composable () -> Unit,
    sourceTabs: @Composable () -> Unit,
    onSettings: () -> Unit,
) {
    // rebuild every couple of seconds while this screen is open
    LaunchedEffect(heatId) { while (true) { onRefresh(heatId); delay(2000) } }

    var showHeat by remember { mutableStateOf(true) }
    var showWalls by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(1f) }
    val m = state.map

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🗺 Map (experimental)", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = NeonCyan, modifier = Modifier.weight(1f))
            Text("⚙", fontSize = 28.sp, color = Color.White, modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
        }
        modeTabs()
        sourceTabs()

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NeonOrange.copy(0.18f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Experimental: a rough sketch, not a floor plan", color = NeonOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "The app can only tell where walls are by noticing that several networks’ signals jump at once as you walk through them. " +
                    "It only sees walls you actually cross, thin interior walls (drywall, about 3–5 dB) hide in normal signal fading, and your walked path drifts " +
                    "by 1–2% of the distance. Treat orange bars as “probably a wall near here”.",
                color = Color.White.copy(0.8f), fontSize = 12.sp, lineHeight = 17.sp,
            )
        }

        MapCanvas(state, showHeat, showWalls, zoom)

        // legend
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Legend(Color.White.copy(0.6f), "Your walked path (floor you know is open)")
            Legend(ragColor(0.9f), "Signal strength of $heatName, interpolated near where you walked (red weak → green strong)")
            Legend(NeonOrange, "Wall: several networks changed more than distance explains while you crossed here")
            Legend(NeonCyan, "Doorway candidate: you passed through an inferred wall line with no signal jump")
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NavyCard).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("MAP DATA", color = Color.White.copy(0.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(
                "${m.path.size} path points · ${state.totalReadings} signal samples from ${m.networksUsed} usable " +
                    "${if (state.kind == RadioKind.WIFI) "networks" else "devices"} · ${m.ticks.size} crossings flagged · ${m.walls.size} wall pieces · ${m.doorways.size} doorway candidates",
                color = Color.White.copy(0.8f), fontSize = 12.sp, lineHeight = 17.sp,
            )
            ToggleRow("Signal heat map", showHeat) { showHeat = it }
            ToggleRow("Walls & doorways", showWalls) { showWalls = it }
            Text("Zoom", color = Color.White, fontWeight = FontWeight.SemiBold)
            Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 0.5f..3f, colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan))
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NavyCard).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("HOW TO GET A BETTER SKETCH", color = Color.White.copy(0.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(
                "• Walk steadily through each room, doorway and corridor, and cross each wall you care about more than once, at different places.\\n" +
                    "• More networks help a lot: wall detection needs at least two networks to jump together. In a quiet area use Bluetooth (turn on its scan from the Bluetooth tab first), which gives far more readings.\\n" +
                    "• In ⚙ Settings, turn on “Disable WiFi scan throttling” so WiFi readings arrive every ~6 s instead of ~30 s; with slow scans you only get a reading every 20+ metres of walking.\\n" +
                    "• Hold the phone flat and pointing the way you walk; set your height on the Tracking screen so distances are right.\\n" +
                    "• Use Reset samples (on the network list) before mapping a new area.",
                color = Color.White.copy(0.75f), fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 8.dp, vertical = 4.dp))
        Text(text, color = Color.White.copy(0.7f), fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = NeonCyan, checkedThumbColor = Color.White))
    }
}

@Composable
private fun MapCanvas(state: UiState, showHeat: Boolean, showWalls: Boolean, zoom: Float) {
    val m = state.map
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF050818))) {
        // frame the walked path (and the user), then apply the zoom slider about the user
        val pts = m.path
        val minX = min(pts.minOfOrNull { it.x } ?: 0.0, state.posX) - 2.0
        val maxX = max(pts.maxOfOrNull { it.x } ?: 0.0, state.posX) + 2.0
        val minY = min(pts.minOfOrNull { it.y } ?: 0.0, state.posY) - 2.0
        val maxY = max(pts.maxOfOrNull { it.y } ?: 0.0, state.posY) + 2.0
        val span = max(max(maxX - minX, maxY - minY), 8.0)
        val base = (size.minDimension / span).toFloat()
        val scale = base * zoom
        val cx = ((minX + maxX) / 2).toFloat(); val cy = ((minY + maxY) / 2).toFloat()
        fun p(x: Double, y: Double) = Offset(center.x + ((x - cx) * scale).toFloat(), center.y - ((y - cy) * scale).toFloat())

        // 1 m grid
        val step = if (scale < 12f) 5.0 else 1.0
        var gx = Math.floor(minX / step) * step
        while (gx <= maxX + span) { drawLine(Color.White.copy(0.04f), p(gx, minY - span), p(gx, maxY + span)); gx += step }
        var gy = Math.floor(minY / step) * step
        while (gy <= maxY + span) { drawLine(Color.White.copy(0.04f), p(minX - span, gy), p(maxX + span, gy)); gy += step }

        if (showHeat && m.heat.isNotEmpty()) {
            val lo = m.heat.minOf { it.rssi }; val hi = max(m.heat.maxOf { it.rssi }, lo + 6.0)
            val cell = max(0.5f * scale, 3f)
            m.heat.forEach { c ->
                val o = p(c.x, c.y)
                drawRect(ragColor(((c.rssi - lo) / (hi - lo)).toFloat()).copy(0.32f), Offset(o.x - cell / 2, o.y - cell / 2), Size(cell, cell))
            }
        }

        // walked path, drawn wide so corridors look like floor
        for (i in 1 until pts.size) drawLine(Color.White.copy(0.28f), p(pts[i - 1].x, pts[i - 1].y), p(pts[i].x, pts[i].y), max(1.2f * scale, 4f), StrokeCap.Round)
        for (i in 1 until pts.size) drawLine(Color.White.copy(0.7f), p(pts[i - 1].x, pts[i - 1].y), p(pts[i].x, pts[i].y), 2f)

        if (showWalls) {
            m.ticks.forEach { t -> drawCircle(NeonOrange.copy(0.25f), 0.8f * scale, p(t.x, t.y)) }
            m.walls.forEach { w ->
                drawLine(NeonOrange.copy(0.25f), p(w.ax, w.ay), p(w.bx, w.by), 16f, StrokeCap.Round)
                drawLine(NeonOrange, p(w.ax, w.ay), p(w.bx, w.by), 7f, StrokeCap.Round)
            }
            m.doorways.forEach { d ->
                val o = p(d.x, d.y)
                drawCircle(NeonCyan.copy(0.2f), 22f, o)
                drawCircle(NeonCyan, 14f, o, style = Stroke(4f))
            }
        }

        // you
        val me = p(state.posX, state.posY)
        drawCircle(NeonLime.copy(0.3f), 18f, me); drawCircle(NeonLime, 8f, me)
        drawCircle(Color.White, 8f, me, style = Stroke(2f))
        // start-up "up" marker
        drawCircle(HotRed, 6f, Offset(size.width - 18f, 18f))
    }
    Text("Up = the direction your phone pointed when tracking started. Faint grid lines are 1 m apart (5 m when zoomed out).", color = Color.White.copy(0.5f), fontSize = 11.sp)
}
