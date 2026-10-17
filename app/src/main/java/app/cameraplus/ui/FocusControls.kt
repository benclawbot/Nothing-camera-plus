package app.cameraplus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cameraplus.camera.SUBJECT_TRACKING_BOX_DP
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.FaintWhite
import app.cameraplus.ui.theme.White
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Viewfinder focus + brightness controls (v1.1).
 *
 * Tap-to-focus puts a ring on the screen and, next to it, the brightness
 * slider the stock app shows: dragging it walks the exposure-compensation
 * index the hardware reports, and the ring's lock badge means the metering
 * point no longer times out.
 */

/** The focus ring: an outer circle, plus a lock badge when metering is locked. */
@Composable
fun FocusRing(
    locked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(RING_SIZE)) {
            val radius = size.minDimension / 2f - 2.dp.toPx()
            drawCircle(
                color = if (locked) White else FaintWhite,
                radius = radius,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(color = if (locked) White else DimWhite, radius = 2.5.dp.toPx())
        }
        if (locked) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = "Focus and exposure locked",
                tint = White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(16.dp),
            )
        }
    }
}

/**
 * Subject-tracking reticle: four corner brackets around the tracked subject.
 * It fades while the tracker is not confident (subject occluded or gone).
 */
@Composable
fun TrackingReticle(
    confidence: Float,
    modifier: Modifier = Modifier,
) {
    val strong = confidence >= 0.5f
    Box(modifier = modifier.size(SUBJECT_TRACKING_BOX_DP.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val color = if (strong) White else White.copy(alpha = 0.55f)
            val stroke = 2.dp.toPx()
            val corner = size.minDimension * 0.3f
            // Top-left, top-right, bottom-left, bottom-right brackets.
            drawLine(color, Offset(0f, 0f), Offset(corner, 0f), stroke)
            drawLine(color, Offset(0f, 0f), Offset(0f, corner), stroke)
            drawLine(color, Offset(size.width, 0f), Offset(size.width - corner, 0f), stroke)
            drawLine(color, Offset(size.width, 0f), Offset(size.width, corner), stroke)
            drawLine(color, Offset(0f, size.height), Offset(corner, size.height), stroke)
            drawLine(color, Offset(0f, size.height), Offset(0f, size.height - corner), stroke)
            drawLine(color, Offset(size.width, size.height), Offset(size.width - corner, size.height), stroke)
            drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - corner), stroke)
            drawCircle(color, radius = 2.5.dp.toPx())
        }
    }
}

/**
 * Vertical brightness slider shown next to the focus ring.
 *
 * [onChange] receives the new exposure-compensation index. The mapping from
 * drag distance to index uses the camera's own EV step, so the slider spans
 * exactly the range the hardware accepts, and it is hidden when there is none.
 */
@Composable
fun BrightnessSlider(
    index: Int,
    indexRange: IntRange?,
    evStep: Float,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (indexRange == null || indexRange.last <= indexRange.first) return
    val density = LocalDensity.current
    val steps = (indexRange.last - indexRange.first).toFloat().coerceAtLeast(1f)

    Box(
        modifier = modifier
            .width(SLIDER_WIDTH)
            .height(SLIDER_HEIGHT),
        contentAlignment = Alignment.TopCenter,
    ) {
        Icon(
            imageVector = Icons.Outlined.WbSunny,
            contentDescription = "Brightness",
            tint = White,
            modifier = Modifier.size(20.dp),
        )
        Box(
            modifier = Modifier
                .padding(top = TRACK_TOP_PADDING, bottom = TRACK_BOTTOM_PADDING)
                .width(3.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Color(0x55FFFFFF)),
        )
        // Neutral (index 0) tick, so "how bright am I" is readable at a glance.
        if (indexRange.first <= 0 && indexRange.last >= 0) {
            Box(
                modifier = Modifier
                    .offset(y = trackTopFor(0f, indexRange, steps, density) - NEUTRAL_TICK_HALF)
                    .width(11.dp)
                    .height(NEUTRAL_TICK_HALF * 2)
                    .background(Color(0x99FFFFFF)),
            )
        }
        Box(
            modifier = Modifier
                .offset(y = trackTopFor(index.toFloat(), indexRange, steps, density) - KNOB_HALF)
                .size(KNOB_HALF * 2)
                .clip(CircleShape)
                .background(White),
        )
        Text(
            text = formatEv(index * evStep),
            color = DimWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // Drag surface across the whole column.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(SLIDER_WIDTH)
                .pointerInput(indexRange) {
                    val trackHeightPx = TRACK_HEIGHT.toPx()
                    var accumulated = index.toFloat()
                    detectVerticalDragGestures(
                        onDragStart = { accumulated = index.toFloat() },
                        onDragEnd = { onChange(accumulated.roundToInt()) },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            // Dragging up brightens: the full track is the
                            // camera's whole compensation range.
                            accumulated -= dragAmount * steps / trackHeightPx
                            val snapped = accumulated.roundToInt()
                            if (snapped != index) onChange(snapped)
                        },
                    )
                },
        )
    }
}

/** Where a value sits on the track, as a top offset. */
private fun trackTopFor(
    value: Float,
    indexRange: IntRange,
    steps: Float,
    density: Density,
): Dp {
    val fraction = ((value - indexRange.first) / steps).coerceIn(0f, 1f)
    return with(density) { (TRACK_HEIGHT.toPx() * (1f - fraction)).toDp() }
}

private fun formatEv(ev: Float): String {
    if (abs(ev) < 0.05f) return "EV 0"
    val rounded = (ev * 10f).roundToInt() / 10f
    return "EV ${if (rounded > 0f) "+" else ""}$rounded"
}

private val RING_SIZE = 54.dp
private val SLIDER_HEIGHT = 190.dp
private val SLIDER_WIDTH = 46.dp
private val TRACK_TOP_PADDING = 26.dp
private val TRACK_BOTTOM_PADDING = 18.dp
private val TRACK_HEIGHT = SLIDER_HEIGHT - TRACK_TOP_PADDING - TRACK_BOTTOM_PADDING
private val KNOB_HALF = 6.dp
private val NEUTRAL_TICK_HALF = 1.dp
