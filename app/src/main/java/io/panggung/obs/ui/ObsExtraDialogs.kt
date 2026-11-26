package io.panggung.obs.ui

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Custom Browser Dock / Platform Chat & Interactive Browser Dialog (DLG-05, DLG-16, INT-01..04).
 */
@Composable
fun BrowserDockDialog(initialUrl: String, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    var activeUrl by remember { mutableStateOf(initialUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Browser Dock / Interact", fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { activeUrl = url }) { Text("Go", fontSize = 11.sp) }
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl(activeUrl)
                        }
                    },
                    update = { it.loadUrl(activeUrl) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Log Viewer & Crash Diagnostics Dialog (DLG-10).
 */
@Composable
fun LogViewerDialog(crashLog: String, statusLog: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Studio Log & Crash Viewer", fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (crashLog.isNotEmpty()) {
                    Text("Last Crash Report:\n$crashLog\n", color = Color(0xFFF87171), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Text("Runtime Status:\n$statusLog", color = Color(0xFFCBD5E1), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Portable Scene Collection .zip Exporter (PRO-05) & Storage Estimator (AP-07).
 */
object StorageAndExportHelper {
    fun estimateRemainingRecording(ctx: Context, bitrateKbps: Int): String {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        val stat = StatFs(dir.absolutePath)
        val availBytes = stat.availableBytes
        val availGb = availBytes / (1024.0 * 1024.0 * 1024.0)
        val bytesPerSec = ((bitrateKbps.coerceAtLeast(500) + 128) * 1000L) / 8L
        val mins = (availBytes / bytesPerSec) / 60L
        return String.format(Locale.US, "Free: %.1f GB (~%d min @ %d kbps)", availGb, mins, bitrateKbps)
    }

    fun exportPortableZip(filesDir: File, outZip: File): Boolean {
        return try {
            ZipOutputStream(FileOutputStream(outZip)).use { zos ->
                listOf("scene_collection.json").forEach { name ->
                    val f = File(filesDir, name)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry(name))
                        FileInputStream(f).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }
}
