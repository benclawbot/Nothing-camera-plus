package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocalPresetsTest {

    @Test
    fun convertsPresetsAgainstTheReferenceLens() {
        // A 24 mm reference lens: 28 mm is ~1.17x, 35 mm ~1.46x.
        assertEquals(28f / 24f, FocalPresets.displayedZoomFor(28, 24f, 1f, 8f)!!, 0.001f)
        assertEquals(35f / 24f, FocalPresets.displayedZoomFor(35, 24f, 1f, 8f)!!, 0.001f)
    }

    @Test
    fun clampsToTheSessionRange() {
        assertEquals(1f, FocalPresets.displayedZoomFor(24, 26f, 1f, 8f)!!, 0.001f)
        assertEquals(35f / 24f, FocalPresets.displayedZoomFor(35, 24f, 1f, 2f)!!, 0.001f)
    }

    @Test
    fun anUnknownReferenceFocalAsksTheCallerToSaySo() {
        assertNull(FocalPresets.displayedZoomFor(28, 0f, 1f, 8f))
        assertNull(FocalPresets.displayedZoomFor(28, Float.NaN, 1f, 8f))
        assertNull(FocalPresets.displayedZoomFor(0, 24f, 1f, 8f))
    }

    @Test
    fun aTapOnTheActiveChipStepsThrough28And35ThenBack() {
        assertEquals(28, FocalPresets.next(FocalPresets.AUTO))
        assertEquals(28, FocalPresets.next(24))
        assertEquals(35, FocalPresets.next(28))
        assertEquals(FocalPresets.AUTO, FocalPresets.next(35))
    }

    @Test
    fun theNearestPresetRecognisesALandedFocal() {
        assertEquals(28, FocalPresets.nearestPreset(27.4f))
        assertEquals(35, FocalPresets.nearestPreset(35f))
        assertEquals(FocalPresets.AUTO, FocalPresets.nearestPreset(31f))
        assertEquals(FocalPresets.AUTO, FocalPresets.nearestPreset(0f))
        assertEquals(FocalPresets.AUTO, FocalPresets.nearestPreset(Float.NaN))
    }

    @Test
    fun labelsRoundTripThroughTheSetting() {
        assertEquals("AUTO", FocalPresets.labelFor(FocalPresets.AUTO))
        assertEquals("28 MM", FocalPresets.labelFor(28))
        assertEquals(FocalPresets.AUTO, FocalPresets.fromLabel("AUTO"))
        assertEquals(35, FocalPresets.fromLabel("35 MM"))
        assertEquals(FocalPresets.AUTO, FocalPresets.fromLabel("50 MM"))
    }
}
