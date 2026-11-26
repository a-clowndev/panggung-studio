package io.panggung.obs.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import io.panggung.obs.NativeBridge
import io.panggung.obs.capture.ScreenCapture

class PanggungService : Service() {
    companion object {
        const val ACTION_START_FGS = "io.panggung.obs.START_FGS"
        const val ACTION_START_SCREEN = "io.panggung.obs.START_SCREEN"
        const val ACTION_STOP_SCREEN = "io.panggung.obs.STOP_SCREEN"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_FGS_TYPE = "fgs_type"
    }

    private val screenCapture = ScreenCapture()
    private var activeFgsTypes: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildCombinedFgsTypes(requested: Int): Int {
        var types = activeFgsTypes or requested
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            types = types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA.inv()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            types = types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
        }
        activeFgsTypes = types
        return types
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "panggung_studio"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Panggung Studio Active", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Panggung Studio")
            .setContentText("Broadcast engine active (capture / stream / record)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        when (intent?.action) {
            ACTION_START_SCREEN -> {
                if (Build.VERSION.SDK_INT >= 34) {
                    val combined = buildCombinedFgsTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    startForeground(1001, notif, combined)
                } else {
                    startForeground(1001, notif)
                }
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (code == Activity.RESULT_OK && data != null) {
                    try {
                        val mpm = getSystemService(MediaProjectionManager::class.java)
                        val mp = mpm.getMediaProjection(code, data)
                        if (mp != null) {
                            NativeBridge.nativeAddSource("android_screen", "Screen Capture", "")
                            NativeBridge.nativeAddSource("android_audio_output", "Internal Audio", "")
                            screenCapture.start(mp, 1280, 720, 320, true, context = this)
                            Log.i("PanggungService", "Screen + Internal Audio started cleanly (FGS=$activeFgsTypes)")
                        }
                    } catch (e: Exception) {
                        Log.e("PanggungService", "Failed to start MediaProjection", e)
                    }
                }
            }
            ACTION_STOP_SCREEN -> {
                screenCapture.stop()
                activeFgsTypes = activeFgsTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.inv()
            }
            else -> {
                if (Build.VERSION.SDK_INT >= 34) {
                    val req = intent?.getIntExtra(
                        EXTRA_FGS_TYPE,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    ) ?: (ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    val combined = buildCombinedFgsTypes(req)
                    startForeground(1001, notif, combined)
                } else {
                    startForeground(1001, notif)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        screenCapture.stop()
        super.onDestroy()
    }
}
