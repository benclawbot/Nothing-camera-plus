package app.cameraplus.camera

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * Frame buffer, progress model and stitch call for a panorama sweep.
 *
 * The camera is none of this class's business: the UI owns the shutter, the
 * sweep direction and the on-screen guidance, and hands every captured frame
 * here as an ARGB [IntArray]. That keeps panorama mode a pure consumer of
 * CameraX stills — it works the same on a CameraX JPEG, a MediaStore read or
 * a synthetic frame from a test.
 *
 * How the UI drives it:
 *
 * ```
 * val capture = PanoramaCapture()
 * // ... on each captured frame, off the main thread:
 * val progress = capture.addFrame(pixels, width, height)
 * progressBar.progress = (progress.progressFraction * 100).toInt()
 * subtitle.text = getString(R.string.pano_frames, progress.acceptedFrames, progress.maxFrames)
 * // ... on shutter release:
 * val result = capture.finish() ?: abandonThisSweep()
 * save(bitmap = capture.resultToBitmap(result))
 * ```
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
 * [maxDimension] allows, not as large as the captures were.
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

    /**
     * What the UI draws while the sweep is running: a frame counter, a
     * progress bar, and the estimated output width so the user can see the
     * panorama growing before it is committed.
     */
    data class StitchProgress(
        /** Frames stored so far, and the number the stitcher will try to use. */
        val acceptedFrames: Int,
        /** Frames dropped so far: full sweep, unusable size, or no movement. */
        val rejectedFrames: Int,
        /** Running estimate of the finished canvas width, in working pixels. */
        val estimatedWidth: Int,
        val estimatedHeight: Int,
        val maxFrames: Int,
        /** True once [maxFrames] is reached; further frames are rejected. */
        val isFull: Boolean,
    ) {
        /** Frame-count progress in `0f..1f`, for a progress bar. */
        val progressFraction: Float
            get() = if (maxFrames <= 0) 0f else (acceptedFrames.toFloat() / maxFrames).coerceIn(0f, 1f)
    }

    private val frames = ArrayList<IntArray>(maxFrames)
    private var workingWidth = 0
    private var workingHeight = 0
    private var estimatedWidth = 0
    private var rejectedFrames = 0

    /** Frames currently held, i.e. what [finish] will try to stitch. */
    val frameCount: Int get() = frames.size

    /** Width of the stored frames, 0 before the first one arrives. */
    val workingResolutionWidth: Int get() = workingWidth

    /** Height of the stored frames, 0 before the first one arrives. */
    val workingResolutionHeight: Int get() = workingHeight

    /**
     * Stores one captured frame and returns the updated progress.
     *
     * Frames are downscaled to [maxDimension] before being kept, and the
     * shift against the previous frame is measured here so [StitchProgress]
     * can report a canvas width that grows as the user pans.
     *
     * A frame is rejected (counted, sweep continues) when it does not hold the
     * image it claims, when it is a different capture size from the frames
     * already stored, or when the sweep is already at [maxFrames]. This
     * never throws: it is fed straight from a decode callback.
     */
    fun addFrame(pixels: IntArray, width: Int, height: Int): StitchProgress {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return reject()
        if (frames.size >= maxFrames) return reject()

        val (scaled, scaledWidth, scaledHeight) = downscale(pixels, width, height, maxDimension)
        if (frames.isEmpty()) {
            workingWidth = scaledWidth
            workingHeight = scaledHeight
        } else if (scaledWidth != workingWidth || scaledHeight != workingHeight) {
            // Same camera, same aspect: rounding can differ by a pixel, which
            // is snapped. Anything more is a different capture size and has no
            // business being spliced into this sweep.
            if (abs(scaledWidth - workingWidth) > 1 || abs(scaledHeight - workingHeight) > 1) {
                return reject()
            }
            val snapped = resize(scaled, scaledWidth, scaledHeight, workingWidth, workingHeight)
            frames += snapped
            return accept()
        }
        frames += scaled
        return accept()
    }

    /**
     * Convenience for the common UI path: reads the bitmap and adds it.
     *
     * A frame far larger than [maxDimension] is scaled *in the bitmap domain*
     * first, so a 50 MP capture never becomes a 200 MB pixel array. Returns
     * null if the bitmap cannot be read (recycled, or the heap gave out) — in
     * which case the sweep is unchanged and the UI should just not draw it.
     */
    fun addBitmap(bitmap: Bitmap): StitchProgress? {
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
            addFrame(pixels, scaled.width, scaled.height)
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
    fun progress(): StitchProgress = currentProgress()

    /**
     * Stitches everything collected so far and clears the sweep.
     *
     * Returns null when there is nothing to stitch — fewer than two frames, or
     * frames the matcher refuses to align — and the state is cleared either
     * way, so the next sweep starts clean.
     */
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
    fun reset() = clear()

    /** Wraps a stitched result in a bitmap for saving or preview. */
    fun resultToBitmap(result: PanoramaStitcher.StitchResult): Bitmap =
        Bitmap.createBitmap(result.pixels, result.width, result.height, Bitmap.Config.ARGB_8888)

    private fun accept(): StitchProgress {
        if (frames.size == 1) {
            estimatedWidth = workingWidth
        } else {
            // Measure against the frame just stored, so the estimate tracks the
            // user's actual cadence rather than the planned one.
            val previous = frames[frames.size - 2]
            val last = frames[frames.size - 1]
            val shift = PanoramaStitcher.estimateShiftX(
                previous,
                last,
                workingWidth,
                workingHeight,
                maxShift(),
            )
            if (shift != 0) estimatedWidth += abs(shift)
        }
        return currentProgress()
    }

    private fun reject(): StitchProgress {
        rejectedFrames++
        return currentProgress()
    }

    private fun currentProgress(): StitchProgress = StitchProgress(
        acceptedFrames = frames.size,
        rejectedFrames = rejectedFrames,
        estimatedWidth = estimatedWidth,
        estimatedHeight = workingHeight,
        maxFrames = maxFrames,
        isFull = frames.size >= maxFrames,
    )

    private fun maxShift(): Int = maxShiftOverride ?: workingWidth / 3

    private fun clear() {
        frames.clear()
        workingWidth = 0
        workingHeight = 0
        estimatedWidth = 0
        rejectedFrames = 0
    }

    companion object {
        /**
         * Working resolution for a sweep. Chosen for memory, not for pixels:
         * a 12-frame sweep at this size needs a canvas of roughly
         * 6400 x 480, i.e. ~60 MB for the canvas and its weight buffer.
         */
        const val DEFAULT_MAX_DIMENSION = 640
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