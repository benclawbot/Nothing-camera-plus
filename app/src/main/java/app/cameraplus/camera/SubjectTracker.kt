package app.cameraplus.camera

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A downsampled luma frame used by subject tracking: [width] x [height],
 * row-major 8-bit grayscale.
 */
class GrayFrame(val width: Int, val height: Int, val data: ByteArray) {
    fun at(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
}

/**
 * Translation-only template tracker for the viewfinder subject-tracking
 * feature (zero-mean normalized cross-correlation over a small search
 * window, with a slow template update so the subject's appearance may
 * drift without the box jumping onto the background).
 *
 * The tracker works on a small upright luma frame (about 192 px wide), so
 * one update costs a few million multiply-adds. It is deliberately
 * dependency-free so the maths can be unit tested off-device.
 */
class SubjectTracker {
    data class Result(val x: Float, val y: Float, val score: Float)

    private var template = FloatArray(0)
    private var side = 0
    private var centerX = 0f
    private var centerY = 0f
    private var lostFrames = 0
    private var sumTable = FloatArray(0)
    private var squareTable = FloatArray(0)

    /** True while a subject template is held. */
    var isTracking: Boolean = false
        private set

    /** Score of the last [update] (1 = identical, 0 = unrelated). */
    var lastScore: Float = 0f
        private set

    @Synchronized
    fun reset() {
        template = FloatArray(0)
        side = 0
        isTracking = false
        lostFrames = 0
        lastScore = 0f
    }

    /**
     * Locks onto the square of [halfSize] pixels around ([x], [y]) in frame
     * pixels. Returns false when the area is too flat to track (the caller
     * can then tell the user to pick a more detailed subject).
     */
    @Synchronized
    fun select(frame: GrayFrame, x: Float, y: Float, halfSize: Int): Boolean {
        val size = (halfSize * 2).coerceIn(MIN_SIDE, MAX_SIDE)
        if (frame.width < size + 2 || frame.height < size + 2) return false
        val half = size / 2
        val cx = x.roundToInt().coerceIn(half, frame.width - half - 1)
        val cy = y.roundToInt().coerceIn(half, frame.height - half - 1)
        val vector = normalizedPatch(frame, cx - half, cy - half, size) ?: return false
        template = vector
        side = size
        centerX = cx.toFloat()
        centerY = cy.toFloat()
        lostFrames = 0
        lastScore = 1f
        isTracking = true
        return true
    }

    /**
     * Finds the best match for the template around the last position.
     * Returns null only when no candidate patch has usable contrast.
     */
    @Synchronized
    fun update(frame: GrayFrame): Result? {
        if (!isTracking || side <= 0) return null
        val half = side / 2
        if (frame.width < side + 2 || frame.height < side + 2) return null
        buildIntegral(frame)

        val margin = (frame.width / 12).coerceAtLeast(MIN_SEARCH_MARGIN)
            .let { if (lostFrames >= LOST_BEFORE_WIDEN) it * 2 else it }
        val minX = (centerX.toInt() - margin).coerceIn(half, frame.width - half - 1)
        val maxX = (centerX.toInt() + margin).coerceIn(half, frame.width - half - 1)
        val minY = (centerY.toInt() - margin).coerceIn(half, frame.height - half - 1)
        val maxY = (centerY.toInt() + margin).coerceIn(half, frame.height - half - 1)

        val stride = frame.width + 1
        val pixels = side * side
        var bestScore = -2f
        var bestX = centerX
        var bestY = centerY

        var y = minY
        while (y <= maxY) {
            val top = y - half
            val rowTop = top * stride
            val rowBottom = (top + side) * stride
            var x = minX
            while (x <= maxX) {
                val left = x - half
                val right = left + side
                val sum = sumTable[rowBottom + right] - sumTable[rowTop + right] -
                    sumTable[rowBottom + left] + sumTable[rowTop + left]
                val sumSq = squareTable[rowBottom + right] - squareTable[rowTop + right] -
                    squareTable[rowBottom + left] + squareTable[rowTop + left]
                val mean = sum / pixels
                val variance = sumSq / pixels - mean * mean
                if (variance > MIN_PATCH_VARIANCE) {
                    var dot = 0f
                    var t = 0
                    var yy = 0
                    while (yy < side) {
                        var index = (top + yy) * frame.width + left
                        var xx = 0
                        while (xx < side) {
                            dot += template[t++] * (frame.data[index + xx].toInt() and 0xFF)
                            xx++
                        }
                        yy++
                    }
                    val score = dot / sqrt(variance * pixels)
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x.toFloat()
                        bestY = y.toFloat()
                    }
                }
                x++
            }
            y++
        }

        if (bestScore < -1f) return null
        centerX = bestX
        centerY = bestY
        if (bestScore < LOST_CONFIDENCE) lostFrames++ else lostFrames = 0

        if (bestScore >= TEMPLATE_UPDATE_CONFIDENCE) {
            adaptTemplate(frame, (bestX - half).roundToInt(), (bestY - half).roundToInt())
        }
        lastScore = bestScore
        return Result(centerX, centerY, bestScore)
    }

    /** Zero-mean unit-norm patch, or null when the patch lacks contrast. */
    private fun normalizedPatch(frame: GrayFrame, left: Int, top: Int, size: Int): FloatArray? {
        val count = size * size
        val values = FloatArray(count)
        var sum = 0f
        var sumSq = 0f
        var i = 0
        var yy = 0
        while (yy < size) {
            var index = (top + yy) * frame.width + left
            var xx = 0
            while (xx < size) {
                val value = (frame.data[index + xx].toInt() and 0xFF).toFloat()
                values[i++] = value
                sum += value
                sumSq += value * value
                xx++
            }
            yy++
        }
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        if (variance < MIN_SELECT_VARIANCE) return null
        val inverse = 1f / sqrt(variance * count)
        for (j in values.indices) values[j] = (values[j] - mean) * inverse
        return values
    }

    private fun adaptTemplate(frame: GrayFrame, left: Int, top: Int) {
        val fresh = normalizedPatch(frame, left, top, side) ?: return
        val blended = FloatArray(template.size)
        var norm = 0f
        for (i in blended.indices) {
            val value = template[i] * (1f - TEMPLATE_ADAPT_RATE) + fresh[i] * TEMPLATE_ADAPT_RATE
            blended[i] = value
            norm += value * value
        }
        if (norm <= 0.0001f) return
        val inverse = 1f / sqrt(norm)
        for (i in blended.indices) blended[i] *= inverse
        template = blended
    }

    /** Summed-area tables for O(1) patch mean/variance. */
    private fun buildIntegral(frame: GrayFrame) {
        val stride = frame.width + 1
        val size = stride * (frame.height + 1)
        if (sumTable.size < size) {
            sumTable = FloatArray(size)
            squareTable = FloatArray(size)
        }
        java.util.Arrays.fill(sumTable, 0, stride, 0f)
        java.util.Arrays.fill(squareTable, 0, stride, 0f)
        var y = 0
        while (y < frame.height) {
            val rowBase = (y + 1) * stride
            var rowSum = 0f
            var rowSquares = 0f
            sumTable[rowBase] = 0f
            squareTable[rowBase] = 0f
            val frameBase = y * frame.width
            var x = 0
            while (x < frame.width) {
                val value = (frame.data[frameBase + x].toInt() and 0xFF).toFloat()
                rowSum += value
                rowSquares += value * value
                val above = y * stride + (x + 1)
                sumTable[rowBase + x + 1] = sumTable[above] + rowSum
                squareTable[rowBase + x + 1] = squareTable[above] + rowSquares
                x++
            }
            y++
        }
    }

    private companion object {
        const val MIN_SIDE = 12
        const val MAX_SIDE = 64
        const val MIN_SEARCH_MARGIN = 8
        const val LOST_BEFORE_WIDEN = 4
        const val LOST_CONFIDENCE = 0.45f
        const val TEMPLATE_UPDATE_CONFIDENCE = 0.8f
        const val TEMPLATE_ADAPT_RATE = 0.15f

        /** Below ~3 grey levels of standard deviation a patch is not usable. */
        const val MIN_SELECT_VARIANCE = 9f
        const val MIN_PATCH_VARIANCE = 0.5f
    }
}
