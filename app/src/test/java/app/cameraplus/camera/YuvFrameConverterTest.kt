package app.cameraplus.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YuvFrameConverterTest {

    @Test
    fun rotatesClockwiseAndPreservesGrayLuma() {
        val y = ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        val u = ByteBuffer.wrap(ByteArray(2) { 128.toByte() })
        val v = ByteBuffer.wrap(ByteArray(2) { 128.toByte() })

        val (pixels, width, height) = YuvFrameConverter.toArgb(
            y, 2, 1,
            u, 1, 1,
            v, 1, 1,
            width = 2,
            height = 4,
            rotationDegrees = 90,
            maxDimension = 8,
        )

        assertEquals(4, width)
        assertEquals(2, height)
        assertEquals(
            listOf(70, 50, 30, 10, 80, 60, 40, 20),
            pixels.map { it and 0xFF },
        )
    }

    @Test
    fun handlesBothLandscapeOrientationsAndUpsideDownRotation() {
        val y = ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        val u = ByteBuffer.wrap(ByteArray(2) { 128.toByte() })
        val v = ByteBuffer.wrap(ByteArray(2) { 128.toByte() })

        fun convert(rotation: Int): Triple<IntArray, Int, Int> = YuvFrameConverter.toArgb(
            y, 2, 1,
            u, 1, 1,
            v, 1, 1,
            width = 2,
            height = 4,
            rotationDegrees = rotation,
            maxDimension = 8,
        )

        val (halfTurn, halfTurnWidth, halfTurnHeight) = convert(180)
        assertEquals(2, halfTurnWidth)
        assertEquals(4, halfTurnHeight)
        assertEquals(listOf(80, 70, 60, 50, 40, 30, 20, 10), halfTurn.map { it and 0xFF })

        val (counterClockwise, counterClockwiseWidth, counterClockwiseHeight) = convert(270)
        assertEquals(4, counterClockwiseWidth)
        assertEquals(2, counterClockwiseHeight)
        assertEquals(listOf(20, 40, 60, 80, 10, 30, 50, 70), counterClockwise.map { it and 0xFF })
    }

    @Test
    fun honorsPixelAndRowStridesAndNonZeroBufferPosition() {
        val y = ByteBuffer.wrap(byteArrayOf(99, 10, 88, 20, 77, 30, 66, 40, 55)).apply {
            position(1)
        }
        val u = ByteBuffer.wrap(byteArrayOf(9, 128.toByte(), 8)).apply { position(1) }
        val v = ByteBuffer.wrap(byteArrayOf(7, 128.toByte(), 6)).apply { position(1) }

        val (pixels, width, height) = YuvFrameConverter.toArgb(
            y, yRowStride = 4, yPixelStride = 2,
            u, uRowStride = 2, uPixelStride = 2,
            v, vRowStride = 2, vPixelStride = 2,
            width = 2,
            height = 2,
            rotationDegrees = 0,
            maxDimension = 2,
        )

        assertEquals(2, width)
        assertEquals(2, height)
        assertEquals(listOf(10, 20, 30, 40), pixels.map { it and 0xFF })
    }

    @Test
    fun scalesTheUprightOutputToTheRequestedLongEdge() {
        val width = 8
        val height = 4
        val y = ByteBuffer.wrap(ByteArray(width * height) { 128.toByte() })
        val chroma = ByteBuffer.wrap(ByteArray((width / 2) * (height / 2)) { 128.toByte() })

        val (_, outWidth, outHeight) = YuvFrameConverter.toArgb(
            y, width, 1,
            chroma, width / 2, 1,
            chroma, width / 2, 1,
            width, height,
            rotationDegrees = 90,
            maxDimension = 4,
        )

        assertEquals(2, outWidth)
        assertEquals(4, outHeight)
    }

    @Test
    fun rejectsNonRightAngleRotation() {
        val y = ByteBuffer.wrap(ByteArray(4))
        val chroma = ByteBuffer.wrap(ByteArray(1))

        assertThrows(IllegalArgumentException::class.java) {
            YuvFrameConverter.toArgb(
                y, 2, 1,
                chroma, 1, 1,
                chroma, 1, 1,
                width = 2,
                height = 2,
                rotationDegrees = 45,
                maxDimension = 2,
            )
        }
    }
}
