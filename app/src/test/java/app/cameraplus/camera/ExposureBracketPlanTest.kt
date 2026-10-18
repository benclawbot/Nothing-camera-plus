package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureBracketPlanTest {

    @Test
    fun defaultStepTurnsTwoEvIntoSixIndexes() {
        assertEquals(
            listOf(-6, 0, 6),
            ExposureBracketPlan.indexOffsets(listOf(-2f, 0f, 2f), 1f / 3f),
        )
    }

    @Test
    fun fiveShotKeepsItsStepsOnAHalfEvGrid() {
        assertEquals(
            listOf(-4, -2, 0, 2, 4),
            ExposureBracketPlan.indexOffsets(listOf(-2f, -1f, 0f, 1f, 2f), 0.5f),
        )
    }

    @Test
    fun aCoarseStepFoldsDuplicateIndexesAway() {
        // A camera whose step is 2 EV cannot tell -1 EV from 0 EV.
        assertEquals(
            listOf(0, 1),
            ExposureBracketPlan.indexOffsets(listOf(-1f, 0f, 1f), 2f),
        )
    }

    @Test
    fun noOffsetsOrABadStepBracketNothing() {
        assertTrue(ExposureBracketPlan.indexOffsets(emptyList(), 1f / 3f).isEmpty())
        assertTrue(ExposureBracketPlan.indexOffsets(listOf(-1f, 1f), 0f).isEmpty())
        assertTrue(ExposureBracketPlan.indexOffsets(listOf(-1f, 1f), Float.NaN).isEmpty())
    }

    @Test
    fun theOffPresetBracketsNothingAndTheOthersDo() {
        assertTrue(ExposureBracket.OFF.evOffsets.isEmpty())
        assertEquals(false, ExposureBracket.OFF.enabled)
        assertEquals(3, ExposureBracket.THREE_SHOT.evOffsets.size)
        assertEquals(5, ExposureBracket.FIVE_SHOT.evOffsets.size)
        assertTrue(ExposureBracket.THREE_SHOT.enabled)
    }

    @Test
    fun rawModeCyclesThroughItsThreeStates() {
        assertEquals(RawMode.RAW_JPEG, RawMode.OFF.next())
        assertEquals(RawMode.RAW_ONLY, RawMode.RAW_JPEG.next())
        assertEquals(RawMode.OFF, RawMode.RAW_ONLY.next())
        assertEquals(false, RawMode.OFF.enabled)
        assertTrue(RawMode.RAW_ONLY.enabled)
    }
}
