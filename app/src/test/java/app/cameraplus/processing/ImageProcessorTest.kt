package app.cameraplus.processing

import app.cameraplus.settings.ProcessingSettings
import app.cameraplus.settings.PhotoFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageProcessorTest {

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF

    @Test
    fun naturalIsANoOp() {
        val pixels = intArrayOf(rgb(10, 20, 30), rgb(200, 100, 50))
        val original = pixels.copyOf()
        ImageProcessor.apply(pixels, 2, 1, ProcessingSettings.NATURAL)
        assertTrue(original.contentEquals(pixels))
    }

    @Test
    fun horizontalMirrorReversesEachRowWithoutReorderingRows() {
        val pixels = intArrayOf(1, 2, 3, 4, 5, 6)
        ImageProcessor.mirrorHorizontally(pixels, width = 3, height = 2)
        assertTrue(intArrayOf(3, 2, 1, 6, 5, 4).contentEquals(pixels))
    }

    @Test
    fun cleanRoomFiltersApplyDistinctColorLooks() {
        val source = intArrayOf(rgb(200, 80, 30))
        val monochrome = source.copyOf()
        ImageProcessor.apply(monochrome, 1, 1, ProcessingSettings(filter = PhotoFilter.MONOCHROME))
        val r = red(monochrome[0])
        assertEquals(r, (monochrome[0] ushr 8) and 0xFF)
        assertEquals(r, monochrome[0] and 0xFF)

        val warm = source.copyOf()
        ImageProcessor.apply(warm, 1, 1, ProcessingSettings(filter = PhotoFilter.WARM))
        assertTrue(red(warm[0]) > red(source[0]))
        assertTrue((warm[0] and 0xFF) < (source[0] and 0xFF))

        val cool = source.copyOf()
        ImageProcessor.apply(cool, 1, 1, ProcessingSettings(filter = PhotoFilter.COOL))
        assertTrue((cool[0] and 0xFF) > (source[0] and 0xFF))
    }

    @Test
    fun hdrLiftsShadowsAndKeepsWhite() {
        val pixels = intArrayOf(rgb(10, 10, 10), rgb(255, 255, 255))
        ImageProcessor.apply(pixels, 2, 1, ProcessingSettings(hdr = 1f))
        assertTrue("shadow pixel should lift", red(pixels[0]) > 10)
        assertEquals("white should stay white", 255, red(pixels[1]))
    }

    @Test
    fun noiseReductionPullsAnIsolatedPixelTowardItsNeighbours() {
        val dark = rgb(0, 0, 0)
        val bright = rgb(255, 255, 255)
        val pixels = intArrayOf(dark, dark, bright, dark, dark)
        ImageProcessor.apply(pixels, 5, 1, ProcessingSettings(noiseReduction = 1f))
        assertTrue("bright spike should dim", red(pixels[2]) < 255)
        assertTrue("neighbour should rise slightly", red(pixels[1]) > 0)
    }

    @Test
    fun skinSmoothingTouchesSkinTonesOnly() {
        val skinA = rgb(200, 150, 120)
        val skinB = rgb(180, 140, 110)
        val grayA = rgb(128, 128, 128)
        val grayB = rgb(90, 90, 90)
        val pixels = intArrayOf(skinA, skinB, grayA, grayB)
        ImageProcessor.apply(pixels, 4, 1, ProcessingSettings(skinSmoothing = 1f))
        assertNotEquals("skin pixel should smooth toward its neighbour", skinA, pixels[0])
        assertEquals("grey pixel stays untouched", grayA, pixels[2])
    }

    @Test
    fun sharpenRaisesContrastAcrossAnEdge() {
        val left = rgb(100, 100, 100)
        val right = rgb(140, 140, 140)
        val pixels = intArrayOf(left, left, left, right, right, right)
        ImageProcessor.apply(pixels, 6, 1, ProcessingSettings(sharpening = 1f))
        assertTrue("dark side of the edge should darken", red(pixels[2]) < 100)
        assertTrue("bright side of the edge should brighten", red(pixels[3]) > 140)
    }

    @Test
    fun tiledProcessingIsPixelIdenticalAcrossTileBoundariesAndImageEdges() {
        val width = 29
        val height = 37
        val source = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val r = (x * 17 + y * 29) and 0xFF
            val g = (x * 11 + y * 7) and 0xFF
            val b = (x * 3 + y * 19) and 0xFF
            rgb(r, g, b)
        }
        val settings = ProcessingSettings(
            sharpening = 0.7f,
            noiseReduction = 0.4f,
            hdr = 0.3f,
            skinSmoothing = 0.6f,
            filter = PhotoFilter.WARM,
        )
        val expected = source.copyOf()
        ImageProcessor.apply(expected, width, height, settings)
        val actual = IntArray(source.size)

        ImageProcessor.applyTiled(
            width = width,
            height = height,
            settings = settings,
            tileRows = 5,
            readRow = { row, destination, offset ->
                source.copyInto(destination, offset, row * width, (row + 1) * width)
            },
            writeRows = { firstRow, rowCount, processed, offset ->
                processed.copyInto(actual, firstRow * width, offset, offset + rowCount * width)
            },
        )

        val mismatches = expected.indices.filter { expected[it] != actual[it] }
        assertTrue(
            "tile halos must match full-frame convolution; mismatches=${mismatches.size}, " +
                "first=${mismatches.firstOrNull()?.let { "$it:${expected[it]}!=${actual[it]}" }}",
            mismatches.isEmpty(),
        )
    }
}
