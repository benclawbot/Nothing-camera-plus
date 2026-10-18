package app.cameraplus.camera

/**
 * Luma histogram plus the few exposure statistics the expert overlay shows.
 *
 * Pure math over an 8-bit luma plane (see [LumaExtractor]), so it is unit
 * tested without a camera: [of] counts the samples into [BINS] bars, normalizes
 * the bars to the tallest one (the overlay has fixed height) and reports the
 * mean plus the share of crushed blacks and blown highlights.
 */
data class LumaHistogram(
    /** Bar heights, [BINS] entries, normalized to 0..1 by the tallest bar. */
    val bins: FloatArray,
    /** Mean luma, 0..255. */
    val mean: Int,
    /** Share of samples at or below [SHADOW_CUT] — crushed blacks. */
    val shadowClipped: Float,
    /** Share of samples at or above [HIGHLIGHT_CUT] — blown highlights. */
    val highlightClipped: Float,
) {
    companion object {
        /** 32 bars is the most a small viewfinder panel can show. */
        const val BINS = 32

        /** At or below this luma a sample counts as clipped black. */
        const val SHADOW_CUT = 8

        /** At or above this luma a sample counts as clipped white. */
        const val HIGHLIGHT_CUT = 247

        /**
         * Folds the first [count] bytes of [samples] (unsigned 8-bit luma) into
         * a histogram. An empty sample set yields zeroed statistics rather than
         * a division by zero, so a stalled stream can be drawn as-is.
         */
        fun of(samples: ByteArray, count: Int = samples.size): LumaHistogram {
            val n = count.coerceIn(0, samples.size)
            val counts = IntArray(BINS)
            var sum = 0L
            var shadows = 0
            var highlights = 0
            for (i in 0 until n) {
                val luma = samples[i].toInt() and 0xFF
                counts[luma * BINS / 256]++
                sum += luma
                if (luma <= SHADOW_CUT) shadows++
                if (luma >= HIGHLIGHT_CUT) highlights++
            }
            val peak = counts.maxOrNull() ?: 0
            return LumaHistogram(
                bins = FloatArray(BINS) { if (peak == 0) 0f else counts[it].toFloat() / peak },
                mean = if (n == 0) 0 else (sum / n).toInt(),
                shadowClipped = if (n == 0) 0f else shadows.toFloat() / n,
                highlightClipped = if (n == 0) 0f else highlights.toFloat() / n,
            )
        }
    }
}
