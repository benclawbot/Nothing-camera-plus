package app.cameraplus.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SubjectTrackerTest {

    /** Deterministic textured frame: a grid of soft blobs plus noise. */
    private fun texturedFrame(width: Int, height: Int, seed: Int, shiftX: Int = 0, shiftY: Int = 0): GrayFrame {
        val random = Random(seed)
        val base = IntArray(width * height) { random.nextInt(80, 200) }
        // Add structure the tracker can follow: bright blobs.
        val blobs = listOf(30 to 30, 70 to 60, 120 to 40, 50 to 90)
        for ((bx, by) in blobs) {
            for (y in by - 12..by + 12) {
                for (x in bx - 12..bx + 12) {
                    if (x in 0 until width && y in 0 until height) {
                        val distance = abs(x - bx) + abs(y - by)
                        if (distance < 12) base[y * width + x] =
                            (base[y * width + x] + (12 - distance) * 8).coerceAtMost(255)
                    }
                }
            }
        }
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = (x - shiftX).coerceIn(0, width - 1)
                val sy = (y - shiftY).coerceIn(0, height - 1)
                data[y * width + x] = base[sy * width + sx].toByte()
            }
        }
        return GrayFrame(width, height, data)
    }

    @Test
    fun followsATranslatedSubject() {
        val tracker = SubjectTracker()
        val first = texturedFrame(192, 256, seed = 7)
        assertTrue(tracker.select(first, 70f, 60f, halfSize = 14))
        assertTrue(tracker.isTracking)

        val moved = texturedFrame(192, 256, seed = 7, shiftX = 9, shiftY = -6)
        val result = tracker.update(moved)
        assertNotNull(result)
        result!!
        assertEquals(79f, result.x, 2.5f)
        assertEquals(54f, result.y, 2.5f)
        assertTrue("score should stay high on a clean translation: ${result.score}", result.score > 0.6f)
    }

    @Test
    fun tracksRepeatedlyWithoutDriftingAway() {
        val tracker = SubjectTracker()
        assertTrue(tracker.select(texturedFrame(192, 256, seed = 11), 100f, 120f, 14))
        var x = 100f
        var y = 120f
        repeat(4) { step ->
            val dx = 4
            val dy = -3
            val frame = texturedFrame(
                192, 256, seed = 11, shiftX = dx * (step + 1), shiftY = dy * (step + 1),
            )
            val result = tracker.update(frame)
            assertNotNull(result)
            x += dx
            y += dy
            assertEquals(x, result!!.x, 3f)
            assertEquals(y, result.y, 3f)
        }
    }

    @Test
    fun refusesAFlatSelection() {
        val flat = GrayFrame(192, 256, ByteArray(192 * 256) { 120.toByte() })
        val tracker = SubjectTracker()
        assertFalse(tracker.select(flat, 96f, 128f, 14))
        assertFalse(tracker.isTracking)
    }

    @Test
    fun resetStopsTracking() {
        val tracker = SubjectTracker()
        assertTrue(tracker.select(texturedFrame(192, 256, seed = 3), 80f, 80f, 12))
        tracker.reset()
        assertFalse(tracker.isTracking)
        assertEquals(null, tracker.update(texturedFrame(192, 256, seed = 3)))
    }

    @Test
    fun lowConfidenceWhenTheSubjectDisappears() {
        val tracker = SubjectTracker()
        assertTrue(tracker.select(texturedFrame(192, 256, seed = 5), 90f, 70f, 14))
        // Replace the whole frame with unrelated texture: the template has
        // nothing left to match.
        val unrelated = GrayFrame(192, 256, ByteArray(192 * 256) { 60.toByte() })
        val result = tracker.update(unrelated)
        assertEquals(null, result) // every candidate patch is flat
    }

    @Test
    fun selectionIsClampedAtTheFrameEdge() {
        val tracker = SubjectTracker()
        assertTrue(tracker.select(texturedFrame(192, 256, seed = 9), 1f, 1f, 14))
        val result = tracker.update(texturedFrame(192, 256, seed = 9))
        assertNotNull(result)
        assertTrue(result!!.x >= 14f)
        assertTrue(result.y >= 14f)
    }
}
