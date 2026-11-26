package io.panggung.obs.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import java.io.File

data class DirectFdRecordingTarget(
    val posixPath: String,
    val displayPath: String,
    val pfd: ParcelFileDescriptor?,
)

/**
 * Scoped Storage Direct FD Recording Bridge (Movies/Panggung) & StatFs 50 MiB Guard (T-D04).
 * Maps MediaStore URI into a seekable POSIX FD (/proc/self/fd/<fd>) so FFmpeg/libobs mp4_output
 * writes Hybrid MP4 directly into public gallery storage without staging copies.
 */
object SafFdBridge {
    private const val MIN_FREE_BYTES = 50L * 1024L * 1024L // 50 MiB safety floor (fixes Android statvfs 0-byte bug)

    fun getAvailableBytes(context: Context): Long {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    fun hasEnoughDiskSpace(context: Context): Boolean = getAvailableBytes(context) >= MIN_FREE_BYTES

    fun openPublicMovieTarget(context: Context, fileName: String): DirectFdRecordingTarget? {
        if (!hasEnoughDiskSpace(context)) {
            Log.e("SafFdBridge", "Insufficient storage (< 50 MiB free)")
            return null
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Panggung")
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                    if (pfd != null) {
                        val fdPath = "/proc/self/fd/${pfd.fd}"
                        return DirectFdRecordingTarget(fdPath, "Movies/Panggung/$fileName", pfd)
                    }
                }
            } catch (e: Exception) {
                Log.w("SafFdBridge", "MediaStore direct FD fallback to app Movies dir", e)
            }
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        dir.mkdirs()
        val f = File(dir, fileName)
        return DirectFdRecordingTarget(f.absolutePath, f.absolutePath, null)
    }
}
