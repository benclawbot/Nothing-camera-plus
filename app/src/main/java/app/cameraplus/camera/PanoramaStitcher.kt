package app.cameraplus.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stitches a horizontal sweep of overlapping frames into one canvas.
 *
 * Everything works on plain ARGB [IntArray]s (as
 * `Bitmap.getPixels`/`setPixels` hand out) with no `android.graphics` import at
 * all, so the whole matcher is exercisable from plain JVM tests.
 *
 * How alignment works:
 *
 *  1. Each frame is collapsed to a **column luminance projection** — one mean
 *     value per column, not per pixel. Building that projection once per frame
 *     and reusing it is what keeps the matcher affordable: the search is
 *     `2 * maxShift + 1` passes over `width` floats, never over
 *     `width * height` pixels.
 *  2. For every candidate shift `s` in `[-maxShift, maxShift]` the two
 *     projections are correlated over the columns they share (normalized
 *     cross-correlation, so brightness and contrast changes between frames do
 *     not move the peak). The shift with the highest correlation wins.
 *  3. The winner is only accepted when it is *convincing*: a correlation above
 *     [MIN_CORRELATION] and clearly better than the best shift at least
 *     [NEIGHBOUR_HOOD] away from it. A flat frame (all sky, all black) or a
 *     periodic one (a picket fence, a striped wall) has no single best shift,
 *     and both return 0 instead of a confident wrong answer.
 *
 * Honest limits, because they are the difference between this and the OEM
 * ArcSoft/morpho panorama engine the stock app ships:
 *
 *  - **Translation only.** No rotation, no rolling-shutter rectification, no
 *    parallax, no lens-distortion correction. Hand-held, the user's pan is
 *    never perfectly straight, so a real sweep can come out with a stepped or
 *    slightly wavy horizon and doubled detail in the overlaps.
 *  - Integer shifts only, so sub-pixel jitter ends up in the seam ramp.
 *  - Exposure differences are absorbed by the seam feather only.
 *  - Frames are kept at whatever resolution the caller passes, and the canvas
 *    costs roughly `frames * cadence` pixels wide, so callers hand in
 *    downscaled frames (see `PanoramaCapture`).
 *
 * A clean-room translation-only stitcher, not a bundle-adjustment engine: a
 * tripod-or-steady-hand sweep looks good, a sloppy one shows its seams.
 */
object PanoramaStitcher {

    /**
     * Minimum overlap, as a fraction of the frame width, that two consecutive
     * frames must still share. A shift that leaves less than this behind means
     * the matcher locked onto the wrong thing, and the resulting canvas would
     * be torn rather than merely soft.
     */
    const val MIN_OVERLAP_FRACTION = 0.10f

    /** Correlation a candidate shift has to reach before it is believed. */
    const val MIN_CORRELATION = 0.30f

    /**
     * How far the best shift has to beat the best *other* shift (one at least
     * two steps away, so neighbouring samples of the same peak do not count).
     *
     * Small on purpose. A broad peak is normal — real scenes are bright and
     * smooth — so a large margin would throw away perfectly good alignments,
     * while only genuine ties (a picket fence, a striped wall, a repeating
     * facade, all of which correlate just as well a period away) need to be
     * rejected.
     */
    const val AMBIGUITY_MARGIN = 0.02f

    /**
     * Standard deviation, in luminance levels, a column projection needs
     * before it counts as having structure. A perfectly flat projection (all
     * black, a blank wall, a clear sky) can be matched to anything.
     */
    const val MIN_COLUMN_CONTRAST = 1.5f

    /** Shifts within this many steps of the winner are treated as the same peak. */
    private const val NEIGHBOUR_HOOD = 2

    /** Columns that must overlap before a correlation is trusted at all. */
    private const val MIN_SAMPLES = 8

    /**
     * One stitched panorama.
     *
     * [pixels] is ARGB_8888 packed, row-major, `width * height` long.
     * [rejectedFrames] counts inputs that were dropped: too little overlap to
     * trust, no measurable movement, or a move that contradicted the sweep so
     * far.
     */
    data class StitchResult(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val usedFrames: Int,
        val rejectedFrames: Int,
    )

    /**
     * Estimates how far [current] is shifted horizontally relative to
     * [previous], in pixels.
     *
     * A positive result means the frame belongs to the *right* of the previous
     * one on the canvas — the camera turned right. A negative result means it
     * belongs to its left.
     *
     * Returns 0 when the answer would be a guess: a flat frame, an ambiguous
     * (periodic) match, no shift requested, or a correlation that is simply too
     * weak. The search is clamped to `[-(width - 1), width - 1]`, so a shift
     * beyond [maxShift] comes back as [maxShift], never as something bigger.
     *
     * @throws IllegalArgumentException if the dimensions are empty or a buffer
     *   is smaller than the image it claims to hold — those are caller bugs,
     *   not scene content.
     */
    fun estimateShiftX(
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
        maxShift: Int,
    ): Int {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(previous.size >= width * height && current.size >= width * height) {
            "pixel buffer too small for ${width}x$height"
        }
        val limit = min(maxShift, width - 1)
        if (limit <= 0) return 0
        return bestShift(
            columnLuminance(previous, width, height),
            columnLuminance(current, width, height),
            limit,
        )
    }

    /**
     * Stitches [frames] left to right in sweep order.
     *
     * Consecutive shifts are estimated with [estimateShiftX] and accumulated
     * into a canvas offset per frame. Overlaps are feather-blended with a
     * linear alpha ramp across the seam band, which spreads the exposure and
     * registration error of the two frames instead of cutting to it.
     *
     * A frame is **rejected** (counted in [StitchResult.rejectedFrames], and
     * the next frame is matched against the last *accepted* one) when it shows
     * no measurable movement — the user paused — or when it moves against the
     * direction the sweep has already committed to.
     *
     * The whole stitch returns **null** when it cannot produce an honest
     * panorama: fewer than two usable frames, a first frame with no structure
     * to align against, or a shift that leaves less than
     * [MIN_OVERLAP_FRACTION] of the frame overlapping. The last case is fatal
     * on purpose — a torn canvas is worse than no panorama, and the caller can
     * tell the user to slow down and try again.
     *
     * @param maxShift search radius per frame; defaults to a third of the
     *   frame width, which leaves at least two thirds of overlap at the limit.
     */
    fun stitch(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        maxShift: Int = width / 3,
    ): StitchResult? {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(maxShift >= 0) { "maxShift must not be negative, was $maxShift" }
        if (frames.size < 2) return null

        // A frame that does not even hold the claimed image is dropped, not
        // fatal: one truncated callback should not throw away the sweep.
        val usable = frames.filter { it.size >= width * height }
        if (usable.size < 2) return null

        val projections = usable.map { columnLuminance(it, width, height) }
        if (stdDev(projections[0]) < MIN_COLUMN_CONTRAST) return null

        val limit = min(maxShift, width - 1)
        val maxOffset = (width * (1f - MIN_OVERLAP_FRACTION)).toInt()

        val offsets = IntArray(usable.size)
        val placed = ArrayList<Int>(usable.size)
        var reference = 0
        var direction = 0
        var rejected = frames.size - usable.size
        placed += reference

        for (index in 1 until usable.size) {
            val shift = bestShift(projections[reference], projections[index], limit)
            val plausible = shift != 0 &&
                abs(shift) <= maxOffset &&
                (direction == 0 || sign(shift) == direction)
            if (plausible) {
                offsets[index] = offsets[reference] + shift
                placed += index
                reference = index
                if (direction == 0) direction = sign(shift)
            } else {
                rejected++
            }
        }
        if (placed.size < 2) return null

        val left = placed.minOf { offsets[it] }
        val right = placed.maxOf { offsets[it] }
        val canvasWidth = right + width - left
        if (canvasWidth <= 0) return null

        val canvas = IntArray(canvasWidth * height)
        // Running alpha weight, so overlaps divide out to a true average
        // instead of a "last writer wins" seam.
        val weight = FloatArray(canvasWidth * height)

        for (position in placed.indices) {
            val index = placed[position]
            val x0 = offsets[index] - left
            // Seam band shared with the previous placed frame; 0 for the first.
            val seam = if (position == 0) {
                0
            } else {
                (offsets[placed[position - 1]] + width - offsets[index]).coerceIn(0, width)
            }
            composite(usable[index], width, canvas, canvasWidth, x0, seam, weight, height)
        }
        normalize(canvas, weight)

        return StitchResult(
            pixels = canvas,
            width = canvasWidth,
            height = height,
            usedFrames = placed.size,
            rejectedFrames = rejected,
        )
    }

    /**
     * Mean luminance per column, built once per frame.
     *
     * Rec. 601 weights on the packed channels, summed into a float column and
     * averaged over the rows at the end.
     */
    private fun columnLuminance(pixels: IntArray, width: Int, height: Int): FloatArray {
        val columns = FloatArray(width)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[index++]
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                columns[x] += ((77 * r + 150 * g + 29 * b) ushr 8).toFloat()
            }
        }
        val rows = 1f / height
        for (x in 0 until width) columns[x] *= rows
        return columns
    }

    /**
     * The shift that best aligns [current] against [previous], or 0 when there
     * is no defensible answer.
     */
    private fun bestShift(previous: FloatArray, current: FloatArray, maxShift: Int): Int {
        if (maxShift <= 0) return 0
        // A projection with no contrast matches everything equally well.
        if (stdDev(previous) < MIN_COLUMN_CONTRAST || stdDev(current) < MIN_COLUMN_CONTRAST) return 0

        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (shift in -maxShift..maxShift) {
            val score = correlationAt(previous, current, shift)
            if (score > bestScore) {
                bestScore = score
                best = shift
            }
        }
        if (bestScore < MIN_CORRELATION) return 0

        // Second pass: the strongest candidate that is not part of this peak.
        // A periodic scene scores the same at every multiple of its period, and
        // that tie is exactly when a "confident" shift would be a lie.
        var rival = Float.NEGATIVE_INFINITY
        for (shift in -maxShift..maxShift) {
            if (abs(shift - best) <= NEIGHBOUR_HOOD) continue
            val score = correlationAt(previous, current, shift)
            if (score > rival) rival = score
        }
        if (bestScore - rival < AMBIGUITY_MARGIN) return 0
        return best
    }

    /**
     * Normalized cross-correlation of the two projections at one shift.
     *
     * A positive shift means the current frame sits to the right on the canvas,
     * so previous column `x` lines up with current column `x - shift`.
     */
    private fun correlationAt(previous: FloatArray, current: FloatArray, shift: Int): Float {
        val width = previous.size
        val start = max(0, shift)
        val end = min(width, width + shift)
        val samples = end - start
        if (samples < MIN_SAMPLES) return Float.NEGATIVE_INFINITY

        var sumPrevious = 0f
        var sumCurrent = 0f
        for (x in start until end) {
            sumPrevious += previous[x]
            sumCurrent += current[x - shift]
        }
        val meanPrevious = sumPrevious / samples
        val meanCurrent = sumCurrent / samples

        var cross = 0f
        var energyPrevious = 0f
        var energyCurrent = 0f
        for (x in start until end) {
            val a = previous[x] - meanPrevious
            val b = current[x - shift] - meanCurrent
            cross += a * b
            energyPrevious += a * a
            energyCurrent += b * b
        }
        val denominator = sqrt(energyPrevious * energyCurrent)
        if (denominator <= 0f) return Float.NEGATIVE_INFINITY
        return cross / denominator
    }

    private fun stdDev(values: FloatArray): Float {
        var sum = 0f
        for (value in values) sum += value
        val mean = sum / values.size
        var variance = 0f
        for (value in values) {
            val delta = value - mean
            variance += delta * delta
        }
        return sqrt(variance / values.size)
    }

    /**
     * Writes one frame into the canvas.
     *
     * The first [seam] columns — the band it shares with the previous frame —
     * crossfade: this frame's contribution ramps 0 -> 1 while what is already
     * there ramps 1 -> 0, so the two meet in the middle of a linear ramp
     * instead of at a cut. Past the band the frame simply replaces what was
     * there, which keeps a column written by three frames from accumulating
     * three exposures.
     */
    private fun composite(
        source: IntArray,
        sourceWidth: Int,
        canvas: IntArray,
        canvasWidth: Int,
        x0: Int,
        seam: Int,
        weight: FloatArray,
        height: Int,
    ) {
        for (y in 0 until height) {
            val canvasRow = y * canvasWidth
            val sourceRow = y * sourceWidth
            for (x in 0 until sourceWidth) {
                val target = canvasRow + x0 + x
                val pixel = source[sourceRow + x]
                if (seam <= 0 || x >= seam) {
                    canvas[target] = pixel
                    weight[target] = 1f
                    continue
                }
                val fade = (x + 1f) / (seam + 1f)
                val fading = weight[target] * (1f - fade)
                val total = fade + fading
                val r = ((pixel ushr 16) and 0xFF) * fade + ((canvas[target] ushr 16) and 0xFF) * fading
                val g = ((pixel ushr 8) and 0xFF) * fade + ((canvas[target] ushr 8) and 0xFF) * fading
                val b = (pixel and 0xFF) * fade + (canvas[target] and 0xFF) * fading
                canvas[target] = pack(r, g, b)
                weight[target] = if (total > 0f) total else 1f
            }
        }
    }

    /** Divides the accumulated channels by their weight. */
    private fun normalize(canvas: IntArray, weight: FloatArray) {
        for (index in canvas.indices) {
            val accumulated = weight[index]
            if (accumulated <= 0f) {
                canvas[index] = 0
                continue
            }
            val pixel = canvas[index]
            canvas[index] = pack(
                ((pixel ushr 16) and 0xFF) / accumulated,
                ((pixel ushr 8) and 0xFF) / accumulated,
                (pixel and 0xFF) / accumulated,
            )
        }
    }

    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or
            (channel(r) shl 16) or
            (channel(g) shl 8) or
            channel(b)

    private fun channel(value: Float): Int = (value + 0.5f).toInt().coerceIn(0, 255)

    private fun sign(value: Int): Int = when {
        value > 0 -> 1
        value < 0 -> -1
        else -> 0
    }
}