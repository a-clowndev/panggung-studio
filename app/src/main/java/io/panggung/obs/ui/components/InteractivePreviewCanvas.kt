package io.panggung.obs.ui.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.panggung.obs.NativeBridge
import io.panggung.obs.ui.theme.PanggungColors
import io.panggung.obs.ui.theme.PanggungRadius
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private object PreviewSurfaceTracker {
    val activeGen = AtomicLong(0)
}

/**
 * Interactive OBS Preview Canvas (T-B03):
 * - Hosts libobs-gles TextureView
 * - Red OBS Selection Bounding Box + 4 corner handles
 * - Action Safe (90%) & Title Safe (80%) broadcast guides
 * - Snap-to-center/edge guide lines
 * - Lock Preview & Zoom controls
 */
@Composable
fun InteractivePreviewCanvas(
    obsReady: Boolean,
    baseWidth: Int,
    baseHeight: Int,
    selectedSource: String?,
    selectedLocked: Boolean,
    recording: Boolean,
    streaming: Boolean,
    studioMode: Boolean,
    transformVersion: Int = 0,
    modifier: Modifier = Modifier,
    showZoomToolbar: Boolean = false,
) {
    var safeGuides by remember { mutableStateOf(false) }
    var previewLocked by remember { mutableStateOf(false) }
    var zoomPct by remember { mutableIntStateOf(100) }
    var snapCenterX by remember { mutableStateOf(false) }
    var snapCenterY by remember { mutableStateOf(false) }

    var tx by remember(selectedSource) { mutableFloatStateOf(baseWidth / 2f) }
    var ty by remember(selectedSource) { mutableFloatStateOf(baseHeight / 2f) }
    var rawTx by remember(selectedSource) { mutableFloatStateOf(baseWidth / 2f) }
    var rawTy by remember(selectedSource) { mutableFloatStateOf(baseHeight / 2f) }
    var sx by remember(selectedSource) { mutableFloatStateOf(1f) }
    var sy by remember(selectedSource) { mutableFloatStateOf(1f) }
    var rot by remember(selectedSource) { mutableFloatStateOf(0f) }
    var rawRot by remember(selectedSource) { mutableFloatStateOf(0f) }
    var rotSnappedStraight by remember { mutableStateOf(true) }
    var draggingHud by remember { mutableStateOf("") }

    LaunchedEffect(selectedSource, obsReady, transformVersion) {
        if (obsReady && !selectedSource.isNullOrEmpty()) {
            try {
                val j = JSONObject(NativeBridge.nativeGetSourceTransformJson(selectedSource))
                tx = j.optDouble("x", baseWidth / 2.0).toFloat()
                ty = j.optDouble("y", baseHeight / 2.0).toFloat()
                rawTx = tx
                rawTy = ty
                sx = j.optDouble("scale_x", 1.0).toFloat()
                sy = j.optDouble("scale_y", 1.0).toFloat()
                rot = j.optDouble("rot", 0.0).toFloat()
                rawRot = rot
                rotSnappedStraight = (abs(rot % 90f) < 1f)
                snapCenterX = false
                snapCenterY = false
                draggingHud = "$selectedSource • ${rot.toInt()}°${if (rotSnappedStraight) " LURUS" else ""} • ${(sx * 100).toInt()}%"
            } catch (_: Exception) {}
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .aspectRatio((baseWidth.coerceAtLeast(1).toFloat() / baseHeight.coerceAtLeast(1).toFloat()).coerceIn(0.56f, 2.2f))
                .clip(PanggungRadius.Card)
                .background(Color.Black)
                .border(
                    width = 1.dp,
                    color = when {
                        streaming -> PanggungColors.LiveBright
                        recording -> PanggungColors.RecBright
                        else -> PanggungColors.Border
                    },
                    shape = PanggungRadius.Card,
                ),
        ) {
            if (obsReady) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            isOpaque = false
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                private var attachedSurface: Surface? = null
                                private var myGen: Long = 0
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    myGen = PreviewSurfaceTracker.activeGen.incrementAndGet()
                                    val s = Surface(st)
                                    attachedSurface = s
                                    NativeBridge.nativeAttachSurface(s, w.coerceAtLeast(1), h.coerceAtLeast(1))
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    if (myGen == PreviewSurfaceTracker.activeGen.get()) {
                                        NativeBridge.nativeResizeSurface(w.coerceAtLeast(1), h.coerceAtLeast(1))
                                    }
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    // Only detach native display if a newer TextureView hasn't already taken over during rotation
                                    if (myGen == PreviewSurfaceTracker.activeGen.get()) {
                                        NativeBridge.nativeDetachSurface()
                                    }
                                    attachedSurface?.release()
                                    attachedSurface = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                )
            }

            // Top-most Gesture + Broadcast Overlay (above AndroidView so 1-finger drag & 2-finger pinch/rotate NEVER get swallowed)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(selectedSource, previewLocked, selectedLocked, baseWidth, baseHeight) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            val target = selectedSource ?: return@detectTransformGestures
                            if (previewLocked || selectedLocked) return@detectTransformGestures
                            val scaleToCanvasX = baseWidth.toFloat() / size.width.coerceAtLeast(1)
                            val scaleToCanvasY = baseHeight.toFloat() / size.height.coerceAtLeast(1)
                            val cxCanvas = baseWidth / 2f
                            val cyCanvas = baseHeight / 2f
                            rawTx = (rawTx + pan.x * scaleToCanvasX).coerceIn(-baseWidth * 0.5f, baseWidth * 1.5f)
                            rawTy = (rawTy + pan.y * scaleToCanvasY).coerceIn(-baseHeight * 0.5f, baseHeight * 1.5f)
                            if (abs(rawTx - cxCanvas) < 18f) { tx = cxCanvas; snapCenterX = true } else { tx = rawTx; snapCenterX = false }
                            if (abs(rawTy - cyCanvas) < 18f) { ty = cyCanvas; snapCenterY = true } else { ty = rawTy; snapCenterY = false }
                            sx = (sx * zoom).coerceIn(0.15f, 3.0f)
                            sy = (sy * zoom).coerceIn(0.15f, 3.0f)
                            rawRot = ((rawRot + rotation) % 360f + 360f) % 360f
                            // Magnetic snap to 0° / 90° / 180° / 270° (±4.5°) so straight alignment is effortless
                            val nearest90 = ((rawRot + 45f).toInt() / 90 * 90) % 360
                            val diff90 = abs(rawRot - nearest90).let { if (it > 180f) 360f - it else it }
                            if (diff90 <= 4.5f) {
                                rot = nearest90.toFloat()
                                rotSnappedStraight = true
                            } else {
                                rot = rawRot
                                rotSnappedStraight = false
                            }
                            val straightTag = if (rotSnappedStraight) " LURUS ✓" else ""
                            draggingHud = "$target • ${rot.toInt()}°$straightTag • ${(sx * 100).toInt()}%"
                            NativeBridge.nativeSetSourceTransform(target, tx, ty, sx, sy, rot)
                        }
                    },
            ) {
                val w = size.width
                val h = size.height

                if (safeGuides) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                    // Action Safe 90%
                    drawRect(
                        color = Color(0x8814B8A6),
                        topLeft = Offset(w * 0.05f, h * 0.05f),
                        size = Size(w * 0.90f, h * 0.90f),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                    )
                    // Title Safe 80%
                    drawRect(
                        color = Color(0x88F59E0B),
                        topLeft = Offset(w * 0.10f, h * 0.10f),
                        size = Size(w * 0.80f, h * 0.80f),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                    )
                }

                if (snapCenterX) {
                    drawLine(PanggungColors.TealActive, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1.dp.toPx())
                }
                if (snapCenterY) {
                    drawLine(PanggungColors.TealActive, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1.dp.toPx())
                }

                if (!selectedSource.isNullOrEmpty()) {
                    val boxColor = when {
                        selectedLocked || previewLocked -> PanggungColors.TextMuted
                        rotSnappedStraight && rot != 0f -> PanggungColors.TealActive
                        else -> PanggungColors.RecBright
                    }
                    val cx = (tx / baseWidth.coerceAtLeast(1)) * w
                    val cy = (ty / baseHeight.coerceAtLeast(1)) * h
                    val bw = (w * sx.coerceIn(0.15f, 3.0f)).coerceAtLeast(24f)
                    val bh = (h * sy.coerceIn(0.15f, 3.0f)).coerceAtLeast(24f)
                    val left = cx - bw / 2f
                    val top = cy - bh / 2f
                    withTransform({ rotate(degrees = rot, pivot = Offset(cx, cy)) }) {
                        drawRect(
                            color = boxColor,
                            topLeft = Offset(left, top),
                            size = Size(bw, bh),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                        // 4 Corner resize handles
                        val hs = 6.dp.toPx()
                        listOf(
                            Offset(left, top),
                            Offset(left + bw, top),
                            Offset(left, top + bh),
                            Offset(left + bw, top + bh),
                        ).forEach { c ->
                            drawRect(
                                color = boxColor,
                                topLeft = Offset(c.x - hs / 2f, c.y - hs / 2f),
                                size = Size(hs, hs),
                            )
                        }
                    }
                    // Center pivot crosshair + straight-horizon level indicator when snapped to 0°/90°/180°/270°
                    val crossColor = if (rotSnappedStraight) PanggungColors.TealActive else PanggungColors.WarnBright
                    val cr = 8.dp.toPx()
                    drawLine(crossColor, Offset(cx - cr, cy), Offset(cx + cr, cy), strokeWidth = 1.5.dp.toPx())
                    drawLine(crossColor, Offset(cx, cy - cr), Offset(cx, cy + cr), strokeWidth = 1.5.dp.toPx())
                }
            }

            // Bottom-left live drag coordinates HUD
            if (!selectedSource.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(PanggungRadius.Badge)
                        .background(Color(0xAA0D1117))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = draggingHud.ifEmpty { "Drag/Pinch: $selectedSource" },
                        color = PanggungColors.TextPrimary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // Top-left live mode overlay badge
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (studioMode) {
                    TallyBadge("PREVIEW", PanggungColors.TealActive)
                }
                if (streaming) {
                    TallyBadge("LIVE", PanggungColors.LiveBright)
                } else if (recording) {
                    TallyBadge("REC", PanggungColors.RecBright)
                }
            }

            // Top-right quick Safe/Lock pills
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PreviewOverlayChip(label = "SAFE", active = safeGuides) { safeGuides = !safeGuides }
                PreviewOverlayChip(label = if (previewLocked) "LOCKED" else "LOCK", active = previewLocked) {
                    previewLocked = !previewLocked
                }
            }
        }

        if (showZoomToolbar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanggungRadius.Button)
                    .background(PanggungColors.DockSurface)
                    .border(1.dp, PanggungColors.Border, PanggungRadius.Button)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("-", color = PanggungColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { zoomPct = (zoomPct - 25).coerceAtLeast(25) }.padding(horizontal = 4.dp))
                    Text("$zoomPct%", color = PanggungColors.TealActive, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("+", color = PanggungColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { zoomPct = (zoomPct + 25).coerceAtMost(200) }.padding(horizontal = 4.dp))
                    Text("Scale to Window", color = PanggungColors.TextMuted, fontSize = 10.sp,
                        modifier = Modifier.clickable { zoomPct = 100 }.padding(horizontal = 4.dp))
                }
                Text(
                    "${baseWidth}x${baseHeight}",
                    color = PanggungColors.TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun PreviewOverlayChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(PanggungRadius.Badge)
            .background(if (active) PanggungColors.TealPrimary.copy(alpha = 0.85f) else Color(0x990D1117))
            .border(1.dp, if (active) PanggungColors.TealActive else PanggungColors.Border, PanggungRadius.Badge)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = PanggungColors.TextPrimary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}
