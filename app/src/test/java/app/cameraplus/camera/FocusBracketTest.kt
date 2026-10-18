package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusBracketTest {
    @Test
    fun diopterSweepRunsFromNearFocusToInfinity() {
        assertEquals(listOf(8f, 4f, 0f), FocusBracketPlan.diopters(8f, 3))
        assertEquals(listOf(4f, 3f, 2f, 1f, 0f), FocusBracketPlan.diopters(4f, 5))
    }

    @Test
    fun invalidRangesDoNotProduceAFocusSweep() {
        assertTrue(FocusBracketPlan.diopters(0f, 5).isEmpty())
        assertTrue(FocusBracketPlan.diopters(Float.NaN, 5).isEmpty())
        assertTrue(FocusBracketPlan.diopters(4f, 1).isEmpty())
    }

    @Test
    fun stackCombinesSharpRegionsFromDifferentFocusFrames() {
        val width = 64
        val height = 64
        val gray = 0xFF777777.toInt()
        val first = IntArray(width * height)
        val second = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val texture = if ((x + y) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                val index = y * width + x
                first[index] = if (x < width / 2) texture else gray
                second[index] = if (x >= width / 2) texture else gray
            }
        }
        val merged = requireNotNull(FocusStacker.merge(listOf(first, second), width, height))
        val firstEnergy = gradientEnergy(first, width, height)
        val secondEnergy = gradientEnergy(second, width, height)
        val mergedEnergy = gradientEnergy(merged, width, height)
        assertTrue("stack $mergedEnergy should preserve both focus regions", mergedEnergy > maxOf(firstEnergy, secondEnergy))
    }

    @Test
    fun oneUsableFrameIsNotAStack() {
        assertNull(FocusStacker.merge(listOf(IntArray(16)), 4, 4))
    }

    private fun gradientEnergy(pixels: IntArray, width: Int, height: Int): Long {
        var energy = 0L
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                energy += kotlin.math.abs(luma(pixels[i]) * 4 - (
                    luma(pixels[i - 1]) + luma(pixels[i + 1]) +
                        luma(pixels[i - width]) + luma(pixels[i + width])
                    )).toLong()
            }
        }
        return energy
    }

    private fun luma(pixel: Int): Int =
        (0.299f * ((pixel ushr 16) and 0xFF) +
            0.587f * ((pixel ushr 8) and 0xFF) +
            0.114f * (pixel and 0xFF)).toInt()
}
