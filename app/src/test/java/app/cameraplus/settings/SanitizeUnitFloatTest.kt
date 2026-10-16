package app.cameraplus.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizeUnitFloatTest {

    @Test
    fun clampsOutOfRangeValues() {
        assertEquals(0f, sanitizeUnitFloat(-0.5f), 0f)
        assertEquals(1f, sanitizeUnitFloat(1.75f), 0f)
    }

    @Test
    fun mapsNonFiniteValuesToNeutral() {
        assertEquals(0f, sanitizeUnitFloat(Float.NaN), 0f)
        assertEquals(0f, sanitizeUnitFloat(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, sanitizeUnitFloat(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun passesThroughValidValues() {
        assertEquals(0.42f, sanitizeUnitFloat(0.42f), 0f)
    }
}
