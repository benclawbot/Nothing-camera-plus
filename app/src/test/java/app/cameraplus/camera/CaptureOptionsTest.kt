package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOptionsTest {

    @Test
    fun selfTimerCyclesThroughOffThreeAndTen() {
        var timer = SelfTimer.OFF
        assertFalse(timer.enabled)
        timer = timer.next()
        assertEquals(SelfTimer.THREE_SECONDS, timer)
        assertTrue(timer.enabled)
        timer = timer.next()
        assertEquals(SelfTimer.TEN_SECONDS, timer)
        timer = timer.next()
        assertEquals(SelfTimer.OFF, timer)
    }

    @Test
    fun videoQualityKeepsRequestedTierWhenSupported() {
        assertEquals(
            VideoQuality.UHD,
            VideoQuality.resolve(VideoQuality.UHD, setOf(VideoQuality.HD, VideoQuality.FHD, VideoQuality.UHD)),
        )
    }

    @Test
    fun videoQualityFallsDownWhenTierIsUnsupported() {
        assertEquals(
            VideoQuality.FHD,
            VideoQuality.resolve(VideoQuality.UHD, setOf(VideoQuality.HD, VideoQuality.FHD)),
        )
    }

    @Test
    fun videoQualityNeverGoesBelowTheLowestSupported() {
        assertEquals(
            VideoQuality.HD,
            VideoQuality.resolve(VideoQuality.FHD, setOf(VideoQuality.HD)),
        )
    }

    @Test
    fun videoQualityDefaultsToFullHdWhenNothingIsKnown() {
        assertEquals(VideoQuality.FHD, VideoQuality.resolve(VideoQuality.UHD, emptySet()))
    }

    @Test
    fun videoQualityParsesStockLabels() {
        assertEquals(VideoQuality.FHD, VideoQuality.fromLabel("1080p"))
        assertEquals(VideoQuality.UHD, VideoQuality.fromLabel("4K"))
        assertNull(VideoQuality.fromLabel("8K"))
    }

    @Test
    fun captureTimerFormatsMinutesAndSeconds() {
        assertEquals("00:00", CaptureTimer.format(0L))
        assertEquals("00:07", CaptureTimer.format(7_400L))
        assertEquals("01:05", CaptureTimer.format(65_000L))
        assertEquals("10:00", CaptureTimer.format(600_000L))
    }

    @Test
    fun selfTimerRemainingCountsDownAndNeverGoesNegative() {
        val start = 1_000L
        assertEquals(3, CaptureTimer.remaining(3, start, start + 500L))
        assertEquals(1, CaptureTimer.remaining(3, start, start + 2_500L))
        assertEquals(0, CaptureTimer.remaining(3, start, start + 9_000L))
    }

    @Test
    fun flashCycleIncludesOffAutoAndOn() {
        assertEquals(FlashChoice.AUTO, FlashChoice.OFF.next())
        assertEquals(FlashChoice.ON, FlashChoice.AUTO.next())
        assertEquals(FlashChoice.OFF, FlashChoice.ON.next())
    }

    @Test
    fun aspectRatioEntriesMatchStockLabelsAndExactFractions() {
        assertEquals("20.5:9", PhotoAspectRatio.TWENTY_POINT_FIVE_NINE.label)
        assertEquals(41, PhotoAspectRatio.TWENTY_POINT_FIVE_NINE.numerator)
        assertEquals(18, PhotoAspectRatio.TWENTY_POINT_FIVE_NINE.denominator)
        assertEquals(1, PhotoAspectRatio.SQUARE.numerator)
        assertEquals(1, PhotoAspectRatio.SQUARE.denominator)
    }

    @Test
    fun videoFrameRateCyclesOnlyAmongSupportedChoices() {
        assertEquals(
            VideoFrameRate.FPS_60,
            VideoFrameRate.FPS_30.next(listOf(VideoFrameRate.FPS_30, VideoFrameRate.FPS_60)),
        )
        assertEquals(
            VideoFrameRate.FPS_30,
            VideoFrameRate.FPS_30.next(listOf(VideoFrameRate.FPS_30)),
        )
    }
}
