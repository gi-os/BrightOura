package com.gios.brightoura.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The monochrome Oura theme. Ground is #141414, text is the grey ramp, and the whole thing is
 * Space Grotesk — light and large for the numbers a glance is for, small and tracked for labels.
 * The named type scale mirrors the mockup's sizes (11 → 88).
 */
private val MonoDark = darkColorScheme(
    primary = Ink.White, onPrimary = Ink.Bg,
    background = Ink.Bg, onBackground = Ink.Near,
    surface = Ink.Bg, onSurface = Ink.Near,
    surfaceVariant = Ink.Bg, onSurfaceVariant = Ink.Soft,
    outline = Ink.Rule,
)

@Composable
fun BrightOuraTheme(content: @Composable () -> Unit) {
    val type = Typography(
        // Hero score — light and huge, the one number a glance reads.
        displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 88.sp, fontWeight = FontWeight.Normal),
        displayMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 62.sp, fontWeight = FontWeight.Normal),
        displaySmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 34.sp, fontWeight = FontWeight.Normal),
        headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 22.sp, fontWeight = FontWeight.Medium),
        titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 17.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 14.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 13.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(
            fontFamily = SpaceGrotesk, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.8.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = SpaceGrotesk, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp,
        ),
    )
    MaterialTheme(colorScheme = MonoDark, typography = type, content = content)
}
