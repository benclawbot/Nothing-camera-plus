package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LensPlanTest {

    /**
     * A phone laid out like the real thing: HAL order puts the telephoto
     * first (the reason the app used to open at "2x"), main in the middle,
     * ultrawide last. Sensors are ~1/1.5" for main and tele, small for UW.
     */
    private val nothingLike = listOf(
        LensSpec(
            cameraId = "2",
            focalsMm = listOf(12.0f),
            sensorWidthMm = 7.6f,
            sensorHeightMm = 5.7f,
            pixelCount = 50_000_000L,
            minZoom = 1f,
            maxZoom = 2f,
        ),
        LensSpec(
            cameraId = "0",
            focalsMm = listOf(5.9f),
            sensorWidthMm = 7.6f,
            sensorHeightMm = 5.7f,
            pixelCount = 50_000_000L,
            minZoom = 1f,
            maxZoom = 10f,
        ),
        LensSpec(
            cameraId = "1",
            focalsMm = listOf(2.4f),
            sensorWidthMm = 5.6f,
            sensorHeightMm = 4.2f,
            pixelCount = 12_000_000L,
            minZoom = 1f,
            maxZoom = 1f,
        ),
    )

    @Test
    fun referenceLensIsTheMainCameraNotTheFirstHalEntry() {
        val layout = LensPlan.build(nothingLike)
        assertEquals("0", layout.referenceCameraId)
        assertEquals(1f, layout.lenses.first { it.cameraId == "0" }.baseStop, 1e-3f)
    }

    @Test
    fun stopsIncludeUltrawideMainAndTelephotoPlusDigitalLadder() {
        val layout = LensPlan.build(nothingLike)
        assertEquals(listOf(0.4f, 1f, 2f, 3f, 5f, 10f), layout.stops)
    }

    @Test
    fun equivalentFocalLabelsComeFromTheServingLens() {
        val layout = LensPlan.build(nothingLike)
        // 1x is the main lens: 5.9 mm on a 7.6x5.7 mm sensor (~26.9 mm equiv).
        assertEquals(26.9f, layout.eqFocalMmFor(1f)!!, 0.1f)
        // The ultrawide stop serves the ultrawide sensor, not a scaled main.
        assertEquals(14.8f, layout.eqFocalMmFor(0.4f)!!, 0.1f)
        // 2x takes the telephoto lens (same sensor size, 12 mm).
        assertEquals(54.6f, layout.eqFocalMmFor(2f)!!, 0.1f)
        // 10x is digital on the main lens and scales the focal.
        assertEquals(268.7f, layout.eqFocalMmFor(10f)!!, 1f)
    }

    @Test
    fun referenceEqFocalFollowsTheReferenceLens() {
        val layout = LensPlan.build(nothingLike)
        assertEquals(26.9f, layout.referenceEqFocalMm, 0.1f)
    }

    @Test
    fun equivalentFocalOfAnImpossibleZoomIsNull() {
        val layout = LensPlan.build(nothingLike)
        assertNull(layout.eqFocalMmFor(0f))
        assertNull(layout.eqFocalMmFor(-2f))
        assertNull(layout.eqFocalMmFor(Float.NaN))
    }

    @Test
    fun ultrawideStopRebindsToTheUltrawideCamera() {
        val layout = LensPlan.build(nothingLike)
        val target = layout.targetFor(0.4f, currentCameraId = "0")
        assertEquals("1", target.cameraId)
        assertEquals(1f, target.zoomRatio, 1e-3f)
        assertTrue(target.needsRebind)
    }

    @Test
    fun teleStopSwitchesToTheTeleCameraOptically() {
        // The fixture's telephoto is 12 mm / 5.9 mm = 2.0x, so 2x is a real
        // lens stop: it must switch cameras and shoot at native ratio 1.0.
        val layout = LensPlan.build(nothingLike)
        val target = layout.targetFor(2f, currentCameraId = "0")
        assertEquals("2", target.cameraId)
        assertEquals(1f, target.zoomRatio, 1e-3f)
        assertTrue(target.needsRebind)
    }

    @Test
    fun digitalZoomBetweenStaysOnTheCurrentCamera() {
        val layout = LensPlan.build(nothingLike)
        val target = layout.targetFor(3f, currentCameraId = "0")
        assertEquals("0", target.cameraId)
        assertEquals(3f, target.zoomRatio, 1e-3f)
        assertFalse(target.needsRebind)
    }

    @Test
    fun ultraWideReachableInsideOneLogicalCameraStaysOnIt() {
        // Some devices expose a single logical camera whose zoom range already
        // dips below 1x; 0.6x must then be a plain ratio, not a rebind.
        val logical = listOf(
            LensSpec(
                cameraId = "0",
                focalsMm = listOf(2.4f, 5.9f, 12.0f),
                sensorWidthMm = 7.6f,
                sensorHeightMm = 5.7f,
                pixelCount = 50_000_000L,
                minZoom = 0.5f,
                maxZoom = 20f,
            ),
        )
        val layout = LensPlan.build(logical)
        val target = layout.targetFor(0.6f, currentCameraId = "0")
        assertEquals("0", target.cameraId)
        assertEquals(0.6f, target.zoomRatio, 1e-3f)
        assertFalse(target.needsRebind)
    }

    @Test
    fun logicalCameraFocalListProvidesPointSixOneAndTwoStops() {
        val logical = listOf(
            LensSpec(
                cameraId = "logical-back",
                focalsMm = listOf(3.5f, 5.9f, 11.8f),
                sensorWidthMm = 7.6f,
                sensorHeightMm = 5.7f,
                pixelCount = 50_000_000L,
                minZoom = 0.6f,
                maxZoom = 20f,
            ),
        )
        val layout = LensPlan.build(logical)
        assertTrue(layout.stops.any { abs(it - 0.6f) < 0.06f })
        assertTrue(layout.stops.any { abs(it - 1f) < 0.06f })
        assertTrue(layout.stops.any { abs(it - 2f) < 0.06f })

        val ultrawide = layout.targetFor(0.6f, currentCameraId = "logical-back")
        assertEquals("logical-back", ultrawide.cameraId)
        assertEquals(0.6f, ultrawide.zoomRatio, 1e-3f)
        assertFalse(ultrawide.needsRebind)
    }

    @Test
    fun zoomRangeSpansHalfToTwentyTimesOnALogicalCamera() {
        val logical = listOf(
            LensSpec(
                cameraId = "0",
                focalsMm = listOf(5.9f),
                sensorWidthMm = 7.6f,
                sensorHeightMm = 5.7f,
                pixelCount = 50_000_000L,
                minZoom = 0.5f,
                maxZoom = 20f,
            ),
        )
        val layout = LensPlan.build(logical)
        assertEquals(0.5f, layout.minDisplayedZoom, 1e-3f)
        assertEquals(20f, layout.maxDisplayedZoom, 1e-3f)
        assertEquals(20f, layout.clampDisplayed(100f), 1e-3f)
        assertEquals(0.5f, layout.clampDisplayed(0.01f), 1e-3f)
    }

    @Test
    fun zoomBeyondTheDeviceMaximumClampsToTheWidestReachableLens() {
        val layout = LensPlan.build(nothingLike)
        // This device tops out at 10x (main); asking for 20x must land on the
        // best 10x available rather than throwing or going to a 6x tele.
        val target = layout.targetFor(20f, currentCameraId = "0")
        assertEquals("0", target.cameraId)
        assertEquals(10f, target.zoomRatio, 1e-3f)
        assertEquals(10f, target.displayedZoom, 1e-3f)
    }

    @Test
    fun singleLensKeepsOnlyTheOneTimesStop() {
        val layout = LensPlan.single("0", minZoom = 1f, maxZoom = 8f)
        assertEquals(listOf(1f), layout.stops)
        val target = layout.targetFor(4f, currentCameraId = "0")
        assertEquals(4f, target.zoomRatio, 1e-3f)
        assertFalse(target.needsRebind)
    }

    @Test
    fun equivalentFocalMatchesKnownMainLens() {
        // ~26 mm-equivalent main camera: 5.9 mm on a 1/1.5"-class sensor.
        val eq = LensPlan.eqFocalMm(5.9f, 7.6f, 5.7f)
        assertTrue("eq was $eq", eq in 25f..28f)
    }
}
