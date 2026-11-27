package io.panggung.obs.androidplus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import io.panggung.obs.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ThermalState(
    val tempC: Double = 32.0,
    val headroom: Float = 0f,
    val throttled: Boolean = false,
    val effectiveBitrateKbps: Int = 4500,
    val abrReason: String = "NORMAL", // NORMAL | THERMAL | CONGESTION
)

/**
 * Dual-Loop Thermal + Network Congestion AIMD Adaptive Bitrate Controller (v0.2.0).
 * Combines PowerManager.getThermalHeadroom + Battery temp + RTMP/SRT obs_output_get_congestion
 * and dropped frame deltas to dynamically scale encoder bitrate without dropping the stream.
 */
object ThermalGovernor {
    private var lastAppliedBitrate = -1
    private var currentAbrScale = 1.0f
    private var cleanTicks = 0
    private var lastDroppedFrames = 0

    fun sampleAndApply(
        context: Context,
        baseBitrateKbps: Int,
        outputActive: Boolean,
        congestion: Float = 0f,
        droppedFrames: Int = 0,
    ): ThermalState {
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320) / 10.0
        val pm = context.getSystemService(PowerManager::class.java)
        val headroom = if (Build.VERSION.SDK_INT >= 30) {
            try { pm.getThermalHeadroom(10).coerceIn(0f, 1.5f) } catch (_: Exception) { 0f }
        } else 0f

        val thermalScale = when {
            headroom >= 0.90f || tempC >= 46.0 -> 0.50f
            headroom >= 0.80f || tempC >= 43.5 -> 0.75f
            else -> 1.0f
        }

        val droppedDelta = if (outputActive && droppedFrames >= lastDroppedFrames) droppedFrames - lastDroppedFrames else 0
        lastDroppedFrames = if (outputActive) droppedFrames else 0

        if (!outputActive) {
            currentAbrScale = 1.0f
            cleanTicks = 0
            lastAppliedBitrate = -1
        } else {
            if (congestion > 0.35f || droppedDelta > 2) {
                // Fast multiplicative decrease (-25%) on network congestion or frame drops
                currentAbrScale = (currentAbrScale * 0.75f).coerceAtLeast(0.25f)
                cleanTicks = 0
            } else if (congestion < 0.05f && droppedDelta == 0 && thermalScale >= 1.0f) {
                // Smooth additive increase (+10%) after sustained clean window
                cleanTicks++
                if (cleanTicks >= 3 && currentAbrScale < 1.0f) {
                    currentAbrScale = (currentAbrScale + 0.10f).coerceAtMost(1.0f)
                    cleanTicks = 0
                }
            } else {
                cleanTicks = 0
            }
        }

        val combinedScale = minOf(thermalScale, currentAbrScale)
        val reason = when {
            thermalScale < 1.0f && thermalScale <= currentAbrScale -> "THERMAL"
            currentAbrScale < 1.0f -> "CONGESTION"
            else -> "NORMAL"
        }
        val targetBitrate = (baseBitrateKbps * combinedScale).toInt().coerceAtLeast(600)
        if (outputActive && targetBitrate != lastAppliedBitrate) {
            if (NativeBridge.nativeUpdateLiveBitrate(targetBitrate)) {
                lastAppliedBitrate = targetBitrate
            }
        }
        return ThermalState(
            tempC = tempC,
            headroom = headroom,
            throttled = combinedScale < 1.0f,
            effectiveBitrateKbps = targetBitrate,
            abrReason = reason,
        )
    }

    fun setKeepScreenOn(activity: Activity, enabled: Boolean) {
        activity.runOnUiThread {
            if (enabled) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun parseSemVer(raw: String): Triple<Int, Int, Int> {
        val clean = raw.trim().removePrefix("v").removePrefix("V").substringBefore("-")
        val parts = clean.split(".")
        return Triple(
            parts.getOrNull(0)?.toIntOrNull() ?: 0,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }

    internal fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val (rMaj, rMin, rPat) = parseSemVer(remoteTag)
        val (cMaj, cMin, cPat) = parseSemVer(currentVersion)
        if (rMaj != cMaj) return rMaj > cMaj
        if (rMin != cMin) return rMin > cMin
        return rPat > cPat
    }

    /**
     * In-App GitHub Releases Update Checker with proper SemVer comparison.
     */
    suspend fun checkLatestRelease(currentVersion: String): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.github.com/repos/a-clowndev/panggung-studio/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val tag = JSONObject(body).optString("tag_name", currentVersion)
                if (isNewerVersion(tag, currentVersion)) {
                    "Update available: $tag (current v$currentVersion)"
                } else {
                    "Up to date (v$currentVersion)"
                }
            } else {
                "Running v$currentVersion (offline/private repo)"
            }
        } catch (_: Exception) {
            "Running v$currentVersion (check offline)"
        }
    }
}
