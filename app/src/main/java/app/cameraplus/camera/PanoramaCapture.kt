package app.cameraplus.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frame buffer, progress model, live preview and stitch call for a panorama
 * sweep.
 *
 * The camera is none of this class's business: the UI owns the sweep
 * lifecycle — the shutter starts the frame stream, guidance runs off the
 * sensors — and hands every frame here as an ARGB [IntArray]. That keeps
 * panorama mode a pure consumer of frames, whether they come from the live
 * YUV stream ([YuvFrameConverter]), a decoded JPEG or a synthetic frame from
 * a test.
 *
 * How the UI drives it:
 *
 * ```
 * val capture = PanoramaCapture()
 * // ... on each streamed frame, off the main thread:
 * val progress = capture.addFrame(pixels, width, height, expectedDirection)
 * progressBar.progress = (progress.progressFraction * 100).toInt()
 * strip.setImageBitmap(capture.previewBitmap(STRIP_PIXELS))
 * // ... when the user stops:
 * val result = capture.finish() ?: abandonThisSweep()
 * save(bitmap = capture.resultToBitmap(result))
 * ```
 *
 * Frames are filtered **as they arrive**, not just at the end: one the
 * matcher cannot align to its predecessor (flat subject, pause, motion blur)
 * or that moves against the sweep direction is counted and dropped, so the
 * live preview never jumps and [StitchProgress] is honest while the user is
 * still panning. The full stitch in [finish] re-measures everything anyway,
 * so a bad frame that got through is still caught.
 *
 * Every frame is downscaled to [maxDimension] on the way in, because the
 * stitched canvas is roughly `frames * cadence` pixels wide and a 50 MP frame
 * would put a 200 MB pixel array (and a gigabyte-sized canvas) on the heap.
 * All stored frames therefore share one working size: the first frame decides
 * it, and a later frame whose working size differs by more than a pixel is
 * rejected as belonging to a different capture configuration.
 *
 * **Honest limitation.** Alignment here is translation-only: there is no
 * rotation or rolling-shutter correction, no parallax or lens-distortion
 * model, and exposure differences are absorbed only by the seam feather. So
 * the user has to pan smoothly and at a steady rate, and a sloppy sweep will
 * show a stepped horizon or softened detail where the overlaps are. This will
 * not match the OEM ArcSoft/morpho panorama engine the stock app bundles,
 * which registers with rotation and rolling-shutter models; it is a
 * clean-room substitute that gets a steady handheld sweep right and nothing
 * more. It is stitched at working resolution, so the output is as large as
 * [maxDimension] allows, not as large as the stream frame had.
 */
class PanoramaCapture(
    /** Frames in one sweep; the UI should stop asking for more at this point. */
    private val maxFrames: Int = PanoramaPlan.MAX_FRAMES,
    /** Longest edge, in pixels, of the frames actually stored and stitched. */
    private val maxDimension: Int = DEFAULT_MAX_DIMENSION,
    /** Matcher search radius per frame; null means a third of the frame width. */
    private val maxShiftOverride: Int? = null,
) {

    init {
        require(maxFrames >= 2) { "a panorama needs at least two frames" }
        require(maxDimension > 0) { "maxDimension must be positive, was $maxDimension" }
        require(maxShiftOverride == null || maxShiftOverride >= 0) { "maxShift must not be negative" }
    }

    /** Why the most recent frame was dropped. */
    enum class Rejection {
        /** The last frame was stored. */
        NONE,

        /** The sweep already holds [maxFrames] frames. */
        FULL,

        /** Wrong buffer size, or a different capture geometry than the sweep. */
        SIZE,

        /** No measurable movement: the user paused, or the subject is flat. */
        NO_MOVEMENT,

        /** Movement against the direction this sweep has committed to. */
        WRONG_DIRECTION,
    }

    /**
     * What the UI draws while the sweep is running: a frame counter, a
     * progress bar, the measured sweep so far, and why the last frame that
     * was not stored got dropped.
     */
    data class StitchProgress(
        /** Frames stored so far, and the number the stitcher will try to use. */
        val acceptedFrames: Int,
        /** Frames dropped so far: full sweep, unusable frame, no movement, or wrong way. */
        val rejectedFrames: Int,
        /** Running estimate of the finished canvas size, in working pixels. */
        val estimatedWidth: Int,
        val estimatedHeight: Int,
        val maxFrames: Int,
        /** True once [maxFrames] is reached; further frames are rejected. */
        val isFull: Boolean,
        /** Shift to the previous stored frame, in working pixels; null before the second one. */
        val shiftPixels: Int?,
        /** The direction the measured shifts have committed to, once there is one. */
        val direction: Direction?,
        /** Why the most recent frame was not stored. */
        val lastRejection: Rejection,
    ) {
        /** Frame-count progress in `0f..1f`, for a progress bar. */
        val progressFraction: Float
            get() = if (maxFrames <= 0) 0f else (acceptedFrames.toFloat() / maxFrames).coerceIn(0f, 1f)
    }

    private val frames = ArrayList<IntArray>(maxFrames)
    private val offsets = ArrayList<Int>(maxFrames)
    private var workingWidth = 0
    private var workingHeight = 0
    private var rejectedFrames = 0
    private var committedDirection: Direction? = null
    private var lastShift: Int? = null
    private var lastRejection = Rejection.NONE

    /** Frames currently held, i.e. what [finish] will try to stitch. */
    val frameCount: Int get() = frames.size

    /** Width of the stored frames, 0 before the first one arrives. */
    val workingResolutionWidth: Int get() = workingWidth

    /** Height of the stored frames, 0 before the first one arrives. */
    val workingResolutionHeight: Int get() = workingHeight

    /**
     * Stores one captured frame and returns the updated progress.
     *
     * Frames are downscaled to [maxDimension] before being kept; the shift
     * against the previous frame is measured here, both so the sweep can
     * reject a frame that does not advance it and so [StitchProgress] can
     * report a canvas width that grows as the user pans.
     *
     * @param expectedDirection the direction the sweep is committed to, in
     *   canvas-shift sign (`Direction.canvasShiftSign`), or 0 to accept
     *   whichever way the frames actually move.
     */
    @Synchronized
    fun addFrame(pixels: IntArray, width: Int, height: Int, expectedDirection: Int = 0): StitchProgress {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return reject(Rejection.SIZE)
        if (frames.size >= maxFrames) return reject(Rejection.FULL)

        val (scaled, scaledWidth, scaledHeight) = downscale(pixels, width, height, maxDimension)
        var candidate = scaled
        if (frames.isEmpty()) {
            workingWidth = scaledWidth
            workingHeight = scaledHeight
        } else if (scaledWidth != workingWidth || scaledHeight != workingHeight) {
            // Same camera, same aspect: rounding can differ by a pixel, which
            // is snapped. Anything more is a different capture size and has no
            // business being spliced into this sweep.
            if (abs(scaledWidth - workingWidth) > 1 || abs(scaledHeight - workingHeight) > 1) {
                return reject(Rejection.SIZE)
            }
            candidate = resize(scaled, scaledWidth, scaledHeight, workingWidth, workingHeight)
        }

        if (frames.isEmpty()) {
            frames += candidate
            offsets += 0
            lastShift = null
            lastRejection = Rejection.NONE
            return currentProgress()
        }

        val shift = PanoramaStitcher.estimateShiftX(
            frames[frames.size - 1],
            candidate,
            workingWidth,
            workingHeight,
            maxShift(),
        )
        if (abs(shift) < minShiftPixels()) return reject(Rejection.NO_MOVEMENT)
        val enforced = if (expectedDirection != 0) expectedDirection else committedDirection?.canvasShiftSign ?: 0
        val measuredDirection = when {
            shift > 0 -> 1
            shift < 0 -> -1
            else -> 0
        }
        if (enforced != 0 && measuredDirection != enforced) return reject(Rejection.WRONG_DIRECTION)
        if (committedDirection == null) committedDirection = Direction.fromShift(shift)

        frames += candidate
        offsets += offsets[offsets.size - 1] + shift
        lastShift = shift
        lastRejection = Rejection.NONE
        return currentProgress()
    }

    /**
     * Convenience for callers holding a bitmap: reads the pixels and adds
     * them.
     *
     * A frame far larger than [maxDimension] is scaled *in the bitmap domain*
     * first, so a 50 MP capture never becomes a 200 MB pixel array. Returns
     * null if the bitmap cannot be read (recycled, or the heap gave out) — in
     * which case the sweep is unchanged and the UI should just not draw it.
     */
    fun addBitmap(bitmap: Bitmap, expectedDirection: Int = 0): StitchProgress? {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val longest = max(sourceWidth, sourceHeight)
        val scaled = if (longest > maxDimension) {
            val scale = maxDimension.toDouble() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (sourceWidth * scale).toInt()),
                max(1, (sourceHeight * scale).toInt()),
                true,
            )
        } else {
            bitmap
        }
        return try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            addFrame(pixels, scaled.width, scaled.height, expectedDirection)
        } catch (t: Throwable) {
            // OutOfMemoryError included: a missing frame is better than a lost sweep.
            null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * Progress without adding anything, for a UI that redraws on a timer.
     */
    @Synchronized
    fun progress(): StitchProgress = currentProgress()

    /**
     * A small composite of everything stored so far, for the live "the photo
     * is appearing" strip: the frames at the offsets the matcher measured,
     * seam-feathered the same way [PanoramaStitcher.stitch] will do it.
     *
     * Built fresh per call from the stored frames, at whatever scale fits
     * [maxPreviewWidth]. Returns null before the first frame; the pixel array,
     * width and height otherwise.
     */
    @Synchronized
    fun previewPixels(maxPreviewWidth: Int): Triple<IntArray, Int, Int>? {
        require(maxPreviewWidth > 0) { "maxPreviewWidth must be positive" }
        if (frames.isEmpty() || workingWidth <= 0) return null

        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        for (offset in offsets) {
            if (offset < left) left = offset
            if (offset > right) right = offset
        }
        val canvasWidth = right - left + workingWidth
        val scale = min(1f, maxPreviewWidth.toFloat() / canvasWidth)
        val previewWidth = max(1, (canvasWidth * scale).roundToInt())
        val previewHeight = max(1, (workingHeight * scale).roundToInt())
        val frameWidth = max(1, (workingWidth * scale).roundToInt())
        val frameHeight = max(1, (workingHeight * scale).roundToInt())

        val out = IntArray(previewWidth * previewHeight)
        for (i in frames.indices) {
            val frame = frames[i]
            val x0 = ((offsets[i] - left) * scale).roundToInt()
            val seam = if (i == 0) {
                0
            } else {
                (offsets[i - 1] + workingWidth - offsets[i]).coerceIn(0, workingWidth)
            }
            val seamScaled = if (seam <= 0) 0 else max(1, (seam * scale).roundToInt())
            for (py in 0 until frameHeight) {
                val sourceY = ((py + 0.5f) / frameHeight * workingHeight).toInt().coerceIn(0, workingHeight - 1)
                val sourceRow = sourceY * workingWidth
                val targetRow = py * previewWidth
                for (px in 0 until frameWidth) {
                    val targetX = x0 + px
                    if (targetX < 0 || targetX >= previewWidth) continue
                    val sourceX = ((px + 0.5f) / frameWidth * workingWidth).toInt().coerceIn(0, workingWidth - 1)
                    val pixel = frame[sourceRow + sourceX]
                    val target = targetRow + targetX
                    if (seamScaled <= 0 || px >= seamScaled) {
                        out[target] = pixel
                        continue
                    }
                    // Same linear ramp as the stitcher's composite: this
                    // frame fades in across the shared band.
                    val fade = ((px + 1) * 256) / (seamScaled + 1)
                    val existing = out[target]
                    val r = ((((pixel ushr 16) and 0xFF) * fade + ((existing ushr 16) and 0xFF) * (256 - fade)) shr 8)
                        .coerceIn(0, 255)
                    val g = ((((pixel ushr 8) and 0xFF) * fade + ((existing ushr 8) and 0xFF) * (256 - fade)) shr 8)
                        .coerceIn(0, 255)
                    val b = (((pixel and 0xFF) * fade + (existing and 0xFF) * (256 - fade)) shr 8)
                        .coerceIn(0, 255)
                    out[target] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Triple(out, previewWidth, previewHeight)
    }

    /** [previewPixels] wrapped in a bitmap, ready for an `Image` composable. */
    fun previewBitmap(maxPreviewWidth: Int): Bitmap? =
        previewPixels(maxPreviewWidth)?.let { (pixels, width, height) ->
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

    /**
     * Stitches everything collected so far and clears the sweep.
     *
     * Returns null when there is nothing to stitch — fewer than two frames, or
     * frames the matcher refuses to align — and the state is cleared either
     * way, so the next sweep starts clean.
     */
    @Synchronized
    fun finish(): PanoramaStitcher.StitchResult? {
        if (workingWidth <= 0 || workingHeight <= 0) {
            clear()
            return null
        }
        return try {
            PanoramaStitcher.stitch(frames.toList(), workingWidth, workingHeight, maxShift())
        } finally {
            clear()
        }
    }

    /** Drops the sweep without stitching, e.g. the user cancelled. */
    @Synchronized
    fun reset() = clear()

    /** Wraps a stitched result in a bitmap for saving or preview. */
    fun resultToBitmap(result: PanoramaStitcher.StitchResult): Bitmap =
        Bitmap.createBitmap(result.pixels, result.width, result.height, Bitmap.Config.ARGB_8888)

    private fun reject(reason: Rejection): StitchProgress {
        rejectedFrames++
        lastRejection = reason
        return currentProgress()
    }

    private fun currentProgress(): StitchProgress {
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        for (offset in offsets) {
            if (offset < left) left = offset
            if (offset > right) right = offset
        }
        return StitchProgress(
            acceptedFrames = frames.size,
            rejectedFrames = rejectedFrames,
            estimatedWidth = if (frames.isEmpty()) 0 else right - left + workingWidth,
            estimatedHeight = workingHeight,
            maxFrames = maxFrames,
            isFull = frames.size >= maxFrames,
            shiftPixels = lastShift,
            direction = committedDirection,
            lastRejection = lastRejection,
        )
    }

    private fun maxShift(): Int = maxShiftOverride ?: workingWidth / 3

    /** Movement below this is a pause (or a flat subject), not a sweep step. */
    private fun minShiftPixels(): Int = max(2, workingWidth / 64)

    private fun clear() {
        frames.clear()
        offsets.clear()
        workingWidth = 0
        workingHeight = 0
        rejectedFrames = 0
        committedDirection = null
        lastShift = null
        lastRejection = Rejection.NONE
    }

    companion object {
        /**
         * Working resolution for a sweep. Chosen for memory, not for pixels:
         * a 12-frame sweep at this size needs a canvas of roughly
         * roughly 3800 x 1120 on a portrait-held sweep: around 34 MB for the
         * canvas and its weight buffer at stitch time, while the frames cost
         * around 45 MB. The hard frame cap keeps this bounded.
         */
        const val DEFAULT_MAX_DIMENSION = 1120

        /** Longest edge of the live preview strip, in pixels. */
        const val PREVIEW_MAX_WIDTH = 1024
    }
}

/**
 * Box-averaged downscale of an ARGB buffer, longest edge capped at
 * [maxDimension].
 *
 * Plain average per source box (integer-rounded box bounds, so the boxes
 * partition the image and nothing is dropped or counted twice), alpha forced
 * opaque. When the image is already small enough the input array is returned
 * untouched rather than copied.
 *
 * @throws IllegalArgumentException if the dimensions are empty or the buffer
 *   is smaller than the image it claims to hold.
 */
fun downscale(pixels: IntArray, width: Int, height: Int, maxDimension: Int): Triple<IntArray, Int, Int> {
    require(width > 0 && height > 0) { "image must not be empty" }
    require(pixels.size >= width * height) { "pixel buffer too small for ${width}x$height" }

    val longest = max(width, height)
    if (maxDimension <= 0 || longest <= maxDimension) return Triple(pixels, width, height)

    val targetWidth = max(1, ((width.toLong() * maxDimension) / longest).toInt())
    val targetHeight = max(1, ((height.toLong() * maxDimension) / longest).toInt())
    return Triple(resize(pixels, width, height, targetWidth, targetHeight), targetWidth, targetHeight)
}

/**
 * Box-averaged resize to exact dimensions, preserving the aspect ratio only
 * as far as the caller asked for. See [downscale].
 */
fun resize(pixels: IntArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): IntArray {
    require(width > 0 && height > 0) { "image must not be empty" }
    require(targetWidth > 0 && targetHeight > 0) { "target must not be empty" }
    require(pixels.size >= width * height) { "pixel buffer too small for ${width}x$height" }
    if (targetWidth == width && targetHeight == height) return pixels

    val out = IntArray(targetWidth * targetHeight)
    for (y in 0 until targetHeight) {
        val y0 = (y.toLong() * height / targetHeight).toInt()
        val y1 = (((y + 1).toLong() * height + targetHeight - 1) / targetHeight).toInt()
        for (x in 0 until targetWidth) {
            val x0 = (x.toLong() * width / targetWidth).toInt()
            val x1 = (((x + 1).toLong() * width + targetWidth - 1) / targetWidth).toInt()
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (sourceY in y0 until y1) {
                var index = sourceY * width + x0
                for (sourceX in x0 until x1) {
                    val pixel = pixels[index++]
                    r += (pixel ushr 16) and 0xFF
                    g += (pixel ushr 8) and 0xFF
                    b += pixel and 0xFF
                    count++
                }
            }
            out[y * targetWidth + x] =
                (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
        }
    }
    return out
}
