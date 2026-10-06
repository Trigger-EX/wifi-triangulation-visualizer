package com.wifitri.visualizer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifitri.visualizer.ui.theme.*

enum class AppMode(val label: String) { WIFI("WiFi"), BLUETOOTH("Bluetooth"), MAP("Map β") }

/** Segmented control that switches between the WiFi and Bluetooth radar screens. */
@Composable
fun ModeTabs(mode: AppMode, onMode: (AppMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(NavyCard).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMode.values().forEach { k ->
            val on = k == mode
            Text(
                k.label, textAlign = TextAlign.Center, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, color = if (on) Navy else Color.White.copy(0.75f),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) NeonCyan else Color.Transparent)
                    .clickable { onMode(k) }.padding(vertical = 9.dp),
            )
        }
    }
}

/** Small switch used on the Map screen to choose which radio's data feeds the map. */
@Composable
fun SourceTabs(source: RadioKind, onSource: (RadioKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Data from:", color = Color.White.copy(0.6f), fontSize = 12.sp)
        RadioKind.values().forEach { k ->
            val on = k == source
            Text(
                k.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) Navy else Color.White.copy(0.75f),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) NeonCyan else NavyCard)
                    .clickable { onSource(k) }.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
