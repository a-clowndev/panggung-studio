package io.panggung.obs.capture

import android.graphics.*
import io.panggung.obs.NativeBridge
import java.nio.ByteBuffer

/**
 * Real offscreen Android Canvas -> RGBA ByteBuffer frame pump for:
 *  - panggung_device_stats (SRC-24: live FPS, battery, temp, thermal headroom)
 *  - panggung_text_android (SRC-07: gradient text, outline, drop shadow, badge)
 *  - panggung_now_playing  (SRC-23: MediaSession Now Playing card)
 */
object LiveOverlaysEngine {
    private var reuseBuf: ByteBuffer? = null

    private fun pushBitmap(sourceName: String, bmp: Bitmap) {
        val bytes = bmp.byteCount
        if (reuseBuf == null || reuseBuf!!.capacity() < bytes) {
            reuseBuf = ByteBuffer.allocateDirect(bytes)
        }
        val buf = reuseBuf!!
        buf.clear()
        bmp.copyPixelsToBuffer(buf)
        buf.rewind()
        NativeBridge.nativePushSourceRgba(sourceName, buf, bmp.rowBytes, bmp.width, bmp.height)
    }

    fun renderDeviceStats(sourceName: String, fps: Double, tempC: Double, battPct: Int, thermal: Float) {
        val w = 520; val h = 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 15, 23, 42) }
        c.drawRoundRect(RectF(4f, 4f, w - 4f, h - 4f), 16f, 16f, bg)
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 189, 248) }
        c.drawRoundRect(RectF(4f, 4f, 14f, h - 4f), 8f, 8f, accent)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        c.drawText("PANGGUNG LIVE  |  %.1f FPS".format(fps), 28f, 40f, txt)
        txt.color = Color.rgb(148, 163, 184)
        txt.textSize = 22f
        c.drawText("BATT %d%%  •  %.1f°C  •  THERMAL %.2f".format(battPct, tempC, thermal), 28f, 76f, txt)
        pushBitmap(sourceName, bmp)
        bmp.recycle()
    }

    fun renderRichGradientText(sourceName: String, text: String, subtitle: String = "Panggung Studio Android") {
        val w = 800; val h = 140
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            color = Color.argb(230, 0, 0, 0)
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Color.rgb(56, 189, 248), Color.rgb(167, 139, 250), Shader.TileMode.CLAMP)
        }
        c.drawText(text, 24f, 74f, stroke)
        c.drawText(text, 24f, 74f, fill)
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 226, 232, 240)
            textSize = 26f
        }
        c.drawText(subtitle, 26f, 116f, sub)
        pushBitmap(sourceName, bmp)
        bmp.recycle()
    }
}
