package app.cameraplus.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ImageAnalysis analyzer for viewfinder subject tracking.
 *
 * Every frame it extracts a small upright luma image (rotated by the
 * camera's reported rotation, mirrored for the front camera so it matches
 * the mirrored preview), then either locks the [SubjectTracker] onto a
 * pending selection or updates the current subject.
 *
 * Coordinates handed to the callbacks are normalized over the upright luma
 * frame (0..1, origin top-left); the session converts them to preview-view
 * pixels with [TrackingGeometry].
 */
class SubjectTrackingAnalyzer : ImageAnalysis.Analyzer {

    private val tracker = SubjectTracker()
    private var frame: GrayFrame? = null
    private var counter = 0

    @Volatile
    var enabled: Boolean = false

    /** Front cameras are analyzed mirrored, matching PreviewView's output. */
    @Volatile
    var mirrorHorizontally: Boolean = false

    /** Pending selection as [nx, ny, halfWidthFraction]; set from the UI thread. */
    @Volatile
    var pendingSelection: FloatArray? = null

    @Volatile
    var frameWidth: Int = 0
        private set

    @Volatile
    var frameHeight: Int = 0
        private set

    /** Invoked on the analysis thread when a selection locked successfully. */
    var onSelected: ((Float, Float, Float) -> Unit)? = null

    /** Invoked on the analysis thread for each tracked position. */
    var onTracked: ((Float, Float, Float) -> Unit)? = null

    /** Invoked on the analysis thread when the selected area has no texture. */
    var onSelectFailed: (() -> Unit)? = null

    fun setTrackingEnabled(value: Boolean) {
        enabled = value
        if (!value) cancelSelection()
    }

    fun cancelSelection() {
        pendingSelection = null
        tracker.reset()
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled) return
            counter++
            val pending = pendingSelection
            // Keep full rate while a selection is waiting, otherwise ~15 Hz
            // is plenty for a preview-space tracking box.
            if (pending == null && counter % FRAME_SKIP != 0) return
            val gray = LumaExtractor.extract(
                image = image,
                targetWidth = TARGET_WIDTH,
                mirror = mirrorHorizontally,
                reuse = frame,
            ) ?: return
            if (frameWidth != gray.width && Log.isLoggable("SubjectTracking", Log.DEBUG)) {
                Log.d("SubjectTracking", "frames flowing: ${gray.width}x${gray.height} rotation=${image.imageInfo.rotationDegrees} mirror=$mirrorHorizontally")
            }
            frame = gray
            frameWidth = gray.width
            frameHeight = gray.height

            if (pending != null) {
                pendingSelection = null
                val halfPx = (pending[2] * gray.width).roundToInt()
                    .coerceIn(MIN_TEMPLATE_HALF, MAX_TEMPLATE_HALF)
                if (tracker.select(gray, pending[0] * gray.width, pending[1] * gray.height, halfPx)) {
                    val center = TrackPoint(
                        pending[0].coerceIn(0f, 1f),
                        pending[1].coerceIn(0f, 1f),
                    )
                    onSelected?.invoke(center.x, center.y, 1f)
                } else {
                    onSelectFailed?.invoke()
                }
            } else if (tracker.isTracking) {
                tracker.update(gray)?.let { result ->
                    onTracked?.invoke(
                        result.x / gray.width,
                        result.y / gray.height,
                        result.score,
                    )
                }
            }
        } catch (_: Throwable) {
            // A dropped analysis frame must never take the camera down;
            // the next frame simply tries again.
        } finally {
            image.close()
        }
    }

    private companion object {
        const val TARGET_WIDTH = 192
        const val FRAME_SKIP = 2
        const val MIN_TEMPLATE_HALF = 6
        const val MAX_TEMPLATE_HALF = 32
    }
}

/**
 * Extracts an upright, optionally mirrored, downsampled luma image from a
 * YUV_420_888 [ImageProxy] in a single pass (rotation and scaling combined,
 * nearest-neighbour sampling). Reuses the output buffer across frames.
 */
internal object LumaExtractor {
    fun extract(
        image: ImageProxy,
        targetWidth: Int,
        mirror: Boolean,
        reuse: GrayFrame?,
    ): GrayFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) return null
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val quarterTurn = rotation == 90 || rotation == 270
        val uprightWidth = if (quarterTurn) crop.height() else crop.width()
        val uprightHeight = if (quarterTurn) crop.width() else crop.height()
        val outWidth = min(targetWidth, uprightWidth).coerceAtLeast(1)
        val outHeight = (uprightHeight.toFloat() * outWidth / uprightWidth)
            .roundToInt().coerceAtLeast(1)
        val data = if (
            reuse != null && reuse.width == outWidth && reuse.height == outHeight &&
            reuse.data.size == outWidth * outHeight
        ) {
            reuse.data
        } else {
            ByteArray(outWidth * outHeight)
        }

        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val cropWidth = crop.width().toFloat()
        val cropHeight = crop.height().toFloat()

        var oy = 0
        while (oy < outHeight) {
            val v = (oy + 0.5f) / outHeight
            var ox = 0
            while (ox < outWidth) {
                var u = (ox + 0.5f) / outWidth
                if (mirror) u = 1f - u
                // Invert the clockwise rotation that would make the buffer
                // upright, in normalized coordinates.
                val sx: Float
                val sy: Float
                when (rotation) {
                    90 -> { sx = v; sy = 1f - u }
                    180 -> { sx = 1f - u; sy = 1f - v }
                    270 -> { sx = 1f - v; sy = u }
                    else -> { sx = u; sy = v }
                }
                val px = (crop.left + sx * cropWidth).toInt()
                    .coerceIn(crop.left, crop.right - 1)
                val py = (crop.top + sy * cropHeight).toInt()
                    .coerceIn(crop.top, crop.bottom - 1)
                val index = py * rowStride + px * pixelStride
                data[oy * outWidth + ox] =
                    if (index in 0 until buffer.limit()) buffer.get(index) else 0
                ox++
            }
            oy++
        }
        return GrayFrame(outWidth, outHeight, data)
    }
}
