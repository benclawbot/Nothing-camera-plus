package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLevelPlanTest {
    @Test
    fun correctionCancelsSmallRollAndIsCapped() {
        assertEquals(-4f, AutoLevelPlan.correctionDegrees(4f), 0f)
        assertEquals(4f, AutoLevelPlan.correctionDegrees(-4f), 0f)
        assertEquals(-8f, AutoLevelPlan.correctionDegrees(12f), 0f)
    }

    @Test
    fun missingNonFiniteAndLargeRollsAreIgnored() {
        assertEquals(0f, AutoLevelPlan.correctionDegrees(null), 0f)
        assertEquals(0f, AutoLevelPlan.correctionDegrees(Float.NaN), 0f)
        assertEquals(0f, AutoLevelPlan.correctionDegrees(90f), 0f)
    }

    @Test
    fun outputScaleDoesNotUpscaleLevelFrames() {
        assertEquals(1f, AutoLevelPlan.fillScale(4000, 3000, 0f), 0f)
        assertTrue(AutoLevelPlan.fillScale(4000, 3000, 5f) > 1f)
    }
}
