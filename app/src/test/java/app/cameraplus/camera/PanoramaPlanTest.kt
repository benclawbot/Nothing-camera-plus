package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the sweep maths: how many frames a panorama needs, how far the
 * camera has to turn between them, and how that maps onto canvas offsets.
 *
 * The invalid-input tests matter as much as the arithmetic here: a field of
 * view of zero or an overlap of 1.0 is what a half-initialised camera
 * characteristic or a bad settings value looks like, and it must fail loudly
 * at the plan rather than silently produce a one-frame "panorama".
 */
class PanoramaPlanTest {

    // ------------------------------------------------------ frame counts

    @Test
    fun recommendedFramesCoversTheTargetSweep() {
        // 20 degrees per frame at 30% overlap: 14 degrees of new scene per
        // step, so five frames reach 76 degrees.
        assertEquals(5, PanoramaPlan.recommendedFrames(20f, overlap = 0.3f))
        assertTrue(
            PanoramaPlan.maxSweepDegrees(5, 20f, 0.3f) >= PanoramaPlan.DEFAULT_TARGET_SWEEP_DEGREES,
        )
    }

    @Test
    fun recommendedFramesIsAtLeastTwoEvenWhenOneFrameAlreadyCoversTheTarget() {
        // A 75 degree field of view covers the whole target sweep on its own,
        // but one frame is a photo, not a panorama.
        assertEquals(2, PanoramaPlan.recommendedFrames(75f, overlap = 0.3f))
        assertEquals(2, PanoramaPlan.recommendedFrames(140f, overlap = 0.3f))
    }

    @Test
    fun recommendedFramesTracksTheFieldOfViewAcrossRealisticCameras() {
        // Wide main lens: two or three frames. 2x tele: many more, because the
        // sweep angle per frame is proportionally smaller.
        val main = PanoramaPlan.recommendedFrames(70f, overlap = 0.3f)
        val ultrawide = PanoramaPlan.recommendedFrames(120f, overlap = 0.3f)
        val tele = PanoramaPlan.recommendedFrames(35f, overlap = 0.3f)

        assertTrue("main was $main", main in 2..3)
        assertTrue("ultrawide was $ultrawide", ultrawide <= main)
        assertTrue("tele was $tele", tele > main)
    }

    @Test
    fun everyRecommendedFrameCountActuallyReachesTheTarget() {
        for (fov in floatArrayOf(24f, 35f, 52f, 70f, 90f, 120f)) {
            val frames = PanoramaPlan.recommendedFrames(fov, overlap = 0.3f)
            val covered = PanoramaPlan.maxSweepDegrees(frames, fov, overlap = 0.3f)
            assertTrue("fov $fov: $frames frames cover only $covered", covered >= 75f)
            // And it does not wildly over-shoot: the worst case is the two
            // frame floor on a lens wider than the target sweep.
            val cadence = PanoramaPlan.captureCadenceDegrees(fov, overlap = 0.3f)
            val bound = maxOf(fov, PanoramaPlan.DEFAULT_TARGET_SWEEP_DEGREES) + cadence
            assertTrue("fov $fov overshot to $covered (bound $bound)", covered <= bound + 1e-3f)
        }
    }

    @Test
    fun recommendedFramesIsCappedForAnAbsurdlyNarrowFieldOfView() {
        // 1 degree per frame at 10% overlap would want thousands of frames;
        // the cap is what keeps the canvas memory bounded.
        assertEquals(PanoramaPlan.MAX_FRAMES, PanoramaPlan.recommendedFrames(1f, overlap = 0.9f))
    }

    @Test
    fun recommendedFramesRespectsTheOverlapArgument() {
        // More overlap means more frames for the same sweep.
        val light = PanoramaPlan.recommendedFrames(20f, overlap = 0.1f)
        val heavy = PanoramaPlan.recommendedFrames(20f, overlap = 0.5f)
        assertTrue("light was $light, heavy was $heavy", heavy > light)
    }

    // ------------------------------------------------------- sweep geometry

    @Test
    fun captureCadenceIsTheFieldOfViewMinusTheOverlap() {
        assertEquals(49f, PanoramaPlan.captureCadenceDegrees(70f, overlap = 0.3f), 1e-3f)
        assertEquals(21f, PanoramaPlan.captureCadenceDegrees(70f, overlap = 0.7f), 1e-3f)
    }

    @Test
    fun oneFrameCoversExactlyTheFieldOfView() {
        assertEquals(70f, PanoramaPlan.maxSweepDegrees(1, 70f, overlap = 0.3f), 1e-3f)
        assertEquals(70f, PanoramaPlan.maxSweepDegrees(1, 70f, overlap = 0.9f), 1e-3f)
    }

    @Test
    fun maxSweepAddsOneCadencePerExtraFrame() {
        val fov = 70f
        val cadence = PanoramaPlan.captureCadenceDegrees(fov, overlap = 0.3f)
        assertEquals(fov + 4 * cadence, PanoramaPlan.maxSweepDegrees(5, fov, overlap = 0.3f), 1e-3f)
        assertEquals(
            PanoramaPlan.maxSweepDegrees(4, fov, overlap = 0.3f) + cadence,
            PanoramaPlan.maxSweepDegrees(5, fov, overlap = 0.3f),
            1e-3f,
        )
    }

    @Test
    fun shiftFractionsBracketTheAllowedMovement() {
        // At 30% planned overlap the scene may move up to 70% of the frame
        // width per frame; 15 points of slack puts the believable floor at 55%
        // of the width. A slower pan than that is a duplicated frame.
        assertEquals(0.7f, PanoramaPlan.maxShiftFraction(0.3f), 1e-3f)
        assertEquals(0.55f, PanoramaPlan.minShiftFraction(0.3f), 1e-3f)
        assertEquals(0.1f, PanoramaPlan.minShiftFraction(0.3f, tolerance = 0.6f), 1e-3f)
        assertEquals(0f, PanoramaPlan.minShiftFraction(0.3f, tolerance = 0.9f), 1e-3f)
    }

    // ------------------------------------------------------------ direction

    @Test
    fun directionsCarryMirroredCanvasShiftSigns() {
        assertEquals(1, Direction.RIGHT.canvasShiftSign)
        assertEquals(-1, Direction.LEFT.canvasShiftSign)
    }

    @Test
    fun directionTogglesToItsOpposite() {
        assertEquals(Direction.RIGHT, Direction.LEFT.opposite)
        assertEquals(Direction.LEFT, Direction.RIGHT.opposite)
    }

    @Test
    fun directionIsReadBackOutOfAMeasuredOffset() {
        // Camera right: the new frame lands to the right of the canvas.
        assertEquals(Direction.RIGHT, Direction.fromShift(48))
        assertEquals(Direction.LEFT, Direction.fromShift(-48))
        assertNull("no movement is no direction", Direction.fromShift(0))
    }

    // ------------------------------------------------------ invalid input

    @Test
    fun aZeroFieldOfViewIsRejected() {
        expectIllegalArgument { PanoramaPlan.recommendedFrames(0f, overlap = 0.3f) }
        expectIllegalArgument { PanoramaPlan.recommendedFrames(-65f, overlap = 0.3f) }
        expectIllegalArgument { PanoramaPlan.recommendedFrames(Float.NaN, overlap = 0.3f) }
        expectIllegalArgument { PanoramaPlan.captureCadenceDegrees(0f) }
        expectIllegalArgument { PanoramaPlan.maxSweepDegrees(3, 0f, overlap = 0.3f) }
    }

    @Test
    fun anOverlapOutsideTheOpenIntervalIsRejected() {
        expectIllegalArgument { PanoramaPlan.recommendedFrames(70f, overlap = 0f) }
        expectIllegalArgument { PanoramaPlan.recommendedFrames(70f, overlap = 1f) }
        expectIllegalArgument { PanoramaPlan.recommendedFrames(70f, overlap = -0.2f) }
        expectIllegalArgument { PanoramaPlan.recommendedFrames(70f, overlap = 1.4f) }
        expectIllegalArgument { PanoramaPlan.recommendedFrames(70f, overlap = Float.NaN) }
        expectIllegalArgument { PanoramaPlan.captureCadenceDegrees(70f, overlap = 0f) }
        expectIllegalArgument { PanoramaPlan.maxShiftFraction(1f) }
        expectIllegalArgument { PanoramaPlan.minShiftFraction(0f) }
    }

    @Test
    fun aNonPositiveTargetOrFrameCountIsRejected() {
        expectIllegalArgument { PanoramaPlan.recommendedFrames(70f, overlap = 0.3f, targetSweepDegrees = 0f) }
        expectIllegalArgument { PanoramaPlan.maxSweepDegrees(0, 70f, overlap = 0.3f) }
        expectIllegalArgument { PanoramaPlan.maxSweepDegrees(-2, 70f, overlap = 0.3f) }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected the call to be rejected")
        } catch (expected: IllegalArgumentException) {
            // Exactly what PanoramaPlan documents.
        }
    }
}