package app.cameraplus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The shortcut intent contract: mode names and facing values from extras. */
class ModeShortcutTest {

    @Test
    fun parsesEveryModeName() {
        CameraMode.entries.forEach { mode ->
            assertEquals(
                ModeShortcut.Selection(mode, null),
                ModeShortcut.fromExtras(mode.name, null),
            )
        }
    }

    @Test
    fun modeNamesAreCaseAndSpaceTolerant() {
        assertEquals(CameraMode.VIDEO, ModeShortcut.fromExtras(" video ", null)?.mode)
        assertEquals(CameraMode.NIGHT, ModeShortcut.fromExtras("Night", null)?.mode)
    }

    @Test
    fun parsesFacingValues() {
        assertEquals(ModeShortcut.Facing.FRONT, ModeShortcut.fromExtras("PHOTO", "front")?.facing)
        assertEquals(ModeShortcut.Facing.BACK, ModeShortcut.fromExtras("PHOTO", " Back ")?.facing)
    }

    @Test
    fun unknownFacingLeavesTheCameraAsItIs() {
        assertEquals(
            ModeShortcut.Selection(CameraMode.PORTRAIT, null),
            ModeShortcut.fromExtras("PORTRAIT", "sideways"),
        )
    }

    @Test
    fun missingOrUnknownModeIsNotASelection() {
        assertNull(ModeShortcut.fromExtras(null, "front"))
        assertNull(ModeShortcut.fromExtras("LASAGNA", null))
        assertNull(ModeShortcut.selectionFor(null))
    }
}
