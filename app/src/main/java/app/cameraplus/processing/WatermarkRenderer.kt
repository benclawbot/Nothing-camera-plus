package app.cameraplus.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import app.cameraplus.settings.WatermarkOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Draws the user's optional Camera+ text watermark on a decoded still.
 * It is deliberately generated from text (no manufacturer logo/artwork), and
 * only runs when a watermark is opted into. If optional processing fails, the
 * capture path saves the original JPEG rather than losing the photo.
 */
object WatermarkRenderer {

    fun draw(
        bitmap: Bitmap,
        options: WatermarkOptions,
        location: Location? = null,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        if (!options.enabled) return
        val lines = buildList {
            add("CAMERA+")
            if (options.includeDate) add(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMillis)))
            if (options.includeTime) add(SimpleDateFormat("HH:mm", Locale.US).format(Date(timestampMillis)))
            if (options.includeLocation && location != null) {
                add(String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude))
            }
            options.authorName.trim().takeIf { it.isNotEmpty() }?.let { add(it.take(MAX_AUTHOR_LENGTH)) }
        }
        if (lines.isEmpty()) return

        val canvas = Canvas(bitmap)
        val textSize = (bitmap.width * TEXT_SIZE_FRACTION).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        val margin = max(12f, bitmap.width * MARGIN_FRACTION)
        val lineHeight = textSize * LINE_HEIGHT_MULTIPLIER
        val padding = textSize * PADDING_MULTIPLIER
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setShadowLayer(textSize * 0.12f, 0f, textSize * 0.04f, Color.BLACK)
        }
        val widest = lines.maxOf { textPaint.measureText(it) }
        val blockWidth = widest + 2f * padding
        val blockHeight = lines.size * lineHeight + 2f * padding
        val left = margin
        val top = bitmap.height - margin - blockHeight
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        canvas.drawRoundRect(
            RectF(left, top, left + blockWidth, top + blockHeight),
            padding * 0.35f,
            padding * 0.35f,
            background,
        )
        var baseline = top + padding + textSize
        lines.forEach { line ->
            canvas.drawText(line, left + padding, baseline, textPaint)
            baseline += lineHeight
        }
    }

    private const val TEXT_SIZE_FRACTION = 0.024f
    private const val LINE_HEIGHT_MULTIPLIER = 1.25f
    private const val PADDING_MULTIPLIER = 0.55f
    private const val MARGIN_FRACTION = 0.035f
    private const val MIN_TEXT_SIZE = 14f
    private const val MAX_TEXT_SIZE = 72f
    private const val MAX_AUTHOR_LENGTH = 32
}
