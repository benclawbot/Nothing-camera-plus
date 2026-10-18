package app.cameraplus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.cameraplus.camera.LumaHistogram
import kotlin.math.roundToInt

/**
 * Expert-mode histogram (M5): the live luma distribution with the mean and the
 * clipped-black/white share under it, fed by [LumaHistogram] statistics from
 * the session's analysis stream.
 *
 * Deliberately small and monochrome: it should read as a camera instrument,
 * not as a chart, so it does not compete with the viewfinder.
 */
@Composable
fun HistogramOverlay(histogram: LumaHistogram, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Canvas(
            modifier = Modifier
                .width(120.dp)
                .height(44.dp),
        ) {
            val barWidth = size.width / histogram.bins.size
            histogram.bins.forEachIndexed { index, normalizedHeight ->
                val barHeight = size.height * normalizedHeight.coerceIn(0f, 1f)
                drawRect(
                    color = Color.White,
                    topLeft = Offset(index * barWidth, size.height - barHeight),
                    size = Size(barWidth * 0.8f, barHeight),
                )
            }
        }
        Text(
            text = "MEAN ${histogram.mean}",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = "CLIP ${percent(histogram.shadowClipped)}/${percent(histogram.highlightClipped)}",
            color = Color.White.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Clip readout in tenths of a percent, so a small clip does not read as 0. */
private fun percent(fraction: Float): String =
    "${(fraction * 1000f).roundToInt() / 10f}%"
