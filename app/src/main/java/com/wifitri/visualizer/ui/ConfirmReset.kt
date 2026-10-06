package com.wifitri.visualizer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.wifitri.visualizer.ui.theme.*

/**
 * Wraps a "Reset samples" trigger with a confirmation prompt, because it throws away every network's collected
 * samples and the walked path. [trigger] receives a callback that opens the dialog.
 */
@Composable
fun ConfirmReset(state: UiState, onConfirm: () -> Unit, trigger: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    trigger { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = NavyCard,
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(0.8f),
            title = { Text("Reset all samples?") },
            text = {
                Text(
                    "This deletes all ${state.totalReadings} signal samples collected so far (every WiFi network and Bluetooth device) and the path you have walked. " +
                        "Your position origin restarts wherever you are standing now, and every network will need new samples. " +
                        "This can’t be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { open = false; onConfirm() }) { Text("Reset", color = HotRed) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel", color = NeonCyan) } },
        )
    }
}
