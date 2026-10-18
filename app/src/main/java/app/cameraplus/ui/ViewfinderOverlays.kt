package app.cameraplus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import app.cameraplus.camera.NormRect
import app.cameraplus.camera.OverlayMasks
import app.cameraplus.camera.TrackingGeometry
import kotlin.math.abs
import kotlin.math.min

/** Stripe spacing in view pixels. */
private const val ZEBRA_STRIPE_WIDTH = 7f

/** How see-through the crushed-black wash is. */
private const val SHADOW_WASH_ALPHA = 0.33f

/**
 * Zebra stripes over blown highlights plus a cool wash over crushed blacks,
 * drawn in the same frame space as subject tracking, so the warning sits on the
 * pixels it is warning about.
 *
 * Marked cells are merged into row runs ([OverlayMasks.highlightSpans]), so a
 * blown sky costs one striped rectangle rather than hundreds.
 */
@Composable
fun ZebraOverlay(masks: OverlayMasks, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (masks.isBlank || !masks.isMappable()) return@Canvas
        val visible = TrackingGeometry.visibleRect(
            NormRect.FULL, masks.frameWidth, masks.frameHeight, size.width, size.height,
        )
        masks.shadowSpans().forEach { span ->
            val bounds = cellBounds(span, masks, size.width, size.height, visible)
            drawRect(
                color = Color(0x5533AAFF).copy(alpha = SHADOW_WASH_ALPHA),
                topLeft = bounds.topLeft,
                size = bounds.size,
            )
        }
        masks.highlightSpans().forEach { span ->
            val bounds = cellBounds(span, masks, size.width, size.height, visible)
            clipRect(
                left = bounds.topLeft.x,
                top = bounds.topLeft.y,
                right = bounds.topLeft.x + bounds.size.width,
                bottom = bounds.topLeft.y + bounds.size.height,
            ) {
                var x = bounds.topLeft.x - bounds.size.height
                while (x < bounds.topLeft.x + bounds.size.width) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.6f),
                        start = Offset(x, bounds.topLeft.y + bounds.size.height),
                        end = Offset(x + bounds.size.height, bounds.topLeft.y),
                        strokeWidth = ZEBRA_STRIPE_WIDTH,
                    )
                    x += ZEBRA_STRIPE_WIDTH * 2f
                }
            }
        }
    }
}

/**
 * Focus peaking: a thin outline on every cell whose local contrast says "this is
 * an edge", so manual focus has something concrete to land on.
 */
@Composable
fun PeakingOverlay(masks: OverlayMasks, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (masks.columns <= 0 || masks.rows <= 0 || !masks.isMappable()) return@Canvas
        val visible = TrackingGeometry.visibleRect(
            NormRect.FULL, masks.frameWidth, masks.frameHeight, size.width, size.height,
        )
        masks.peakingSpans().forEach { span ->
            val bounds = cellBounds(span, masks, size.width, size.height, visible)
            drawRect(
                color = Color(0xCC39FF6A),
                topLeft = bounds.topLeft,
                size = bounds.size,
                style = Stroke(width = 2f),
            )
        }
    }
}

private fun OverlayMasks.isMappable(): Boolean = frameWidth > 0 && frameHeight > 0

private class CellBounds(val topLeft: Offset, val size: Size)

/** The view-space rectangle of a run of cells, using the tracking mapping. */
private fun cellBounds(
    span: OverlayMasks.Span,
    masks: OverlayMasks,
    viewWidth: Float,
    viewHeight: Float,
    visible: NormRect,
): CellBounds {
    val left = TrackingGeometry.frameToView(
        span.firstColumn.toFloat() / masks.columns,
        span.row.toFloat() / masks.rows,
        masks.frameWidth,
        masks.frameHeight,
        viewWidth,
        viewHeight,
        visible,
    )
    val right = TrackingGeometry.frameToView(
        (span.lastColumn + 1f) / masks.columns,
        (span.row + 1f) / masks.rows,
        masks.frameWidth,
        masks.frameHeight,
        viewWidth,
        viewHeight,
        visible,
    )
    return CellBounds(
        topLeft = Offset(min(left.x, right.x), min(left.y, right.y)),
        size = Size(abs(right.x - left.x), abs(right.y - left.y)),
    )
}
