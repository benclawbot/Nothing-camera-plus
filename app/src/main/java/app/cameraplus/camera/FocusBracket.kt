package app.cameraplus.camera

/**
 * A focus bracket sweeps Camera2's lens-focus distance from near to far.
 * OFF is the regular single-frame path; larger sweeps take longer and are
 * intentionally bounded because the clean-room stacker runs in software.
 */
enum class FocusBracket(val label: String, val shots: Int) {
    OFF("OFF", 0),
    THREE_SHOT("3 SHOT", 3),
    FIVE_SHOT("5 SHOT", 5);

    val enabled: Boolean get() = shots > 0
}

/** Builds near-to-far diopter positions from the camera's reported range. */
object FocusBracketPlan {
    fun diopters(minimumFocusDistance: Float, shots: Int): List<Float> {
        if (!minimumFocusDistance.isFinite() || minimumFocusDistance <= 0f || shots < 2) {
            return emptyList()
        }
        val count = shots.coerceIn(2, 7)
        return List(count) { index ->
            minimumFocusDistance * (1f - index.toFloat() / (count - 1))
        }
    }
}

/**
 * A bounded clean-room focus stacker. It estimates small translations between
 * frames, then selects the locally sharpest source in overlapping blocks.
 * This is a tripod/motionless-subject aid, not a substitute for a depth map;
 * moving subjects and focus breathing can still cause seams.
 */
object FocusStacker {
    private const val BLOCK = 16
    private const val FEATHER = 4

    fun merge(frames: List<IntArray>, width: Int, height: Int, maxShift: Int = 8): IntArray? {
        require(width > 0 && height > 0) { "image must not be empty" }
        val usable = frames.filter { it.size >= width * height }
        if (usable.size < 2) return null

        val reference = usable[usable.size / 2]
        val aligned = usable.map { frame ->
            if (frame === reference) frame else align(reference, frame, width, height, maxShift)
        }
        val output = IntArray(width * height)
        val weightSum = FloatArray(width * height)
        val accumR = FloatArray(width * height)
        val accumG = FloatArray(width * height)
        val accumB = FloatArray(width * height)

        var top = 0
        while (top < height) {
            var left = 0
            val bottom = minOf(height, top + BLOCK)
            while (left < width) {
                val right = minOf(width, left + BLOCK)
                val winner = aligned.indices.maxByOrNull { frameIndex ->
                    focusEnergy(aligned[frameIndex], width, height, left, top, right, bottom)
                } ?: 0
                val source = aligned[winner]
                for (y in top until bottom) {
                    val wy = edgeWeight(y, top, bottom)
                    for (x in left until right) {
                        val wx = edgeWeight(x, left, right)
                        val weight = wx * wy
                        val index = y * width + x
                        val p = source[index]
                        weightSum[index] += weight
                        accumR[index] += ((p ushr 16) and 0xFF) * weight
                        accumG[index] += ((p ushr 8) and 0xFF) * weight
                        accumB[index] += (p and 0xFF) * weight
                    }
                }
                left += BLOCK - FEATHER * 2
            }
            top += BLOCK - FEATHER * 2
        }
        for (i in output.indices) {
            val weight = weightSum[i].coerceAtLeast(1e-6f)
            output[i] = (0xFF shl 24) or
                (accumR[i].div(weight).toInt().coerceIn(0, 255) shl 16) or
                (accumG[i].div(weight).toInt().coerceIn(0, 255) shl 8) or
                accumB[i].div(weight).toInt().coerceIn(0, 255)
        }
        return output
    }

    private fun focusEnergy(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Long {
        var score = 0L
        for (y in maxOf(1, top) until minOf(height - 1, bottom)) {
            for (x in maxOf(1, left) until minOf(width - 1, right)) {
                val i = y * width + x
                val center = luma(pixels[i]) * 4
                val neighbors = luma(pixels[i - 1]) + luma(pixels[i + 1]) +
                    luma(pixels[i - width]) + luma(pixels[i + width])
                score += kotlin.math.abs(center - neighbors).toLong()
            }
        }
        return score
    }

    private fun edgeWeight(position: Int, start: Int, end: Int): Float {
        val edge = minOf(position - start, end - 1 - position)
        return (edge + 1).coerceIn(1, FEATHER).toFloat() / FEATHER
    }

    private fun align(reference: IntArray, frame: IntArray, width: Int, height: Int, maxShift: Int): IntArray {
        val (dx, dy) = NightMerger.estimateShift(reference, frame, width, height, maxShift)
        if (dx == 0 && dy == 0) return frame
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val sx = (x + dx).coerceIn(0, width - 1)
            val sy = (y + dy).coerceIn(0, height - 1)
            frame[sy * width + sx]
        }
    }

    private fun luma(pixel: Int): Int =
        (0.299f * ((pixel ushr 16) and 0xFF) +
            0.587f * ((pixel ushr 8) and 0xFF) +
            0.114f * (pixel and 0xFF)).toInt()
}
