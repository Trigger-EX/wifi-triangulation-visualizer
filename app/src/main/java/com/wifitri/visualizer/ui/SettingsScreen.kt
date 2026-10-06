package com.wifitri.visualizer.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifitri.visualizer.sensors.HeadingSource
import com.wifitri.visualizer.ui.theme.*
import com.wifitri.visualizer.wifi.ThrottleStatus

@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onAutoThrottle: (Boolean) -> Unit,
    onCompass: (Boolean) -> Unit,
    onRecheck: () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 28.sp, color = NeonCyan, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            Text("⚙ Settings", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }

        SettingCard(
            title = "⚡ Disable WiFi scan throttling",
            body = "Turns Developer options → “Wi-Fi scan throttling” off while the app is open (and restores it afterwards), " +
                "so scans arrive every ~6 s instead of ~every 30 s.",
            checked = state.autoDisableThrottle, onChecked = onAutoThrottle,
        ) {
            val (msg, col) = when (state.throttleStatus) {
                ThrottleStatus.NOT_REQUESTED -> "Off — Android's normal 4 scans / 2 min limit applies." to Color.White.copy(0.6f)
                ThrottleStatus.DISABLED_BY_APP -> "✅ Active — throttling is off while you use the app." to NeonLime
                ThrottleStatus.ALREADY_OFF -> "✅ Throttling is already off in Developer options." to NeonLime
                ThrottleStatus.UNSUPPORTED -> "⚠ This Android version/device has no scan-throttling setting." to NeonOrange
                ThrottleStatus.NEEDS_PERMISSION -> "⚠ One-time setup needed: Android only lets apps change this after a permission is granted over adb." to NeonOrange
            }
            Text(msg, color = col, fontSize = 13.sp)
            if (state.throttleStatus == ThrottleStatus.NEEDS_PERMISSION) {
                Text("Run this on a computer with the phone connected (USB debugging on):", color = Color.White.copy(0.75f), fontSize = 12.sp)
                Text(
                    state.adbCommand, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = NeonCyan,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(0.4f)).padding(10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Copy command") { clipboard.setText(AnnotatedString(state.adbCommand)) }
                    PillButton("I ran it — recheck", onRecheck)
                }
            }
            PillButton("Open Developer options") {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }

        SettingCard(
            title = "🧭 Use compass",
            body = "If the compass is missing, broken or disturbed (magnets, steel, cases), the app falls back automatically to a " +
                "gyroscope-based heading. Turn this off to force the compass-free methods.",
            checked = state.compassEnabled, onChecked = onCompass,
        ) {
            val (msg, col) = when (state.headingSource) {
                HeadingSource.COMPASS -> "Active: ${state.headingSource.label}" to NeonLime
                HeadingSource.NONE -> "Active: ${state.headingSource.label} — ${state.headingSource.detail}" to NeonOrange
                else -> "Active: ${state.headingSource.label} — ${state.headingSource.detail}" to NeonCyan
            }
            Text(msg, color = col, fontSize = 13.sp)
            Text(
                "Fallback order: compass → gyro + accelerometer → gyro only → straight-walk mode.",
                color = Color.White.copy(0.6f), fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SettingCard(title: String, body: String, checked: Boolean, onChecked: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NavyCard).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White, modifier = Modifier.weight(1f))
            Switch(
                checked = checked, onCheckedChange = onChecked,
                colors = SwitchDefaults.colors(checkedTrackColor = NeonMagenta, checkedThumbColor = Color.White),
            )
        }
        Text(body, color = Color.White.copy(0.75f), fontSize = 13.sp)
        content()
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(NeonMagenta, NeonOrange)))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
