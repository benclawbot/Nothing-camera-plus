package app.cameraplus.camera

import kotlin.math.max

/** Normalized frame-space rectangle (0..1 on both axes). */
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

/** A point in either normalized frame space or preview-view pixels. */
data class TrackPoint(val x: Float, val y: Float)

/**
 * Maps between the normalized coordinates of the analysis frame (upright,
 * mirrored for the front camera exactly like the preview) and preview-view
 * pixels.
 *
 * The preview uses FILL_CENTER: the image is scaled until it covers the
 * view and the overflow is cropped symmetrically. [visibleRect] turns the
 * camera's current zoom crop into the band actually on screen, so tracking
 * and the on-screen reticle stay aligned while zooming.
 */
object TrackingGeometry {

    fun visibleRect(
        crop: NormRect,
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): NormRect {
        val safeFrameHeight = frameHeight.coerceAtLeast(1)
        val frameAspect = frameWidth.toFloat() / safeFrameHeight
        val viewAspect = viewWidth / viewHeight.coerceAtLeast(1f)
        val centerX = (crop.left + crop.right) / 2f
        val centerY = (crop.top + crop.bottom) / 2f
        return if (viewAspect > frameAspect) {
            // Wider view: the top and bottom of the frame are cropped.
            val height = crop.width * frameAspect / viewAspect
            NormRect(crop.left, centerY - height / 2f, crop.right, centerY + height / 2f)
        } else {
            // Taller view: the left and right of the frame are cropped.
            val width = crop.height * viewAspect / frameAspect
            NormRect(centerX - width / 2f, crop.top, centerX + width / 2f, crop.bottom)
        }
    }

    /** Preview pixels per analysis-frame pixel for the visible band. */
    fun scale(
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        visible: NormRect,
    ): Float = max(
        viewWidth / (frameWidth * visible.width).coerceAtLeast(0.0001f),
        viewHeight / (frameHeight * visible.height).coerceAtLeast(0.0001f),
    )

    fun frameToView(
        nx: Float,
        ny: Float,
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        visible: NormRect,
    ): TrackPoint {
        val scale = scale(frameWidth, frameHeight, viewWidth, viewHeight, visible)
        val renderedWidth = frameWidth * visible.width * scale
        val renderedHeight = frameHeight * visible.height * scale
        val offsetX = (renderedWidth - viewWidth) / 2f
        val offsetY = (renderedHeight - viewHeight) / 2f
        val u = (nx - visible.left) / visible.width.coerceAtLeast(0.0001f)
        val v = (ny - visible.top) / visible.height.coerceAtLeast(0.0001f)
        return TrackPoint(u * renderedWidth - offsetX, v * renderedHeight - offsetY)
    }

    fun viewToFrame(
        vx: Float,
        vy: Float,
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        visible: NormRect,
    ): TrackPoint {
        val scale = scale(frameWidth, frameHeight, viewWidth, viewHeight, visible)
        val renderedWidth = frameWidth * visible.width * scale
        val renderedHeight = frameHeight * visible.height * scale
        val offsetX = (renderedWidth - viewWidth) / 2f
        val offsetY = (renderedHeight - viewHeight) / 2f
        val u = (vx + offsetX) / renderedWidth.coerceAtLeast(0.0001f)
        val v = (vy + offsetY) / renderedHeight.coerceAtLeast(0.0001f)
        return TrackPoint(
            visible.left + u * visible.width,
            visible.top + v * visible.height,
        )
    }
}
