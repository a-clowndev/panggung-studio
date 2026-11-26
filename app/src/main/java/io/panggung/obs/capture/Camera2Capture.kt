package io.panggung.obs.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import io.panggung.obs.NativeBridge
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Race-free, Zero-Green-Line Camera2 Capture Engine:
 * - Single persistent HandlerThread + AtomicLong session generation (eliminates front/back switch race)
 * - Synchronous CountDownLatch wait on onClosed() before opening new camera HAL device
 * - Picks hardware-supported YUV_420_888 size closest to requested dimensions
 * - Bounds-clamped plane copy with neutral chroma fill (128, never 0) so green lines are impossible
 */
class Camera2Capture(private val context: Context) {
    private val camThread = HandlerThread("PanggungCam2").apply { start() }
    private val camHandler = Handler(camThread.looper)

    private val generation = AtomicLong(0)
    private val lock = Any()

    private var cameraDevice: CameraDevice? = null
    private var closeLatch: CountDownLatch? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var activeReqBuilder: CaptureRequest.Builder? = null
    private var currentFacingFront: Boolean? = null

    private var yBuf: ByteBuffer? = null
    private var uBuf: ByteBuffer? = null
    private var vBuf: ByteBuffer? = null
    private var rowScratch = ByteArray(0)

    /** Pro Camera Controls (AP-12): Exposure Compensation, Zoom Ratio (Android 11+), Torch. */
    fun setProControls(exposureComp: Int = 0, zoomRatio: Float = 1.0f, torchOn: Boolean = false) {
        camHandler.post {
            val req = activeReqBuilder ?: return@post
            val s = session ?: return@post
            try {
                req.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureComp.coerceIn(-12, 12))
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    req.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(1.0f, 8.0f))
                }
                req.set(
                    CaptureRequest.FLASH_MODE,
                    if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
                )
                s.setRepeatingRequest(req.build(), null, camHandler)
            } catch (e: Exception) {
                Log.w("Camera2Capture", "setProControls: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(front: Boolean = false, width: Int = 1280, height: Int = 720): Int {
        val myGen = generation.incrementAndGet()
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val camId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
        } ?: mgr.cameraIdList.firstOrNull() ?: return 0

        val chars = mgr.getCameraCharacteristics(camId)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val chosen = pickBestYuvSize(chars, width, height)
        currentFacingFront = front

        // Serialize close-then-open on the single camera handler thread so HAL never races
        camHandler.post {
            if (myGen != generation.get()) return@post
            closeCurrentLocked()
            if (myGen != generation.get()) return@post

            val ir = ImageReader.newInstance(chosen.width, chosen.height, ImageFormat.YUV_420_888, 3)
            synchronized(lock) { reader = ir }

            ir.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    if (myGen != generation.get()) return@setOnImageAvailableListener
                    val w = img.width and 0xFFFE // ensure even width
                    val hPx = img.height and 0xFFFE // ensure even height
                    if (w <= 0 || hPx <= 0) return@setOnImageAvailableListener
                    val planes = img.planes
                    val yPlane = planes[0]
                    val uPlane = planes[1]
                    val vPlane = planes[2]

                    val yNeed = w * hPx
                    val uvW = w / 2
                    val uvH = hPx / 2
                    val uvNeed = uvW * uvH

                    if (yBuf == null || yBuf!!.capacity() < yNeed) {
                        yBuf = ByteBuffer.allocateDirect(yNeed)
                        uBuf = ByteBuffer.allocateDirect(uvNeed)
                        vBuf = ByteBuffer.allocateDirect(uvNeed)
                    }
                    val yb = yBuf!!; val ub = uBuf!!; val vb = vBuf!!

                    copyPlaneSafe(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, w, hPx, yb, 0)
                    copyPlaneSafe(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, uvW, uvH, ub, 128)
                    copyPlaneSafe(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, uvW, uvH, vb, 128)

                    NativeBridge.nativePushCameraI420(yb, ub, vb, w, uvW, uvW, w, hPx, img.timestamp)
                } catch (e: Exception) {
                    Log.w("Camera2Capture", "frame copy err: ${e.message}")
                } finally {
                    img.close()
                }
            }, camHandler)

            val latch = CountDownLatch(1)
            synchronized(lock) { closeLatch = latch }

            try {
                Log.i("Camera2Capture", "Opening camId=$camId (front=$front) ${chosen.width}x${chosen.height} gen=$myGen")
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
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
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
                                        Log.i("Camera2Capture", "Streaming camId=$camId (front=$front) gen=$myGen")
                                    } catch (e: Exception) {
                                        Log.e("Camera2Capture", "setRepeatingRequest failed", e)
                                    }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    Log.e("Camera2Capture", "CaptureSession configure failed for camId=$camId")
                                }
                            }, camHandler)
                        } catch (e: Exception) {
                            Log.e("Camera2Capture", "createCaptureSession failed", e)
                        }
                    }

                    override fun onClosed(cam: CameraDevice) {
                        latch.countDown()
                    }

                    override fun onDisconnected(cam: CameraDevice) {
                        cam.close()
                        latch.countDown()
                    }

                    override fun onError(cam: CameraDevice, error: Int) {
                        Log.e("Camera2Capture", "Camera error=$error camId=$camId gen=$myGen")
                        cam.close()
                        latch.countDown()
                    }
                }, camHandler)
            } catch (e: Exception) {
                Log.e("Camera2Capture", "openCamera failed", e)
                latch.countDown()
            }
        }
        return sensorOrientation
    }

    private fun pickBestYuvSize(chars: CameraCharacteristics, reqW: Int, reqH: Int): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(reqW, reqH)
        return sizes
            .filter { it.width >= 640 && it.width <= 1920 && (it.width % 2 == 0) && (it.height % 2 == 0) }
            .minByOrNull { abs(it.width - reqW) + abs(it.height - reqH) }
            ?: Size(reqW, reqH)
    }

    /**
     * Bounds-safe YUV plane copy with neutral chroma fill (128 for U/V) so truncated last-row
     * strides on Samsung/Exynos/Qualcomm Camera2 NEVER produce U=0/V=0 bright green lines.
     */
    private fun copyPlaneSafe(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        dst: ByteBuffer,
        neutralFill: Int,
    ) {
        dst.clear()
        val cap = src.capacity()
        val fillByte = neutralFill.toByte()
        if (pixelStride == 1 && rowStride == width && cap >= width * height) {
            val dup = src.duplicate()
            dup.position(0)
            dup.limit(width * height)
            dst.put(dup)
            return
        }
        if (rowScratch.size < rowStride) {
            rowScratch = ByteArray(rowStride)
        }
        val dup = src.duplicate()
        for (r in 0 until height) {
            val offset = r * rowStride
            val avail = cap - offset
            if (avail <= 0) {
                for (c in 0 until width) dst.put(fillByte)
                continue
            }
            val len = minOf(rowStride, avail)
            dup.position(offset)
            dup.get(rowScratch, 0, len)
            if (pixelStride == 1) {
                val copyCols = minOf(width, len)
                dst.put(rowScratch, 0, copyCols)
                for (c in copyCols until width) dst.put(fillByte)
            } else {
                var c = 0
                while (c < width) {
                    val idx = c * pixelStride
                    dst.put(if (idx < len) rowScratch[idx] else fillByte)
                    c++
                }
            }
        }
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
            // Wait cleanly for HAL onClosed() before opening next camera
            try { oldLatch.await(1200, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
        try { oldReader?.close() } catch (_: Exception) {}
    }

    fun stop() {
        generation.incrementAndGet()
        camHandler.post { closeCurrentLocked() }
    }
}
