package io.github.kahme247.ompweb.ui.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The lightbox transform: a scale about the viewport centre and a pan in
 * pixels. Kept as a value so the clamp and double-tap decisions are plain
 * functions the JVM tests can exercise without a device.
 */
internal data class ImageZoom(
    val scale: Float = MIN_SCALE,
    val offset: Offset = Offset.Zero,
) {
    val isZoomed: Boolean get() = scale > DOUBLE_TAP_THRESHOLD

    companion object {
        /** Fit-to-screen is the floor: the viewer never shrinks below the viewport. */
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f

        /** Scale above which a double tap zooms back out instead of in. */
        const val DOUBLE_TAP_THRESHOLD = 1.01f

        /** Vertical drag in dp that closes the viewer while it is not zoomed. */
        const val SWIPE_DISMISS_DP = 120f
    }
}

/** The scale at which a [natural] image fits completely inside [viewport]. */
internal fun fitScale(viewport: IntSize, natural: IntSize): Float {
    if (natural.width <= 0 || natural.height <= 0 || viewport.width <= 0 || viewport.height <= 0) {
        return 1f
    }
    return min(
        1f,
        min(
            viewport.width.toFloat() / natural.width,
            viewport.height.toFloat() / natural.height,
        ),
    )
}

/**
 * The largest pan that still keeps the scaled image covering the viewport on
 * that axis; zero when the image is smaller than the viewport, so it stays
 * centred instead of being dragged into a corner.
 */
internal fun maxPan(viewport: IntSize, natural: IntSize, fit: Float, scale: Float): Offset {
    if (viewport.width <= 0 || viewport.height <= 0 || natural.width <= 0 || natural.height <= 0) {
        return Offset.Zero
    }
    val drawnWidth = natural.width * fit * scale
    val drawnHeight = natural.height * fit * scale
    return Offset(
        max(0f, (drawnWidth - viewport.width) / 2f),
        max(0f, (drawnHeight - viewport.height) / 2f),
    )
}

/** Clamps this transform to the supported scale range and on-image pan. */
internal fun ImageZoom.clamped(
    viewport: IntSize,
    natural: IntSize,
    fit: Float = fitScale(viewport, natural),
): ImageZoom {
    val boundedScale = scale.coerceIn(ImageZoom.MIN_SCALE, ImageZoom.MAX_SCALE)
    val limit = maxPan(viewport, natural, fit, boundedScale)
    // coerceIn with a `-0f` lower bound can produce `-0.0`; normalize so an
    // un-panned image compares equal to Offset.Zero.
    fun axis(value: Float, max: Float): Float {
        val bounded = value.coerceIn(-max, max)
        return if (bounded == 0f) 0f else bounded
    }
    return ImageZoom(
        scale = boundedScale,
        offset = Offset(axis(offset.x, limit.x), axis(offset.y, limit.y)),
    )
}

/**
 * Double tap toggles between fit-to-screen and 1:1 pixels: a zoomed image
 * returns to fit, a fitted one grows until one natural pixel is one screen
 * pixel (never past [ImageZoom.MAX_SCALE]).
 */
internal fun doubleTapZoom(current: ImageZoom, fit: Float): ImageZoom {
    if (current.isZoomed) return ImageZoom()
    val actual = (1f / fit.coerceIn(0.01f, 1f)).coerceIn(ImageZoom.MIN_SCALE, ImageZoom.MAX_SCALE)
    return ImageZoom(scale = actual)
}

/** 1:1 for the explicit "1:1" button. */
internal fun actualSizeZoom(fit: Float): ImageZoom =
    ImageZoom(scale = (1f / fit.coerceIn(0.01f, 1f)).coerceIn(ImageZoom.MIN_SCALE, ImageZoom.MAX_SCALE))

/**
 * Whether releasing a drag closes the viewer: only while the image is not
 * zoomed (a pan must never dismiss), and only for a mostly vertical drag past
 * the threshold.
 */
internal fun shouldDismissOnSwipe(zoom: ImageZoom, drag: Offset, thresholdPx: Float): Boolean {
    if (zoom.isZoomed) return false
    if (drag.y < thresholdPx) return false
    return abs(drag.y) > abs(drag.x)
}
