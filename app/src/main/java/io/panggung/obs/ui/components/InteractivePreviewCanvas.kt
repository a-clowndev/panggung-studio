package io.panggung.obs.ui.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.input.pointer.positionChanged
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private object PreviewSurfaceTracker {
    val activeGen = AtomicLong(0)
}

private enum class DragHandle {
    NONE,
    BODY,
    CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR,
    EDGE_L, EDGE_R, EDGE_T, EDGE_B,
}

/**
 * Interactive OBS Preview Canvas (v0.3.0):
 * - Hosts libobs-gles TextureView
 * - Direct 4-corner resize handles (drag any corner to scale cleanly)
 * - Direct 4-edge CROP mode (toggle CROP pill -> drag green edge handles -> obs_sceneitem_set_crop)
 * - Full Magnetic Snapping: Canvas Center X/Y, Canvas Edges, Action/Title Safe Guides, and 0°/90°/180°/270° rotation
 * - 2-finger pinch zoom + rotate & Lock Preview controls
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
    var cropMode by remember { mutableStateOf(false) }
    var previewLocked by remember { mutableStateOf(false) }
    var zoomPct by remember { mutableIntStateOf(100) }
    var snapLineX by remember { mutableStateOf<Float?>(null) } // normalized 0..1 on canvas
    var snapLineY by remember { mutableStateOf<Float?>(null) }
    var activeHandle by remember { mutableStateOf(DragHandle.NONE) }

    var tx by remember(selectedSource) { mutableFloatStateOf(baseWidth / 2f) }
    var ty by remember(selectedSource) { mutableFloatStateOf(baseHeight / 2f) }
    var rawTx by remember(selectedSource) { mutableFloatStateOf(baseWidth / 2f) }
    var rawTy by remember(selectedSource) { mutableFloatStateOf(baseHeight / 2f) }
    var sx by remember(selectedSource) { mutableFloatStateOf(1f) }
    var sy by remember(selectedSource) { mutableFloatStateOf(1f) }
    var rot by remember(selectedSource) { mutableFloatStateOf(0f) }
    var rawRot by remember(selectedSource) { mutableFloatStateOf(0f) }
    var rotSnappedStraight by remember { mutableStateOf(true) }
    var cropLeft by remember(selectedSource) { mutableIntStateOf(0) }
    var cropTop by remember(selectedSource) { mutableIntStateOf(0) }
    var cropRight by remember(selectedSource) { mutableIntStateOf(0) }
    var cropBottom by remember(selectedSource) { mutableIntStateOf(0) }
    var srcWidth by remember(selectedSource) { mutableIntStateOf(baseWidth) }
    var srcHeight by remember(selectedSource) { mutableIntStateOf(baseHeight) }
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
                cropLeft = j.optInt("crop_left", 0)
                cropTop = j.optInt("crop_top", 0)
                cropRight = j.optInt("crop_right", 0)
                cropBottom = j.optInt("crop_bottom", 0)
                srcWidth = j.optInt("src_w", baseWidth).coerceAtLeast(64)
                srcHeight = j.optInt("src_h", baseHeight).coerceAtLeast(64)
                rotSnappedStraight = (abs(rot % 90f) < 1f)
                snapLineX = null
                snapLineY = null
                draggingHud = "$selectedSource • ${rot.toInt()}°${if (rotSnappedStraight) " LURUS" else ""} • ${(sx * 100).toInt()}%"
            } catch (_: Exception) {}
        }
    }

    val canvasRatio = (baseWidth.coerceAtLeast(1).toFloat() / baseHeight.coerceAtLeast(1).toFloat()).coerceIn(0.56f, 2.2f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(PanggungRadius.Card)
                .background(Color(0xFF06080D))
                .border(
                    width = 1.dp,
                    color = when {
                        streaming -> PanggungColors.LiveBright
                        recording -> PanggungColors.RecBright
                        cropMode -> Color(0xFF22C55E)
                        else -> PanggungColors.Border
                    },
                    shape = PanggungRadius.Card,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val containerRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else canvasRatio
            val fitByHeight = containerRatio > canvasRatio
            val canvasMod = if (fitByHeight) {
                Modifier.fillMaxHeight().aspectRatio(canvasRatio, matchHeightConstraintsFirst = true)
            } else {
                Modifier.fillMaxWidth().aspectRatio(canvasRatio, matchHeightConstraintsFirst = false)
            }
            Box(
                modifier = canvasMod.background(Color.Black),
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

                // Direct-Manipulation Gesture + Broadcast Overlay
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(selectedSource, previewLocked, selectedLocked, cropMode, safeGuides, baseWidth, baseHeight) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val target = selectedSource ?: return@awaitEachGesture
                                if (previewLocked || selectedLocked) return@awaitEachGesture

                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val cx = (tx / baseWidth.coerceAtLeast(1)) * w
                                val cy = (ty / baseHeight.coerceAtLeast(1)) * h
                                val bw = (w * sx.coerceIn(0.15f, 3.0f)).coerceAtLeast(24f)
                                val bh = (h * sy.coerceIn(0.15f, 3.0f)).coerceAtLeast(24f)
                                val left = cx - bw / 2f
                                val top = cy - bh / 2f

                                // Transform touch point into unrotated local box coordinates for accurate handle hit-testing
                                val rad = Math.toRadians((-rot).toDouble())
                                val dx0 = down.position.x - cx
                                val dy0 = down.position.y - cy
                                val lx = cx + (dx0 * cos(rad) - dy0 * sin(rad)).toFloat()
                                val ly = cy + (dx0 * sin(rad) + dy0 * cos(rad)).toFloat()
                                val hitR = 26.dp.toPx()

                                val handle = when {
                                    cropMode && hypot(lx - left, ly - cy) <= hitR -> DragHandle.EDGE_L
                                    cropMode && hypot(lx - (left + bw), ly - cy) <= hitR -> DragHandle.EDGE_R
                                    cropMode && hypot(lx - cx, ly - top) <= hitR -> DragHandle.EDGE_T
                                    cropMode && hypot(lx - cx, ly - (top + bh)) <= hitR -> DragHandle.EDGE_B
                                    hypot(lx - left, ly - top) <= hitR -> DragHandle.CORNER_TL
                                    hypot(lx - (left + bw), ly - top) <= hitR -> DragHandle.CORNER_TR
                                    hypot(lx - left, ly - (top + bh)) <= hitR -> DragHandle.CORNER_BL
                                    hypot(lx - (left + bw), ly - (top + bh)) <= hitR -> DragHandle.CORNER_BR
                                    else -> DragHandle.BODY
                                }
                                activeHandle = handle

                                val scaleToCanvasX = baseWidth.toFloat() / w
                                val scaleToCanvasY = baseHeight.toFloat() / h

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break

                                    val pan = event.calculatePan()
                                    val zoom = event.calculateZoom()
                                    val rotation = event.calculateRotation()
                                    val moved = event.changes.any { it.positionChanged() }
                                    if (!moved) continue

                                    if (pressed.size >= 2) {
                                        // 2-finger pinch + rotate
                                        sx = (sx * zoom).coerceIn(0.15f, 3.0f)
                                        sy = (sy * zoom).coerceIn(0.15f, 3.0f)
                                        rawRot = ((rawRot + rotation) % 360f + 360f) % 360f
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
                                    } else {
                                        when (handle) {
                                            DragHandle.CORNER_TL, DragHandle.CORNER_TR,
                                            DragHandle.CORNER_BL, DragHandle.CORNER_BR -> {
                                                // Direct corner resize (drag outward = grow, inward = shrink)
                                                val signX = if (handle == DragHandle.CORNER_TR || handle == DragHandle.CORNER_BR) 1f else -1f
                                                val signY = if (handle == DragHandle.CORNER_BL || handle == DragHandle.CORNER_BR) 1f else -1f
                                                val deltaScale = ((pan.x * signX / w) + (pan.y * signY / h))
                                                val nextS = (sx + deltaScale).coerceIn(0.15f, 3.0f)
                                                sx = nextS
                                                sy = nextS
                                                draggingHud = "RESIZE $target • ${(sx * 100).toInt()}%"
                                                NativeBridge.nativeSetSourceTransform(target, tx, ty, sx, sy, rot)
                                            }
                                            DragHandle.EDGE_L -> {
                                                val maxCropX = (srcWidth - cropRight - 64).coerceAtLeast(0)
                                                cropLeft = (cropLeft + (pan.x * scaleToCanvasX).toInt()).coerceIn(0, maxCropX)
                                                draggingHud = "CROP L:$cropLeft T:$cropTop R:$cropRight B:$cropBottom"
                                                NativeBridge.nativeSetSourceCrop(target, cropLeft, cropTop, cropRight, cropBottom)
                                            }
                                            DragHandle.EDGE_R -> {
                                                val maxCropX = (srcWidth - cropLeft - 64).coerceAtLeast(0)
                                                cropRight = (cropRight - (pan.x * scaleToCanvasX).toInt()).coerceIn(0, maxCropX)
                                                draggingHud = "CROP L:$cropLeft T:$cropTop R:$cropRight B:$cropBottom"
                                                NativeBridge.nativeSetSourceCrop(target, cropLeft, cropTop, cropRight, cropBottom)
                                            }
                                            DragHandle.EDGE_T -> {
                                                val maxCropY = (srcHeight - cropBottom - 64).coerceAtLeast(0)
                                                cropTop = (cropTop + (pan.y * scaleToCanvasY).toInt()).coerceIn(0, maxCropY)
                                                draggingHud = "CROP L:$cropLeft T:$cropTop R:$cropRight B:$cropBottom"
                                                NativeBridge.nativeSetSourceCrop(target, cropLeft, cropTop, cropRight, cropBottom)
                                            }
                                            DragHandle.EDGE_B -> {
                                                val maxCropY = (srcHeight - cropTop - 64).coerceAtLeast(0)
                                                cropBottom = (cropBottom - (pan.y * scaleToCanvasY).toInt()).coerceIn(0, maxCropY)
                                                draggingHud = "CROP L:$cropLeft T:$cropTop R:$cropRight B:$cropBottom"
                                                NativeBridge.nativeSetSourceCrop(target, cropLeft, cropTop, cropRight, cropBottom)
                                            }
                                            else -> {
                                                // 1-finger move with Magnetic Snapping (Center + Canvas Edges + Safe Guides)
                                                rawTx = (rawTx + pan.x * scaleToCanvasX).coerceIn(-baseWidth * 0.5f, baseWidth * 1.5f)
                                                rawTy = (rawTy + pan.y * scaleToCanvasY).coerceIn(-baseHeight * 0.5f, baseHeight * 1.5f)
                                                val halfW = (baseWidth * sx.coerceIn(0.15f, 3.0f)) * 0.5f
                                                val halfH = (baseHeight * sy.coerceIn(0.15f, 3.0f)) * 0.5f
                                                val snapThresh = 18f

                                                val xTargets = buildList {
                                                    add(baseWidth * 0.5f to 0.5f) // Center
                                                    add(halfW to 0.0f) // Left edge flush
                                                    add((baseWidth - halfW) to 1.0f) // Right edge flush
                                                    if (safeGuides) {
                                                        add((baseWidth * 0.05f + halfW) to 0.05f)
                                                        add((baseWidth * 0.95f - halfW) to 0.95f)
                                                        add((baseWidth * 0.10f + halfW) to 0.10f)
                                                        add((baseWidth * 0.90f - halfW) to 0.90f)
                                                    }
                                                }
                                                val bestX = xTargets.minByOrNull { abs(rawTx - it.first) }
                                                if (bestX != null && abs(rawTx - bestX.first) <= snapThresh) {
                                                    tx = bestX.first
                                                    snapLineX = bestX.second
                                                } else {
                                                    tx = rawTx
                                                    snapLineX = null
                                                }

                                                val yTargets = buildList {
                                                    add(baseHeight * 0.5f to 0.5f) // Center
                                                    add(halfH to 0.0f) // Top edge flush
                                                    add((baseHeight - halfH) to 1.0f) // Bottom edge flush
                                                    if (safeGuides) {
                                                        add((baseHeight * 0.05f + halfH) to 0.05f)
                                                        add((baseHeight * 0.95f - halfH) to 0.95f)
                                                        add((baseHeight * 0.10f + halfH) to 0.10f)
                                                        add((baseHeight * 0.90f - halfH) to 0.90f)
                                                    }
                                                }
                                                val bestY = yTargets.minByOrNull { abs(rawTy - it.first) }
                                                if (bestY != null && abs(rawTy - bestY.first) <= snapThresh) {
                                                    ty = bestY.first
                                                    snapLineY = bestY.second
                                                } else {
                                                    ty = rawTy
                                                    snapLineY = null
                                                }

                                                val snapTag = if (snapLineX != null || snapLineY != null) " SNAP ✓" else ""
                                                draggingHud = "$target • (${tx.toInt()},${ty.toInt()})$snapTag • ${(sx * 100).toInt()}%"
                                                NativeBridge.nativeSetSourceTransform(target, tx, ty, sx, sy, rot)
                                            }
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                                activeHandle = DragHandle.NONE
                                snapLineX = null
                                snapLineY = null
                            }
                        },
                ) {
                    val w = size.width
                    val h = size.height

                    if (safeGuides) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        drawRect(
                            color = Color(0x8814B8A6),
                            topLeft = Offset(w * 0.05f, h * 0.05f),
                            size = Size(w * 0.90f, h * 0.90f),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                        )
                        drawRect(
                            color = Color(0x88F59E0B),
                            topLeft = Offset(w * 0.10f, h * 0.10f),
                            size = Size(w * 0.80f, h * 0.80f),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                        )
                    }

                    snapLineX?.let { nx ->
                        val c = if (nx == 0.5f) PanggungColors.TealActive else PanggungColors.WarnBright
                        drawLine(c, Offset(w * nx, 0f), Offset(w * nx, h), strokeWidth = 1.5.dp.toPx())
                    }
                    snapLineY?.let { ny ->
                        val c = if (ny == 0.5f) PanggungColors.TealActive else PanggungColors.WarnBright
                        drawLine(c, Offset(0f, h * ny), Offset(w, h * ny), strokeWidth = 1.5.dp.toPx())
                    }

                    if (!selectedSource.isNullOrEmpty()) {
                        val boxColor = when {
                            selectedLocked || previewLocked -> PanggungColors.TextMuted
                            cropMode -> Color(0xFF22C55E)
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
                                style = Stroke(
                                    width = if (cropMode) 2.dp.toPx() else 1.5.dp.toPx(),
                                    pathEffect = if (cropMode) PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f) else null,
                                ),
                            )
                            // 4 Corner resize handles
                            val hs = 8.dp.toPx()
                            listOf(
                                Offset(left, top) to DragHandle.CORNER_TL,
                                Offset(left + bw, top) to DragHandle.CORNER_TR,
                                Offset(left, top + bh) to DragHandle.CORNER_BL,
                                Offset(left + bw, top + bh) to DragHandle.CORNER_BR,
                            ).forEach { (c, dh) ->
                                val isHot = activeHandle == dh
                                drawRect(
                                    color = if (isHot) Color.White else boxColor,
                                    topLeft = Offset(c.x - hs / 2f, c.y - hs / 2f),
                                    size = Size(hs, hs),
                                )
                            }
                            // 4 Edge crop bars (visible & draggable in CROP mode)
                            if (cropMode) {
                                val barLong = 18.dp.toPx()
                                val barThick = 4.dp.toPx()
                                drawRect(Color(0xFF22C55E), Offset(left - barThick / 2f, cy - barLong / 2f), Size(barThick, barLong))
                                drawRect(Color(0xFF22C55E), Offset(left + bw - barThick / 2f, cy - barLong / 2f), Size(barThick, barLong))
                                drawRect(Color(0xFF22C55E), Offset(cx - barLong / 2f, top - barThick / 2f), Size(barLong, barThick))
                                drawRect(Color(0xFF22C55E), Offset(cx - barLong / 2f, top + bh - barThick / 2f), Size(barLong, barThick))
                            }
                        }
                        val crossColor = if (rotSnappedStraight) PanggungColors.TealActive else PanggungColors.WarnBright
                        val cr = 8.dp.toPx()
                        drawLine(crossColor, Offset(cx - cr, cy), Offset(cx + cr, cy), strokeWidth = 1.5.dp.toPx())
                        drawLine(crossColor, Offset(cx, cy - cr), Offset(cx, cy + cr), strokeWidth = 1.5.dp.toPx())
                    }
                }

                // Bottom-left live drag coordinates / crop HUD
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
                            text = draggingHud.ifEmpty { "Drag/Resize: $selectedSource" },
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

                // Top-right quick CROP / SAFE / LOCK pills
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PreviewOverlayChip(label = "CROP", active = cropMode) { cropMode = !cropMode }
                    PreviewOverlayChip(label = "SAFE", active = safeGuides) { safeGuides = !safeGuides }
                    PreviewOverlayChip(label = if (previewLocked) "LOCKED" else "LOCK", active = previewLocked) {
                        previewLocked = !previewLocked
                    }
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
