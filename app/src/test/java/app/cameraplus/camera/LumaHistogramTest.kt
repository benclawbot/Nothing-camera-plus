package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaHistogramTest {

    private fun plane(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    @Test
    fun blackFrameFillsTheLowestBarAndCountsAsClipped() {
        val histogram = LumaHistogram.of(ByteArray(64))

        assertEquals(1f, histogram.bins.first(), 0f)
        assertEquals(0f, histogram.bins.last(), 0f)
        assertEquals(0, histogram.mean)
        assertEquals(1f, histogram.shadowClipped, 0f)
        assertEquals(0f, histogram.highlightClipped, 0f)
    }

    @Test
    fun whiteFrameFillsTheHighestBarAndCountsAsClipped() {
        val histogram = LumaHistogram.of(ByteArray(64) { 255.toByte() })

        assertEquals(0f, histogram.bins.first(), 0f)
        assertEquals(1f, histogram.bins.last(), 0f)
        assertEquals(255, histogram.mean)
        assertEquals(0f, histogram.shadowClipped, 0f)
        assertEquals(1f, histogram.highlightClipped, 0f)
    }

    @Test
    fun midGreyLandsInTheMiddleBar() {
        val histogram = LumaHistogram.of(plane(128, 128, 128, 128))

        assertEquals(128, histogram.mean)
        assertEquals(1f, histogram.bins[128 * LumaHistogram.BINS / 256], 0f)
        // Only the middle bar is occupied, so every other bar stays empty.
        assertEquals(1, histogram.bins.count { it > 0f })
        assertEquals(0f, histogram.shadowClipped, 0f)
        assertEquals(0f, histogram.highlightClipped, 0f)
    }

    @Test
    fun uniformRampNormalizesEveryOccupiedBarToThePeak() {
        val histogram = LumaHistogram.of(ByteArray(256) { it.toByte() })

        // 256 samples over 32 bins: 8 each, so the tallest bar is every bar.
        assertTrue(histogram.bins.all { it == 1f })
        assertEquals(127, histogram.mean)
        // Luma 0..8 is 9 values, luma 247..255 is 9 values, of 256 samples.
        assertEquals(9f / 256f, histogram.shadowClipped, 0f)
        assertEquals(9f / 256f, histogram.highlightClipped, 0f)
    }

    @Test
    fun darkestSampleSetsThePeakSoLaterBarsScaleUnderIt() {
        val histogram = LumaHistogram.of(plane(0, 0, 0, 200, 200))

        // The three black samples are the peak (1.0); the two bright ones
        // scale to two thirds under it.
        assertEquals(1f, histogram.bins[0], 0f)
        assertEquals(2f / 3f, histogram.bins[200 * LumaHistogram.BINS / 256], 1e-6f)
    }

    @Test
    fun emptyAndPartialCountsAreSafe() {
        val empty = LumaHistogram.of(ByteArray(4), count = 0)

        assertEquals(0, empty.mean)
        assertEquals(0f, empty.shadowClipped, 0f)
        assertEquals(0f, empty.highlightClipped, 0f)
        assertTrue(empty.bins.all { it == 0f })

        // Only the first two samples are counted, so the two black bytes that
        // follow must not show up.
        val partial = LumaHistogram.of(plane(255, 255, 0, 0), count = 2)

        assertEquals(1, partial.bins.count { it > 0f })
        assertEquals(255, partial.mean)
        assertEquals(0f, partial.shadowClipped, 0f)
    }
}
