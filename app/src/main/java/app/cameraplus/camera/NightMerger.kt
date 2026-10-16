package app.cameraplus.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One merged night frame, plus the bookkeeping the capture layer needs to
 * tell the user (or the log) what actually happened during the burst.
 *
 * @property pixels merged ARGB image, `width * height` long
 * @property usedFrames frames that made it into the average (>= 1)
 * @property rejectedFrames frames dropped for moving too far
 * @property meanShiftX mean alignment offset in pixels that was applied to the
 *   non-reference frames; a large value means shaky hands
 * @property meanShiftY as [meanShiftX], vertically
 */
data class MergeResult(
    val pixels: IntArray,
    val usedFrames: Int,
    val rejectedFrames: Int,
    val meanShiftX: Float,
    val meanShiftY: Float,
)

/**
 * Multi-frame night merge on plain ARGB pixel arrays.
 *
 * Night mode is a burst of short exposures: every frame is dark, noisy and
 * slightly moved, because what is holding the scene still is the
 * photographer's hands. Averaging N aligned frames recovers the exposure one
 * frame could not record, and averages the noise down at the same time.
 *
 * The pipeline is the plain version of that:
 *  1. estimate each frame's translation against the reference frame,
 *  2. drop frames that moved further than the caller allows,
 *  3. average the survivors in linear light (sRGB encoding is not linear, and
 *     averaging encoded values is not the same picture as averaging light),
 *  4. apply a night tone curve: roll the remaining highlights off, lift the
 *     shadows a little.
 *
 * Alignment is a coarse luminance projection plus normalized cross
 * correlation, not optical flow. It handles the drift a hand introduces. It
 * does not rescue a walking subject, a passing car, or anything that moves
 * more than `maxShift` between frames - those are counted as rejected rather
 * than smeared into ghosts.
 *
 * Cost is linear in (pixels x frames) and the linear-light accumulator wants
 * 12 bytes per pixel, so hand it frames that have already been downscaled:
 * a 12 MP merge would otherwise ask for roughly 145 MB of float buffers.
 *
 * Free of Android types, so the whole thing is JVM-testable.
 */
object NightMerger {

    /** Long side of the coarse luminance grid used for alignment. */
    private const val GRID_MAX = 128

    /** Correlation below this is noise, not a match. */
    private const val MIN_CORRELATION = 0.3f

    /** A best peak this close to the runner-up is a coin toss, not a shift. */
    private const val PEAK_MARGIN = 0.05f

    /** How much of the fine search may beat the coarse score to be believed. */
    private const val FINE_TOLERANCE = 0.02f

    /** Minimum samples in a correlation window before it means anything. */
    private const val MIN_OVERLAP = 8

    /** Per-sample energy below which a projection counts as featureless. */
    private const val FLAT_ENERGY = 1e-4f

    private const val EPS = 1e-9f

    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    /** sRGB byte -> linear light, tabulated because every pixel uses it. */
    private val DECODE = FloatArray(256) { srgbToLinear(it / 255f) }

    /** Linear light -> sRGB byte, sampled in sqrt space and interpolated. */
    private const val ENCODE_STEPS = 4096

    private val ENCODE = FloatArray(ENCODE_STEPS + 1) { i ->
        val v = i.toFloat() / ENCODE_STEPS
        srgbEncode(v * v)
    }

    /**
     * Integer translation that maps [previous] onto [current], searched over
     * plus or minus [maxShift] pixels on each axis.
     *
     * Return `(dx, dy)` means: the content of [previous] sits `dx` pixels to
     * the right and `dy` pixels down in [current], so sampling [current] at
     * `(x + dx, y + dy)` lines the two up.
     *
     * Returns `(0, 0)` whenever the answer would be a guess rather than a
     * measurement: a flat or untextured frame, a correlation peak that is not
     * clearly better than its rivals, or a search window of zero. Callers can
     * treat `(0, 0)` as "do not move anything", which is the safe answer.
     */
    fun estimateShift(
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
        maxShift: Int,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "image must not be empty" }
        val count = width * height
        require(previous.size >= count) { "previous buffer too small" }
        require(current.size >= count) { "current buffer too small" }

        val match = findShift(previous, current, width, height, maxShift)
        return if (match.confident) match.x to match.y else 0 to 0
    }

    /**
     * Merges a night burst.
     *
     * Each frame is aligned to the first usable frame and averaged with it in
     * linear light. A frame is dropped into [MergeResult.rejectedFrames] when
     * its measured shift exceeds [maxShift] on either axis, or when it carries
     * texture that matches nothing within reach of the reference. A frame with
     * nothing measurable in it - a black exposure, a flat wall - is kept where
     * it is, because dropping light costs more than an unaligned average.
     *
     * [highlightRoll] is the strength of the night tone curve (0 = plain
     * average in linear light, 1 = hard highlight compression). Values outside
     * 0..1 are clamped.
     *
     * Returns `null` when there is nothing to merge - fewer than two frames, or
     * no frame with a usable buffer size. A burst where everything except the
     * reference got rejected still returns a result (the reference frame,
     * tone mapped, with `usedFrames = 1`): callers can fall back to it.
     *
     * Alpha is copied from the reference frame.
     */
    fun merge(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        maxShift: Int = width / 8,
        highlightRoll: Float = 0.6f,
    ): MergeResult? {
        require(width > 0 && height > 0) { "image must not be empty" }
        val count = width * height
        if (frames.size < 2) return null

        val accumulator = FloatArray(count * 3)
        var reference: IntArray? = null
        var used = 0
        var rejected = 0
        var shiftSumX = 0f
        var shiftSumY = 0f
        var shiftCount = 0

        for (frame in frames) {
            if (frame.size < count) {
                rejected++
                continue
            }
            val ref = reference
            if (ref == null) {
                reference = frame
                accumulate(accumulator, frame, width, height, 0, 0)
                used++
                continue
            }
            // Search wider than the accepted range so an over-limit frame is
            // recognised as over-limit instead of silently pinned at the edge.
            val match = findShift(ref, frame, width, height, maxShift.coerceAtLeast(0) * 2)
            val dx: Int
            val dy: Int
            when {
                // No texture to correlate against, so nothing was measured.
                // Keeping the frame unmoved is the only honest option.
                !match.alignable -> {
                    dx = 0
                    dy = 0
                }
                match.confident -> {
                    dx = match.x
                    dy = match.y
                }
                // A match exists but the peak is not clearly the best one: a
                // repeated pattern, a scene that is moving. Guessing would
                // ghost the frame, so leave it where it is.
                match.score >= MIN_CORRELATION -> {
                    dx = 0
                    dy = 0
                }
                // Textured on both sides yet nothing lines up anywhere within
                // reach: the frame moved too much to belong in this average.
                else -> {
                    rejected++
                    continue
                }
            }
            if (abs(dx) > maxShift || abs(dy) > maxShift) {
                rejected++
                continue
            }
            shiftSumX += dx
            shiftSumY += dy
            shiftCount++
            accumulate(accumulator, frame, width, height, dx, dy)
            used++
        }

        if (used == 0 || reference == null) return null

        val roll = highlightRoll.coerceIn(0f, 1f)
        val alphaSource = reference
        val inverse = 1f / used
        val out = IntArray(count)
        for (i in 0 until count) {
            val r = encodeSrgb(nightTone(accumulator[3 * i] * inverse, roll))
            val g = encodeSrgb(nightTone(accumulator[3 * i + 1] * inverse, roll))
            val b = encodeSrgb(nightTone(accumulator[3 * i + 2] * inverse, roll))
            out[i] = pack(r, g, b, alphaSource[i] ushr 24)
        }

        return MergeResult(
            pixels = out,
            usedFrames = used,
            rejectedFrames = rejected,
            meanShiftX = if (shiftCount > 0) shiftSumX / shiftCount else 0f,
            meanShiftY = if (shiftCount > 0) shiftSumY / shiftCount else 0f,
        )
    }

    // ---------------------------------------------------------------- alignment

    private class ShiftMatch(
        val x: Int,
        val y: Int,
        val score: Float,
        val confident: Boolean,
        val alignable: Boolean,
    )

    private class Peak(val offset: Int, val score: Float, val confident: Boolean)

    /**
     * Coarse grid correlation, then a full-resolution refinement around the
     * coarse peak. The coarse pass averages neighbouring pixels, which throws
     * away sensor noise and gives a clean peak; the fine pass then pins the
     * answer down to a single pixel.
     */
    private fun findShift(
        reference: IntArray,
        frame: IntArray,
        width: Int,
        height: Int,
        searchLimit: Int,
    ): ShiftMatch {
        val limit = searchLimit.coerceAtLeast(0)
        if (limit == 0) return ShiftMatch(0, 0, 0f, confident = false, alignable = false)

        val stride = max(1, (max(width, height) + GRID_MAX - 1) / GRID_MAX)
        val gridW = (width + stride - 1) / stride
        val gridH = (height + stride - 1) / stride

        val lumRef = FloatArray(gridW * gridH)
        val lumFrame = FloatArray(gridW * gridH)
        luminanceGrid(reference, width, height, stride, gridW, gridH, lumRef)
        luminanceGrid(frame, width, height, stride, gridW, gridH, lumFrame)

        val colRef = marginal(lumRef, gridW, gridH, horizontal = true)
        val colFrame = marginal(lumFrame, gridW, gridH, horizontal = true)
        val rowRef = marginal(lumRef, gridW, gridH, horizontal = false)
        val rowFrame = marginal(lumFrame, gridW, gridH, horizontal = false)

        // A frame with no luminance variation at all (a black exposure, a flat
        // wall) has nothing to correlate. Say so, rather than reporting the
        // first offset in the search window as if it were a measurement.
        val alignable = hasEnergy(colRef) && hasEnergy(colFrame) &&
            hasEnergy(rowRef) && hasEnergy(rowFrame)
        if (!alignable) return ShiftMatch(0, 0, 0f, confident = false, alignable = false)

        val colLimit = if (stride == 1) limit else min(limit / stride + 1, max(gridW, gridH))
        val peakX = bestOffset(colRef, colFrame, -colLimit, colLimit)
        val peakY = bestOffset(rowRef, rowFrame, -colLimit, colLimit)

        var shiftX = peakX.offset * stride
        var shiftY = peakY.offset * stride
        var score = min(peakX.score, peakY.score)
        var confident = peakX.confident && peakY.confident

        if (confident && stride > 1) {
            val fineColRef = FloatArray(width)
            val fineColFrame = FloatArray(width)
            val fineRowRef = FloatArray(height)
            val fineRowFrame = FloatArray(height)
            marginalsFull(reference, width, height, fineColRef, fineRowRef)
            marginalsFull(frame, width, height, fineColFrame, fineRowFrame)
            val fineX = bestOffset(fineColRef, fineColFrame, shiftX - stride, shiftX + stride)
            val fineY = bestOffset(fineRowRef, fineRowFrame, shiftY - stride, shiftY + stride)
            val fineScore = min(fineX.score, fineY.score)
            if (fineX.confident && fineY.confident && fineScore >= score - FINE_TOLERANCE) {
                shiftX = fineX.offset
                shiftY = fineY.offset
                score = fineScore
            }
        }

        return ShiftMatch(shiftX, shiftY, score, confident, alignable = true)
    }

    /** True when the projection is not a flat line, i.e. it carries texture. */
    private fun hasEnergy(values: FloatArray): Boolean {
        if (values.isEmpty()) return false
        var sum = 0f
        for (v in values) sum += v
        val mean = sum / values.size
        var energy = 0f
        for (v in values) {
            val d = v - mean
            energy += d * d
        }
        return energy > values.size * FLAT_ENERGY
    }

    /** Box-averaged luminance, one sample per `stride` x `stride` block. */
    private fun luminanceGrid(
        pixels: IntArray,
        width: Int,
        height: Int,
        stride: Int,
        gridW: Int,
        gridH: Int,
        out: FloatArray,
    ) {
        var index = 0
        for (gy in 0 until gridH) {
            val y0 = gy * stride
            val y1 = min(height, y0 + stride)
            for (gx in 0 until gridW) {
                val x0 = gx * stride
                val x1 = min(width, x0 + stride)
                var sum = 0f
                var count = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        val p = pixels[row + x]
                        sum += LUMA_R * ((p shr 16) and 0xFF) +
                            LUMA_G * ((p shr 8) and 0xFF) +
                            LUMA_B * (p and 0xFF)
                        count++
                    }
                }
                out[index++] = if (count > 0) sum / count else 0f
            }
        }
    }

    /**
     * Projects the grid onto one axis: the column sums answer "did the content
     * move sideways", the row sums answer the same for downwards. It loses the
     * 2-D arrangement, which is the price of a search that stays cheap.
     */
    private fun marginal(grid: FloatArray, gridW: Int, gridH: Int, horizontal: Boolean): FloatArray {
        if (horizontal) {
            val out = FloatArray(gridW)
            for (y in 0 until gridH) {
                val row = y * gridW
                for (x in 0 until gridW) out[x] += grid[row + x]
            }
            return out
        }
        val out = FloatArray(gridH)
        for (y in 0 until gridH) out[y] = grid[y * gridW]
        return out
    }

    /** Full-resolution column and row luminance sums, for the fine pass. */
    private fun marginalsFull(
        pixels: IntArray,
        width: Int,
        height: Int,
        columns: FloatArray,
        rows: FloatArray,
    ) {
        for (y in 0 until height) {
            val row = y * width
            var rowSum = 0f
            for (x in 0 until width) {
                val p = pixels[row + x]
                val luma = LUMA_R * ((p shr 16) and 0xFF) +
                    LUMA_G * ((p shr 8) and 0xFF) +
                    LUMA_B * (p and 0xFF)
                columns[x] += luma
                rowSum += luma
            }
            rows[y] = rowSum
        }
    }

    /**
     * Best shift of `cur` relative to `ref` over `from..to`, by normalized
     * cross correlation of the mean-removed overlap window.
     */
    private fun bestOffset(ref: FloatArray, cur: FloatArray, from: Int, to: Int): Peak {
        val n = ref.size
        val lo = max(from, -(n - 1))
        val hi = min(to, n - 1)
        if (hi < lo) return Peak(0, 0f, confident = false)

        val scores = FloatArray(hi - lo + 1)
        for (offset in lo..hi) scores[offset - lo] = normalizedCorrelation(ref, cur, offset)

        var bestIndex = 0
        for (i in 1 until scores.size) if (scores[i] > scores[bestIndex]) bestIndex = i
        var runnerUp = 0f
        for (i in scores.indices) {
            // Neighbours of the peak are the same peak, not a rival.
            if (i >= bestIndex - 1 && i <= bestIndex + 1) continue
            if (scores[i] > runnerUp) runnerUp = scores[i]
        }

        val best = scores[bestIndex]
        val confident = best >= MIN_CORRELATION && (best - runnerUp) > PEAK_MARGIN
        return Peak(lo + bestIndex, best, confident)
    }

    /**
     * Zero-mean normalized cross correlation of the overlapping window.
     * Returns 0 for a flat window, which is what keeps an all-black frame
     * from turning into 0/0.
     */
    private fun normalizedCorrelation(ref: FloatArray, cur: FloatArray, offset: Int): Float {
        val n = ref.size
        val start = max(0, -offset)
        val end = min(n, n - offset)
        val count = end - start
        if (count < MIN_OVERLAP) return 0f

        var sumRef = 0f
        var sumCur = 0f
        for (i in start until end) {
            sumRef += ref[i]
            sumCur += cur[i + offset]
        }
        val meanRef = sumRef / count
        val meanCur = sumCur / count

        var numerator = 0f
        var energyRef = 0f
        var energyCur = 0f
        for (i in start until end) {
            val a = ref[i] - meanRef
            val b = cur[i + offset] - meanCur
            numerator += a * b
            energyRef += a * a
            energyCur += b * b
        }
        val denominator = sqrt(energyRef * energyCur)
        if (denominator <= EPS) return 0f
        return (numerator / denominator).coerceIn(-1f, 1f)
    }

    // ------------------------------------------------------------------- merge

    /** Adds one frame, sampled at `x + shiftX` / `y + shiftY`, in linear light. */
    private fun accumulate(
        accumulator: FloatArray,
        frame: IntArray,
        width: Int,
        height: Int,
        shiftX: Int,
        shiftY: Int,
    ) {
        val maxX = width - 1
        val maxY = height - 1
        for (y in 0 until height) {
            val sourceRow = (y + shiftY).coerceIn(0, maxY) * width
            val targetRow = y * width
            for (x in 0 until width) {
                val p = frame[sourceRow + (x + shiftX).coerceIn(0, maxX)]
                val i = 3 * (targetRow + x)
                accumulator[i] += DECODE[(p shr 16) and 0xFF]
                accumulator[i + 1] += DECODE[(p shr 8) and 0xFF]
                accumulator[i + 2] += DECODE[p and 0xFF]
            }
        }
    }

    /**
     * The night curve, applied in linear light: a shadow lift that is zero at
     * black (a night sky must stay black, not go milky) and zero at white, and
     * a soft-knee highlight rolloff whose strength tracks [highlightRoll].
     *
     * Monotonically increasing for every input, so it cannot invert an edge.
     */
    private fun nightTone(linear: Float, highlightRoll: Float): Float {
        val x = linear.coerceIn(0f, 1f)
        val inverse = 1f - x
        var y = x + SHADOW_LIFT * x * inverse * inverse

        val knee = KNEE_MAX - (KNEE_MAX - KNEE_MIN) * highlightRoll
        if (y > knee && knee < 1f) {
            val over = (y - knee) / (1f - knee)
            val rolled = knee + (1f - knee) * (over / (1f + over))
            y += (rolled - y) * highlightRoll
        }
        return y.coerceIn(0f, 1f)
    }

    /** Shadow lift strength, in linear light. */
    private const val SHADOW_LIFT = 0.12f

    /** Highlight knee at [highlightRoll] = 0, in linear light. */
    private const val KNEE_MAX = 0.78f

    /** Highlight knee at [highlightRoll] = 1, in linear light. */
    private const val KNEE_MIN = 0.30f

    /**
     * The tone curve applied to a single sRGB byte. Internal, but it is the
     * exact reference for what [merge] does to a stack of identical frames.
     */
    internal fun toneMapByte(byte: Int, highlightRoll: Float = 0.6f): Int =
        encodeSrgb(nightTone(DECODE[byte and 0xFF], highlightRoll.coerceIn(0f, 1f)))

    /** Reference for one output channel formed by averaging two sRGB samples. */
    internal fun linearAverageToneMapByte(
        first: Int,
        second: Int,
        highlightRoll: Float = 0.6f,
    ): Int {
        val mean = (DECODE[first.coerceIn(0, 255)] + DECODE[second.coerceIn(0, 255)]) * 0.5f
        return encodeSrgb(nightTone(mean, highlightRoll.coerceIn(0f, 1f)))
    }

    // ------------------------------------------------------------------ colour

    /** Only used to fill the 256-entry [DECODE] table, so pow is affordable. */
    private fun srgbToLinear(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f
        else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    /** The gamma part is only built for the encode table, so pow here is fine. */
    private fun srgbEncode(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f)
        val encoded = if (v <= 0.0031308f) v * 12.92f
        else 1.055f * Math.pow(v.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        return (encoded * 255f).coerceIn(0f, 255f)
    }

    /** Linear light -> sRGB byte, via the sqrt-spaced table. */
    private fun encodeSrgb(linear: Float): Int {
        val position = sqrt(linear.coerceIn(0f, 1f)) * ENCODE_STEPS
        val index = position.toInt().coerceIn(0, ENCODE_STEPS - 1)
        val fraction = position - index
        val a = ENCODE[index]
        return (a + (ENCODE[index + 1] - a) * fraction).roundToInt().coerceIn(0, 255)
    }

    private fun pack(r: Int, g: Int, b: Int, a: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}
