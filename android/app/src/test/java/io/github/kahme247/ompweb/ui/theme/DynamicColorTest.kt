package io.github.kahme247.ompweb.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicColorTest {

    @Test
    fun `material you exists from api 31 on`() {
        assertTrue(!materialYouSupported(26))
        assertTrue(!materialYouSupported(30))
        assertTrue(materialYouSupported(31))
        assertTrue(materialYouSupported(36))
    }

    @Test
    fun `contrast ratio follows wcag`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color(0xFF3366CC), Color(0xFF3366CC)), 0.001)
        // A translucent token is judged as it is actually drawn over the
        // backdrop, not as opaque black.
        val translucentBlack = contrastRatio(Color(0x80000000), Color.White)
        assertTrue(translucentBlack > 1.0)
        assertTrue(translucentBlack < contrastRatio(Color.Black, Color.White))
    }

    @Test
    fun `ensure contrast keeps a readable color and lifts an unreadable one`() {
        val readable = Color(0xFF1C1B1F)
        assertEquals(readable, ensureContrast(readable, Color.White))

        val unreadable = Color(0xFFB0B0B0) // ~2.2:1 on white
        val corrected = ensureContrast(unreadable, Color.White)
        assertNotEquals(unreadable, corrected)
        assertTrue(contrastRatio(corrected, Color.White) >= AA_TEXT_CONTRAST)

        val darkBackdrop = Color(0xFF101010)
        val darkCorrected = ensureContrast(Color(0xFF202020), darkBackdrop)
        assertTrue(contrastRatio(darkCorrected, darkBackdrop) >= AA_TEXT_CONTRAST)
    }

    @Test
    fun `mapping keeps the material roles on the matching omp tokens`() {
        val scheme = lightColorScheme()
        val colors = scheme.toOmpColors()

        assertEquals(scheme.background, colors.background)
        assertEquals(scheme.primary, colors.accent)
        assertEquals(scheme.primaryContainer, colors.userBackground)
        assertEquals(scheme.surfaceContainer, colors.panel)
        assertEquals(scheme.surfaceContainerHigh, colors.hover)
        assertEquals(scheme.surfaceContainerHighest, colors.selected)
        assertEquals(scheme.outlineVariant, colors.border)
        assertEquals(scheme.tertiary, colors.secondaryAccent)
        assertEquals(scheme.onPrimary, colors.onAccent)
        assertEquals(scheme.error, colors.statusError)
    }

    @Test
    fun `mapped text reaches aa on the background in both modes`() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val colors = scheme.toOmpColors()
            assertTrue(
                "text ${colors.text} on ${colors.background}",
                contrastRatio(colors.text, colors.background) >= AA_TEXT_CONTRAST,
            )
            assertTrue(
                "muted text ${colors.textMuted} on ${colors.background}",
                contrastRatio(colors.textMuted, colors.background) >= AA_TEXT_CONTRAST,
            )
            assertTrue(
                "dim text ${colors.textDim} on ${colors.background}",
                contrastRatio(colors.textDim, colors.background) >= AA_LARGE_CONTRAST,
            )
        }
    }

    @Test
    fun `a low contrast wallpaper text token is corrected`() {
        val scheme = lightColorScheme(
            background = Color(0xFF9A9A9A),
            onBackground = Color(0xFF909090),
        )
        val colors = scheme.toOmpColors()

        assertNotEquals(Color(0xFF909090), colors.text)
        assertTrue(contrastRatio(colors.text, colors.background) >= AA_TEXT_CONTRAST)
    }
}
