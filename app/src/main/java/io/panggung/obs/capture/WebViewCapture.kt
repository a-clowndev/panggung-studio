package io.panggung.obs.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import io.panggung.obs.NativeBridge
import java.nio.ByteBuffer

/**
 * Transparent Browser Overlay Source (T-C03):
 * - Off-screen transparent WebView with window.obsstudio JS bridge
 * - Custom CSS injection on page load
 * - Configurable FPS cap (default 30 fps)
 * - Touch interact forwarding via dispatchTouch()
 */
class WebViewCapture(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var directBuf: ByteBuffer? = null
    private var running = false
    private var width = 1280
    private var height = 720
    private var frameIntervalMs = 33L

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val wv = webView ?: return
            val bmp = bitmap ?: return
            val c = canvas ?: return
            val buf = directBuf ?: return

            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            wv.draw(c)
            buf.clear()
            bmp.copyPixelsToBuffer(buf)
            NativeBridge.nativePushWebViewRgba(buf, width * 4, width, height)

            mainHandler.postDelayed(this, frameIntervalMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start(
        urlOrHtml: String,
        w: Int = 1280,
        h: Int = 720,
        isHtml: Boolean = false,
        customCss: String = "",
        fpsCap: Int = 30,
    ) {
        stop()
        width = w
        height = h
        frameIntervalMs = (1000L / fpsCap.coerceIn(5, 60)).coerceAtLeast(16L)
        mainHandler.post {
            val wv = WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val cssEsc = customCss.replace("\\", "\\\\").replace("`", "\\`")
                        val cssJs = if (cssEsc.isNotBlank()) {
                            "var s=document.createElement('style');s.innerHTML=`$cssEsc`;document.head.appendChild(s);"
                        } else ""
                        view?.evaluateJavascript(
                            """
                            window.obsstudio = window.obsstudio || {
                                pluginVersion: "33.0.3-android",
                                getCurrentScene: function(cb) { if (cb) cb({ name: "Main Scene", width: $w, height: $h }); },
                                getStatus: function(cb) { if (cb) cb({ recording: false, streaming: false }); }
                            };
                            $cssJs
                            """.trimIndent(),
                            null,
                        )
                    }
                }
                measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, w, h)
            }
            if (isHtml) {
                wv.loadDataWithBaseURL("https://panggung.local/", urlOrHtml, "text/html", "utf-8", null)
            } else {
                wv.loadUrl(urlOrHtml)
            }
            webView = wv
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            canvas = Canvas(bmp)
            directBuf = ByteBuffer.allocateDirect(w * h * 4)
            running = true
            mainHandler.postDelayed(frameRunnable, 150)
        }
    }

    fun dispatchTouch(x: Float, y: Float, action: Int = MotionEvent.ACTION_DOWN) {
        mainHandler.post {
            val wv = webView ?: return@post
            val now = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(now, now, action, x, y, 0)
            wv.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(frameRunnable)
        mainHandler.post {
            webView?.destroy()
            webView = null
            bitmap?.recycle()
            bitmap = null
            canvas = null
            directBuf = null
        }
    }
}
