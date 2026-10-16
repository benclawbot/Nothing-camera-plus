package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProfileTest {

    @Test
    fun prefersPreviewStabilization() {
        assertEquals(
            StabilizationLevel.PREVIEW,
            StabilizationProfile.choose(
                previewSupported = true,
                standardSupported = true,
                opticalSupported = true,
            ),
        )
    }

    @Test
    fun fallsBackToStandardThenOpticalThenNone() {
        assertEquals(
            StabilizationLevel.STANDARD,
            StabilizationProfile.choose(
                previewSupported = false,
                standardSupported = true,
                opticalSupported = true,
            ),
        )
        assertEquals(
            StabilizationLevel.OPTICAL,
            StabilizationProfile.choose(
                previewSupported = false,
                standardSupported = false,
                opticalSupported = true,
            ),
        )
        assertEquals(
            StabilizationLevel.NONE,
            StabilizationProfile.choose(
                previewSupported = false,
                standardSupported = false,
                opticalSupported = false,
            ),
        )
    }

    @Test
    fun cropEstimateGrowsWithStabilizationStrength() {
        assertTrue(
            StabilizationProfile.estimatedCrop(StabilizationLevel.PREVIEW) >
                StabilizationProfile.estimatedCrop(StabilizationLevel.STANDARD),
        )
        assertTrue(
            StabilizationProfile.estimatedCrop(StabilizationLevel.STANDARD) >=
                StabilizationProfile.estimatedCrop(StabilizationLevel.OPTICAL),
        )
        assertEquals(0f, StabilizationProfile.estimatedCrop(StabilizationLevel.NONE), 0f)
    }

    @Test
    fun zoomStopsAreDerivedFromPhysicalFocals() {
        val stops = LensZoomPlan.zoomStops(
            mainFocalMm = 4.8f,
            allFocalsMm = listOf(2.0f, 4.8f, 12.0f),
        )
        assertEquals(listOf(0.4f, 1f, 2.5f), stops)
    }

    @Test
    fun zoomStopsAlwaysContainTheMainLens() {
        assertEquals(listOf(1f), LensZoomPlan.zoomStops(mainFocalMm = 4.5f, allFocalsMm = emptyList()))
        assertEquals(listOf(1f), LensZoomPlan.zoomStops(mainFocalMm = 0f, allFocalsMm = listOf(2f)))
    }

    @Test
    fun zoomStopLabelsAreCameraLike() {
        assertEquals("0.5x", LensZoomPlan.stopLabel(0.5f))
        assertEquals("2x", LensZoomPlan.stopLabel(2f))
        assertEquals("2.5x", LensZoomPlan.stopLabel(2.5f))
    }

    @Test
    fun perModeZoomLimitsNeverExceedHardware() {
        assertEquals(2f, ModeZoomPlan.maximum(ZoomUseCase.SLOW_MOTION, 20f), 0f)
        assertEquals(10f, ModeZoomPlan.maximum(ZoomUseCase.TIME_LAPSE, 20f), 0f)
        assertEquals(20f, ModeZoomPlan.maximum(ZoomUseCase.NIGHT, 20f), 0f)
        assertEquals(30f, ModeZoomPlan.maximum(ZoomUseCase.NIGHT, 60f), 0f)
        assertEquals(1f, ModeZoomPlan.maximum(ZoomUseCase.PANORAMA, 20f), 0f)
        assertEquals(3f, ModeZoomPlan.maximum(ZoomUseCase.PORTRAIT, 3f), 0f)
        assertEquals(1f, ModeZoomPlan.maximum(ZoomUseCase.VIDEO, 0.5f), 0f)
    }
}
