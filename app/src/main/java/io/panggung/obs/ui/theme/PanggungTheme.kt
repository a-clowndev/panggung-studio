package io.panggung.obs.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AntiSlopUI v3.0.0 Broadcast Darkroom Design System (FigCraft Page 3 Spec).
 * Strictly zero purple/indigo/violet; Deep Teal (#0F766E) primary on neutral slate darkroom.
 */
object PanggungColors {
    val BgCanvas = Color(0xFF0D1117)
    val DockSurface = Color(0xFF161B22)
    val CardElevated = Color(0xFF1E242E)
    val CardSelected = Color(0xFF132F30)
    val Border = Color(0xFF2D3643)
    val BorderActive = Color(0xFF14B8A6)

    val TealPrimary = Color(0xFF0F766E)
    val TealActive = Color(0xFF14B8A6)
    val TealMuted = Color(0xFF0D4F4A)

    val RecRed = Color(0xFFB91C1C)
    val RecBright = Color(0xFFEF4444)
    val LiveGreen = Color(0xFF15803D)
    val LiveBright = Color(0xFF22C55E)
    val WarnAmber = Color(0xFFB45309)
    val WarnBright = Color(0xFFF59E0B)

    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFFCBD5E1)
    val TextMuted = Color(0xFF94A3B8)
    val TextDim = Color(0xFF64748B)

    // VU Meter gradient bands (Broadcast IEC 60268-18)
    val VuGreen = Color(0xFF22C55E)
    val VuYellow = Color(0xFFF59E0B)
    val VuRed = Color(0xFFEF4444)
    val VuTrack = Color(0xFF0B0F14)
}

object PanggungRadius {
    val Badge = RoundedCornerShape(4.dp)
    val Button = RoundedCornerShape(6.dp)
    val Card = RoundedCornerShape(8.dp)
    val Modal = RoundedCornerShape(12.dp)
}

private val PanggungDarkScheme = darkColorScheme(
    primary = PanggungColors.TealPrimary,
    onPrimary = Color.White,
    primaryContainer = PanggungColors.TealMuted,
    onPrimaryContainer = PanggungColors.TextPrimary,
    secondary = PanggungColors.TealActive,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = PanggungColors.CardElevated,
    onSecondaryContainer = PanggungColors.TextPrimary,
    tertiary = PanggungColors.LiveGreen,
    onTertiary = Color.White,
    background = PanggungColors.BgCanvas,
    onBackground = PanggungColors.TextPrimary,
    surface = PanggungColors.DockSurface,
    onSurface = PanggungColors.TextPrimary,
    surfaceVariant = PanggungColors.CardElevated,
    onSurfaceVariant = PanggungColors.TextMuted,
    outline = PanggungColors.Border,
    outlineVariant = PanggungColors.Border,
    error = PanggungColors.RecRed,
    onError = Color.White,
)

private val PanggungTypography = Typography(
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        letterSpacing = (-0.2).sp,
        color = PanggungColors.TextPrimary,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        color = PanggungColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = PanggungColors.TextPrimary,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        color = PanggungColors.TextSecondary,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        color = PanggungColors.TextMuted,
    ),
)

private val PanggungShapes = Shapes(
    extraSmall = PanggungRadius.Badge,
    small = PanggungRadius.Button,
    medium = PanggungRadius.Card,
    large = PanggungRadius.Modal,
)

@Composable
fun PanggungTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PanggungDarkScheme,
        typography = PanggungTypography,
        shapes = PanggungShapes,
        content = content,
    )
}
