package io.panggung.obs.persistence

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Full standalone persistence for Panggung Studio settings & state across restarts.
 */
data class PersistedSettings(
    val locale: String = "id-ID",
    val rtmpUrl: String = "rtmp://a.rtmp.youtube.com/live2",
    val bitrateKbps: Int = 4500,
    val videoEncoderId: String = "android_mediacodec_h264",
    val baseWidth: Int = 1280,
    val baseHeight: Int = 720,
    val fps: Int = 30,
    val transitionId: String = "fade_transition",
    val transitionMs: Int = 300,
    val thermalGovernor: Boolean = true,
    val autoReconnect: Boolean = true,
    val confirmStop: Boolean = true,
    val orientationLock: String = "AUTO", // AUTO | PORTRAIT | LANDSCAPE
)

object SettingsPersistence {
    private const val FILE_NAME = "panggung_settings.json"

    fun load(ctx: Context): PersistedSettings {
        return try {
            val f = File(ctx.filesDir, FILE_NAME)
            if (!f.exists()) return PersistedSettings()
            val o = JSONObject(f.readText())
            PersistedSettings(
                locale = o.optString("locale", "id-ID"),
                rtmpUrl = o.optString("rtmpUrl", "rtmp://a.rtmp.youtube.com/live2"),
                bitrateKbps = o.optInt("bitrateKbps", 4500),
                videoEncoderId = o.optString("videoEncoderId", "android_mediacodec_h264"),
                baseWidth = o.optInt("baseWidth", 1280),
                baseHeight = o.optInt("baseHeight", 720),
                fps = o.optInt("fps", 30),
                transitionId = o.optString("transitionId", "fade_transition"),
                transitionMs = o.optInt("transitionMs", 300),
                thermalGovernor = o.optBoolean("thermalGovernor", true),
                autoReconnect = o.optBoolean("autoReconnect", true),
                confirmStop = o.optBoolean("confirmStop", true),
                orientationLock = o.optString("orientationLock", "AUTO"),
            )
        } catch (_: Exception) {
            PersistedSettings()
        }
    }

    fun save(ctx: Context, s: PersistedSettings) {
        try {
            val o = JSONObject()
                .put("locale", s.locale)
                .put("rtmpUrl", s.rtmpUrl)
                .put("bitrateKbps", s.bitrateKbps)
                .put("videoEncoderId", s.videoEncoderId)
                .put("baseWidth", s.baseWidth)
                .put("baseHeight", s.baseHeight)
                .put("fps", s.fps)
                .put("transitionId", s.transitionId)
                .put("transitionMs", s.transitionMs)
                .put("thermalGovernor", s.thermalGovernor)
                .put("autoReconnect", s.autoReconnect)
                .put("confirmStop", s.confirmStop)
                .put("orientationLock", s.orientationLock)
            File(ctx.filesDir, FILE_NAME).writeText(o.toString(2))
        } catch (_: Exception) {}
    }
}
