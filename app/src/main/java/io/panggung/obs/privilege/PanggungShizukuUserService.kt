package io.panggung.obs.privilege

import android.os.Process
import java.io.ByteArrayOutputStream
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's privileged UID 2000 (ADB shell) process (PRV-01..PRV-05).
 */
class PanggungShizukuUserService : IPanggungShizukuService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

    /** PRV-02: Dialog-less screen capture without MediaProjection confirmation dialog. */
    override fun captureScreenRaw(): ByteArray? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
            val out = ByteArrayOutputStream()
            p.inputStream.copyTo(out)
            p.waitFor()
            val bytes = out.toByteArray()
            if (bytes.size > 100) bytes else null
        } catch (_: Exception) {
            null
        }
    }

    /** PRV-03: Launch an allowlisted Android component on a virtual display (strictly validated). */
    override fun launchAppOnDisplay(componentName: String?, displayId: Int): Boolean {
        if (componentName.isNullOrBlank()) return false
        if (displayId !in 0..999) return false
        // Strict ComponentName validation prevents argument/flag injection
        if (!Regex("^[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+$").matches(componentName)) return false
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("am", "start", "--display", displayId.toString(), "-n", componentName))
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** PRV-05: Inject touch tap into captured app directly from OBS preview/Interact. */
    override fun injectTap(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val ix = x.toInt().coerceIn(0, 10000)
        val iy = y.toInt().coerceIn(0, 10000)
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("input", "tap", ix.toString(), iy.toString()))
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** PRV-05: Inject key event into captured app. */
    override fun injectKeyEvent(keyCode: Int): Boolean {
        if (keyCode !in 0..320) return false
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** PRV-04: Verify UID 2000 shell access for REMOTE_SUBMIX audio capture. */
    override fun hasRemoteSubmixPermission(): Boolean = Process.myUid() == 2000 || Process.myUid() == 0

    /** SECURITY: Arbitrary shell execution is permanently disabled to enforce least privilege. */
    override fun execShell(cmd: String?): String = "Disabled for security"
}
