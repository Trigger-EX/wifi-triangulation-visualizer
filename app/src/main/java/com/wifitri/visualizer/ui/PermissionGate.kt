package com.wifitri.visualizer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifitri.visualizer.ui.theme.*

@Composable
fun PermissionGate(
    body: String = "To scan networks and track your steps, the app needs Location (Android requires it for WiFi scans), " +
        "Nearby devices and Physical activity permissions. Nothing leaves your phone.",
    onGrant: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Navy, Color(0xFF2A0A4A), Color(0xFF05324A))))
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📡", fontSize = 72.sp)
        Text("WiFi Compass", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = NeonCyan)
        Text(
            body,
            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        Button(
            onClick = onGrant, shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
        ) { Text("Grant permissions", fontWeight = FontWeight.Bold) }
    }
}
