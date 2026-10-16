package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NightMergerTest {

    private val roll = 0.6f

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    /** JUnit 4 has no assertEquals for two ints plus a tolerance, so here. */
    private fun assertChannel(expected: Int, actual: Int, what: String, tolerance: Int = 1) {
        assertEquals(what, expected.toDouble(), actual.toDouble(), tolerance.toDouble())
    }

    /**
     * Blocky structure plus fine detail: the blocks make misalignment visible
     * as ghosting, the noise makes the correlation peak unambiguous. Fully
     * deterministic, so a failure is always the algorithm and not the seed.
     */
    private fun texture(width: Int, height: Int, seed: Int): IntArray {
        var state = seed
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                state = state * 1664525 + 1013904223
                val noise = (state ushr 8) and 0xFF
                val block = if (((x / 9) + (y / 11)) % 2 == 0) 25 else -25
                val v = (110 + block + noise - 128).coerceIn(0, 255)
                out[y * width + x] = rgb(v, (v * 3 / 4) + 20, 255 - v)
            }
        }
        return out
    }

    /** Content moved by (`dx`, `dy`), clamped at the borders. */
    private fun shiftPixels(source: IntArray, width: Int, height: Int, dx: Int, dy: Int): IntArray {
        val out = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = (x - dx).coerceIn(0, width - 1)
                val sy = (y - dy).coerceIn(0, height - 1)
                out[y * width + x] = source[sy * width + sx]
            }
        }
        return out
    }

    /** Sum of squared luminance gradients: higher means a sharper image. */
    private fun gradientEnergy(pixels: IntArray, width: Int, height: Int, margin: Int): Double {
        fun luma(p: Int): Double = 0.299 * red(p) + 0.587 * green(p) + 0.114 * blue(p)
        var energy = 0.0
        for (y in margin until height - margin) {
            for (x in margin until width - margin) {
                val i = y * width + x
                val dx = luma(pixels[i + 1]) - luma(pixels[i])
                val dy = luma(pixels[i + width]) - luma(pixels[i])
                energy += dx * dx + dy * dy
            }
        }
        return energy
    }

    /** What merging without alignment would produce, for the same frames. */
    private fun naiveAverage(a: IntArray, b: IntArray): IntArray = IntArray(a.size) { i ->
        rgb(
            NightMerger.toneMapByte((red(a[i]) + red(b[i])) / 2, roll),
            NightMerger.toneMapByte((green(a[i]) + green(b[i])) / 2, roll),
            NightMerger.toneMapByte((blue(a[i]) + blue(b[i])) / 2, roll),
        )
    }

    @Test
    fun oneFrameIsNotAMerge() {
        val base = texture(32, 32, 3)
        assertNull(NightMerger.merge(listOf(base), 32, 32))
        assertNull(NightMerger.merge(emptyList(), 32, 32))
    }

    @Test
    fun identicalFramesAverageToTheToneMappedFrame() {
        val width = 40
        val height = 40
        val base = texture(width, height, 11)
        val result = NightMerger.merge(List(4) { base.copyOf() }, width, height, maxShift = 6)
        assertNotNull(result)
        requireNotNull(result)

        assertEquals(4, result.usedFrames)
        assertEquals(0, result.rejectedFrames)
        assertEquals("identical frames need no alignment", 0f, result.meanShiftX, 0f)
        assertEquals(0f, result.meanShiftY, 0f)

        for (i in base.indices) {
            assertChannel(NightMerger.toneMapByte(red(base[i]), roll), red(result.pixels[i]), "red at $i")
            assertChannel(NightMerger.toneMapByte(green(base[i]), roll), green(result.pixels[i]), "green at $i")
            assertChannel(NightMerger.toneMapByte(blue(base[i]), roll), blue(result.pixels[i]), "blue at $i")
        }
    }

    @Test
    fun aKnownShiftIsRecoveredExactly() {
        val width = 48
        val height = 48
        val base = texture(width, height, 5)
        val moved = shiftPixels(base, width, height, 5, 3)

        assertEquals(5 to 3, NightMerger.estimateShift(base, moved, width, height, 8))
        assertEquals(0 to 0, NightMerger.estimateShift(base, base, width, height, 8))
    }

    @Test
    fun realignedMergeStaysSharperThanANaiveAverage() {
        val width = 48
        val height = 48
        val margin = 10
        val base = texture(width, height, 21)
        val moved = shiftPixels(base, width, height, 5, 3)

        val result = NightMerger.merge(listOf(base, moved), width, height, maxShift = 8)
        assertNotNull(result)
        requireNotNull(result)
        assertEquals(2, result.usedFrames)
        assertEquals(0, result.rejectedFrames)

        // Both frames describe the same scene, so a correct alignment rebuilds
        // it exactly instead of averaging two copies of it into mush.
        for (y in margin until height - margin) {
            for (x in margin until width - margin) {
                val i = y * width + x
                assertChannel(
                    NightMerger.toneMapByte(red(base[i]), roll),
                    red(result.pixels[i]),
                    "aligned interior red at ($x, $y)",
                )
            }
        }

        val aligned = gradientEnergy(result.pixels, width, height, margin)
        val naive = gradientEnergy(naiveAverage(base, moved), width, height, margin)
        assertTrue("aligned $aligned should beat unaligned $naive", aligned > naive * 1.5)
    }

    @Test
    fun framesThatMoveTooFarAreRejectedInsteadOfGhosted() {
        val width = 48
        val height = 48
        val base = texture(width, height, 9)
        val far = shiftPixels(base, width, height, 20, 0)

        val result = NightMerger.merge(listOf(base, far), width, height, maxShift = 12)
        assertNotNull(result)
        requireNotNull(result)
        assertEquals("the frame that moved 20px is not usable at maxShift 12", 1, result.usedFrames)
        assertEquals(1, result.rejectedFrames)

        // What is left is the reference frame, tone mapped.
        assertChannel(NightMerger.toneMapByte(red(base[0]), roll), red(result.pixels[0]), "reference frame kept")
    }

    @Test
    fun aFrameWithNothingToCorrelateStaysInTheAverage() {
        val width = 32
        val height = 32
        val base = texture(width, height, 27)
        val blank = IntArray(width * height) { rgb(20, 20, 24) }

        val result = NightMerger.merge(listOf(base, blank), width, height, maxShift = 8)
        assertNotNull(result)
        requireNotNull(result)
        // Nothing was measured, so nothing is guessed: the frame is kept, and
        // the average sits between the two rather than being either one.
        assertEquals(2, result.usedFrames)
        assertEquals(0, result.rejectedFrames)

        val interior = (height / 2) * width + width / 2
        val blended = red(result.pixels[interior])
        assertTrue("average of $blended should be darker than the texture", blended < red(base[interior]))
    }

    @Test
    fun meanShiftReportsTheDriftBetweenFrames() {
        val width = 48
        val height = 48
        val base = texture(width, height, 33)
        val result = NightMerger.merge(
            listOf(base, shiftPixels(base, width, height, 2, 0), shiftPixels(base, width, height, 4, 0)),
            width,
            height,
            maxShift = 8,
        )
        assertNotNull(result)
        requireNotNull(result)
        assertEquals(3, result.usedFrames)
        assertEquals("mean of the 2px and 4px offsets", 3f, result.meanShiftX, 0.01f)
        assertEquals(0f, result.meanShiftY, 0.01f)
    }

    @Test
    fun anAmbiguousCorrelationReportsNoShift() {
        val width = 32
        val height = 32
        val flat = IntArray(width * height) { rgb(90, 120, 150) }
        val black = IntArray(width * height)

        // Nothing to correlate: identical, featureless or simply absent.
        assertEquals(0 to 0, NightMerger.estimateShift(flat, flat, width, height, 8))
        assertEquals(0 to 0, NightMerger.estimateShift(flat, flat.copyOf(), width, height, 8))
        assertEquals(0 to 0, NightMerger.estimateShift(black, black.copyOf(), width, height, 8))
        // A zero search window cannot answer, so it does not pretend to.
        assertEquals(0 to 0, NightMerger.estimateShift(flat, flat, width, height, 0))
    }

    @Test
    fun allBlackFramesStayBlack() {
        val width = 24
        val height = 24
        val black = IntArray(width * height) { rgb(0, 0, 0) }
        val result = NightMerger.merge(listOf(black, black.copyOf()), width, height, maxShift = 4)
        assertNotNull(result)
        requireNotNull(result)

        // The shadow lift must not turn an unlit sky milky, and nothing here
        // may divide by a zero correlation.
        for (i in 0 until width * height) {
            assertEquals("black in, black out at $i", 0xFF000000.toInt(), result.pixels[i])
        }
        assertEquals(2, result.usedFrames)
        assertEquals(0f, result.meanShiftX, 0f)
    }

    @Test
    fun highlightsRollOffOnlyWhenAsked() {
        val pixels = intArrayOf(rgb(128, 128, 128), rgb(255, 255, 255))

        val gentle = NightMerger.merge(listOf(pixels.copyOf(), pixels.copyOf()), 2, 1, highlightRoll = 0f)
        requireNotNull(gentle)
        val hard = NightMerger.merge(listOf(pixels.copyOf(), pixels.copyOf()), 2, 1, highlightRoll = 1f)
        requireNotNull(hard)

        assertEquals("white is untouched with no rolloff", 255, red(gentle.pixels[1]))
        val rolled = red(hard.pixels[1])
        assertTrue("white should come down under a hard rolloff, got $rolled", rolled in 180..240)
        assertTrue("rolloff should compress white, got $rolled", rolled < red(gentle.pixels[1]))

        val mid = red(gentle.pixels[0])
        assertTrue("shadows lift a little, got $mid", mid in 129..150)
        assertEquals("lifting shadows is independent of the rolloff", mid, red(hard.pixels[0]))
    }

    @Test
    fun undersizedFramesAreRejectedRatherThanCrashing() {
        val width = 24
        val height = 24
        val base = texture(width, height, 17)
        val result = NightMerger.merge(listOf(base, IntArray(8)), width, height, maxShift = 4)
        assertNotNull(result)
        requireNotNull(result)
        assertEquals(1, result.usedFrames)
        assertEquals(1, result.rejectedFrames)
        assertChannel(NightMerger.toneMapByte(red(base[10]), roll), red(result.pixels[10]), "reference frame kept")
    }

    @Test
    fun alphaIsCarriedOverFromTheReferenceFrame() {
        val width = 4
        val height = 4
        // The reference has one see-through pixel; the frame averaged into it
        // is opaque. Output alpha follows the reference, not the contributor.
        val reference = IntArray(width * height) { i ->
            if (i == 5) rgb(200, 100, 50) and 0x00FFFFFF else rgb(200, 100, 50)
        }
        val other = IntArray(reference.size) { rgb(30, 60, 90) }

        val result = NightMerger.merge(listOf(reference, other), width, height, maxShift = 2)
        assertNotNull(result)
        requireNotNull(result)

        assertEquals(0x00, result.pixels[5] ushr 24)
        assertEquals(0xFF, result.pixels[6] ushr 24)
        // Colour still averages both frames.
        // Frames are averaged in linear light, not by averaging encoded sRGB
        // bytes; 200 and 30 therefore produce about 152 before tone mapping.
        val expected = NightMerger.linearAverageToneMapByte(200, 30, roll)
        assertTrue("averaged red ${red(result.pixels[6])} near $expected", abs(red(result.pixels[6]) - expected) <= 1)
    }
}
