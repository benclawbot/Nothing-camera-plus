package app.cameraplus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionGuidesTest {

    @Test
    fun cyclesThroughAllGuidesAndWraps() {
        assertEquals(CompositionGuides.THIRDS, CompositionGuides.NONE.next())
        assertEquals(CompositionGuides.CENTER, CompositionGuides.THIRDS.next())
        assertEquals(CompositionGuides.DIAGONALS, CompositionGuides.CENTER.next())
        assertEquals(CompositionGuides.NONE, CompositionGuides.DIAGONALS.next())
    }
}
