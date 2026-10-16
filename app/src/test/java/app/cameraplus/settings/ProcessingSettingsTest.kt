package app.cameraplus.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProcessingSettingsTest {

    @Test
    fun naturalPresetIsNeutral() {
        val natural = ProcessingSettings.NATURAL
        assertEquals(0f, natural.sharpening, 0f)
        assertEquals(0f, natural.noiseReduction, 0f)
        assertEquals(0f, natural.hdr, 0f)
        assertEquals(0f, natural.skinSmoothing, 0f)
    }

    @Test
    fun rejectsValuesOutsideRange() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessingSettings(sharpening = 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessingSettings(skinSmoothing = -0.25f)
        }
    }

    @Test
    fun acceptsRangeBoundaries() {
        val maxed = ProcessingSettings(
            sharpening = 1f,
            noiseReduction = 1f,
            hdr = 1f,
            skinSmoothing = 1f,
        )
        assertEquals(1f, maxed.hdr, 0f)
        assertEquals(1f, maxed.noiseReduction, 0f)
    }
}
