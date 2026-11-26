package io.panggung.obs.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Log
import io.panggung.obs.NativeBridge
import rikka.shizuku.Shizuku
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full Shizuku UserService bridge (PRV-01..PRV-05):
 *  - PRV-01: Binder detection, permission grant, UserService binding (UID 2000)
 *  - PRV-02: Dialog-less Display Capture stream -> libobs android_screen
 *  - PRV-03: Virtual Display per-app launcher
 *  - PRV-04: REMOTE_SUBMIX privilege verification
 *  - PRV-05: Touch/Key input injection
 */
object ShizukuHelper {
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    const val REQ_CODE = 4455

    @Volatile
    private var service: IPanggungShizukuService? = null
    private val capturing = AtomicBoolean(false)
    private var captureThread: Thread? = null

    fun isShizukuInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isBinderReady(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        isBinderReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun requestPermission() {
        try {
            if (isBinderReady() && !hasPermission()) {
                Shizuku.requestPermission(REQ_CODE)
            }
        } catch (e: Throwable) {
            Log.w("ShizukuHelper", "requestPermission failed: ${e.message}")
        }
    }

    fun bindUserService(ctx: Context, onReady: (String) -> Unit) {
        if (!hasPermission()) {
            requestPermission()
            onReady("Requesting Shizuku permission...")
            return
        }
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, PanggungShizukuUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shizuku")
            .debuggable(false)
            .version(1)

        try {
            Shizuku.bindUserService(args, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val svc = IPanggungShizukuService.Stub.asInterface(binder)
                    service = svc
                    val uid = try { svc.uid } catch (_: Exception) { -1 }
                    val submix = try { svc.hasRemoteSubmixPermission() } catch (_: Exception) { false }
                    onReady("Shizuku CONNECTED (UID=$uid, REMOTE_SUBMIX=$submix)")
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    onReady("Shizuku UserService disconnected")
                }
            })
        } catch (e: Throwable) {
            onReady("Shizuku bind error: ${e.message}")
        }
    }

    fun startDialoglessCapture(): Boolean {
        val svc = service ?: return false
        if (capturing.getAndSet(true)) return true
        captureThread = Thread {
            var buf: ByteBuffer? = null
            while (capturing.get()) {
                try {
                    val png = svc.captureScreenRaw()
                    if (png != null) {
                        val rawBmp = BitmapFactory.decodeByteArray(png, 0, png.size)
                        if (rawBmp != null) {
                            val bmp = Bitmap.createScaledBitmap(rawBmp, 1280, 720, true)
                            if (bmp !== rawBmp) rawBmp.recycle()
                            val bytes = bmp.byteCount
                            if (buf == null || buf!!.capacity() < bytes) buf = ByteBuffer.allocateDirect(bytes)
                            val b = buf!!
                            b.clear()
                            bmp.copyPixelsToBuffer(b)
                            b.rewind()
                            NativeBridge.nativePushScreenRgba(b, bmp.rowBytes, bmp.width, bmp.height, System.nanoTime())
                            bmp.recycle()
                        }
                    }
                } catch (_: Throwable) {
                    Thread.sleep(250)
                }
            }
        }.apply { name = "ShizukuScreenCap"; start() }
        return true
    }

    fun stopDialoglessCapture() {
        capturing.set(false)
        captureThread = null
    }

    fun isCapturing(): Boolean = capturing.get()

    fun injectTap(x: Float, y: Float): Boolean = try { service?.injectTap(x, y) == true } catch (_: Throwable) { false }

    fun launchAppOnDisplay(component: String, displayId: Int): Boolean =
        try { service?.launchAppOnDisplay(component, displayId) == true } catch (_: Throwable) { false }

    fun getStatusSummary(ctx: Context): String {
        return when {
            service != null -> "Shizuku: CONNECTED (UID 2000 • Dialog-less Capture, Virtual Display, REMOTE_SUBMIX, Input Inject READY)"
            hasPermission() -> "Shizuku: GRANTED (tap Connect Shizuku to bind UID 2000 service)"
            isBinderReady() -> "Shizuku: RUNNING (tap Connect Shizuku to grant permission)"
            isShizukuInstalled(ctx) -> "Shizuku: INSTALLED (start Shizuku via Wireless Debugging, then tap Connect)"
            else -> "Shizuku: Not installed"
        }
    }
}
