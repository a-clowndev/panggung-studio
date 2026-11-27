package io.panggung.obs.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import io.panggung.obs.NativeBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

data class CameraLensInfo(
    val cameraId: String,
    val label: String,
    val isFront: Boolean,
    val focalLengthMm: Float,
    val isPhysical: Boolean = false,
)

data class ProCameraCapabilities(
    val isoRange: Range<Int> = Range(100, 3200),
    val exposureNsRange: Range<Long> = Range(250_000L, 33_000_000L),
    val evRange: Range<Int> = Range(-12, 12),
    val zoomRange: Range<Float> = Range(1.0f, 8.0f),
    val minFocusDistance: Float = 0.0f,
    val supportsManualExposure: Boolean = false,
    val supportsManualFocus: Boolean = false,
    val supportsTorch: Boolean = false,
    val supportsOis: Boolean = false,
    val supportsEis: Boolean = false,
)

data class ProCameraState(
    val manualExposure: Boolean = false,
    val iso: Int = 400,
    val shutterSpeedDenom: Int = 60, // 1/60s -> 16_666_666 ns
    val exposureComp: Int = 0,
    val aeLock: Boolean = false,
    val manualFocus: Boolean = false,
    val focusDistance: Float = 0.0f, // 0 = infinity, minFocusDistance = macro
    val wbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO, // -1 = manual Kelvin
    val wbKelvin: Int = 5500,
    val awbLock: Boolean = false,
    val zoomRatio: Float = 1.0f,
    val torchOn: Boolean = false,
    val stabilization: Boolean = true,
)

/**
 * Zero-Copy AHardwareBuffer + Multi-Lens + Pro Camera2 Capture Engine (v0.3.0):
 * - Zero-copy GPU path: ImageReader(ImageFormat.PRIVATE, USAGE_GPU_SAMPLED_IMAGE) -> AHardwareBuffer -> EGLImageKHR -> libobs-gles
 * - Multi-instance safe: each instance owns its own HandlerThread & target OBS source name for concurrent dual-camera (Back + Front PiP)
 * - Complete Pro Controls: ISO, Shutter Speed, EV, AE/AWB Lock, Manual Focus, White Balance (Presets + Kelvin RGGB), Zoom, Torch, OIS/EIS
 */
class Camera2Capture(
    private val context: Context,
    private val instanceTag: String = "MainCam",
) {
    private val camThread = HandlerThread("PanggungCam2-$instanceTag").apply { start() }
    private val camHandler = Handler(camThread.looper)

    private val generation = AtomicLong(0)
    private val lock = Any()

    private var cameraDevice: CameraDevice? = null
    private var closeLatch: CountDownLatch? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var activeReqBuilder: CaptureRequest.Builder? = null

    @Volatile var currentCameraId: String? = null; private set
    @Volatile var currentSourceName: String = "Back Camera"; private set
    @Volatile var currentFacingFront: Boolean = false; private set
    @Volatile var capabilities: ProCameraCapabilities = ProCameraCapabilities(); private set
    @Volatile var proState: ProCameraState = ProCameraState(); private set

    companion object {
        /** Enumerate all logical & physical camera lenses (Ultra-Wide, Wide, Telephoto, Front). */
        fun enumerateLenses(context: Context): List<CameraLensInfo> {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val out = mutableListOf<CameraLensInfo>()
            val backLenses = mutableListOf<Pair<String, Float>>()
            var frontId: String? = null
            var frontFocal = 3.0f

            for (id in mgr.cameraIdList) {
                try {
                    val c = mgr.getCameraCharacteristics(id)
                    val facing = c.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val fl = focals?.firstOrNull() ?: 4.0f
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        if (frontId == null) {
                            frontId = id
                            frontFocal = fl
                        }
                    } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        backLenses.add(id to fl)
                    }
                } catch (_: Exception) {}
            }

            // Sort back lenses by focal length ascending (Ultra-Wide -> Wide -> Telephoto)
            val sortedBack = backLenses.sortedBy { it.second }
            if (sortedBack.isNotEmpty()) {
                val baseFl = sortedBack.firstOrNull { it.first == "0" }?.second ?: sortedBack[sortedBack.size / 2].second
                sortedBack.forEachIndexed { idx, (id, fl) ->
                    val ratio = if (baseFl > 0.1f) fl / baseFl else 1.0f
                    val label = when {
                        id == "0" -> "1x Wide"
                        ratio < 0.78f -> "0.6x UW"
                        ratio > 1.7f -> "${String.format("%.0f", ratio)}x Tele"
                        else -> "Back #$idx (${String.format("%.1f", fl)}mm)"
                    }
                    if (out.none { it.label == label }) {
                        out.add(CameraLensInfo(id, label, isFront = false, focalLengthMm = fl))
                    }
                }
            }
            if (frontId != null) {
                out.add(CameraLensInfo(frontId, "Front", isFront = true, focalLengthMm = frontFocal))
            }
            return out
        }

        /** Query hardware-supported concurrent Back+Front camera ID pair (Android 11+ / API 30+). */
        fun findConcurrentBackFrontPair(context: Context): Pair<String, String>? {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    for (set in mgr.concurrentCameraIds) {
                        var back: String? = null
                        var front: String? = null
                        for (id in set) {
                            val facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                            if (facing == CameraCharacteristics.LENS_FACING_BACK && back == null) back = id
                            if (facing == CameraCharacteristics.LENS_FACING_FRONT && front == null) front = id
                        }
                        if (back != null && front != null) {
                            return back to front
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Camera2Capture", "concurrentCameraIds query failed: ${e.message}")
                }
            }
            // Fallback best-effort pair ("0" back + "1" front)
            val back = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val front = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
            return if (back != null && front != null) back to front else null
        }

        /** Convert correlated color temperature (2000K-10000K) to Camera2 RGGB gain vector. */
        private fun kelvinToRggb(kelvin: Int): RggbChannelVector {
            val temp = kelvin.coerceIn(2000, 10000) / 100.0
            val r: Double
            val g: Double
            val b: Double
            if (temp <= 66.0) {
                r = 255.0
                g = (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(1.0, 255.0)
                b = if (temp <= 19.0) 1.0 else (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(1.0, 255.0)
            } else {
                r = (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(1.0, 255.0)
                g = (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(1.0, 255.0)
                b = 255.0
            }
            // Normalize so green = 1.0
            val gn = g.coerceAtLeast(1.0)
            return RggbChannelVector((r / gn).toFloat(), 1.0f, 1.0f, (b / gn).toFloat())
        }
    }

    /** Apply complete Pro Camera Controls to the active Camera2 repeating request. */
    fun applyProControls(state: ProCameraState) {
        proState = state
        camHandler.post {
            val req = activeReqBuilder ?: return@post
            val s = session ?: return@post
            try {
                applyProControlsToBuilder(req, state, capabilities)
                s.setRepeatingRequest(req.build(), null, camHandler)
            } catch (e: Exception) {
                Log.w("Camera2Capture", "[$instanceTag] applyProControls: ${e.message}")
            }
        }
    }

    /** Backwards-compatible quick helper. */
    fun setProControls(
        exposureComp: Int = 0,
        zoomRatio: Float = 1.0f,
        torchOn: Boolean = false,
        stabilization: Boolean = true,
    ) {
        applyProControls(
            proState.copy(
                exposureComp = exposureComp,
                zoomRatio = zoomRatio,
                torchOn = torchOn,
                stabilization = stabilization,
            )
        )
    }

    private fun applyProControlsToBuilder(
        req: CaptureRequest.Builder,
        st: ProCameraState,
        cap: ProCameraCapabilities,
    ) {
        // 1. Exposure: Auto vs Manual (ISO + Shutter Speed)
        if (st.manualExposure && cap.supportsManualExposure) {
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val iso = st.iso.coerceIn(cap.isoRange.lower, cap.isoRange.upper)
            val expNs = (1_000_000_000L / st.shutterSpeedDenom.coerceIn(15, 4000))
                .coerceIn(cap.exposureNsRange.lower, cap.exposureNsRange.upper)
            req.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
        } else {
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            req.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                st.exposureComp.coerceIn(cap.evRange.lower, cap.evRange.upper),
            )
            req.set(CaptureRequest.CONTROL_AE_LOCK, st.aeLock)
        }

        // 2. Focus: Continuous Video AF vs Manual Focus Distance
        if (st.manualFocus && cap.supportsManualFocus) {
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            req.set(CaptureRequest.LENS_FOCUS_DISTANCE, st.focusDistance.coerceIn(0f, cap.minFocusDistance))
        } else {
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // 3. White Balance: Auto, Hardware Preset, or Manual Kelvin RGGB Gains
        if (st.wbMode == -1) {
            req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            req.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            req.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(st.wbKelvin))
        } else {
            req.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            req.set(CaptureRequest.CONTROL_AWB_MODE, st.wbMode)
            req.set(CaptureRequest.CONTROL_AWB_LOCK, st.awbLock)
        }

        // 4. Optical/Digital Zoom Ratio (Android 11+ supports < 1.0x ultra-wide smoothly)
        if (Build.VERSION.SDK_INT >= 30) {
            req.set(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                st.zoomRatio.coerceIn(cap.zoomRange.lower, cap.zoomRange.upper),
            )
        }

        // 5. Torch
        if (cap.supportsTorch) {
            req.set(
                CaptureRequest.FLASH_MODE,
                if (st.torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
            )
        }

        // 6. Hardware OIS (zero crop, preferred) vs EIS
        if (st.stabilization) {
            if (cap.supportsOis) {
                req.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            } else if (cap.supportsEis) {
                req.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
        } else {
            if (cap.supportsOis) req.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            if (cap.supportsEis) req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(
        front: Boolean = false,
        width: Int = 1280,
        height: Int = 720,
        explicitCameraId: String? = null,
        sourceName: String? = null,
    ): Int {
        val myGen = generation.incrementAndGet()
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = explicitCameraId ?: run {
            val targetFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: mgr.cameraIdList.firstOrNull() ?: return 0
        }

        val chars = mgr.getCameraCharacteristics(camId)
        val facingFront = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val cap = queryCapabilities(chars)
        capabilities = cap
        currentCameraId = camId
        currentFacingFront = facingFront
        currentSourceName = sourceName ?: if (facingFront) "Front Camera" else "Back Camera"

        val chosen = pickBestPrivateSize(chars, width, height)

        camHandler.post {
            if (myGen != generation.get()) return@post
            closeCurrentLocked()
            if (myGen != generation.get()) return@post

            // Zero-Copy GPU AHardwareBuffer ImageReader (ImageFormat.PRIVATE + USAGE_GPU_SAMPLED_IMAGE)
            val ir = ImageReader.newInstance(
                chosen.width,
                chosen.height,
                ImageFormat.PRIVATE,
                3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
            )
            synchronized(lock) { reader = ir }

            val srcNameSnapshot = currentSourceName
            ir.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    if (myGen != generation.get()) return@setOnImageAvailableListener
                    val hb = img.hardwareBuffer
                    if (hb != null) {
                        try {
                            NativeBridge.nativePushCameraHardwareBuffer(
                                srcNameSnapshot,
                                hb,
                                img.width,
                                img.height,
                                img.timestamp,
                            )
                        } finally {
                            hb.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Camera2Capture", "[$instanceTag] AHB frame err: ${e.message}")
                } finally {
                    img.close()
                }
            }, camHandler)

            val latch = CountDownLatch(1)
            synchronized(lock) { closeLatch = latch }

            try {
                Log.i("Camera2Capture", "[$instanceTag] Opening camId=$camId (front=$facingFront, src='$srcNameSnapshot') ${chosen.width}x${chosen.height} AHB-ZeroCopy gen=$myGen")
                mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        if (myGen != generation.get()) {
                            cam.close()
                            return
                        }
                        synchronized(lock) { cameraDevice = cam }
                        val surface = ir.surface
                        try {
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                applyProControlsToBuilder(this, proState, cap)
                            }
                            activeReqBuilder = req
                            @Suppress("DEPRECATION")
                            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (myGen != generation.get()) {
                                        try { s.close() } catch (_: Exception) {}
                                        return
                                    }
                                    synchronized(lock) { session = s }
                                    try {
                                        s.setRepeatingRequest(req.build(), null, camHandler)
                                        Log.i("Camera2Capture", "Streaming camId=$camId (front=$facingFront, src='$srcNameSnapshot') AHB-ZeroCopy gen=$myGen")
                                    } catch (e: Exception) {
                                        Log.e("Camera2Capture", "[$instanceTag] setRepeatingRequest failed", e)
                                    }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    Log.e("Camera2Capture", "[$instanceTag] CaptureSession configure failed for camId=$camId")
                                }
                            }, camHandler)
                        } catch (e: Exception) {
                            Log.e("Camera2Capture", "[$instanceTag] createCaptureSession failed", e)
                        }
                    }

                    override fun onClosed(cam: CameraDevice) { latch.countDown() }
                    override fun onDisconnected(cam: CameraDevice) { cam.close(); latch.countDown() }
                    override fun onError(cam: CameraDevice, error: Int) {
                        Log.e("Camera2Capture", "[$instanceTag] Camera error=$error camId=$camId gen=$myGen")
                        cam.close()
                        latch.countDown()
                    }
                }, camHandler)
            } catch (e: Exception) {
                Log.e("Camera2Capture", "[$instanceTag] openCamera failed", e)
                latch.countDown()
            }
        }
        return sensorOrientation
    }

    private fun queryCapabilities(chars: CameraCharacteristics): ProCameraCapabilities {
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
        val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(250_000L, 33_000_000L)
        val ev = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-12, 12)
        val zoom = if (Build.VERSION.SDK_INT >= 30) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1.0f, 8.0f)
        } else {
            Range(1.0f, 4.0f)
        }
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        return ProCameraCapabilities(
            isoRange = iso,
            exposureNsRange = exp,
            evRange = ev,
            zoomRange = zoom,
            minFocusDistance = minFocus,
            supportsManualExposure = manualSensor,
            supportsManualFocus = minFocus > 0.0f,
            supportsTorch = flash,
            supportsOis = oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON),
            supportsEis = eisModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON),
        )
    }

    private fun pickBestPrivateSize(chars: CameraCharacteristics, reqW: Int, reqH: Int): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.PRIVATE)
            ?: map?.getOutputSizes(ImageFormat.YUV_420_888)
            ?: return Size(reqW, reqH)
        return sizes
            .filter { it.width >= 640 && it.width <= 1920 && (it.width % 2 == 0) && (it.height % 2 == 0) }
            .minByOrNull { abs(it.width - reqW) + abs(it.height - reqH) }
            ?: Size(reqW, reqH)
    }

    private fun closeCurrentLocked() {
        val oldSession: CameraCaptureSession?
        val oldCam: CameraDevice?
        val oldReader: ImageReader?
        val oldLatch: CountDownLatch?
        synchronized(lock) {
            oldSession = session; session = null
            oldCam = cameraDevice; cameraDevice = null
            oldReader = reader; reader = null
            oldLatch = closeLatch; closeLatch = null
            activeReqBuilder = null
        }
        try { oldSession?.abortCaptures() } catch (_: Exception) {}
        try { oldSession?.close() } catch (_: Exception) {}
        try { oldCam?.close() } catch (_: Exception) {}
        if (oldCam != null && oldLatch != null) {
            try { oldLatch.await(1200, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
        try { oldReader?.close() } catch (_: Exception) {}
    }

    fun isRunning(): Boolean = synchronized(lock) { cameraDevice != null }

    fun stop() {
        generation.incrementAndGet()
        camHandler.post { closeCurrentLocked() }
    }
}
