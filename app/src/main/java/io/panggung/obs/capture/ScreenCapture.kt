package io.panggung.obs.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import io.panggung.obs.NativeBridge
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 14+ Compliant Screen Capture (T-C03) + Per-App Audio Capture (T-D03):
 * - Single persistent VirtualDisplay per MediaProjection authorization
 * - Proportional aspect-preserving resize() + DisplayListener orientation sync (eliminates black letterbox bars!)
 * - Per-app UID filtering via AudioPlaybackCaptureConfiguration.Builder.addMatchingUid / excludeUid
 */
class ScreenCapture {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var currentDpi: Int = 320
    private var currentW: Int = 0
    private var currentH: Int = 0
    private val audioRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(
        mediaProjection: MediaProjection,
        width: Int = 1280,
        height: Int = 720,
        dpi: Int = 320,
        captureInternalAudio: Boolean = true,
        matchingUids: List<Int> = emptyList(),
        excludedUids: List<Int> = emptyList(),
        context: Context? = null,
    ) {
        stop()
        projection = mediaProjection
        currentDpi = dpi
        val ht = HandlerThread("PanggungScreen").apply { start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        // Match initial VirtualDisplay orientation to actual physical display orientation
        var initW = width
        var initH = height
        if (context != null) {
            val dm = context.getSystemService(DisplayManager::class.java)
            displayManager = dm
            val disp = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (disp != null) {
                val pt = Point()
                @Suppress("DEPRECATION")
                disp.getRealSize(pt)
                if (pt.x > 16 && pt.y > 16) {
                    val scale = minOf(1f, 1280f / maxOf(pt.x, pt.y).toFloat())
                    initW = ((pt.x * scale).toInt() and 0xFFFE).coerceAtLeast(320)
                    initH = ((pt.y * scale).toInt() and 0xFFFE).coerceAtLeast(320)
                }
            }
            val dl = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != Display.DEFAULT_DISPLAY) return
                    val d = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
                    val p = Point()
                    @Suppress("DEPRECATION")
                    d.getRealSize(p)
                    if (p.x > 16 && p.y > 16) {
                        resizeVirtualDisplay(p.x, p.y)
                    }
                }
            }
            displayListener = dl
            dm?.registerDisplayListener(dl, h)
        }

        // Android 14+ mandatory callback BEFORE createVirtualDisplay() + dynamic resize tracking
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i("ScreenCapture", "MediaProjection stopped by system")
                stop()
            }

            override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
                if (newWidth > 16 && newHeight > 16) {
                    Log.i("ScreenCapture", "onCapturedContentResize -> ${newWidth}x${newHeight}")
                    resizeVirtualDisplay(newWidth, newHeight)
                }
            }
        }, h)

        val ir = createImageReader(initW, initH, h)
        reader = ir
        currentW = initW
        currentH = initH

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "PanggungScreen",
            initW,
            initH,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface,
            null,
            h,
        )

        if (captureInternalAudio) {
            startInternalAudio(mediaProjection, matchingUids, excludedUids)
        }
    }

    /**
     * Proportionally scales & resizes the VirtualDisplay so landscape/portrait games NEVER get letterboxed inside the buffer.
     */
    fun resizeVirtualDisplay(newWidth: Int, newHeight: Int) {
        val maxDim = maxOf(newWidth, newHeight).coerceAtLeast(1)
        val scale = minOf(1f, 1280f / maxDim.toFloat())
        val targetW = ((newWidth * scale).toInt().coerceIn(320, 1280)) and 0xFFFE
        val targetH = ((newHeight * scale).toInt().coerceIn(320, 1280)) and 0xFFFE
        // Prevent Android 14-17 infinite onCapturedContentResize loop when dimensions haven't changed!
        if (targetW == currentW && targetH == currentH) return
        val vd = virtualDisplay ?: return
        val h = handler ?: return
        Log.i("ScreenCapture", "Proportional VirtualDisplay resize -> ${targetW}x${targetH} (from ${newWidth}x${newHeight})")
        currentW = targetW
        currentH = targetH
        val oldReader = reader
        val nextReader = createImageReader(targetW, targetH, h)
        reader = nextReader
        try {
            vd.resize(targetW, targetH, currentDpi)
            vd.surface = nextReader.surface
        } catch (e: Exception) {
            Log.w("ScreenCapture", "Dynamic VirtualDisplay resize failed", e)
        }
        try { oldReader?.close() } catch (_: Exception) {}
    }

    private fun createImageReader(width: Int, height: Int, h: Handler): ImageReader {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = img.planes[0]
                    NativeBridge.nativePushScreenRgba(
                        plane.buffer,
                        plane.rowStride,
                        img.width,
                        img.height,
                        img.timestamp,
                    )
                } finally {
                    img.close()
                }
            }, h)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startInternalAudio(
        mediaProjection: MediaProjection,
        matchingUids: List<Int>,
        excludedUids: List<Int>,
    ) {
        val sampleRate = 48000
        val cfgBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        matchingUids.forEach { cfgBuilder.addMatchingUid(it) }
        excludedUids.forEach { cfgBuilder.excludeUid(it) }
        val cfg = cfgBuilder.build()

        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(8192)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        } catch (e: Exception) {
            Log.e("ScreenCapture", "AudioPlaybackCapture init failed", e)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        audioRunning.set(true)
        audioThread = Thread({
            val buf = ByteBuffer.allocateDirect(4096)
            record.startRecording()
            try {
                while (audioRunning.get()) {
                    buf.clear()
                    val n = record.read(buf, buf.capacity())
                    if (n > 0) {
                        NativeBridge.nativePushAudioOutputS16(buf, n / 4, sampleRate, 2, SystemClock.elapsedRealtimeNanos())
                    }
                }
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }, "PanggungInternalAudio").apply { start() }
    }

    fun stop() {
        audioRunning.set(false)
        try { displayListener?.let { displayManager?.unregisterDisplayListener(it) } } catch (_: Exception) {}
        displayListener = null
        displayManager = null
        try { audioThread?.join(300) } catch (_: Exception) {}
        audioThread = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        virtualDisplay = null
        reader = null
        projection = null
        thread = null
        handler = null
    }
}
