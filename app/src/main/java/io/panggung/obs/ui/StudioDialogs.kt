package io.panggung.obs.ui

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.panggung.obs.NativeBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * Auto-rendered obs_properties dialog (CORE-13 / DLG-01).
 * Works for ANY source or filter (built-in or third-party plugin).
 */
@Composable
fun PropertiesDialog(sourceName: String, onDismiss: () -> Unit) {
    val raw = remember(sourceName) { NativeBridge.nativeGetSourcePropertiesJson(sourceName) }
    val parsed = remember(raw) {
        try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }
    val propsArr = remember(parsed) { parsed.optJSONArray("properties") ?: JSONArray() }
    val settingsObj = remember(parsed) {
        try { JSONObject(parsed.optJSONObject("settings")?.toString() ?: "{}") } catch (_: Exception) { JSONObject() }
    }
    var revision by remember { mutableIntStateOf(0) }

    fun applySettings() {
        NativeBridge.nativeUpdateSourceSettings(sourceName, settingsObj.toString())
        revision++
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties: $sourceName", fontSize = 15.sp) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                @Suppress("UNUSED_EXPRESSION")
                revision
                if (propsArr.length() == 0) {
                    Text("No configurable properties for this source.", color = Color.Gray, fontSize = 12.sp)
                }
                for (i in 0 until propsArr.length()) {
                    val p = propsArr.getJSONObject(i)
                    val key = p.optString("name")
                    val desc = p.optString("description").ifEmpty { key }
                    val type = p.optInt("type", 0)
                    when (type) {
                        1 -> { // OBS_PROPERTY_BOOL
                            val cur = settingsObj.optBoolean(key, false)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(desc, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Switch(checked = cur, onCheckedChange = {
                                    settingsObj.put(key, it); applySettings()
                                })
                            }
                        }
                        2 -> { // OBS_PROPERTY_INT
                            val min = p.optInt("min", 0).toFloat()
                            val max = p.optInt("max", 100).coerceAtLeast(p.optInt("min", 0) + 1).toFloat()
                            val cur = settingsObj.optInt(key, min.toInt()).toFloat().coerceIn(min, max)
                            Column {
                                Text("$desc: ${cur.toInt()}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                Slider(value = cur, valueRange = min..max, onValueChange = {
                                    settingsObj.put(key, it.toInt()); applySettings()
                                })
                            }
                        }
                        3 -> { // OBS_PROPERTY_FLOAT
                            val min = p.optDouble("min", 0.0).toFloat()
                            val max = p.optDouble("max", 1.0).toFloat().coerceAtLeast(min + 0.01f)
                            val cur = settingsObj.optDouble(key, min.toDouble()).toFloat().coerceIn(min, max)
                            Column {
                                Text("$desc: ${"%.2f".format(cur)}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                Slider(value = cur, valueRange = min..max, onValueChange = {
                                    settingsObj.put(key, it.toDouble()); applySettings()
                                })
                            }
                        }
                        4, 5 -> { // OBS_PROPERTY_TEXT, OBS_PROPERTY_PATH
                            var txt by remember(key, revision) { mutableStateOf(settingsObj.optString(key, "")) }
                            OutlinedTextField(
                                value = txt,
                                onValueChange = {
                                    txt = it
                                    settingsObj.put(key, it)
                                    NativeBridge.nativeUpdateSourceSettings(sourceName, settingsObj.toString())
                                },
                                label = { Text(desc, fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        6 -> { // OBS_PROPERTY_LIST
                            val itemsArr = p.optJSONArray("items") ?: JSONArray()
                            val curStr = settingsObj.optString(key, "")
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(desc, color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (j in 0 until itemsArr.length()) {
                                        val itObj = itemsArr.getJSONObject(j)
                                        val lbl = itObj.optString("name", "Item $j")
                                        val v = itObj.optString("value", lbl)
                                        FilterChip(
                                            selected = curStr == v,
                                            onClick = { settingsObj.put(key, v); applySettings() },
                                            label = { Text(lbl, fontSize = 11.sp) },
                                        )
                                    }
                                }
                            }
                        }
                        7, 13 -> { // OBS_PROPERTY_COLOR / OBS_PROPERTY_COLOR_ALPHA
                            val presets = listOf(
                                0xFF2563EB.toInt() to "Blue",
                                0xFF0F766E.toInt() to "Teal",
                                0xFF15803D.toInt() to "Green",
                                0xFFB91C1C.toInt() to "Red",
                                0xFF0D1117.toInt() to "Black",
                                0xFFFFFFFF.toInt() to "White",
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(desc, color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    presets.forEach { (argb, lbl) ->
                                        val abgr = (argb and 0xFF00FF00.toInt()) or ((argb and 0xFF) shl 16) or ((argb ushr 16) and 0xFF)
                                        OutlinedButton(
                                            onClick = { settingsObj.put(key, abgr.toLong() and 0xFFFFFFFFL); applySettings() },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        ) { Text(lbl, fontSize = 10.sp) }
                                    }
                                }
                            }
                        }
                        else -> {
                            Text("$desc (type $type)", color = Color(0xFF64748B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Edit Transform Dialog (DLG-03 / UI-04).
 */
@Composable
fun TransformDialog(sourceName: String, onDismiss: () -> Unit) {
    val init = remember(sourceName) {
        try { JSONObject(NativeBridge.nativeGetSourceTransformJson(sourceName)) } catch (_: Exception) { JSONObject() }
    }
    val baseW = init.optInt("base_w", 1280).coerceAtLeast(1).toFloat()
    val baseH = init.optInt("base_h", 720).coerceAtLeast(1).toFloat()
    val rawSx = init.optDouble("scale_x", 1.0).toFloat()
    val rawSy = init.optDouble("scale_y", 1.0).toFloat()
    var posX by remember(sourceName) { mutableFloatStateOf(init.optDouble("x", baseW / 2.0).toFloat()) }
    var posY by remember(sourceName) { mutableFloatStateOf(init.optDouble("y", baseH / 2.0).toFloat()) }
    var scale by remember(sourceName) { mutableFloatStateOf(kotlin.math.abs(rawSx).coerceIn(0.1f, 3.0f)) }
    var rot by remember(sourceName) { mutableFloatStateOf(init.optDouble("rot", 0.0).toFloat().coerceIn(0f, 360f)) }
    var flipH by remember(sourceName) { mutableStateOf(rawSx < 0f) }
    var flipV by remember(sourceName) { mutableStateOf(rawSy < 0f) }

    fun apply() {
        val sx = if (flipH) -scale else scale
        val sy = if (flipV) -scale else scale
        NativeBridge.nativeSetSourceTransform(sourceName, posX, posY, sx, sy, rot)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transform: $sourceName", fontSize = 14.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Position X: ${posX.toInt()} px", fontSize = 11.sp)
                Slider(value = posX, valueRange = 0f..baseW, onValueChange = { posX = it; apply() })
                Text("Position Y: ${posY.toInt()} px", fontSize = 11.sp)
                Slider(value = posY, valueRange = 0f..baseH, onValueChange = { posY = it; apply() })
                Text("Scale: ${"%.2f".format(scale)}x", fontSize = 11.sp)
                Slider(value = scale, valueRange = 0.1f..3.0f, onValueChange = { scale = it; apply() })
                Text("Rotation: ${rot.toInt()}°", fontSize = 11.sp)
                Slider(value = rot, valueRange = 0f..360f, onValueChange = { rot = it; apply() })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = {
                        posX = baseW / 2f; posY = baseH / 2f; scale = 1f; rot = 0f; flipH = false; flipV = false
                        NativeBridge.nativeResetSourceTransform(sourceName)
                    }) { Text("Reset", fontSize = 10.sp) }
                    OutlinedButton(onClick = { rot = (rot + 90f) % 360f; apply() }) { Text("+90°", fontSize = 10.sp) }
                    OutlinedButton(onClick = { flipH = !flipH; apply() }) { Text(if (flipH) "⇄ ON" else "⇄ H", fontSize = 10.sp) }
                    OutlinedButton(onClick = { flipV = !flipV; apply() }) { Text(if (flipV) "⇅ ON" else "⇅ V", fontSize = 10.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Advanced Audio Properties Dialog (AUD-02 / AUD-03 / DLG-04).
 */
@Composable
fun AdvancedAudioDialog(sourceName: String, onDismiss: () -> Unit) {
    val initJson = remember(sourceName) {
        try { JSONObject(NativeBridge.nativeGetAudioAdvancedJson(sourceName)) } catch (_: Exception) { JSONObject() }
    }
    var syncMs by remember { mutableFloatStateOf(initJson.optInt("sync_offset_ms", 0).toFloat()) }
    var balance by remember { mutableFloatStateOf(initJson.optDouble("balance", 0.5).toFloat()) }
    var mixers by remember { mutableIntStateOf(initJson.optInt("mixers", 0x3F)) }
    var monitorType by remember { mutableIntStateOf(initJson.optInt("monitor_type", 0)) }

    fun apply() {
        NativeBridge.nativeSetAudioAdvanced(sourceName, syncMs.toInt(), balance, mixers, monitorType)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio Properties: $sourceName", fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync Offset: ${syncMs.toInt()} ms", fontSize = 12.sp)
                Slider(value = syncMs, valueRange = -500f..1000f, onValueChange = { syncMs = it; apply() })
                Text("Balance (L/R): ${"%.2f".format(balance)}", fontSize = 12.sp)
                Slider(value = balance, valueRange = 0f..1f, onValueChange = { balance = it; apply() })
                Text("Audio Monitoring", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0 to "Off", 1 to "Mon Only", 2 to "Mon+Out").forEach { (mt, label) ->
                        FilterChip(
                            selected = monitorType == mt,
                            onClick = { monitorType = mt; apply() },
                            label = { Text(label, fontSize = 10.sp) },
                        )
                    }
                }
                Text("Audio Tracks (1–6)", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in 0 until 6) {
                        val bit = 1 shl t
                        val on = (mixers and bit) != 0
                        FilterChip(
                            selected = on,
                            onClick = { mixers = mixers xor bit; apply() },
                            label = { Text("${t + 1}", fontSize = 10.sp) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * External Display Fullscreen Projector via Android Presentation API (STU-03).
 */
class ProjectorPresentation(context: Context, display: Display) : Presentation(context, display) {
    private var attachedSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextureView(context).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    val s = Surface(st)
                    attachedSurface = s
                    NativeBridge.nativeAttachProjectorSurface(s, w.coerceAtLeast(1), h.coerceAtLeast(1))
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    NativeBridge.nativeDetachProjectorSurface()
                    attachedSurface?.release()
                    attachedSurface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        setContentView(tv)
    }
}
