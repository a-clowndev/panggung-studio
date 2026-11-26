package io.panggung.obs.androidplus

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager

/**
 * Android Plus hardware managers:
 *  - ENC-10: Hardware Codec Capability Detector (H.264 / HEVC / AV1 / AAC)
 *  - AP-01:  Thermal Governor (PowerManager.getThermalHeadroom -> bitrate recommendation)
 *  - SRC-12: USB UVC Webcam / HDMI Capture Card detector
 */
object AndroidPlusManager {

    data class CodecReport(
        val hwH264: Boolean,
        val hwHevc: Boolean,
        val hwAv1: Boolean,
        val hwAac: Boolean,
        val summary: String,
    )

    fun detectHardwareCodecs(): CodecReport {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        fun hasHwEncoder(mime: String): Boolean = list.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google."))
        }
        val h264 = hasHwEncoder("video/avc")
        val hevc = hasHwEncoder("video/hevc")
        val av1 = hasHwEncoder("video/av01")
        val aac = hasHwEncoder("audio/mp4a-latm")
        val s = buildList {
            if (h264) add("HW-H.264")
            if (hevc) add("HW-HEVC")
            if (av1) add("HW-AV1")
            if (aac) add("HW-AAC")
        }.joinToString(" | ").ifEmpty { "SW-only (x264)" }
        return CodecReport(h264, hevc, av1, aac, s)
    }

    /** Returns recommended bitrate multiplier (1.0 normal, 0.75 warm, 0.5 hot) based on thermal headroom. */
    fun thermalGovernorMultiplier(ctx: Context): Pair<Float, Float> {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else 0f
        val mult = when {
            headroom >= 0.92f -> 0.50f
            headroom >= 0.80f -> 0.75f
            else -> 1.0f
        }
        return headroom to mult
    }

    /** Detects connected USB UVC video capture devices (webcams / HDMI capture cards). */
    fun listUvcDevices(ctx: Context): List<String> {
        val um = ctx.getSystemService(UsbManager::class.java) ?: return emptyList()
        return um.deviceList.values.filter { dev ->
            (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
        }.map { "${it.productName ?: "UVC Device"} (${it.vendorId}:${it.productId})" }
    }
}
