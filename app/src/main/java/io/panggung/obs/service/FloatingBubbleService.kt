package io.panggung.obs.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.panggung.obs.MainActivity
import io.panggung.obs.NativeBridge
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Draggable & Expandable Floating Studio Hub Popup + In-Game Camera PiP Position & Resize Overlay.
 * Auto-appears when leaving Panggung Studio into a game:
 * - 1-tap REC, MIC, CAM (PiP drag & resize + Front/Back switch), SCENE switch, and Return to Studio.
 */
class FloatingBubbleService : Service() {
    private var wm: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var camPipView: FrameLayout? = null
    private var camPipParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var micMuted = false
    private var activeCamSourceName: String = "Front Camera"
    private var camRotDeg: Float = 0f
    private var camFlipH: Boolean = false
    private var camFlipV: Boolean = false
    private var layoutPresetIdx: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }

        val bg = GradientDrawable().apply {
            setColor(Color.argb(235, 13, 17, 23)) // AntiSlopUI #0D1117
            cornerRadius = 24f
            setStroke(2, Color.rgb(20, 184, 166)) // Deep Teal Active #14B8A6
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(18, 10, 18, 10)
        }

        val statusPill = TextView(this).apply {
            text = "● HUB"
            setTextColor(Color.rgb(20, 184, 166))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(4, 4, 8, 4)
        }
        container.addView(statusPill)

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        fun makeBtn(label: String, textColor: Int = Color.rgb(241, 245, 249), onClick: (TextView) -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(textColor)
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                val btnBg = GradientDrawable().apply {
                    setColor(Color.rgb(30, 36, 46))
                    cornerRadius = 12f
                    setStroke(1, Color.rgb(45, 54, 67))
                }
                background = btnBg
                setPadding(12, 7, 12, 7)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(6, 0, 0, 0) }
                layoutParams = lp
                setOnClickListener { onClick(this) }
            }
        }

        val recBtn = makeBtn("REC", Color.rgb(239, 68, 68)) { tv ->
            try {
                val snap = JSONObject(NativeBridge.nativeGetSnapshotJson())
                val isRec = snap.optBoolean("recording", false)
                if (isRec) {
                    NativeBridge.nativeStopRecording()
                    tv.text = "REC"
                    statusPill.text = "● HUB"
                    statusPill.setTextColor(Color.rgb(20, 184, 166))
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                    dir.mkdirs()
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val out = File(dir, "panggung_$ts.mp4").absolutePath
                    if (NativeBridge.nativeStartRecording(out, 4500)) {
                        tv.text = "STOP"
                        statusPill.text = "● REC"
                        statusPill.setTextColor(Color.rgb(239, 68, 68))
                    }
                }
            } catch (_: Exception) {}
        }

        val muteBtn = makeBtn("MIC") { tv ->
            micMuted = !micMuted
            NativeBridge.nativeSetSourceMuted("Microphone", micMuted)
            tv.text = if (micMuted) "MUTED" else "MIC"
            tv.setTextColor(if (micMuted) Color.rgb(239, 68, 68) else Color.rgb(34, 197, 94))
        }

        val camPipBtn = makeBtn("CAM", Color.rgb(20, 184, 166)) { tv ->
            if (camPipView != null) {
                hideCamPipOverlay()
                tv.text = "CAM"
            } else {
                showCamPipOverlay()
                tv.text = "CAM ✓"
            }
        }

        val layoutBtn = makeBtn("LAYOUT", Color.rgb(56, 189, 248)) { tv ->
            val label = applyNextLayoutPreset()
            tv.text = label
        }

        val sceneBtn = makeBtn("SCENE") {
            try {
                val snap = JSONObject(NativeBridge.nativeGetSnapshotJson())
                val scenes = snap.optJSONArray("scenes")
                if (scenes != null && scenes.length() > 1) {
                    val cur = snap.optInt("active_scene_idx", 0)
                    NativeBridge.nativeSwitchScene((cur + 1) % scenes.length())
                }
            } catch (_: Exception) {}
        }

        val studioBtn = makeBtn("STUDIO", Color.rgb(20, 184, 166)) {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(i)
        }

        val closeBtn = makeBtn("×", Color.rgb(148, 163, 184)) {
            stopSelf()
        }

        actionBar.addView(recBtn)
        actionBar.addView(muteBtn)
        actionBar.addView(camPipBtn)
        actionBar.addView(layoutBtn)
        actionBar.addView(sceneBtn)
        actionBar.addView(studioBtn)
        actionBar.addView(closeBtn)
        container.addView(actionBar)

        // Sync initial REC/LIVE status
        try {
            val snap = JSONObject(NativeBridge.nativeGetSnapshotJson())
            if (snap.optBoolean("streaming", false)) {
                statusPill.text = "● LIVE"
                statusPill.setTextColor(Color.rgb(34, 197, 94))
            } else if (snap.optBoolean("recording", false)) {
                statusPill.text = "● REC"
                statusPill.setTextColor(Color.rgb(239, 68, 68))
                recBtn.text = "STOP"
            }
        } catch (_: Exception) {}

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        statusPill.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (ev.rawX - touchX).toInt()
                    params.y = initY + (ev.rawY - touchY).toInt()
                    wm?.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(ev.rawX - touchX) < 14 && abs(ev.rawY - touchY) < 14) {
                        expanded = !expanded
                        actionBar.visibility = if (expanded) View.VISIBLE else View.GONE
                        wm?.updateViewLayout(container, params)
                    }
                    true
                }
                else -> false
            }
        }

        rootView = container
        wm?.addView(container, params)
    }

    /**
     * 1-tap Broadcast Layout Presets directly from the Floating Studio Hub (works in Portrait 9:16 & Landscape 16:9):
     * 0: SPLIT 2  -> Top: Camera Fit, Bottom: Game Screen Full-Width Fit (zero crop)
     * 1: 3-SLOT   -> Top: Camera, Middle: Game Screen Full-Width Fit, Bottom: Overlay/Chat/Banner slot
     * 2: GAME+PiP -> Full Game Screen Fit + Camera PiP in top-right corner
     * 3: FULL CAM -> Camera Full Screen (for chatting between matches)
     */
    private fun applyNextLayoutPreset(): String {
        val labels = listOf("SPLIT 2", "3-SLOT", "GAME+PiP", "FULL CAM")
        val mode = layoutPresetIdx % labels.size
        layoutPresetIdx = (layoutPresetIdx + 1) % labels.size
        try {
            val snap = JSONObject(NativeBridge.nativeGetSnapshotJson())
            val bw = snap.optInt("base_width", 720).coerceAtLeast(1).toFloat()
            val bh = snap.optInt("base_height", 1280).coerceAtLeast(1).toFloat()
            val isPortrait = bh > bw
            val sources = snap.optJSONArray("sources")

            var camName: String? = null
            var screenName: String? = null
            val extraOverlays = mutableListOf<String>()
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val s = sources.getJSONObject(i)
                    val id = s.optString("id")
                    val name = s.optString("name")
                    when (id) {
                        "android_camera" -> if (camName == null) camName = name
                        "android_screen" -> if (screenName == null) screenName = name
                        "android_webview", "text_ft2_source_v2", "image_source" -> extraOverlays.add(name)
                    }
                }
            }
            if (camName != null) activeCamSourceName = camName
            val sxSign = if (camFlipH) -1f else 1f
            val sySign = if (camFlipV) -1f else 1f

            when (mode) {
                0 -> { // SPLIT 2: Tight TikTok Gaming Stack (uniform scale = 100% true aspect ratio, ZERO squishing!)
                    extraOverlays.forEach { NativeBridge.nativeSetSourceVisible(it, false) }
                    if (isPortrait) {
                        camName?.let {
                            NativeBridge.nativeSetSourceVisible(it, true)
                            NativeBridge.nativeSetSourceTransform(it, bw * 0.5f, bh * 0.24f, 1.0f * sxSign, 1.0f * sySign, camRotDeg)
                        }
                        screenName?.let {
                            NativeBridge.nativeSetSourceVisible(it, true)
                            NativeBridge.nativeSetSourceTransform(it, bw * 0.5f, bh * 0.53f, 1.0f, 1.0f, 0f)
                        }
                    } else {
                        // Landscape side-by-side split: Left 72% Game + Right 28% Camera (uniform scale)
                        screenName?.let {
                            NativeBridge.nativeSetSourceVisible(it, true)
                            NativeBridge.nativeSetSourceTransform(it, bw * 0.36f, bh * 0.5f, 0.72f, 0.72f, 0f)
                        }
                        camName?.let {
                            NativeBridge.nativeSetSourceVisible(it, true)
                            NativeBridge.nativeSetSourceTransform(it, bw * 0.86f, bh * 0.5f, 0.28f * sxSign, 0.28f * sySign, camRotDeg)
                        }
                    }
                }
                1 -> { // 3-SLOT: Top Camera + Middle Game Full-Width Fit + Bottom Chat/Overlay Slot (uniform scale)
                    camName?.let {
                        NativeBridge.nativeSetSourceVisible(it, true)
                        NativeBridge.nativeSetSourceTransform(it, bw * 0.5f, bh * 0.17f, 0.88f * sxSign, 0.88f * sySign, camRotDeg)
                    }
                    screenName?.let {
                        NativeBridge.nativeSetSourceVisible(it, true)
                        NativeBridge.nativeSetSourceTransform(it, bw * 0.5f, bh * 0.48f, 1.0f, 1.0f, 0f)
                    }
                    extraOverlays.forEach { ov ->
                        NativeBridge.nativeSetSourceVisible(ov, true)
                        NativeBridge.nativeSetSourceTransform(ov, bw * 0.5f, bh * 0.80f, 0.90f, 0.90f, 0f)
                    }
                }
                2 -> { // GAME + PiP: Full Game Fit + Camera PiP corner (uniform scale = true 16:9!)
                    extraOverlays.forEach { NativeBridge.nativeSetSourceVisible(it, false) }
                    screenName?.let {
                        NativeBridge.nativeSetSourceVisible(it, true)
                        NativeBridge.nativeSetSourceTransform(it, bw * 0.5f, bh * 0.5f, 1.0f, 1.0f, 0f)
                    }
                    camName?.let {
                        NativeBridge.nativeSetSourceVisible(it, true)
                        NativeBridge.nativeMoveSourceOrder(it, 2)
                        val s = if (isPortrait) 0.44f else 0.28f
                        NativeBridge.nativeSetSourceTransform(it, bw * 0.76f, bh * 0.18f, s * sxSign, s * sySign, camRotDeg)
                    }
                }
                3 -> { // FULL CAM: Clean Camera Full Screen (hides game & overlays cleanly!)
                    screenName?.let { NativeBridge.nativeSetSourceVisible(it, false) }
                    extraOverlays.forEach { NativeBridge.nativeSetSourceVisible(it, false) }
                    camName?.let {
                        NativeBridge.nativeSetSourceVisible(it, true)
                        NativeBridge.nativeMoveSourceOrder(it, 2)
                        NativeBridge.nativeSetSourceTransform(it, bw * 0.5f, bh * 0.5f, 1.0f * sxSign, 1.0f * sySign, camRotDeg)
                    }
                }
            }
        } catch (_: Exception) {}
        return labels[mode]
    }

    private fun findOrActivateCameraSource(): String {
        try {
            val snap = JSONObject(NativeBridge.nativeGetSnapshotJson())
            val sources = snap.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val s = sources.getJSONObject(i)
                    if (s.optString("id") == "android_camera") {
                        return s.optString("name", "Front Camera")
                    }
                }
            }
        } catch (_: Exception) {}
        return MainActivity.activeCameraToggle?.invoke(MainActivity.currentCameraFront) ?: "Front Camera"
    }

    private fun currentDisplaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            val b = wm!!.currentWindowMetrics.bounds
            Pair(b.width().coerceAtLeast(320), b.height().coerceAtLeast(320))
        } else {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels.coerceAtLeast(320), dm.heightPixels.coerceAtLeast(320))
        }
    }

    private fun syncCamTransformToObs(boxX: Int, boxY: Int, boxW: Int, boxH: Int) {
        try {
            val (dw, dh) = currentDisplaySize()
            val dispW = dw.toFloat()
            val dispH = dh.toFloat()
            val snap = JSONObject(NativeBridge.nativeGetSnapshotJson())
            val baseW = snap.optInt("base_width", 1280).coerceAtLeast(1).toFloat()
            val baseH = snap.optInt("base_height", 720).coerceAtLeast(1).toFloat()

            // Exact 1:1 mapping when orientations match; clean full-canvas mapping when Portrait canvas + Landscape game
            val sameOrientation = (baseW >= baseH) == (dispW >= dispH)
            val centerX: Float
            val centerY: Float
            val absScaleX: Float
            val absScaleY: Float
            if (sameOrientation) {
                val fitScale = minOf(baseW / dispW, baseH / dispH)
                val screenCanvasW = dispW * fitScale
                val screenCanvasH = dispH * fitScale
                val offsetX = (baseW - screenCanvasW) / 2f
                val offsetY = (baseH - screenCanvasH) / 2f
                centerX = offsetX + ((boxX + boxW / 2f) / dispW).coerceIn(0.02f, 0.98f) * screenCanvasW
                centerY = offsetY + ((boxY + boxH / 2f) / dispH).coerceIn(0.02f, 0.98f) * screenCanvasH
                absScaleX = ((boxW / dispW) * screenCanvasW / baseW).coerceIn(0.15f, 1.0f)
                absScaleY = ((boxH / dispH) * screenCanvasH / baseH).coerceIn(0.15f, 1.0f)
            } else {
                centerX = ((boxX + boxW / 2f) / dispW).coerceIn(0.05f, 0.95f) * baseW
                centerY = ((boxY + boxH / 2f) / dispH).coerceIn(0.05f, 0.95f) * baseH
                absScaleX = (boxW / dispW).coerceIn(0.20f, 1.0f)
                absScaleY = (boxH / dispH).coerceIn(0.20f, 1.0f)
            }
            // Always use uniform scale (absScaleX == absScaleY) so OBS_BOUNDS_SCALE_INNER preserves 100% true aspect ratio!
            val uniformScale = absScaleX.coerceIn(0.18f, 1.0f)
            val finalSx = if (camFlipH) -uniformScale else uniformScale
            val finalSy = if (camFlipV) -uniformScale else uniformScale

            NativeBridge.nativeSetSourceTransform(activeCamSourceName, centerX, centerY, finalSx, finalSy, camRotDeg)
        } catch (_: Exception) {}
    }

    private fun syncViewToObs(v: View, fallbackX: Int, fallbackY: Int, w: Int, h: Int) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val sx = if (v.isAttachedToWindow && (loc[0] != 0 || loc[1] != 0)) loc[0] else fallbackX
        val sy = if (v.isAttachedToWindow && (loc[0] != 0 || loc[1] != 0)) loc[1] else fallbackY
        syncCamTransformToObs(sx, sy, w, h)
    }

    private fun showCamPipOverlay() {
        hideCamPipOverlay()
        activeCamSourceName = findOrActivateCameraSource()
        val (dispW, dispH) = currentDisplaySize()

        var initW = (dispW * 0.28f).toInt().coerceIn(220, (dispW * 0.65f).toInt())
        var initH = (initW * 9f / 16f).toInt().coerceIn(130, (dispH * 0.65f).toInt())
        var initX = (dispW - initW - 40).coerceAtLeast(16)
        var initY = 48

        // Restore current OBS camera transform if available
        try {
            val snap = JSONObject(NativeBridge.nativeGetSnapshotJson())
            val baseW = snap.optInt("base_width", 1280).coerceAtLeast(1).toFloat()
            val baseH = snap.optInt("base_height", 720).coerceAtLeast(1).toFloat()
            val tj = JSONObject(NativeBridge.nativeGetSourceTransformJson(activeCamSourceName))
            val rawSx = tj.optDouble("scale_x", 0.28).toFloat()
            val rawSy = tj.optDouble("scale_y", 0.28).toFloat()
            camFlipH = rawSx < 0f
            camFlipV = rawSy < 0f
            camRotDeg = tj.optDouble("rot", 0.0).toFloat()
            val sx = abs(rawSx)
            val sy = abs(rawSy)
            if (sx in 0.12f..0.95f && sy in 0.12f..0.95f) {
                initW = (dispW * sx).toInt().coerceIn(200, (dispW * 0.85f).toInt())
                initH = (dispH * sy).toInt().coerceIn(120, (dispH * 0.85f).toInt())
                val cx = (tj.optDouble("x", baseW * 0.80).toFloat() / baseW) * dispW
                val cy = (tj.optDouble("y", baseH * 0.24).toFloat() / baseH) * dispH
                initX = (cx - initW / 2f).toInt().coerceIn(0, (dispW - initW).coerceAtLeast(0))
                initY = (cy - initH / 2f).toInt().coerceIn(0, (dispH - initH).coerceAtLeast(0))
            }
        } catch (_: Exception) {}

        val pipParams = WindowManager.LayoutParams(
            initW,
            initH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val frameBg = GradientDrawable().apply {
            setColor(Color.argb(120, 13, 17, 23))
            cornerRadius = 16f
            setStroke(4, Color.rgb(20, 184, 166)) // Deep Teal PiP border
        }

        val frame = FrameLayout(this).apply {
            background = frameBg
        }

        // Top mini toolbar inside PiP frame: [CAM] [F/B] [S] [M] [L] [✓]
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(210, 13, 17, 23))
            setPadding(8, 4, 8, 4)
        }

        fun miniChip(text: String, color: Int = Color.rgb(241, 245, 249), onTap: (TextView) -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(color)
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(8, 3, 8, 3)
                val bg = GradientDrawable().apply {
                    setColor(Color.rgb(30, 36, 46))
                    cornerRadius = 8f
                }
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(4, 0, 0, 0) }
                layoutParams = lp
                setOnClickListener { onTap(this) }
            }
        }

        val fbBtn = miniChip(if (MainActivity.currentCameraFront) "FRONT" else "BACK", Color.rgb(20, 184, 166)) { tv ->
            val nextFront = !MainActivity.currentCameraFront
            MainActivity.activeCameraToggle?.invoke(nextFront)
            tv.text = if (nextFront) "FRONT" else "BACK"
            // Re-apply current PiP box transform right after camera switch
            syncCamTransformToObs(pipParams.x, pipParams.y, pipParams.width, pipParams.height)
        }

        fun applyScalePreset(frac: Float) {
            val w = (dispW * frac).toInt().coerceIn(200, (dispW * 0.9f).toInt())
            val h = (dispH * frac).toInt().coerceIn(120, (dispH * 0.9f).toInt())
            pipParams.width = w
            pipParams.height = h
            pipParams.x = pipParams.x.coerceIn(0, (dispW - w).coerceAtLeast(0))
            pipParams.y = pipParams.y.coerceIn(0, (dispH - h).coerceAtLeast(0))
            wm?.updateViewLayout(frame, pipParams)
            syncCamTransformToObs(pipParams.x, pipParams.y, w, h)
        }

        bar.addView(fbBtn)
        bar.addView(miniChip("S") { applyScalePreset(0.22f) })
        bar.addView(miniChip("M") { applyScalePreset(0.32f) })
        bar.addView(miniChip("L") { applyScalePreset(0.45f) })
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        bar.addView(spacer)
        bar.addView(miniChip("✓ DONE", Color.rgb(34, 197, 94)) { hideCamPipOverlay() })

        frame.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        val hint = TextView(this).apply {
            text = "DRAG CAM • RESIZE ◢"
            setTextColor(Color.argb(210, 20, 184, 166))
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        frame.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        // Bottom-left Rotate & Flip controls inside PiP frame: [↻ 0°] [⇄ H] [⇅ V]
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(190, 13, 17, 23))
            setPadding(6, 4, 8, 4)
        }

        val rotBtn = miniChip("↻ ${camRotDeg.toInt()}°", Color.rgb(20, 184, 166)) { tv ->
            camRotDeg = ((camRotDeg.toInt() + 90) % 360).toFloat()
            tv.text = "↻ ${camRotDeg.toInt()}°"
            syncViewToObs(frame, pipParams.x, pipParams.y, pipParams.width, pipParams.height)
        }
        val flipHBtn = miniChip("⇄ H", if (camFlipH) Color.rgb(20, 184, 166) else Color.rgb(241, 245, 249)) { tv ->
            camFlipH = !camFlipH
            tv.setTextColor(if (camFlipH) Color.rgb(20, 184, 166) else Color.rgb(241, 245, 249))
            syncViewToObs(frame, pipParams.x, pipParams.y, pipParams.width, pipParams.height)
        }
        val flipVBtn = miniChip("⇅ V", if (camFlipV) Color.rgb(20, 184, 166) else Color.rgb(241, 245, 249)) { tv ->
            camFlipV = !camFlipV
            tv.setTextColor(if (camFlipV) Color.rgb(20, 184, 166) else Color.rgb(241, 245, 249))
            syncViewToObs(frame, pipParams.x, pipParams.y, pipParams.width, pipParams.height)
        }
        bottomBar.addView(rotBtn)
        bottomBar.addView(flipHBtn)
        bottomBar.addView(flipVBtn)

        frame.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ),
        )

        // Bottom-right corner resize handle
        val resizeHandle = TextView(this).apply {
            text = "◢"
            setTextColor(Color.rgb(20, 184, 166))
            textSize = 18f
            setPadding(16, 12, 8, 4)
        }
        frame.addView(
            resizeHandle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ),
        )

        // Drag frame to move camera PiP in real time
        var dragX = 0
        var dragY = 0
        var startRawX = 0f
        var startRawY = 0f
        frame.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = pipParams.x
                    dragY = pipParams.y
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (dw, dh) = currentDisplaySize()
                    val maxX = (dw - pipParams.width).coerceAtLeast(0)
                    val maxY = (dh - pipParams.height).coerceAtLeast(0)
                    pipParams.x = (dragX + (ev.rawX - startRawX).toInt()).coerceIn(0, maxX)
                    pipParams.y = (dragY + (ev.rawY - startRawY).toInt()).coerceIn(0, maxY)
                    wm?.updateViewLayout(frame, pipParams)
                    frame.post { syncViewToObs(frame, pipParams.x, pipParams.y, pipParams.width, pipParams.height) }
                    true
                }
                else -> false
            }
        }

        // Drag corner handle to resize camera PiP in real time
        var startW = 0
        var startH = 0
        var resRawX = 0f
        var resRawY = 0f
        resizeHandle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = pipParams.width
                    startH = pipParams.height
                    resRawX = ev.rawX
                    resRawY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (dw, dh) = currentDisplaySize()
                    val newW = (startW + (ev.rawX - resRawX).toInt()).coerceIn(200, (dw * 0.9f).toInt())
                    val newH = (startH + (ev.rawY - resRawY).toInt()).coerceIn(120, (dh * 0.9f).toInt())
                    pipParams.width = newW
                    pipParams.height = newH
                    wm?.updateViewLayout(frame, pipParams)
                    frame.post { syncViewToObs(frame, pipParams.x, pipParams.y, newW, newH) }
                    true
                }
                else -> false
            }
        }

        camPipView = frame
        camPipParams = pipParams
        wm?.addView(frame, pipParams)
        frame.post { syncViewToObs(frame, pipParams.x, pipParams.y, pipParams.width, pipParams.height) }
    }

    private fun hideCamPipOverlay() {
        camPipView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        camPipView = null
        camPipParams = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideCamPipOverlay()
        rootView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        rootView = null
    }
}
