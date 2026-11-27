package io.panggung.obs.ui.modals

import android.hardware.camera2.CaptureRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.panggung.obs.capture.CameraLensInfo
import io.panggung.obs.capture.ProCameraCapabilities
import io.panggung.obs.capture.ProCameraState
import io.panggung.obs.ui.components.BroadcastButton
import io.panggung.obs.ui.components.BroadcastTone
import io.panggung.obs.ui.theme.PanggungColors
import io.panggung.obs.ui.theme.PanggungRadius
import kotlin.math.roundToInt

/**
 * Complete Pro Camera Controls + Multi-Lens + Concurrent Dual-Cam Sheet (v0.3.0):
 * - Lens Switcher (Ultra-Wide 0.6x / Wide 1x / Telephoto 3x / Front) + Concurrent Dual-Cam PiP
 * - Exposure: Auto vs Manual (ISO, Shutter Speed 1/15..1/4000s, EV Comp, AE Lock)
 * - Focus: AF-C vs Manual Focus Distance (Infinity <-> Macro)
 * - White Balance: Auto, Daylight, Cloudy, Tungsten, Fluorescent, Manual Kelvin (2500K-8000K) + AWB Lock
 * - Optics: Zoom Ratio, Torch, Hardware OIS/EIS Stabilization, Reset
 */
@Composable
fun ProCameraDialog(
    lenses: List<CameraLensInfo>,
    activeCameraId: String?,
    dualCamActive: Boolean,
    capabilities: ProCameraCapabilities,
    initialState: ProCameraState,
    onSelectLens: (CameraLensInfo) -> Unit,
    onToggleDualCam: () -> Unit,
    onStateChange: (ProCameraState) -> Unit,
    onDismiss: () -> Unit,
) {
    var st by remember(initialState) { mutableStateOf(initialState) }

    fun update(next: ProCameraState) {
        st = next
        onStateChange(next)
    }

    val wbPresets = listOf(
        CaptureRequest.CONTROL_AWB_MODE_AUTO to "AUTO",
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "DAY",
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "CLOUD",
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "TUNG",
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "FLUOR",
        -1 to "KELVIN",
    )

    val shutterStops = listOf(15, 24, 30, 50, 60, 100, 120, 250, 500, 1000, 2000, 4000)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanggungColors.DockSurface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("PRO CAMERA CONTROLS", color = PanggungColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                BroadcastButton("Reset", { update(ProCameraState()) }, compact = true)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 1. Multi-Lens & Concurrent Dual-Cam Strip
                Text("LENSES & CONCURRENT DUAL-CAM", color = PanggungColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lenses.forEach { lens ->
                        val active = lens.cameraId == activeCameraId
                        BroadcastButton(
                            text = lens.label,
                            onClick = { onSelectLens(lens) },
                            tone = if (active) BroadcastTone.Primary else BroadcastTone.Neutral,
                            compact = true,
                        )
                    }
                    BroadcastButton(
                        text = if (dualCamActive) "DUAL ON" else "Dual PiP",
                        onClick = onToggleDualCam,
                        tone = if (dualCamActive) BroadcastTone.Live else BroadcastTone.ActiveOutline,
                        compact = true,
                    )
                }

                // 2. Exposure (AUTO / MANUAL + ISO + Shutter + EV + AE Lock)
                SectionBox("EXPOSURE") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BroadcastButton(
                            text = if (st.manualExposure) "MANUAL" else "AUTO AE",
                            onClick = { update(st.copy(manualExposure = !st.manualExposure)) },
                            tone = if (st.manualExposure) BroadcastTone.Primary else BroadcastTone.Neutral,
                            enabled = capabilities.supportsManualExposure,
                            compact = true,
                        )
                        BroadcastButton(
                            text = if (st.aeLock) "AE LOCKED" else "AE Lock",
                            onClick = { update(st.copy(aeLock = !st.aeLock)) },
                            tone = if (st.aeLock) BroadcastTone.ActiveOutline else BroadcastTone.Neutral,
                            compact = true,
                        )
                    }
                    if (st.manualExposure && capabilities.supportsManualExposure) {
                        val isoMin = capabilities.isoRange.lower.toFloat()
                        val isoMax = capabilities.isoRange.upper.coerceAtMost(6400).toFloat().coerceAtLeast(isoMin + 100f)
                        ProSliderRow(
                            text = "ISO",
                            valueText = "${st.iso}",
                            value = st.iso.toFloat().coerceIn(isoMin, isoMax),
                            range = isoMin..isoMax,
                            onChange = { update(st.copy(iso = it.roundToInt())) },
                        )
                        val curIdx = shutterStops.indexOfFirst { it >= st.shutterSpeedDenom }.let { if (it < 0) 4 else it }
                        ProSliderRow(
                            text = "SHUTTER",
                            valueText = "1/${st.shutterSpeedDenom}s",
                            value = curIdx.toFloat(),
                            range = 0f..(shutterStops.size - 1).toFloat(),
                            onChange = { update(st.copy(shutterSpeedDenom = shutterStops[it.roundToInt().coerceIn(shutterStops.indices)])) },
                        )
                    } else {
                        val evMin = capabilities.evRange.lower.toFloat()
                        val evMax = capabilities.evRange.upper.toFloat().coerceAtLeast(evMin + 1f)
                        ProSliderRow(
                            text = "EV COMP",
                            valueText = "${if (st.exposureComp > 0) "+" else ""}${st.exposureComp}",
                            value = st.exposureComp.toFloat().coerceIn(evMin, evMax),
                            range = evMin..evMax,
                            onChange = { update(st.copy(exposureComp = it.roundToInt())) },
                        )
                    }
                }

                // 3. Focus (AF-C vs Manual Focus Distance)
                SectionBox("FOCUS") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BroadcastButton(
                            text = if (st.manualFocus) "MANUAL MF" else "AUTO AF-C",
                            onClick = { update(st.copy(manualFocus = !st.manualFocus)) },
                            tone = if (st.manualFocus) BroadcastTone.Primary else BroadcastTone.Neutral,
                            enabled = capabilities.supportsManualFocus,
                            compact = true,
                        )
                    }
                    if (st.manualFocus && capabilities.supportsManualFocus) {
                        val maxD = capabilities.minFocusDistance.coerceAtLeast(1f)
                        ProSliderRow(
                            text = "DIST",
                            valueText = if (st.focusDistance < 0.1f) "∞" else String.format("%.1fD", st.focusDistance),
                            value = st.focusDistance.coerceIn(0f, maxD),
                            range = 0f..maxD,
                            onChange = { update(st.copy(focusDistance = it)) },
                        )
                    }
                }

                // 4. White Balance (Presets + Manual Kelvin RGGB + AWB Lock)
                SectionBox("WHITE BALANCE") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        wbPresets.forEach { (mode, label) ->
                            BroadcastButton(
                                text = label,
                                onClick = { update(st.copy(wbMode = mode)) },
                                tone = if (st.wbMode == mode) BroadcastTone.Primary else BroadcastTone.Neutral,
                                compact = true,
                            )
                        }
                        BroadcastButton(
                            text = if (st.awbLock) "AWB LOCK" else "AWB Lock",
                            onClick = { update(st.copy(awbLock = !st.awbLock)) },
                            tone = if (st.awbLock) BroadcastTone.ActiveOutline else BroadcastTone.Neutral,
                            compact = true,
                        )
                    }
                    if (st.wbMode == -1) {
                        ProSliderRow(
                            text = "KELVIN",
                            valueText = "${st.wbKelvin}K",
                            value = st.wbKelvin.toFloat().coerceIn(2500f, 8000f),
                            range = 2500f..8000f,
                            onChange = { update(st.copy(wbKelvin = (it / 100f).roundToInt() * 100)) },
                        )
                    }
                }

                // 5. Optics (Zoom, Torch, Hardware OIS/EIS)
                SectionBox("OPTICS & STABILIZATION") {
                    val zMin = capabilities.zoomRange.lower
                    val zMax = capabilities.zoomRange.upper.coerceAtMost(10f).coerceAtLeast(zMin + 0.5f)
                    ProSliderRow(
                        text = "ZOOM",
                        valueText = String.format("%.1fx", st.zoomRatio),
                        value = st.zoomRatio.coerceIn(zMin, zMax),
                        range = zMin..zMax,
                        onChange = { update(st.copy(zoomRatio = it)) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BroadcastButton(
                            text = if (st.torchOn) "TORCH ON" else "Torch",
                            onClick = { update(st.copy(torchOn = !st.torchOn)) },
                            tone = if (st.torchOn) BroadcastTone.Live else BroadcastTone.Neutral,
                            enabled = capabilities.supportsTorch,
                            compact = true,
                        )
                        val stabLabel = when {
                            !st.stabilization -> "Stab OFF"
                            capabilities.supportsOis -> "OIS ON"
                            capabilities.supportsEis -> "EIS ON"
                            else -> "Stab N/A"
                        }
                        BroadcastButton(
                            text = stabLabel,
                            onClick = { update(st.copy(stabilization = !st.stabilization)) },
                            tone = if (st.stabilization) BroadcastTone.Primary else BroadcastTone.Neutral,
                            compact = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = PanggungColors.TealActive)
            }
        },
    )
}

@Composable
private fun SectionBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanggungRadius.Card)
            .background(PanggungColors.DockSurface)
            .border(1.dp, PanggungColors.Border, PanggungRadius.Card)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = PanggungColors.TextMuted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        content()
    }
}

@Composable
private fun ProSliderRow(
    text: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text, color = PanggungColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(valueText, color = PanggungColors.TealActive, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = PanggungColors.TealActive,
                activeTrackColor = PanggungColors.TealPrimary,
                inactiveTrackColor = PanggungColors.Border,
            ),
            modifier = Modifier.height(26.dp),
        )
    }
}
