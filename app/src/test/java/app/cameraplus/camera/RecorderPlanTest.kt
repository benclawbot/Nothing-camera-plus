package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Planning tests for the two modes CameraX cannot record. Everything here is
 * pure arithmetic, so the fixtures below are just what phone HALs actually
 * report: high-speed tables from a typical phone, and AE brackets from a
 * 1080p video stream.
 */
class RecorderPlanTest {

    /** What a mainstream phone lists for high speed: 720p and 1080p ladders. */
    private val phoneHighSpeed = listOf(
        HighSpeedOption(1280, 720, listOf(120..120, 240..240)),
        HighSpeedOption(1920, 1080, listOf(120..120, 240..240)),
    )

    /** A flagship-ish device that also does 480, plus a 4K high-speed entry. */
    private val fastPhoneHighSpeed = listOf(
        HighSpeedOption(1280, 720, listOf(240..240, 480..480)),
        HighSpeedOption(1920, 1080, listOf(240..240, 480..480)),
        HighSpeedOption(3840, 2160, listOf(120..120)),
    )

    // ---------------------------------------------------------- slow motion

    @Test
    fun slowMotionIsTheCaptureToPlaybackRatio() {
        val plan = RecorderPlan.slowMotionPlan(240)
        assertEquals(240, plan.captureFps)
        assertEquals(30, plan.playbackFps)
        assertEquals(8f, plan.slowdownFactor, 1e-4f)
    }

    @Test
    fun slowMotionPlaybackRateIsOverridable() {
        val plan = RecorderPlan.slowMotionPlan(captureFps = 480, playbackFps = 60)
        assertEquals(8f, plan.slowdownFactor, 1e-4f)
    }

    @Test
    fun slowMotionPlaybackLengthIsCaptureTimesSlowdown() {
        val plan = RecorderPlan.slowMotionPlan(240)
        // 6 s of recording becomes 48 s on screen at 240/30.
        assertEquals(48f, plan.playbackSeconds(6f), 1e-3f)
        assertEquals(0f, plan.playbackSeconds(0f), 1e-6f)
    }

    @Test
    fun slowMotionRejectsFpsBelowOne() {
        assertThrows { RecorderPlan.slowMotionPlan(0) }
        assertThrows { RecorderPlan.slowMotionPlan(-120) }
    }

    @Test
    fun slowMotionRejectsPlaybackFpsBelowOne() {
        assertThrows { RecorderPlan.slowMotionPlan(240, playbackFps = 0) }
        assertThrows { RecorderPlan.slowMotionPlan(240, playbackFps = -30) }
    }

    @Test
    fun slowMotionSlowdownIsAlwaysPositive() {
        // 1/1 is the degenerate but legal "no slowdown" case; nothing may
        // produce a zero or negative factor for valid rates.
        assertEquals(1f, RecorderPlan.slowMotionPlan(30).slowdownFactor, 1e-6f)
        assertTrue(RecorderPlan.slowMotionPlan(1000).slowdownFactor > 0f)
    }

    @Test
    fun slowMotionPlaybackSecondsRejectsNonsenseInput() {
        assertThrows { RecorderPlan.slowMotionPlan(240).playbackSeconds(-1f) }
        assertThrows { RecorderPlan.slowMotionPlan(240).playbackSeconds(Float.NaN) }
        assertThrows { RecorderPlan.slowMotionPlan(240).playbackSeconds(Float.POSITIVE_INFINITY) }
    }

    // ------------------------------------------------------------ time lapse

    @Test
    fun timeLapseSpeedUpIsIntervalTimesPlaybackRate() {
        // One frame every 2 s written at 30 fps: 60x, Camera+'s "X60" step.
        val plan = RecorderPlan.timeLapsePlan(intervalSeconds = 2f, realSeconds = 120f)
        assertEquals(0.5f, plan.captureFps, 1e-6f)
        assertEquals(60f, plan.speedUpFactor, 1e-3f)
        assertEquals(30, plan.playbackFps)
    }

    @Test
    fun timeLapseFrameCountIsRealTimeOverInterval() {
        val plan = RecorderPlan.timeLapsePlan(intervalSeconds = 0.5f, realSeconds = 30f)
        assertEquals(60, plan.expectedFrames)
        assertEquals(2f, plan.playbackSeconds, 1e-3f)
        assertEquals(30f, plan.coveredRealSeconds, 1e-3f)
    }

    @Test
    fun timeLapseFrameCountRoundsToWholeFrames() {
        // 10 s at a 0.3 s interval is 33.3 frames — the encoder writes 33.
        val plan = RecorderPlan.timeLapsePlan(intervalSeconds = 0.3f, realSeconds = 10f)
        assertEquals(33, plan.expectedFrames)
        // 1.4 s at 0.5 s is 2.8 frames, which rounds up to 3.
        assertEquals(3, RecorderPlan.timeLapsePlan(0.5f, realSeconds = 1.4f).expectedFrames)
        // Exactly on a frame boundary nothing is lost.
        assertEquals(8, RecorderPlan.timeLapsePlan(0.25f, realSeconds = 2f).expectedFrames)
    }

    @Test
    fun timeLapseShorterThanOneIntervalStillYieldsOneFrame() {
        val plan = RecorderPlan.timeLapsePlan(intervalSeconds = 4f, realSeconds = 1f)
        assertEquals(1, plan.expectedFrames)
    }

    @Test
    fun timeLapseWithNoRealTimePredictsNoFrames() {
        val plan = RecorderPlan.timeLapsePlan(intervalSeconds = 2f, realSeconds = 0f)
        assertEquals(0, plan.expectedFrames)
        assertEquals(0f, plan.playbackSeconds, 1e-6f)
    }

    @Test
    fun timeLapseFrameCountIsClampedForAbsurdDurations() {
        // A millisecond interval over ~28 hours would be 100 million frames.
        val plan = RecorderPlan.timeLapsePlan(intervalSeconds = 0.001f, realSeconds = 100_000f)
        assertEquals(RecorderPlan.MAX_EXPECTED_FRAMES, plan.expectedFrames)
    }

    @Test
    fun timeLapseRejectsZeroInterval() {
        // The division that zero interval would cause happens in captureFps.
        assertThrows { RecorderPlan.timeLapsePlan(intervalSeconds = 0f, realSeconds = 60f) }
        assertThrows { RecorderPlan.timeLapsePlan(intervalSeconds = -1f, realSeconds = 60f) }
    }

    @Test
    fun timeLapseRejectsNonFiniteInterval() {
        assertThrows { RecorderPlan.timeLapsePlan(Float.NaN, realSeconds = 60f) }
        assertThrows { RecorderPlan.timeLapsePlan(Float.POSITIVE_INFINITY, realSeconds = 60f) }
    }

    @Test
    fun timeLapseRejectsUnusablePlaybackAndRealTime() {
        assertThrows { RecorderPlan.timeLapsePlan(1f, playbackFps = 0, realSeconds = 60f) }
        assertThrows { RecorderPlan.timeLapsePlan(1f, realSeconds = -1f) }
        assertThrows { RecorderPlan.timeLapsePlan(1f, realSeconds = Float.NaN) }
    }

    // -------------------------------------------------------- supported rates

    @Test
    fun supportedRatesComeFromTheBracketsUpperEnd() {
        assertEquals(listOf(120, 240), RecorderPlan.supportedSlowMotionRates(listOf(240..240, 120..120)))
    }

    @Test
    fun supportedRatesAreSortedAndDistinct() {
        val rates = RecorderPlan.supportedSlowMotionRates(listOf(240..240, 120..120, 240..240))
        assertEquals(listOf(120, 240), rates)
    }

    @Test
    fun supportedRatesUseTheTopOfAVariableBracket() {
        // 120..240 can be driven at up to 240, so 240 is the rate to offer.
        assertEquals(listOf(120, 240), RecorderPlan.supportedSlowMotionRates(listOf(120..240)))
    }

    @Test
    fun supportedRatesDoNotOfferNormalPlaybackAsSlowMotion() {
        assertEquals(listOf(240), RecorderPlan.supportedSlowMotionRates(listOf(30..240)))
        assertEquals(emptyList<Int>(), RecorderPlan.supportedSlowMotionRates(listOf(30..30)))
    }

    @Test
    fun supportedRatesOfAnEmptyListAreEmpty() {
        assertEquals(emptyList<Int>(), RecorderPlan.supportedSlowMotionRates(emptyList()))
    }

    @Test
    fun supportedRatesDropDegenerateBrackets() {
        // A misbehaving HAL can report an empty range; 0 fps is not a rate.
        assertEquals(listOf(120), RecorderPlan.supportedSlowMotionRates(listOf(1..0, 120..120)))
        assertEquals(emptyList<Int>(), RecorderPlan.supportedSlowMotionRates(listOf(0..0)))
    }

    // ---------------------------------------------------- time-lapse interval

    @Test
    fun intervalIsTheInverseOfTheNearestBracket() {
        // 1080p video brackets on a typical phone.
        val brackets = listOf(15..15, 30..30, 60..60)
        assertEquals(1f / 30f, RecorderPlan.nextTimeLapseInterval(brackets, 30)!!, 1e-6f)
        assertEquals(1f / 60f, RecorderPlan.nextTimeLapseInterval(brackets, 60)!!, 1e-6f)
    }

    @Test
    fun intervalUsesTheBracketThatContainsTheTarget() {
        val brackets = listOf(7..30, 30..60)
        // 20 fps is inside 7..30: the capture runs from the top of that bracket.
        assertEquals(1f / 30f, RecorderPlan.nextTimeLapseInterval(brackets, 20)!!, 1e-6f)
    }

    @Test
    fun intervalPrefersTheCloserOfTwoEquidistantBrackets() {
        val brackets = listOf(30..30, 60..60)
        // 45 fps is 15 away from each; the faster bracket wins, it is the one
        // that yields more frames.
        assertEquals(1f / 60f, RecorderPlan.nextTimeLapseInterval(brackets, 45)!!, 1e-6f)
    }

    @Test
    fun intervalIsNullWhenNoBracketContainsTheTarget() {
        val brackets = listOf(30..30, 60..60)
        // 1 fps (a frame per second) is below every video bracket.
        assertNull(RecorderPlan.nextTimeLapseInterval(brackets, 1))
        // 240 fps is above every bracket.
        assertNull(RecorderPlan.nextTimeLapseInterval(brackets, 240))
    }

    @Test
    fun intervalIsNullForEmptyOrDegenerateInput() {
        assertNull(RecorderPlan.nextTimeLapseInterval(emptyList(), 30))
        assertNull(RecorderPlan.nextTimeLapseInterval(listOf(1..0), 30))
        // Below 1 fps the interval would be a minute or more.
        assertNull(RecorderPlan.nextTimeLapseInterval(listOf(30..30), 0))
        assertNull(RecorderPlan.nextTimeLapseInterval(listOf(30..30), -30))
    }

    // ------------------------------------------------------- nearest AE range

    @Test
    fun nearestRangePicksTheClosestBracket() {
        val brackets = listOf(7..30, 30..60)
        assertEquals(7..30, RecorderPlan.nearestFpsRange(brackets, 30f))
    }

    @Test
    fun nearestRangeFallsBackToTheSlowestForTimeLapseRates() {
        // A time-lapse rate sits below every bracket; the camera is driven at
        // its slowest one instead of being told it cannot record.
        val brackets = listOf(30..30, 60..60, 120..120)
        assertEquals(30..30, RecorderPlan.nearestFpsRange(brackets, 0.25f))
    }

    @Test
    fun nearestRangeIsNullWithoutUsableBrackets() {
        assertNull(RecorderPlan.nearestFpsRange(emptyList(), 30f))
        assertNull(RecorderPlan.nearestFpsRange(listOf(1..0), 30f))
    }

    // ----------------------------------------------------- high-speed sizing

    @Test
    fun largestHighSpeedSizeThatSupportsRateIsChosen() {
        assertEquals(1920, RecorderPlan.selectSlowMotionSize(phoneHighSpeed, 240)!!.width)
        // A 4K entry exists, but it does not advertise 240 fps.
        assertEquals(1920, RecorderPlan.selectSlowMotionSize(fastPhoneHighSpeed, 240)!!.width)
    }

    @Test
    fun fourK120IsUsedWhenTheCameraAdvertisesIt() {
        val option = requireNotNull(RecorderPlan.selectSlowMotionSize(fastPhoneHighSpeed, 120))
        assertEquals(3840, option.width)
        assertEquals(2160, option.height)
    }

    @Test
    fun sizesThatCannotDoTheRateAreSkipped() {
        // 720p here only reaches 240, so 480 has to come from 1080p.
        val options = listOf(
            HighSpeedOption(1280, 720, listOf(240..240)),
            HighSpeedOption(1920, 1080, listOf(240..240, 480..480)),
        )
        assertEquals(1920, RecorderPlan.selectSlowMotionSize(options, 480)!!.width)
    }

    @Test
    fun selectionIsNullWhenNothingSupportsTheRate() {
        assertNull(RecorderPlan.selectSlowMotionSize(phoneHighSpeed, 480))
        assertNull(RecorderPlan.selectSlowMotionSize(emptyList(), 240))
    }

    @Test
    fun selectionPrefersTheLargerFrameAtEqualSupport() {
        val options = listOf(
            HighSpeedOption(1280, 720, listOf(240..240)),
            HighSpeedOption(1920, 1080, listOf(240..240)),
        )
        assertEquals(1920, RecorderPlan.selectSlowMotionSize(options, 240)!!.width)
    }

    @Test
    fun selectionHonoursAnExplicitSizeCap() {
        val options = listOf(
            HighSpeedOption(1280, 720, listOf(240..240)),
            HighSpeedOption(1920, 1080, listOf(240..240)),
        )
        assertEquals(1280, RecorderPlan.selectSlowMotionSize(options, 240, maxWidth = 1280, maxHeight = 720)!!.width)
    }

    @Test
    fun selectionRejectsAFpsBelowOne() {
        assertThrows { RecorderPlan.selectSlowMotionSize(phoneHighSpeed, 0) }
    }

    @Test
    fun bracketForARatePrefersTheExactOne() {
        val option = HighSpeedOption(1920, 1080, listOf(120..240, 240..240))
        assertEquals(240..240, option.bestRange(240))
        // No fixed 200 bracket, so the variable one serves it.
        assertEquals(120..240, option.bestRange(200))
        assertNull(option.bestRange(300))
    }

    @Test
    fun optionReportsSupportAndNominalRates() {
        val option = HighSpeedOption(1280, 720, listOf(120..120, 240..240))
        assertTrue(option.supports(240))
        assertTrue(!option.supports(480))
        assertEquals(listOf(120, 240), option.nominalRates())
        assertEquals(1280 * 720, option.area)
    }

    // ---------------------------------------------------------------- bitrate

    @Test
    fun fhdBaselineBitrateIsTheDefault() {
        assertEquals(
            RecorderPlan.FHD_BASELINE_BITS_PER_SECOND,
            RecorderPlan.bitrateFor(1920, 1080, 30),
        )
    }

    @Test
    fun highSpeedBitrateScalesWithFrameRate() {
        val base = RecorderPlan.bitrateFor(1920, 1080, 30)
        // 240 fps is 8x the baseline frame rate, so ~8x the bitrate.
        assertEquals(base * 8.0, RecorderPlan.bitrateFor(1920, 1080, 240).toDouble(), 2.0)
        assertTrue(RecorderPlan.bitrateFor(1920, 1080, 480) > RecorderPlan.bitrateFor(1920, 1080, 240))
    }

    @Test
    fun bitrateScalesWithPixelCount() {
        val base = RecorderPlan.bitrateFor(1920, 1080, 30)
        assertEquals(base * (16.0 / 9.0), RecorderPlan.bitrateFor(2560, 1440, 30).toDouble(), 2.0)
    }

    @Test
    fun bitrateRejectsDegenerateGeometry() {
        assertThrows { RecorderPlan.bitrateFor(0, 1080, 30) }
        assertThrows { RecorderPlan.bitrateFor(1920, 0, 30) }
        assertThrows { RecorderPlan.bitrateFor(1920, 1080, 0) }
    }

    // ----------------------------------------------------------------- helper

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // exactly what should be thrown
        }
    }
}
