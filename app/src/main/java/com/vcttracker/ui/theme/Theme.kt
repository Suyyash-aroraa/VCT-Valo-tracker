package com.vcttracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vcttracker.R

/**
 * Palette taken from Valorant's own world: bone paper, ink, and Spike red.
 * Red is reserved for "live" and "won"; amber/teal only ever mean attack/defense.
 */
@Immutable
data class VctColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val spike: Color,
    val spikeText: Color,
    val onSpike: Color,
    val attack: Color,
    val defense: Color,
    val isDark: Boolean,
)

private val Bone = VctColors(
    background = Color(0xFFECE8E1),
    surface = Color(0xFFF5F2EC),
    surfaceAlt = Color(0xFFE2DDD3),
    ink = Color(0xFF0F1923),
    muted = Color(0xFF56606B),
    faint = Color(0xFF8C939B),
    line = Color(0xFFD2CBBF),
    spike = Color(0xFFFF4655),
    spikeText = Color(0xFFD2283A),
    onSpike = Color(0xFFFFFFFF),
    attack = Color(0xFFD08416),
    defense = Color(0xFF16978A),
    isDark = false,
)

private val Arena = VctColors(
    background = Color(0xFF0F1923),
    surface = Color(0xFF15212C),
    surfaceAlt = Color(0xFF1C2A37),
    ink = Color(0xFFECE8E1),
    muted = Color(0xFF97A1AC),
    faint = Color(0xFF66727E),
    line = Color(0xFF263541),
    spike = Color(0xFFFF4655),
    spikeText = Color(0xFFFF5A67),
    onSpike = Color(0xFFFFFFFF),
    attack = Color(0xFFE9A23B),
    defense = Color(0xFF2BB3A3),
    isDark = true,
)

@OptIn(ExperimentalTextApi::class)
private fun shoulders(weight: Int) = Font(
    R.font.big_shoulders,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val DisplayFamily = FontFamily(shoulders(700), shoulders(800), shoulders(900))

val BodyFamily = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

val MonoFamily = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

@Immutable
data class VctType(
    val hero: TextStyle,
    val display: TextStyle,
    val score: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val small: TextStyle,
    val label: TextStyle,
    val data: TextStyle,
)

private val Type = VctType(
    hero = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(900), fontSize = 58.sp, lineHeight = 52.sp, letterSpacing = (-0.01).em),
    display = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(800), fontSize = 34.sp, lineHeight = 34.sp),
    score = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(800), fontSize = 26.sp, lineHeight = 26.sp),
    title = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(800), fontSize = 22.sp, lineHeight = 24.sp, letterSpacing = 0.01.em),
    heading = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    body = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyStrong = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    small = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp),
    label = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
    data = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 16.sp),
)

val LocalVctColors = staticCompositionLocalOf { Bone }
val LocalVctType = staticCompositionLocalOf { Type }

object Vct {
    val colors: VctColors @Composable get() = LocalVctColors.current
    val type: VctType @Composable get() = LocalVctType.current
}

@Composable
fun VctTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Arena else Bone
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.ink, onPrimary = c.background, background = c.background, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink, surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.muted,
            outline = c.line, error = c.spike,
        )
    } else {
        lightColorScheme(
            primary = c.ink, onPrimary = c.background, background = c.background, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink, surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.muted,
            outline = c.line, error = c.spike,
        )
    }
    CompositionLocalProvider(LocalVctColors provides c, LocalVctType provides Type) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
