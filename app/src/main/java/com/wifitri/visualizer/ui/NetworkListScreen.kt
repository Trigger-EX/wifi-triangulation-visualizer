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
import com.wifitri.visualizer.ble.BleStatus
import com.wifitri.visualizer.core.rssiToColor01
import com.wifitri.visualizer.ui.theme.*
import com.wifitri.visualizer.wifi.ScanResultUi

private val RagRed = Color(0xFFFF3B30)
private val RagAmber = Color(0xFFFFC107)
private val RagGreen = Color(0xFF30D158)

/** Red (weak) -> amber -> green (strong); t in 0..1. Used for radar samples. */
fun ragColor(t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) androidx.compose.ui.graphics.lerp(RagRed, RagAmber, x * 2) else androidx.compose.ui.graphics.lerp(RagAmber, RagGreen, (x - 0.5f) * 2)
}

/** Cold blue -> cyan -> lime -> orange -> hot red. */
fun heatColor(t: Float): Color {
    val stops = listOf(ColdBlue, NeonCyan, NeonLime, NeonOrange, HotRed)
    val x = t.coerceIn(0f, 1f) * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    return androidx.compose.ui.graphics.lerp(stops[i], stops[i + 1], x - i)
}

@Composable
fun NetworkListScreen(
    state: UiState,
    onSelect: (ScanResultUi) -> Unit,
    onSettings: () -> Unit,
    modeTabs: @Composable () -> Unit,
    onHideUnnamed: (Boolean) -> Unit = {},
    onResetAll: () -> Unit = {},
) {
    val ble = state.kind == RadioKind.BLUETOOTH
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ble) "📡 Pick a Bluetooth device" else "📡 Pick a network to hunt", fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold, color = NeonCyan, modifier = Modifier.weight(1f),
            )
            Text("⚙", fontSize = 28.sp, color = Color.White, modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { modeTabs() }
        if (ble) {
            val msg = when (state.bleStatus) {
                BleStatus.OK -> null
                BleStatus.BLUETOOTH_OFF -> "Bluetooth is off. Turn it on (quick settings) and the list will fill in."
                BleStatus.UNSUPPORTED -> "This device has no Bluetooth LE."
                BleStatus.NO_PERMISSION -> "Bluetooth permission missing: allow “Nearby devices” for this app in system settings."
                BleStatus.SCAN_FAILED -> "The Bluetooth scan failed to start. Toggle Bluetooth off and on and try again."
            }
            if (msg != null) InfoBanner(msg)
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Hide unnamed devices", color = Color.White.copy(0.8f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = state.hideUnnamed, onCheckedChange = onHideUnnamed,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = NeonCyan, checkedThumbColor = Color.White),
                )
            }
            Text(
                "Tip: phones and many wearables change their Bluetooth address every few minutes, so they can't be tracked for long. " +
                    "Trackers, beacons, speakers and earbuds keep a stable address and work best. Bluetooth readings arrive many times a second.",
                color = Color.White.copy(0.55f), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        } else if (state.throttled) ThrottleBanner()
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.totalReadings == 0) "Samples are collected from every ${if (ble) "device" else "network"} in the background as you walk, " +
                    "so any of them is ready to track later."
                else "Collected ${state.totalReadings} samples from ${state.sampleCounts.size} ${if (ble) "devices" else "networks"} so far, " +
                    "tagged with where you were. Walk around, then pick any one.",
                color = Color.White.copy(0.6f), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f),
            )
            if (state.totalReadings > 0) ConfirmReset(state, onResetAll) { open ->
                Text(
                    "Reset", color = HotRed, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(50)).background(HotRed.copy(0.15f))
                        .clickable(onClick = open).padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        if (state.networks.isEmpty()) {
            Text(
                if (ble) "Listening for Bluetooth devices…" else "Scanning… (make sure WiFi and Location are on)",
                color = Color.White.copy(0.7f), modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(state.networks, key = { it.bssid }) { n ->
                NetworkCard(n, isStrongest = n == state.networks.first(), samples = state.sampleCounts[n.bssid] ?: 0, onClick = { onSelect(n) })
            }
        }
    }
}

@Composable
private fun InfoBanner(text: String) {
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(NeonOrange.copy(0.25f)).padding(12.dp),
    ) { Text(text, color = Color.White, fontSize = 12.sp) }
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
private fun NetworkCard(n: ScanResultUi, isStrongest: Boolean, samples: Int, onClick: () -> Unit) {
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
            Text(
                "${n.bssid} · ${n.band}" + if (samples > 0) " · $samples samples" else "",
                fontSize = 11.sp, color = if (samples >= 8) NeonLime.copy(0.85f) else Color.White.copy(0.6f),
            )
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(0.1f))) {
                Box(Modifier.fillMaxWidth(t.coerceAtLeast(0.04f)).height(6.dp).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(ColdBlue, color))))
            }
        }
        Text("${n.rssi}\ndBm", color = color, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
            modifier = Modifier.padding(start = 12.dp).width(48.dp))
    }
}
