package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaGuideTest {

    @Test
    fun gyroIntegratedAlongGravityTracksRightwardSweep() {
        val guide = PanoramaGuide(forwardSign = -1f, targetSweepDegrees = 90f)
        guide.onAccelerometer(0f, -9.8f, 0f, 1_000_000_000L)
        guide.onGyro(0f, 0f, 0f, 1_000_000_000L)
        guide.onGyro(0f, -1.5707963f, 0f, 1_100_000_000L)

        val state = guide.state()
        assertEquals(-9f, state.yawDegrees, 0.2f)
        assertEquals(9f, state.forwardDegrees, 0.2f)
        assertEquals(0.1f, state.sweepProgress, 0.01f)
        assertEquals(PanoramaGuide.Hint.PAN, state.hint)
    }

    @Test
    fun reportsWrongDirectionAndTooFastMotion() {
        val guide = PanoramaGuide(forwardSign = -1f)
        guide.onAccelerometer(0f, -9.8f, 0f, 1_000_000_000L)
        guide.onGyro(0f, 0f, 0f, 1_000_000_000L)
        guide.onGyro(0f, 2f, 0f, 1_100_000_000L)
        assertEquals(PanoramaGuide.Hint.WRONG_WAY, guide.state().hint)

        guide.onGyro(0f, -4f, 0f, 1_200_000_000L)
        assertEquals(PanoramaGuide.Hint.TOO_FAST, guide.state().hint)
    }

    @Test
    fun latchesStartingPoseAndReportsLevelDeviation() {
        val guide = PanoramaGuide(forwardSign = -1f)
        var timestamp = 1_000_000_000L
        repeat(35) {
            guide.onAccelerometer(0f, -9.8f, 0f, timestamp)
            timestamp += 50_000_000L
        }
        assertTrue(guide.state().level)

        // Tilt gravity about device X by 25 degrees; allow the low-pass to
        // catch up while keeping the timestamps monotonic.
        val radians = Math.toRadians(25.0)
        val gy = (-9.8 * kotlin.math.cos(radians)).toFloat()
        val gz = (9.8 * kotlin.math.sin(radians)).toFloat()
        repeat(40) {
            guide.onAccelerometer(0f, gy, gz, timestamp)
            timestamp += 50_000_000L
        }
        assertFalse(guide.state().level)
        assertTrue(guide.state().tiltDegrees > PanoramaGuide.LEVEL_TOLERANCE_DEGREES)
        assertEquals(PanoramaGuide.Hint.HOLD_LEVEL, guide.state().hint)
    }

    @Test
    fun resetClearsProgressAndSensorState() {
        val guide = PanoramaGuide(forwardSign = -1f)
        guide.onGyro(0f, 0f, 0f, 1_000_000_000L)
        guide.onGyro(0f, -1f, 0f, 1_100_000_000L)
        assertTrue(guide.state().forwardDegrees > 0f)

        guide.reset()

        assertEquals(0f, guide.state().forwardDegrees, 0f)
        assertEquals(PanoramaGuide.Hint.READY, guide.state().hint)
    }
}
