package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlPlanTest {

    @Test
    fun zeroGaugeMeansAuto() {
        assertNull(ManualControlPlan.isoFor(0f, 100..3200))
        assertNull(ManualControlPlan.exposureFor(0f, 1_000L..100_000_000L))
        assertNull(ManualControlPlan.focusFor(0f, 10f))
    }

    @Test
    fun fullGaugeReachesTheRangeEnd() {
        assertEquals(3200, ManualControlPlan.isoFor(1f, 100..3200))
        assertEquals(
            100_000_000L,
            ManualControlPlan.exposureFor(1f, 1_000L..100_000_000L),
        )
    }

    @Test
    fun gaugesAreLogarithmicAndMonotonic() {
        val lowIso = ManualControlPlan.isoFor(0.25f, 100..3200)!!
        val midIso = ManualControlPlan.isoFor(0.5f, 100..3200)!!
        val highIso = ManualControlPlan.isoFor(0.75f, 100..3200)!!
        assertTrue(lowIso < midIso && midIso < highIso)
        // Halfway on a log scale is the geometric mean of the range.
        assertEquals(566, midIso)

        val lowExposure = ManualControlPlan.exposureFor(0.25f, 1_000L..1_000_000L)!!
        val highExposure = ManualControlPlan.exposureFor(0.75f, 1_000L..1_000_000L)!!
        assertTrue(lowExposure < highExposure)
    }

    @Test
    fun focusIsALinearFractionOfTheMinimumFocusDistance() {
        assertEquals(5f, ManualControlPlan.focusFor(0.5f, 10f)!!, 0.0001f)
        assertEquals(10f, ManualControlPlan.focusFor(1f, 10f)!!, 0.0001f)
        assertEquals(10f, ManualControlPlan.focusFor(2f, 10f)!!, 0.0001f)
        assertNull(ManualControlPlan.focusFor(0.5f, 0f))
    }

    @Test
    fun autoIsTheDefaultIntent() {
        assertTrue(ManualControls().isAuto())
        assertTrue(!ManualControls(iso = 400).isAuto())
    }
}
