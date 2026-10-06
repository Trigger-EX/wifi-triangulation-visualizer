package com.wifitri.visualizer.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifitri.visualizer.core.rssiToColor01
import com.wifitri.visualizer.ui.theme.*
import com.wifitri.visualizer.wifi.ScanResultUi

/** Cold blue -> cyan -> lime -> orange -> hot red. */
fun heatColor(t: Float): Color {
    val stops = listOf(ColdBlue, NeonCyan, NeonLime, NeonOrange, HotRed)
    val x = t.coerceIn(0f, 1f) * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    return androidx.compose.ui.graphics.lerp(stops[i], stops[i + 1], x - i)
}

@Composable
fun NetworkListScreen(state: UiState, onSelect: (ScanResultUi) -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📡 Pick a network to hunt", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = NeonCyan, modifier = Modifier.weight(1f))
            Text("⚙", fontSize = 28.sp, color = Color.White, modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
        }
        if (state.throttled) ThrottleBanner()
        if (state.networks.isEmpty()) {
            Text("Scanning… (make sure WiFi and Location are on)", color = Color.White.copy(0.7f), modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(state.networks, key = { it.bssid }) { n ->
                NetworkCard(n, isStrongest = n == state.networks.first(), onClick = { onSelect(n) })
            }
        }
    }
}

@Composable
fun ThrottleBanner() {
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(NeonOrange.copy(0.35f), NeonMagenta.copy(0.35f)))).padding(12.dp),
    ) {
        Text(
            "⚠ Android limits scans to ~4 per 2 min. For faster updates: Developer options → turn off “Wi-Fi scan throttling”.",
            color = Color.White, fontSize = 12.sp,
        )
    }
}

@Composable
private fun NetworkCard(n: ScanResultUi, isStrongest: Boolean, onClick: () -> Unit) {
    val t = rssiToColor01(n.rssi.toDouble()).toFloat()
    val color = heatColor(t)
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pa",
    )
    Row(
        Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(NavyCard, color.copy(alpha = 0.22f))))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color).alpha(if (isStrongest) pulse else 1f))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(n.ssid, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White, maxLines = 1)
            Text("${n.bssid} · ${n.band}", fontSize = 11.sp, color = Color.White.copy(0.6f))
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(0.1f))) {
                Box(Modifier.fillMaxWidth(t.coerceAtLeast(0.04f)).height(6.dp).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(ColdBlue, color))))
            }
        }
        Text("${n.rssi}\ndBm", color = color, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
            modifier = Modifier.padding(start = 12.dp).width(48.dp))
    }
}
