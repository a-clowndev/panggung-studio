package io.panggung.obs.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import io.panggung.obs.NativeBridge
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioInputCapture {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(sampleRate: Int = 48000) {
        if (!running.compareAndSet(false, true)) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: Exception) {
            Log.e("AudioInputCapture", "Failed to create AudioRecord", e)
            running.set(false)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            return
        }

        worker = Thread({
            val directBuf = ByteBuffer.allocateDirect(4096)
            record.startRecording()
            try {
                while (running.get()) {
                    directBuf.clear()
                    val read = record.read(directBuf, directBuf.capacity())
                    if (read > 0) {
                        val frames = read / 4 // 2 channels * 2 bytes (S16)
                        NativeBridge.nativePushAudioInputS16(
                            directBuf,
                            frames,
                            sampleRate,
                            2,
                            SystemClock.elapsedRealtimeNanos(),
                        )
                    }
                }
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }, "PanggungMic").apply { start() }
    }

    fun stop() {
        running.set(false)
        try { worker?.join(300) } catch (_: Exception) {}
        worker = null
    }
}
