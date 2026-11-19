package com.meshx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.meshx.app.model.ThemeMode

/** All colours of the app. There is one set for light and one for dark. */
data class MeshPalette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val muted: Color,
    val outline: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val primaryDeep: Color,
    val bubbleIn: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
) {
    /** Colour for a link score from 0 to 100. */
    fun score(score: Int): Color = when {
        score >= 55 -> success
        score >= 35 -> warning
        score > 0 -> danger
        else -> muted
    }
}

val LightPalette = MeshPalette(
    dark = false,
    bg = Color(0xFFF4F7F8), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFEDF2F4),
    ink = Color(0xFF0E1A22), muted = Color(0xFF5E6B73), outline = Color(0xFFDCE3E7),
    primary = Color(0xFF0F9D8A), onPrimary = Color(0xFFFFFFFF), primarySoft = Color(0xFFD7F2EE), primaryDeep = Color(0xFF0B6E61),
    bubbleIn = Color(0xFFE9EEF1),
    success = Color(0xFF1E9E5A), warning = Color(0xFFD9930D), danger = Color(0xFFD64545),
)

val DarkPalette = MeshPalette(
    dark = true,
    bg = Color(0xFF0B1418), surface = Color(0xFF142127), surfaceAlt = Color(0xFF1B2B32),
    ink = Color(0xFFE8EEF0), muted = Color(0xFF93A3AB), outline = Color(0xFF263A43),
    primary = Color(0xFF2CC5AF), onPrimary = Color(0xFF04201C), primarySoft = Color(0xFF12403A), primaryDeep = Color(0xFF7FE3D4),
    bubbleIn = Color(0xFF1E3038),
    success = Color(0xFF45C482), warning = Color(0xFFF0B341), danger = Color(0xFFF07070),
)

private val LocalPalette = staticCompositionLocalOf { LightPalette }

object Mesh {
    val colors: MeshPalette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
}

@Composable
fun MeshXTheme(mode: ThemeMode, typography: Typography = Typography(), content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.primarySoft, onPrimaryContainer = p.primaryDeep,
            background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.surfaceAlt, onSurfaceVariant = p.muted, outline = p.outline, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.primarySoft, onPrimaryContainer = p.primaryDeep,
            background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.surfaceAlt, onSurfaceVariant = p.muted, outline = p.outline, error = p.danger,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
