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
)

/**
 * Active Thermal Governor & Adaptive Bitrate Controller (T-E01).
 * Polls PowerManager.getThermalHeadroom(10) + Battery temperature every 5s when output is active,
 * and dynamically adjusts encoder bitrate via obs_encoder_update() without dropping the stream.
 */
object ThermalGovernor {
    private var lastAppliedBitrate = -1

    fun sampleAndApply(context: Context, baseBitrateKbps: Int, outputActive: Boolean): ThermalState {
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320) / 10.0
        val pm = context.getSystemService(PowerManager::class.java)
        val headroom = if (Build.VERSION.SDK_INT >= 30) {
            try { pm.getThermalHeadroom(10).coerceIn(0f, 1.5f) } catch (_: Exception) { 0f }
        } else 0f

        val scale = when {
            headroom >= 0.90f || tempC >= 46.0 -> 0.50f
            headroom >= 0.80f || tempC >= 43.5 -> 0.75f
            else -> 1.0f
        }
        val targetBitrate = (baseBitrateKbps * scale).toInt().coerceAtLeast(800)
        if (outputActive && targetBitrate != lastAppliedBitrate) {
            if (NativeBridge.nativeUpdateLiveBitrate(targetBitrate)) {
                lastAppliedBitrate = targetBitrate
            }
        } else if (!outputActive) {
            lastAppliedBitrate = -1
        }
        return ThermalState(
            tempC = tempC,
            headroom = headroom,
            throttled = scale < 1.0f,
            effectiveBitrateKbps = targetBitrate,
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
