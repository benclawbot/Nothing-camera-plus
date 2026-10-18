package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold-shutter burst policy: bounded to the original's ten-frame limit. */
class ContinuousShotPlanTest {

    @Test
    fun capMatchesTheOriginalBurstLimit() {
        assertEquals(10, ContinuousShotPlan.MAX_SHOTS)
        assertTrue(ContinuousShotPlan.TRIGGER_INTERVAL_MS > 0)
    }

    @Test
    fun continuesWhileHeldBelowTheCap() {
        assertTrue(ContinuousShotPlan.shouldContinue(held = true, shotsTaken = 0))
        assertTrue(ContinuousShotPlan.shouldContinue(held = true, shotsTaken = 9))
    }

    @Test
    fun releaseEndsTheBurst() {
        assertFalse(ContinuousShotPlan.shouldContinue(held = false, shotsTaken = 3))
    }

    @Test
    fun theCapEndsTheBurst() {
        assertFalse(ContinuousShotPlan.shouldContinue(held = true, shotsTaken = 10))
        assertFalse(ContinuousShotPlan.shouldContinue(held = true, shotsTaken = 11))
    }

    @Test
    fun triggersOnlyWhenTheQueueHasRoom() {
        assertTrue(ContinuousShotPlan.canTrigger(inFlight = 0, maxInFlight = 4))
        assertTrue(ContinuousShotPlan.canTrigger(inFlight = 3, maxInFlight = 4))
        assertFalse(ContinuousShotPlan.canTrigger(inFlight = 4, maxInFlight = 4))
    }
}
