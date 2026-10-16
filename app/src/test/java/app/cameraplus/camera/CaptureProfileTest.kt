package app.cameraplus.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProfileTest {

    @Test
    fun usesZeroShutterLagWhenSupportedAndFlashOff() {
        assertTrue(
            CaptureProfile.usesZeroShutterLag(zslSupported = true, flashEnabled = false)
        )
    }

    @Test
    fun fallsBackWhenFlashIsOn() {
        assertFalse(
            CaptureProfile.usesZeroShutterLag(zslSupported = true, flashEnabled = true)
        )
    }

    @Test
    fun fallsBackWhenUnsupported() {
        assertFalse(
            CaptureProfile.usesZeroShutterLag(zslSupported = false, flashEnabled = false)
        )
    }

    @Test
    fun fallsBackForRawCaptures() {
        assertFalse(
            CaptureProfile.usesZeroShutterLag(
                zslSupported = true,
                flashEnabled = false,
                rawEnabled = true,
            )
        )
    }

    @Test
    fun fallsBackForManualControls() {
        assertFalse(
            CaptureProfile.usesZeroShutterLag(
                zslSupported = true,
                flashEnabled = false,
                manualControlsActive = true,
            )
        )
    }

    @Test
    fun fallsBackForMaximumResolutionCapture() {
        assertFalse(
            CaptureProfile.usesZeroShutterLag(
                zslSupported = true,
                flashEnabled = false,
                highResolutionActive = true,
            ),
        )
    }
}
