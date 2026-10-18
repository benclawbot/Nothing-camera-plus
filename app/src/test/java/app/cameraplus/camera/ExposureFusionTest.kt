package app.cameraplus.camera

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureFusionTest {
    @Test
    fun bracketFramesFuseToOneBoundedSdrImage() {
        val width = 16
        val height = 16
        val exposures = listOf(45, 90, 155)
        val frames = exposures.map { value -> IntArray(width * height) { rgb(value, value, value) } }
        val merged = ExposureFusion.merge(frames, listOf(-2f, 0f, 2f), width, height)
        assertNotNull(merged)
        requireNotNull(merged)
        assertTrue(merged.all { (it ushr 24) == 0xFF })
        val gray = merged[width * height / 2] and 0xFF
        assertTrue("merged SDR value $gray should remain visible", gray in 1..254)
    }

    @Test
    fun malformedAndSingleFrameInputsFallBack() {
        assertTrue(ExposureFusion.merge(listOf(IntArray(4)), listOf(0f), 2, 2) == null)
        assertTrue(
            ExposureFusion.merge(
                listOf(IntArray(4), IntArray(1)),
                listOf(0f, 1f),
                2,
                2,
            ) == null,
        )
    }

    @Test
    fun oneExposureMotionOutlierIsRejectedInsteadOfGhostedIntoTheMerge() {
        val width = 16
        val height = 16
        val calm = IntArray(width * height) { rgb(100, 100, 100) }
        val moving = calm.copyOf()
        for (y in 4 until 12) {
            for (x in 4 until 12) moving[y * width + x] = rgb(240, 240, 240)
        }
        val merged = requireNotNull(
            ExposureFusion.merge(listOf(calm, calm.copyOf(), moving), listOf(0f, 0f, 0f), width, height),
        )
        val ghostRegion = merged[8 * width + 8] and 0xFF
        assertTrue("motion outlier should be rejected; got $ghostRegion", ghostRegion < 140)
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
