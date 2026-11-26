package io.panggung.obs.capture

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

data class UsbCaptureDeviceInfo(
    val deviceName: String,
    val productName: String,
    val vendorId: Int,
    val productId: Int,
    val kind: String, // "UVC_VIDEO", "UAC_AUDIO", "NIKON_PTP"
    val hasPermission: Boolean,
)

/**
 * USB-C OTG UVC Capture Card, Companion UAC Audio & Nikon Z-Series PTP Enumerator (T-D02).
 */
object UsbUvcCapture {
    private const val NIKON_VENDOR_ID = 0x04B0

    fun enumerateCaptureDevices(context: Context): List<UsbCaptureDeviceInfo> {
        val usbManager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        val result = mutableListOf<UsbCaptureDeviceInfo>()
        for (dev: UsbDevice in usbManager.deviceList.values) {
            var isVideo = dev.deviceClass == UsbConstants.USB_CLASS_VIDEO
            var isAudio = dev.deviceClass == UsbConstants.USB_CLASS_AUDIO
            var isStillImage = dev.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE
            for (i in 0 until dev.interfaceCount) {
                val intf = dev.getInterface(i)
                when (intf.interfaceClass) {
                    UsbConstants.USB_CLASS_VIDEO -> isVideo = true
                    UsbConstants.USB_CLASS_AUDIO -> isAudio = true
                    UsbConstants.USB_CLASS_STILL_IMAGE -> isStillImage = true
                }
            }
            val kind = when {
                dev.vendorId == NIKON_VENDOR_ID && isStillImage -> "NIKON_PTP"
                isVideo -> "UVC_VIDEO"
                isAudio -> "UAC_AUDIO"
                else -> continue
            }
            result += UsbCaptureDeviceInfo(
                deviceName = dev.deviceName,
                productName = dev.productName ?: "USB Device ${dev.vendorId}:${dev.productId}",
                vendorId = dev.vendorId,
                productId = dev.productId,
                kind = kind,
                hasPermission = usbManager.hasPermission(dev),
            )
            Log.i("UsbUvcCapture", "Detected $kind: ${dev.productName} (${dev.vendorId}:${dev.productId})")
        }
        return result
    }
}
