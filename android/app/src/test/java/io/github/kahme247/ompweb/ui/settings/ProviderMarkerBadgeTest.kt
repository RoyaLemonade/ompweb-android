package io.github.kahme247.ompweb.ui.settings

import androidx.compose.ui.graphics.Color
import io.github.kahme247.ompweb.domain.providerMarker
import io.github.kahme247.ompweb.ui.theme.OmpTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marker must name a provider without becoming a second text color: the
 * tint is a theme token, every alias keeps its provider's tint, and the
 * monogram keeps WCAG AA contrast on every palette of the app.
 */
class ProviderMarkerBadgeTest {

    @Test
    fun aliasesShareTheirProvidersTint() {
        assertEquals(
            providerTone(providerMarker("commandcode-a").baseId),
            providerTone(providerMarker("commandcode_b").baseId),
        )
        assertEquals(
            providerTone(providerMarker("openai-codex-c").baseId),
            providerTone(providerMarker("openai-codex").baseId),
        )
    }

    @Test
    fun everyProviderHasAStableTint() {
        for (id in listOf("commandcode", "openai", "openai-codex", "anthropic", "google", "gemini")) {
            assertEquals("$id must not change between calls", providerTone(id), providerTone(id))
        }
        assertEquals(ProviderTone.Accent, providerTone("commandcode"))
        assertEquals(ProviderTone.Success, providerTone("openai"))
        assertEquals(ProviderTone.Success, providerTone("openai-codex"))
        assertEquals(ProviderTone.Modified, providerTone("anthropic"))
        assertEquals(ProviderTone.Renamed, providerTone("google"))
        assertEquals(ProviderTone.Renamed, providerTone("gemini"))
    }

    @Test
    fun unknownProvidersStillGetOneOfTheThemeTints() {
        for (id in listOf("acme", "k2", "some-gateway", "42")) {
            val tone = providerTone(id)
            assertTrue("$id mapped outside the known tones", tone in ProviderTone.entries)
        }
    }

    @Test
    fun theMonogramKeepsAaContrastOnEveryTheme() {
        for (theme in OmpTheme.entries) {
            val colors = theme.colors
            val surfaces = listOf(
                colors.background,
                colors.panel,
                // The expanded usage row paints `backgroundSubtle` over the panel.
                composite(colors.backgroundSubtle, colors.panel),
            )
            for (surface in surfaces) {
                for (tone in ProviderTone.entries) {
                    val badge = composite(
                        tone.color(colors).copy(alpha = PROVIDER_BADGE_BACKGROUND_ALPHA),
                        surface,
                    )
                    val ratio = contrast(colors.text, badge)
                    assertTrue(
                        "${theme.id}/${tone.name}: monogram contrast $ratio is below AA 4.5",
                        ratio >= 4.5,
                    )
                }
            }
        }
    }

    @Test
    fun theAliasSuffixKeepsAaContrastOnEveryTheme() {
        for (theme in OmpTheme.entries) {
            val colors = theme.colors
            for (surface in listOf(colors.background, colors.panel)) {
                val ratio = contrast(colors.text, surface)
                assertTrue(
                    "${theme.id}: alias contrast $ratio is below AA 4.5",
                    ratio >= 4.5,
                )
            }
        }
    }

    private fun composite(source: Color, destination: Color): Color {
        val alpha = source.alpha
        return Color(
            red = source.red * alpha + destination.red * (1f - alpha),
            green = source.green * alpha + destination.green * (1f - alpha),
            blue = source.blue * alpha + destination.blue * (1f - alpha),
            alpha = 1f,
        )
    }

    /** WCAG 2.x relative luminance and contrast ratio of sRGB colors. */
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
