package app.cameraplus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GhostImageTest {

    @Test
    fun aSmallShotIsNotDownsampled() {
        assertEquals(1, ghostSampleSize(1_000, 750, 1_440))
    }

    @Test
    fun aFiftyMegapixelShotIsHalvedUntilItNearlyFits() {
        // 8160x6120 (50 MP) -> /4 = 2040x1530. Halving again would drop the
        // long side below the 1440 limit, so /4 is where it stops.
        assertEquals(4, ghostSampleSize(8_160, 6_120, 1_440))
    }

    @Test
    fun exactlyDoubleTheLimitStillFits() {
        assertEquals(2, ghostSampleSize(2_880, 2_160, 1_440))
    }

    @Test
    fun nonsenseDimensionsFallBackToNoDownsample() {
        assertEquals(1, ghostSampleSize(0, 100, 1_440))
        assertEquals(1, ghostSampleSize(100, 100, 0))
    }
}
