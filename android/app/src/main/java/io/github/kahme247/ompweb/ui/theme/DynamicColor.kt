package io.github.kahme247.ompweb.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import kotlin.math.pow

/**
 * Wallpaper-derived colors exist from Android 12 (API 31) on; below that the
 * platform has no `dynamic*ColorScheme` source and the setting is hidden.
 */
const val MATERIAL_YOU_MIN_API = Build.VERSION_CODES.S

internal fun materialYouSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= MATERIAL_YOU_MIN_API

/** WCAG 2.1 AA for normal-size text. */
internal const val AA_TEXT_CONTRAST = 4.5

/** WCAG 2.1 AA for large text and UI components. */
internal const val AA_LARGE_CONTRAST = 3.0

/**
 * WCAG 2.1 relative luminance of an sRGB color. A translucent color is
 * composited over a white backdrop only to keep the function total; callers
 * that care about a real backdrop use [contrastRatio] with that backdrop.
 */
internal fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/**
 * WCAG 2.1 contrast ratio of [foreground] over the opaque [background];
 * 1 for two equal colors, 21 for black on white. Translucent foregrounds are
 * composited first, because the palette stores e.g. the subtle fill as an
 * alpha value.
 */
internal fun contrastRatio(foreground: Color, background: Color): Double {
    val opaque = background.copy(alpha = 1f)
    val fg = foreground.compositeOver(opaque)
    val lighter = maxOf(relativeLuminance(fg), relativeLuminance(opaque))
    val darker = minOf(relativeLuminance(fg), relativeLuminance(opaque))
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Returns [foreground] when it already reaches [minRatio] over [background],
 * otherwise the closest blend toward black or white that does. A wallpaper
 * palette is not guaranteed to carry a readable text token on every surface,
 * so the mapping corrects the few tokens that fall short instead of shipping
 * text below AA (the user can pick any wallpaper).
 */
internal fun ensureContrast(
    foreground: Color,
    background: Color,
    minRatio: Double = AA_TEXT_CONTRAST,
): Color {
    if (contrastRatio(foreground, background) >= minRatio) return foreground
    val opaqueBackground = background.copy(alpha = 1f)
    val target = if (
        contrastRatio(Color.White, opaqueBackground) >= contrastRatio(Color.Black, opaqueBackground)
    ) {
        Color.White
    } else {
        Color.Black
    }
    // Fixed number of steps: deterministic, and 1/24 is finer than the eye
    // can resolve between two AA-passing grays.
    for (step in 1..24) {
        val candidate = lerp(foreground, target, step / 24f)
        if (contrastRatio(candidate, opaqueBackground) >= minRatio) return candidate
    }
    return target
}

/**
 * The dynamic scheme resolved for the current device, or the plain Material 3
 * baseline below API 31 (where callers never ask for it).
 */
private fun dynamicScheme(context: Context, dark: Boolean): ColorScheme {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return if (dark) darkColorScheme() else lightColorScheme()
}

/** A palette plus the luminance the window bars and Material theme follow. */
@Immutable
internal data class ResolvedOmpPalette(val colors: OmpColors, val isDark: Boolean)

/**
 * The palette actually rendered: the stored ompweb theme, or - when the
 * user enabled Material You on API 31+ - the wallpaper-derived scheme mapped
 * onto the same [OmpColors] tokens. The selected web theme is ignored in that
 * case, which is what "use system colors" means.
 */
@Composable
internal fun resolveOmpPalette(themeId: String, useSystemColors: Boolean): ResolvedOmpPalette {
    val theme = resolveOmpTheme(themeId)
    if (!useSystemColors || !materialYouSupported()) {
        return ResolvedOmpPalette(theme.colors, theme.isDark)
    }
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = remember(context, dark) { dynamicScheme(context, dark) }
    return ResolvedOmpPalette(scheme.toOmpColors(), dark)
}

/**
 * Maps a Material 3 scheme onto the app's tokens. The M3 slots carry the roles
 * the web tokens carry (surface/panel/outline/primary), so every screen keeps
 * reading one palette; the two text roles are corrected to AA over the
 * background because a wallpaper hue can be arbitrarily dark or light.
 */
internal fun ColorScheme.toOmpColors(): OmpColors {
    val background = background
    return OmpColors(
        background = background,
        panel = surfaceContainer,
        hover = surfaceContainerHigh,
        selected = surfaceContainerHighest,
        border = outlineVariant,
        text = ensureContrast(onBackground, background),
        textMuted = ensureContrast(onSurfaceVariant, background),
        textDim = ensureContrast(outline, background, AA_LARGE_CONTRAST),
        accent = primary,
        accentStrong = primary,
        accentHover = inversePrimary,
        secondaryAccent = tertiary,
        userBackground = primaryContainer,
        toolBackground = surfaceContainerLow,
        backgroundSubtle = onBackground.copy(alpha = 0.04f),
        statusError = ensureContrast(error, background),
        statusSuccess = ensureContrast(tertiary, background),
        statusWarning = ensureContrast(secondary, background),
        statusModified = ensureContrast(secondary, background),
        statusRenamed = ensureContrast(primary, background),
        onAccent = onPrimary,
        overlayBackdrop = onBackground.copy(alpha = 0.24f),
    )
}
