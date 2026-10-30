package io.github.kahme247.ompweb.ui.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageZoomTest {

    private val viewport = IntSize(400, 800)

    @Test
    fun `fit scale shrinks large images and never enlarges small ones`() {
        assertEquals(0.4f, fitScale(viewport, IntSize(1000, 1000)), 0.0001f)
        assertEquals(1f, fitScale(viewport, IntSize(100, 100)), 0.0001f)
        assertEquals(1f, fitScale(IntSize.Zero, IntSize(1000, 1000)), 0.0001f)
    }

    @Test
    fun `zoom scale is clamped to the supported range`() {
        val natural = IntSize(1000, 1000)
        assertEquals(ImageZoom.MAX_SCALE, ImageZoom(scale = 50f).clamped(viewport, natural).scale, 0.0001f)
        assertEquals(ImageZoom.MIN_SCALE, ImageZoom(scale = 0.2f).clamped(viewport, natural).scale, 0.0001f)
    }

    @Test
    fun `pan is limited to the part of the image outside the viewport`() {
        val natural = IntSize(1000, 1000)
        val clamped = ImageZoom(scale = 2f, offset = Offset(500f, -300f)).clamped(viewport, natural)
        // fit 0.4 x scale 2 => 800 px drawn; (800 - 400) / 2 = 200 horizontally,
        // (800 - 800) / 2 = 0 vertically.
        assertEquals(Offset(200f, 0f), clamped.offset)
    }

    @Test
    fun `an image smaller than the viewport cannot be panned off centre`() {
        val natural = IntSize(100, 100)
        val clamped = ImageZoom(scale = 3f, offset = Offset(100f, 100f)).clamped(viewport, natural)
        assertEquals(Offset.Zero, clamped.offset)
    }

    @Test
    fun `a shrunk image keeps a zero pan even at the zoom floor`() {
        val natural = IntSize(200, 100)
        assertEquals(Offset.Zero, ImageZoom(scale = 1f, offset = Offset(-80f, 40f)).clamped(viewport, natural).offset)
    }

    @Test
    fun `double tap zooms to one natural pixel per screen pixel`() {
        val natural = IntSize(1000, 1000) // fit 0.4 in a 400x800 viewport
        val zoomed = doubleTapZoom(ImageZoom(), fit = 0.4f)
        assertEquals(2.5f, zoomed.scale, 0.0001f)
        assertEquals(Offset.Zero, zoomed.offset)
    }

    @Test
    fun `double tap on a zoomed image returns to fit`() {
        assertEquals(ImageZoom(), doubleTapZoom(ImageZoom(scale = 2.5f, offset = Offset(30f, 40f)), fit = 0.4f))
    }

    @Test
    fun `double tap never exceeds the maximum scale`() {
        assertEquals(ImageZoom.MAX_SCALE, doubleTapZoom(ImageZoom(), fit = 0.01f).scale, 0.0001f)
    }

    @Test
    fun `actual size is one to one and starts centred`() {
        assertEquals(2.5f, actualSizeZoom(0.4f).scale, 0.0001f)
        assertEquals(Offset.Zero, actualSizeZoom(0.4f).offset)
        assertEquals(1f, actualSizeZoom(1f).scale, 0.0001f)
    }

    @Test
    fun `swipe down closes only while not zoomed`() {
        val threshold = 200f
        assertTrue(shouldDismissOnSwipe(ImageZoom(), Offset(10f, 250f), threshold))
        assertFalse("a short drag snaps back", shouldDismissOnSwipe(ImageZoom(), Offset(10f, 100f), threshold))
        assertFalse(
            "a horizontal swipe is not a dismiss",
            shouldDismissOnSwipe(ImageZoom(), Offset(300f, 250f), threshold),
        )
        assertFalse(
            "a zoomed image pans instead of closing",
            shouldDismissOnSwipe(ImageZoom(scale = 2f), Offset(0f, 400f), threshold),
        )
        assertFalse(
            "an upward swipe is not a dismiss",
            shouldDismissOnSwipe(ImageZoom(), Offset(0f, -400f), threshold),
        )
    }
}
