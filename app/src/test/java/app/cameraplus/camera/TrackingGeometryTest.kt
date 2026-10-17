package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingGeometryTest {

    @Test
    fun portraitViewCropsTheSidesOfAFourByThreeFrame() {
        // Upright analysis frame 480x640 (4:3 portrait) on a 1080x2400 view.
        val visible = TrackingGeometry.visibleRect(NormRect.FULL, 480, 640, 1080f, 2400f)
        assertEquals(0.6f, visible.width, 0.001f)
        assertEquals(1f, visible.height, 0.001f)
        assertEquals(0.2f, visible.left, 0.001f)
        assertEquals(0.8f, visible.right, 0.001f)

        val center = TrackingGeometry.frameToView(0.5f, 0.5f, 480, 640, 1080f, 2400f, visible)
        assertEquals(540f, center.x, 0.5f)
        assertEquals(1200f, center.y, 0.5f)

        val leftEdge = TrackingGeometry.frameToView(0.2f, 0.5f, 480, 640, 1080f, 2400f, visible)
        assertEquals(0f, leftEdge.x, 0.5f)
    }

    @Test
    fun roundTripsViewAndFrameCoordinates() {
        val frameWidth = 480
        val frameHeight = 640
        val viewWidth = 1080f
        val viewHeight = 2400f
        val visible = TrackingGeometry.visibleRect(NormRect.FULL, frameWidth, frameHeight, viewWidth, viewHeight)
        val frame = TrackingGeometry.viewToFrame(321f, 1899f, frameWidth, frameHeight, viewWidth, viewHeight, visible)
        val back = TrackingGeometry.frameToView(frame.x, frame.y, frameWidth, frameHeight, viewWidth, viewHeight, visible)
        assertEquals(321f, back.x, 0.5f)
        assertEquals(1899f, back.y, 0.5f)
    }

    @Test
    fun wideViewCropsTheTopAndBottom() {
        // A 16:9 analysis frame in a 20:9 view trims less vertically.
        val visible = TrackingGeometry.visibleRect(NormRect.FULL, 1920, 1080, 2400f, 1080f)
        assertTrue(visible.width == 1f)
        assertTrue(visible.height <= 1f)
        assertEquals(0.5f, (visible.top + visible.bottom) / 2f, 0.001f)
    }

    @Test
    fun scaleMatchesTheAspectFillFactor() {
        // 480 px wide band rendered into 1080 px: scale is 2.25 (vertical
        // needs 2400/640 = 3.75, so the horizontal band restricts? No: scale
        // is max of both, 3.75, and the sides overflow).
        val visible = TrackingGeometry.visibleRect(NormRect.FULL, 480, 640, 1080f, 2400f)
        val scale = TrackingGeometry.scale(480, 640, 1080f, 2400f, visible)
        assertEquals(3.75f, scale, 0.001f)
    }
}
