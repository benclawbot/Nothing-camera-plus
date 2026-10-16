package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PortraitRendererTest {

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF

    /** Checkerboard, the sharpest thing a synthetic frame can contain. */
    private fun checkerboard(width: Int, height: Int, cell: Int = 4): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val v = if (((x / cell) + (y / cell)) % 2 == 0) 40 else 210
            rgb(v, v, v)
        }

    /** Local standard deviation, i.e. how much detail survives here. */
    private fun localContrast(pixels: IntArray, width: Int, x0: Int, y0: Int, size: Int): Double {
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in y0 until y0 + size) {
            for (x in x0 until x0 + size) {
                val v = red(pixels[y * width + x]).toDouble()
                sum += v
                sumSquares += v * v
                count++
            }
        }
        val mean = sum / count
        return sqrt(maxOf(0.0, sumSquares / count - mean * mean))
    }

    @Test
    fun noBlurRadiusLeavesTheFrameAlone() {
        val pixels = checkerboard(48, 48)
        val original = pixels.copyOf()
        val shape = PortraitRenderer.defaultShape(48, 48, 0.5f)

        assertTrue(PortraitRenderer.blurBackground(pixels, 48, 48, shape, 0).contentEquals(original))
        assertTrue(PortraitRenderer.blurBackground(pixels, 48, 48, shape, -4).contentEquals(original))
    }

    @Test
    fun backgroundLosesContrastWhileTheSubjectKeepsIt() {
        val width = 96
        val height = 96
        val shape = BokehShape(
            centerXRatio = 0.5f,
            centerYRatio = 0.5f,
            radiusXRatio = 0.40f,
            radiusYRatio = 0.45f,
            featherRatio = 0.25f,
        )
        // A checkerboard with a flat square dropped in the middle: the "subject"
        // marker. Detail next to the square is still inside the sharp ellipse,
        // detail in the corner is fully blurred.
        val pixels = checkerboard(width, height)
        for (y in 40 until 68) {
            for (x in 40 until 68) pixels[y * width + x] = rgb(128, 128, 128)
        }

        val blurred = PortraitRenderer.blurBackground(pixels, width, height, shape, 14)

        val sharpBefore = localContrast(pixels, width, 40, 68, 8)
        val sharpAfter = localContrast(blurred, width, 40, 68, 8)
        val cornerBefore = localContrast(pixels, width, 2, 2, 8)
        val cornerAfter = localContrast(blurred, width, 2, 2, 8)

        assertTrue("subject detail should survive, $sharpBefore -> $sharpAfter", sharpAfter > sharpBefore * 0.9)
        assertTrue("background detail should collapse, $cornerBefore -> $cornerAfter", cornerAfter < cornerBefore * 0.25)
        assertTrue(sharpAfter > cornerAfter * 3f)
    }

    @Test
    fun insideTheEllipseNotOnePixelChanges() {
        val width = 64
        val height = 64
        val shape = BokehShape(0.5f, 0.5f, 0.35f, 0.35f, 0.2f)
        val pixels = checkerboard(width, height, 3)

        val blurred = PortraitRenderer.blurBackground(pixels, width, height, shape, 9)

        var checkedInside = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (PortraitRenderer.blendFactor(shape, width, height, x, y) != 0f) continue
                assertEquals("($x, $y) is inside the subject", pixels[y * width + x], blurred[y * width + x])
                checkedInside++
            }
        }
        assertTrue("the test should actually cover the subject", checkedInside > 1000)
    }

    @Test
    fun alphaIsAlwaysOpaque() {
        val width = 32
        val height = 32
        val pixels = checkerboard(width, height)
        pixels[0] = rgb(200, 10, 10) and 0x00FFFFFF
        pixels[1] = 0

        val blurred = PortraitRenderer.blurBackground(pixels, width, height, PortraitRenderer.defaultShape(32, 32, 0f), 6)

        for (i in blurred.indices) {
            assertEquals("pixel $i must be opaque", 0xFF, blurred[i] ushr 24)
        }
    }

    @Test
    fun theFeatherIsMonotonicWithNoHardStep() {
        val width = 96
        val height = 96
        val shape = BokehShape(0.5f, 0.5f, 0.3f, 0.3f, 0.25f)

        // Walk a ray straight out from the centre: the blend factor may only
        // ever grow, and it has to travel through the middle values rather
        // than jumping from sharp to blurred in one pixel.
        val samples = (0 until 48).map { x ->
            PortraitRenderer.blendFactor(shape, width, height, width / 2 + x, height / 2)
        }

        assertEquals("the centre is fully sharp", 0f, samples.first(), 0f)
        assertEquals("the far edge is fully blurred", 1f, samples.last(), 1e-4f)

        var largestStep = 0f
        for (i in 1 until samples.size) {
            val step = samples[i] - samples[i - 1]
            assertTrue("blend must not fall at step $i: $step", step >= -1e-5f)
            if (step > largestStep) largestStep = step
        }
        assertTrue("a hard edge would jump in one sample, largest step was $largestStep", largestStep < 0.5f)

        val middling = samples.count { it > 0.05f && it < 0.95f }
        assertTrue("the feather should pass through partial blur, got $middling samples", middling >= 2)
    }

    @Test
    fun theFeatherBandIsWhereItSaysItIs() {
        val width = 100
        val height = 100
        val shape = BokehShape(0.5f, 0.5f, 0.4f, 0.4f, 0.5f)
        val y = height / 2

        // Edge of the ellipse itself: still sharp.
        assertEquals(0f, PortraitRenderer.blendFactor(shape, width, height, 89, y), 0f)
        // Half way through the feather band: half blurred.
        val middle = PortraitRenderer.blendFactor(shape, width, height, 100, y)
        assertTrue("mid feather should be mid blur, got $middle", middle > 0.2f && middle < 0.8f)
        // Twice the radius, i.e. past 1 + feather: fully blurred.
        assertEquals(1f, PortraitRenderer.blendFactor(shape, width, height, 121, y), 0f)
    }

    @Test
    fun anIsolatedBrightPixelSpreadsIntoItsNeighbours() {
        val width = 33
        val height = 33
        val pixels = IntArray(width * height)
        pixels[4 * width + 4] = rgb(255, 255, 255)
        val shape = BokehShape(0.5f, 0.5f, 0.08f, 0.08f, 0.3f)

        val blurred = PortraitRenderer.blurBackground(pixels, width, height, shape, 6)

        assertTrue("the spike should be pulled down, got ${red(blurred[4 * width + 4])}", red(blurred[4 * width + 4]) < 200)
        assertTrue("and it should have spread", red(blurred[4 * width + 7]) > 0)
    }

    @Test
    fun aLargerBokehLevelGivesALargerSubjectAndIsClamped() {
        val low = PortraitRenderer.defaultShape(1080, 1920, 0f)
        val half = PortraitRenderer.defaultShape(1080, 1920, 0.5f)
        val high = PortraitRenderer.defaultShape(1080, 1920, 1f)

        assertTrue("subject grows", low.radiusXRatio < half.radiusXRatio)
        assertTrue("subject grows", half.radiusXRatio < high.radiusXRatio)
        assertTrue("subject grows", low.radiusYRatio < high.radiusYRatio)
        assertTrue("a bigger subject gets a tighter feather", high.featherRatio < low.featherRatio)
        assertTrue("a shape must actually cover something", low.radiusXRatio > 0f)
        assertTrue(low.featherRatio > 0f)

        assertEquals(low, PortraitRenderer.defaultShape(1080, 1920, -3f))
        assertEquals(high, PortraitRenderer.defaultShape(1080, 1920, 7f))
    }

    @Test
    fun theSameLevelKeepsTheSameFramingAtAnyResolution() {
        // Same aspect, double the size: ratios are resolution independent, so
        // the same share of the frame must come through sharp and unblurred.
        fun sharpFraction(width: Int, height: Int): Float {
            val pixels = checkerboard(width, height, cell = 3)
            val blurred = PortraitRenderer.blurBackground(
                pixels, width, height, PortraitRenderer.defaultShape(width, height, 0.5f), 6,
            )
            var unchanged = 0
            for (i in pixels.indices) if (pixels[i] == blurred[i]) unchanged++
            return unchanged.toFloat() / pixels.size
        }

        val small = sharpFraction(54, 96)
        val large = sharpFraction(108, 192)
        assertTrue("small $small vs large $large", abs(small - large) < 0.02f)
    }
}
