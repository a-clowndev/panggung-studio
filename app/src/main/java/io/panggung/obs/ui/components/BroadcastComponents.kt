package io.panggung.obs.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.panggung.obs.ui.theme.PanggungColors
import io.panggung.obs.ui.theme.PanggungRadius

enum class BroadcastTone { Primary, Neutral, Danger, Live, Warning, ActiveOutline }

@Composable
fun BroadcastButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: BroadcastTone = BroadcastTone.Neutral,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val bg = when (tone) {
        BroadcastTone.Primary -> PanggungColors.TealPrimary
        BroadcastTone.Danger -> PanggungColors.RecRed
        BroadcastTone.Live -> PanggungColors.LiveGreen
        BroadcastTone.Warning -> PanggungColors.WarnAmber
        BroadcastTone.ActiveOutline -> PanggungColors.CardSelected
        BroadcastTone.Neutral -> PanggungColors.CardElevated
    }
    val border = when (tone) {
        BroadcastTone.Primary -> PanggungColors.TealActive
        BroadcastTone.Danger -> PanggungColors.RecBright
        BroadcastTone.Live -> PanggungColors.LiveBright
        BroadcastTone.Warning -> PanggungColors.WarnBright
        BroadcastTone.ActiveOutline -> PanggungColors.TealActive
        BroadcastTone.Neutral -> PanggungColors.Border
    }
    val fg = if (enabled) PanggungColors.TextPrimary else PanggungColors.TextDim
    Box(
        modifier = modifier
            .clip(PanggungRadius.Button)
            .background(if (enabled) bg else PanggungColors.DockSurface)
            .border(BorderStroke(1.dp, if (enabled) border else PanggungColors.Border), PanggungRadius.Button)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 5.dp else 8.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TallyBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(PanggungRadius.Badge)
            .background(color.copy(alpha = 0.20f))
            .border(1.dp, color, PanggungRadius.Badge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(PanggungRadius.Badge)
                .background(color),
        )
        Text(
            text = label,
            color = PanggungColors.TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun DockPanel(
    title: String,
    modifier: Modifier = Modifier,
    headerAction: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(PanggungRadius.Card)
            .background(PanggungColors.DockSurface)
            .border(1.dp, PanggungColors.Border, PanggungRadius.Card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanggungColors.CardElevated)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title.uppercase(),
                color = PanggungColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            if (headerAction != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = headerAction,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/**
 * Broadcast Stereo VU Meter (-60 dB to 0 dB) with IEC green/amber/red zones + peak-hold bar.
 * Supports both Horizontal (Phone Portrait / 3-Col Landscape) and Vertical (Tablet / DeX).
 */
@Composable
fun StereoVuMeterBar(
    magnitudeDb: Float,
    peakDb: Float,
    muted: Boolean,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    barThickness: Dp = 6.dp,
) {
    val normMag = if (muted) 0f else ((magnitudeDb.coerceIn(-60f, 0f) + 60f) / 60f)
    val normPeak = if (muted) 0f else ((peakDb.coerceIn(-60f, 0f) + 60f) / 60f)
    // Slight stereo visual differentiation when mono/stereo channel shares magnitude
    val normMagR = (normMag * 0.96f).coerceIn(0f, 1f)

    if (!vertical) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barThickness * 2 + 2.dp),
            ) {
                val h = barThickness.toPx()
                val gap = 2.dp.toPx()
                val w = size.width
                val greenEnd = w * (40f / 60f)   // -60..-20 dB
                val yellowEnd = w * (51f / 60f)  // -20..-9 dB

                fun drawChannel(y: Float, level: Float, pk: Float) {
                    drawRoundRect(PanggungColors.VuTrack, Offset(0f, y), Size(w, h), CornerRadius(2f, 2f))
                    val fillW = w * level
                    if (fillW > 0f) {
                        val gW = fillW.coerceAtMost(greenEnd)
                        drawRect(PanggungColors.VuGreen, Offset(0f, y), Size(gW, h))
                        if (fillW > greenEnd) {
                            val yW = (fillW - greenEnd).coerceAtMost(yellowEnd - greenEnd)
                            drawRect(PanggungColors.VuYellow, Offset(greenEnd, y), Size(yW, h))
                        }
                        if (fillW > yellowEnd) {
                            drawRect(PanggungColors.VuRed, Offset(yellowEnd, y), Size(fillW - yellowEnd, h))
                        }
                    }
                    if (pk > 0.02f) {
                        val px = (w * pk).coerceIn(2f, w - 2f)
                        drawRect(PanggungColors.TextPrimary, Offset(px, y), Size(2.dp.toPx(), h))
                    }
                }
                drawChannel(0f, normMag, normPeak)
                drawChannel(h + gap, normMagR, normPeak)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("-60", "-40", "-20", "-9", "0 dB").forEach { tick ->
                    Text(tick, color = PanggungColors.TextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    } else {
        Canvas(
            modifier = modifier
                .width(barThickness * 2 + 3.dp)
                .fillMaxHeight(),
        ) {
            val w = barThickness.toPx()
            val gap = 3.dp.toPx()
            val h = size.height
            val greenH = h * (40f / 60f)
            val yellowH = h * (51f / 60f)

            fun drawVChannel(x: Float, level: Float, pk: Float) {
                drawRoundRect(PanggungColors.VuTrack, Offset(x, 0f), Size(w, h), CornerRadius(2f, 2f))
                val fillH = h * level
                if (fillH > 0f) {
                    val gH = fillH.coerceAtMost(greenH)
                    drawRect(PanggungColors.VuGreen, Offset(x, h - gH), Size(w, gH))
                    if (fillH > greenH) {
                        val yH = (fillH - greenH).coerceAtMost(yellowH - greenH)
                        drawRect(PanggungColors.VuYellow, Offset(x, h - greenH - yH), Size(w, yH))
                    }
                    if (fillH > yellowH) {
                        val rH = fillH - yellowH
                        drawRect(PanggungColors.VuRed, Offset(x, h - fillH), Size(w, rH))
                    }
                }
                if (pk > 0.02f) {
                    val py = (h - h * pk).coerceIn(0f, h - 2f)
                    drawRect(PanggungColors.TextPrimary, Offset(x, py), Size(w, 2.dp.toPx()))
                }
            }
            drawVChannel(0f, normMag, normPeak)
            drawVChannel(w + gap, normMagR, normPeak)
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = PanggungColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(valueText, color = PanggungColors.TealActive, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            valueRange = valueRange,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = PanggungColors.TealActive,
                activeTrackColor = PanggungColors.TealPrimary,
                inactiveTrackColor = PanggungColors.Border,
            ),
        )
    }
}

@Composable
fun BroadcastToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanggungRadius.Button)
            .background(PanggungColors.CardElevated)
            .border(1.dp, PanggungColors.Border, PanggungRadius.Button)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = PanggungColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, color = PanggungColors.TextMuted, fontSize = 10.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PanggungColors.TealPrimary,
                uncheckedThumbColor = PanggungColors.TextMuted,
                uncheckedTrackColor = PanggungColors.DockSurface,
                uncheckedBorderColor = PanggungColors.Border,
            ),
        )
    }
}
