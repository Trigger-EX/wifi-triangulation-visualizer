package com.wifitri.visualizer.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.wifitri.visualizer.ui.theme.*
import kotlinx.coroutines.delay
import java.io.File

class RecorderActions(val onStart: () -> Unit, val onStop: () -> Unit, val onMark: (String) -> Unit)

/** Records a session to a log file (readings, steps, headings, estimates, markers) and shares it. */
@Composable
fun RecorderBar(state: UiState, a: RecorderActions) {
    val ctx = LocalContext.current
    val rec = state.rec
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NavyCard).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!rec.active) {
            Text("SESSION RECORDER", color = Color.White.copy(0.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(
                "Records every reading, step, heading and estimate to a log file you can share, so the algorithm can be analysed and tuned offline.",
                color = Color.White.copy(0.7f), fontSize = 12.sp, lineHeight = 16.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("● Start recording", HotRed, Color.White) { a.onStart() }
                rec.lastFile?.let { f -> Pill("Share last recording", Color.White.copy(0.12f), Color.White) { share(ctx, f) } }
            }
        } else {
            val s = ((now - rec.startMs) / 1000).toInt()
            Text(
                "● REC  %d:%02d  ·  ${rec.events} events  ·  ${rec.markers} markers".format(s / 60, s % 60),
                color = HotRed, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("⚑ Mark spot", Color.White.copy(0.15f), Color.White) { a.onMark("mark") }
                Pill("📍 I'm at the AP", NeonOrange, Navy) { a.onMark("ap") }
                Pill("■ Stop & share", NeonCyan, Navy) { a.onStop(); rec.lastFile?.let { share(ctx, it) } }
            }
            Text(
                "Mark spot = tap while standing on a place you can describe to me (e.g. “corner of the sofa”). I'm at the AP = tap while standing right next to the router/access point being tracked.",
                color = Color.White.copy(0.6f), fontSize = 11.sp, lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Text(
        label, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private fun share(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, f.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
