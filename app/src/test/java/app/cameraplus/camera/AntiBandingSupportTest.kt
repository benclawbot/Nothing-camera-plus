package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class AntiBandingSupportTest {

    @Test
    fun offersOnlyTheModesTheCameraAdvertises() {
        assertEquals(
            listOf(AntiBanding.AUTO, AntiBanding.HZ_50),
            AntiBandingSupport.available(reportsAuto = true, reports50 = true, reports60 = false),
        )
        assertEquals(
            listOf(AntiBanding.HZ_60),
            AntiBandingSupport.available(reportsAuto = false, reports50 = false, reports60 = true),
        )
    }

    @Test
    fun aCameraThatReportsNothingStillGetsAuto() {
        assertEquals(
            listOf(AntiBanding.AUTO),
            AntiBandingSupport.available(reportsAuto = false, reports50 = false, reports60 = false),
        )
    }

    @Test
    fun fiftyHertzComesBeforeSixty() {
        assertEquals(
            listOf(AntiBanding.AUTO, AntiBanding.HZ_50, AntiBanding.HZ_60),
            AntiBandingSupport.available(reportsAuto = true, reports50 = true, reports60 = true),
        )
    }

    @Test
    fun focusTimeoutSecondsMatchTheirLabels() {
        assertEquals(0, FocusTimeout.OFF.seconds)
        assertEquals(5, FocusTimeout.FIVE_SECONDS.seconds)
        assertEquals(15, FocusTimeout.FIFTEEN_SECONDS.seconds)
        assertEquals(30, FocusTimeout.THIRTY_SECONDS.seconds)
    }
}
