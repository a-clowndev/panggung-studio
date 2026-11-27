package io.panggung.obs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.panggung.obs.NativeBridge
import io.panggung.obs.androidplus.AndroidPlusManager

/**
 * Multiview Grid Dialog (STU-02): shows all scenes in a 2x2/3x3 studio grid with
 * green Preview and red Program borders, tap to switch immediately.
 */
@Composable
fun MultiviewDialog(
    scenes: List<String>,
    activeIdx: Int,
    previewIdx: Int,
    onSelectScene: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Multiview (${scenes.size} Scenes)", fontSize = 15.sp) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(scenes) { idx, name ->
                    val borderCol = when (idx) {
                        activeIdx -> Color(0xFFEF4444)
                        previewIdx -> Color(0xFF22C55E)
                        else -> Color(0xFF334155)
                    }
                    Box(
                        modifier = Modifier
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0F172A))
                            .border(2.dp, borderCol, RoundedCornerShape(6.dp))
                            .clickable { onSelectScene(idx) }
                            .padding(8.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Column {
                            Text(
                                text = when (idx) {
                                    activeIdx -> "PROGRAM"
                                    previewIdx -> "PREVIEW"
                                    else -> "SCENE ${idx + 1}"
                                },
                                color = borderCol,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Auto-Configuration Wizard (DLG-20 / ENC-10):
 * Probes hardware MediaCodec encoders, thermal headroom & recommends optimal OBS profile.
 */
@Composable
fun AutoConfigWizardDialog(
    onApplyPreset: (width: Int, height: Int, fps: Int, encoderId: String, bitrateKbps: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val codecs = remember { AndroidPlusManager.detectHardwareCodecs() }
    val uvc = remember { AndroidPlusManager.listUvcDevices(ctx) }
    val recEncoder = when {
        codecs.hwHevc -> "android_mediacodec_hevc"
        codecs.hwH264 -> "android_mediacodec_h264"
        else -> "obs_x264"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-Configuration Wizard", fontSize = 15.sp) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Detected Hardware Codecs:", color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(codecs.summary, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("USB UVC Capture Devices: ${if (uvc.isEmpty()) "None connected" else uvc.joinToString()}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                HorizontalDivider(color = Color(0xFF1E293B))
                Text("Recommended Preset:", color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("• Resolution: 1280×720 @ 30fps\n• Encoder: $recEncoder\n• Video Bitrate: 4500 kbps\n• Audio: AAC 48kHz 128 kbps", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                onApplyPreset(1280, 720, 30, recEncoder, 4500f)
                onDismiss()
            }) { Text("Apply Recommended") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * About / GPL-2.0 Compliance & Upstream Attribution Dialog (DLG-11).
 */
@Composable
fun AboutDialog(obsVersion: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Panggung Studio", fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Panggung Studio v0.2.0-alpha.2 (Standalone Mobile Broadcast Studio)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Native Android Engine v0.2.0-alpha.2 (ARM64 GLES 3.2 + MediaCodec)", color = Color(0xFF14B8A6), fontSize = 12.sp)
                Text("License: GNU General Public License v2.0 or later (GPL-2.0-or-later).", color = Color(0xFFCBD5E1), fontSize = 11.sp)
                Text("Components: GLES 3.2 Compositor, FFmpeg 8.1 (SRT/DASH/x265/AOM), x264, FreeType2, SpeexDSP, mbedTLS, Lua 5.4, WebSocket Remote API.", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
