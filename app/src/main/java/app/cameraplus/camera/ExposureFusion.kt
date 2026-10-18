package app.cameraplus.camera

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Small bounded-resolution exposure fusion for Camera+'s AE bracket.
 *
 * JPEGs are not RAW radiance, so this is deliberately described as exposure
 * fusion, not scene-referred HDR. It aligns small hand movement, discounts
 * clipped and near-black samples, compensates for the requested EV offset in
 * linear light, and rolls the merged radiance into an SDR JPEG.
 */
object ExposureFusion {
    private val linear = FloatArray(256) { srgbToLinear(it / 255f) }
    private const val GHOST_DEVIATION_LIMIT = 0.65f

    fun merge(
        frames: List<IntArray>,
        evOffsets: List<Float>,
        width: Int,
        height: Int,
        maxShift: Int = 8,
    ): IntArray? {
        require(width > 0 && height > 0) { "image must not be empty" }
        val usable = frames.indices.filter { frames[it].size >= width * height && evOffsets.getOrNull(it)?.isFinite() == true }
        if (usable.size < 2) return null

        val referenceIndex = usable.minByOrNull { kotlin.math.abs(evOffsets[it]) } ?: return null
        val reference = frames[referenceIndex]
        val aligned = usable.map { sourceIndex ->
            val source = frames[sourceIndex]
            if (sourceIndex == referenceIndex) source else align(reference, source, width, height, maxShift)
        }
        val evs = usable.map { evOffsets[it] }
        val exposureScales = evs.map { 2.0.pow(it.toDouble()).toFloat() }
        val radianceLumas = FloatArray(aligned.size)
        val orderedLumas = FloatArray(aligned.size)
        val output = IntArray(width * height)
        for (i in output.indices) {
            for (frameIndex in aligned.indices) {
                val pixel = aligned[frameIndex][i]
                val r = linear[(pixel ushr 16) and 0xFF]
                val g = linear[(pixel ushr 8) and 0xFF]
                val b = linear[pixel and 0xFF]
                radianceLumas[frameIndex] =
                    (0.2126f * r + 0.7152f * g + 0.0722f * b) / exposureScales[frameIndex]
            }
            for (frameIndex in aligned.indices) orderedLumas[frameIndex] = radianceLumas[frameIndex]
            orderedLumas.sort()
            val medianRadiance = orderedLumas[orderedLumas.size / 2]
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var sumWeight = 0f
            for (frameIndex in aligned.indices) {
                val pixel = aligned[frameIndex][i]
                val r = linear[(pixel ushr 16) and 0xFF]
                val g = linear[(pixel ushr 8) and 0xFF]
                val b = linear[pixel and 0xFF]
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val normalizedLuma = luma.coerceIn(0f, 1f)
                val centered = (normalizedLuma - 0.5f) / 0.22f
                val weight = exp(-0.5f * centered * centered) *
                    (1f - normalizedLuma.pow(8)).coerceAtLeast(0.01f)
                // A moving object usually becomes a radiance outlier after
                // exposure compensation. Reject that sample rather than
                // making a transparent double image; the median represents
                // the majority-consistent scene.
                val deviation = kotlin.math.abs(radianceLumas[frameIndex] - medianRadiance) /
                    medianRadiance.coerceAtLeast(0.025f)
                val ghostWeight = if (deviation > GHOST_DEVIATION_LIMIT) 0.02f else 1f
                val exposureScale = exposureScales[frameIndex]
                val finalWeight = weight * ghostWeight
                sumR += r / exposureScale * finalWeight
                sumG += g / exposureScale * finalWeight
                sumB += b / exposureScale * finalWeight
                sumWeight += finalWeight
            }
            val normalization = (sumWeight.coerceAtLeast(1e-6f))
            val toneScale = 2.0f
            output[i] = (0xFF shl 24) or
                (encode(sumR / normalization * toneScale) shl 16) or
                (encode(sumG / normalization * toneScale) shl 8) or
                encode(sumB / normalization * toneScale)
        }
        return output
    }

    private fun align(reference: IntArray, frame: IntArray, width: Int, height: Int, maxShift: Int): IntArray {
        val (dx, dy) = NightMerger.estimateShift(reference, frame, width, height, maxShift)
        if (dx == 0 && dy == 0) return frame
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            frame[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
        }
    }

    private fun encode(linearValue: Float): Int {
        val mapped = (linearValue.coerceAtLeast(0f) / (1f + linearValue.coerceAtLeast(0f)))
        val srgb = if (mapped <= 0.0031308f) mapped * 12.92f else {
            1.055f * mapped.pow(1f / 2.4f) - 0.055f
        }
        return (srgb * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun srgbToLinear(value: Float): Float =
        if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
}
