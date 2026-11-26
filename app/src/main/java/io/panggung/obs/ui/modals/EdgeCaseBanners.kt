package io.panggung.obs.ui.modals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.panggung.obs.ui.components.BroadcastButton
import io.panggung.obs.ui.components.BroadcastTone
import io.panggung.obs.ui.theme.PanggungColors
import io.panggung.obs.ui.theme.PanggungRadius
import java.net.NetworkInterface
import java.util.Locale

@Composable
fun ThermalGovernorBanner(
    tempC: Double,
    headroom: Float,
    activeBitrateKbps: Int,
    onDropTo30Fps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val severe = headroom >= 0.90f || tempC >= 46.0
    val borderColor = if (severe) PanggungColors.RecBright else PanggungColors.WarnBright
    val bgColor = if (severe) Color(0xFF2B1418) else Color(0xFF2A2012)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanggungRadius.Button)
            .background(bgColor)
            .border(1.dp, borderColor, PanggungRadius.Button)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (severe) "THERMAL CRITICAL • ${String.format(Locale.US, "%.1f°C", tempC)}" else "THERMAL GOVERNOR • ${String.format(Locale.US, "%.1f°C", tempC)}",
                color = borderColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Headroom ${String.format(Locale.US, "%.2f", headroom)} • Bitrate throttled to $activeBitrateKbps kbps",
                color = PanggungColors.TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        BroadcastButton(
            text = "30 FPS",
            onClick = onDropTo30Fps,
            tone = if (severe) BroadcastTone.Danger else BroadcastTone.Warning,
            compact = true,
        )
    }
}

@Composable
fun CrashRecoveryBanner(
    onViewLog: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanggungRadius.Button)
            .background(Color(0xFF2B1418))
            .border(1.dp, PanggungColors.RecBright, PanggungRadius.Button)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("CRASH RECOVERY", color = PanggungColors.RecBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Previous session ended unexpectedly. Hybrid MP4 preserved.", color = PanggungColors.TextSecondary, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BroadcastButton("Log", onClick = onViewLog, tone = BroadcastTone.Neutral, compact = true)
            BroadcastButton("Dismiss", onClick = onDismiss, tone = BroadcastTone.Danger, compact = true)
        }
    }
}

@Composable
fun ConfirmStopBroadcastDialog(
    isStreaming: Boolean,
    isRecording: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val what = when {
        isStreaming && isRecording -> "Live Stream & Recording"
        isStreaming -> "Live Stream"
        else -> "Recording"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanggungColors.DockSurface,
        title = { Text("Stop $what?", color = PanggungColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Active broadcast output will be finalized cleanly. Hybrid MP4 segments remain playable.",
                color = PanggungColors.TextSecondary,
                fontSize = 12.sp,
            )
        },
        confirmButton = {
            BroadcastButton("STOP NOW", onClick = onConfirm, tone = BroadcastTone.Danger)
        },
        dismissButton = {
            BroadcastButton("Cancel", onClick = onDismiss, tone = BroadcastTone.Neutral)
        },
    )
}

@Composable
fun SourceContextMenuDialog(
    sourceName: String,
    locked: Boolean,
    visible: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleVisible: () -> Unit,
    onFitToScreen: () -> Unit,
    onCenter: () -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onResetTransform: () -> Unit,
    onOpenProperties: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenTransform: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanggungColors.DockSurface,
        title = {
            Text("Source: $sourceName", color = PanggungColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ORDER & STATE", color = PanggungColors.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BroadcastButton("Move Up", onClick = { onMoveUp(); onDismiss() }, modifier = Modifier.weight(1f), compact = true)
                    BroadcastButton("Move Down", onClick = { onMoveDown(); onDismiss() }, modifier = Modifier.weight(1f), compact = true)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BroadcastButton(if (locked) "Unlock" else "Lock", onClick = { onToggleLock(); onDismiss() }, modifier = Modifier.weight(1f), compact = true)
                    BroadcastButton(if (visible) "Hide" else "Show", onClick = { onToggleVisible(); onDismiss() }, modifier = Modifier.weight(1f), compact = true)
                }
                Text("TRANSFORM", color = PanggungColors.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BroadcastButton("Fit Screen", onClick = { onFitToScreen(); onDismiss() }, tone = BroadcastTone.Primary, modifier = Modifier.weight(1f), compact = true)
                    BroadcastButton("Center", onClick = { onCenter(); onDismiss() }, modifier = Modifier.weight(1f), compact = true)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BroadcastButton("Flip H", onClick = { onFlipH(); onDismiss() }, modifier = Modifier.weight(1f), compact = true)
                    BroadcastButton("Flip V", onClick = { onFlipV(); onDismiss() }, modifier = Modifier.weight(1f), compact = true)
                    BroadcastButton("Reset", onClick = { onResetTransform(); onDismiss() }, modifier = Modifier.weight(1f), compact = true)
                }
                Text("INSPECT", color = PanggungColors.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BroadcastButton("Properties", onClick = { onDismiss(); onOpenProperties() }, modifier = Modifier.weight(1f), compact = true)
                    BroadcastButton("Filters", onClick = { onDismiss(); onOpenFilters() }, modifier = Modifier.weight(1f), compact = true)
                    BroadcastButton("Edit Xfrm", onClick = { onDismiss(); onOpenTransform() }, modifier = Modifier.weight(1f), compact = true)
                }
                BroadcastButton("Remove Source", onClick = { onDelete(); onDismiss() }, tone = BroadcastTone.Danger, modifier = Modifier.fillMaxWidth(), compact = true)
            }
        },
        confirmButton = { BroadcastButton("Close", onClick = onDismiss, compact = true) },
    )
}

@Composable
fun RemoteQrDialog(onDismiss: () -> Unit) {
    val localIp = getLocalIpv4Address()
    val wsUrl = "ws://$localIp:4455"
    val webUrl = "http://$localIp:8080"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanggungColors.DockSurface,
        title = { Text("Remote Control & QR Connect", color = PanggungColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Visual deterministic matrix code box for quick LAN pairing reference
                Box(
                    modifier = Modifier
                        .size(148.dp)
                        .clip(PanggungRadius.Card)
                        .background(Color.White)
                        .padding(10.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val n = 21
                        val cell = size.width / n
                        val seed = webUrl.hashCode()
                        fun drawFinder(ox: Int, oy: Int) {
                            for (r in 0..6) for (c in 0..6) {
                                val edge = r == 0 || r == 6 || c == 0 || c == 6
                                val inner = r in 2..4 && c in 2..4
                                if (edge || inner) {
                                    drawRect(Color.Black, Offset((ox + c) * cell, (oy + r) * cell), Size(cell, cell))
                                }
                            }
                        }
                        drawFinder(0, 0)
                        drawFinder(n - 7, 0)
                        drawFinder(0, n - 7)
                        for (r in 0 until n) for (c in 0 until n) {
                            val inFinder = (r < 8 && c < 8) || (r < 8 && c >= n - 8) || (r >= n - 8 && c < 8)
                            if (!inFinder && ((seed xor (r * 31 + c * 17)) and 1) == 0) {
                                drawRect(Color.Black, Offset(c * cell, r * cell), Size(cell, cell))
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PanggungRadius.Button)
                        .background(PanggungColors.CardElevated)
                        .border(1.dp, PanggungColors.Border, PanggungRadius.Button)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("WebSocket Remote API v5 (Companion / Stream Deck)", color = PanggungColors.TextMuted, fontSize = 10.sp)
                    Text(wsUrl, color = PanggungColors.LiveBright, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Text("Built-in Web Remote UI (Any Browser)", color = PanggungColors.TextMuted, fontSize = 10.sp)
                    Text(webUrl, color = PanggungColors.TealActive, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = { BroadcastButton("Done", onClick = onDismiss, tone = BroadcastTone.Primary) },
    )
}

fun getLocalIpv4Address(): String {
    return try {
        val ifaces = NetworkInterface.getNetworkInterfaces()
        while (ifaces.hasMoreElements()) {
            val nif = ifaces.nextElement()
            if (nif.isLoopback || !nif.isUp) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                val host = a.hostAddress ?: continue
                if (!host.contains(':') && !host.startsWith("127.")) return host
            }
        }
        "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }
}
