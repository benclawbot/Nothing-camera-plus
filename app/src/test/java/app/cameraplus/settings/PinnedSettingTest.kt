package app.cameraplus.settings

import app.cameraplus.camera.PhotoAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedSettingTest {

    @Test
    fun cyclesThroughEveryChoiceAndBackToOff() {
        var setting = PinnedSetting.NONE
        for (expected in PinnedSetting.entries.drop(1) + PinnedSetting.NONE) {
            setting = setting.next()
            assertEquals(expected, setting)
        }
    }

    @Test
    fun everyChoiceHasALabel() {
        assertTrue(PinnedSetting.entries.all { it.label.isNotBlank() })
        assertEquals("OFF", PinnedSetting.NONE.label)
    }

    @Test
    fun nextEntryWrapsAroundItsEnumeration() {
        assertEquals(PhotoAspectRatio.SIXTEEN_NINE, PhotoAspectRatio.FOUR_THREE.nextEntry())
        assertEquals(PhotoAspectRatio.SQUARE, PhotoAspectRatio.entries.last().nextEntry())
    }
}
