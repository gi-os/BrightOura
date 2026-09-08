@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.gios.brightoura.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.gios.brightoura.R

/**
 * The design system for the Oura tracker — a monochrome panel translation of the Oura app.
 *
 * The Light Phone III renders greyscale on a matte panel, so the whole language is luminance:
 * near-black ground, a ramp of greys for hierarchy, and Space Grotesk throughout — big and light
 * for the numbers, small and tracked for the labels. Every hex here is taken from the mockup.
 */
object Ink {
    val Bg = Color(0xFF000000)          // panel ground
    val Card = Color(0xFF1C1C1C)        // a raised block
    val Card2 = Color(0xFF232323)       // a second surface
    val Rule = Color(0xFF2B2B2B)        // hairlines, bar tracks
    val RuleBright = Color(0xFF3A3A3A)
    val Faint = Color(0xFF6D6D6D)        // captions, footnotes
    val Dim = Color(0xFF8A8A8A)          // metric labels
    val Mid = Color(0xFF9A9A9A)
    val Soft = Color(0xFFB8B8B8)         // secondary values
    val Bright = Color(0xFFD2D2D2)
    val Near = Color(0xFFF0F0F0)         // primary text / bar fills
    val White = Color(0xFFFFFFFF)
}

/** Space Grotesk, the mockup's typeface, as a variable font driven to real weights. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.space_grotesk, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.space_grotesk, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)
