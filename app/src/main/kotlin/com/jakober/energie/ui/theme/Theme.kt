package com.jakober.energie.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Farben der Energiefluesse - bewusst unabhaengig vom Material-Schema, damit
 *  PV immer gelb, Speicher immer gruen, Netz immer blau und Haus immer orange ist. */
object EnergyColors {
    val sun = Color(0xFFFACC15)
    val battery = Color(0xFF34D399)
    val grid = Color(0xFF38BDF8)
    val house = Color(0xFFFB923C)
    val car = Color(0xFFA78BFA)
    val export = Color(0xFF60A5FA)
    val neutral = Color(0xFF94A3B8)
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFACC15),
    onPrimary = Color(0xFF1F1B00),
    primaryContainer = Color(0xFF3B3400),
    onPrimaryContainer = Color(0xFFFFE97A),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF00381F),
    secondaryContainer = Color(0xFF0F3D2C),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFF38BDF8),
    onTertiary = Color(0xFF00344A),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE5E9F0),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE5E9F0),
    surfaceVariant = Color(0xFF1A2337),
    onSurfaceVariant = Color(0xFFB4BCCB),
    surfaceContainer = Color(0xFF141C2E),
    surfaceContainerHigh = Color(0xFF1A2337),
    surfaceContainerHighest = Color(0xFF212C44),
    surfaceContainerLow = Color(0xFF101828),
    outline = Color(0xFF3B4661),
    outlineVariant = Color(0xFF283148),
    error = Color(0xFFFB7185),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF7A6400),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE97A),
    onPrimaryContainer = Color(0xFF231B00),
    secondary = Color(0xFF00754A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA7F3D0),
    onSecondaryContainer = Color(0xFF00210F),
    tertiary = Color(0xFF00658E),
    onTertiary = Color.White,
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF151A26),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF151A26),
    surfaceVariant = Color(0xFFE4E7F0),
    onSurfaceVariant = Color(0xFF454B5C),
    surfaceContainer = Color(0xFFEDEFF5),
    surfaceContainerHigh = Color(0xFFE6E9F0),
    surfaceContainerHighest = Color(0xFFDFE2EB),
    surfaceContainerLow = Color(0xFFF1F3F8),
    outline = Color(0xFF767C8E),
    outlineVariant = Color(0xFFC6CAD8),
    error = Color(0xFFB3261E),
)

private val EnergieTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/**
 * Dunkel ist die Hausfarbe: tiefes Nachtblau mit leuchtenden Akzenten. Wer
 * das System hell laesst, bekommt auf Android 12+ die Systemfarben, sonst
 * ein helles Gegenstueck.
 */
@Composable
fun EnergieTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = when {
        dark -> DarkScheme
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(LocalContext.current)
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = EnergieTypography, content = content)
}
