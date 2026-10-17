package app.cameraplus.camera

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a `YUV_420_888` buffer (the shape an `ImageProxy` hands out) into a
 * packed ARGB [IntArray], applying one right-angle rotation and a downscale in
 * the same pass.
 *
 * This is the panorama stream's frame source. The analysis pipeline delivers
 * sensor-orientation buffers, so the rotation has to happen here — and doing
 * it while scaling means the expensive pass over the pixels runs exactly once,
 * on exactly the output-sized buffer the stitcher will keep.
 *
 * Sampling: bilinear on the luma plane (it carries the structure the matcher
 * correlates on), nearest on the chroma planes (they are a quarter of the
 * data and the eye forgives them). Plane strides are honoured, so tightly
 * packed, padded and semi-planar (U and V interleaved in one buffer) layouts
 * all work. Plane buffers are read by absolute index — their position/limit
 * are never touched.
 *
 * Colour is the plain BT.601 mapping Android cameras are calibrated for; no
 * colour management, no tone curve. Nothing Android-specific, so the whole
 * thing runs in plain JVM tests over synthetic planes.
 */
object YuvFrameConverter {

    /**
     * Converts and rotates one frame.
     *
     * @param rotationDegrees clockwise rotation to apply, a multiple of 90.
     * @param maxDimension longest edge, in pixels, of the output frame; the
     *   source is only ever scaled down.
     * @return the ARGB pixels, plus the output width and height.
     * @throws IllegalArgumentException if the geometry is impossible or a
     *   plane buffer is smaller than the layout it claims.
     */
    fun toArgb(
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        v: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        maxDimension: Int,
    ): Triple<IntArray, Int, Int> {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(maxDimension > 0) { "maxDimension must be positive" }
        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation % 90 == 0) { "rotationDegrees must be a multiple of 90, was $rotationDegrees" }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        require(yPixelStride >= 1 && uPixelStride >= 1 && vPixelStride >= 1) {
            "pixelStride must be at least 1"
        }
        require(yRowStride >= (width - 1) * yPixelStride + 1) {
            "luma rowStride is too small for width=$width pixelStride=$yPixelStride"
        }
        require(uRowStride >= (chromaWidth - 1) * uPixelStride + 1 &&
            vRowStride >= (chromaWidth - 1) * vPixelStride + 1
        ) {
            "chroma rowStride is too small for width=$chromaWidth and the reported pixel strides"
        }
        val yBase = y.position()
        val uBase = u.position()
        val vBase = v.position()
        requirePlane(y, "Y", yRowStride, yPixelStride, height, width)
        requirePlane(u, "U", uRowStride, uPixelStride, chromaHeight, chromaWidth)
        requirePlane(v, "V", vRowStride, vPixelStride, chromaHeight, chromaWidth)

        val swap = rotation == 90 || rotation == 270
        val baseWidth = if (swap) height else width
        val baseHeight = if (swap) width else height
        val longest = max(baseWidth, baseHeight)
        val scale = if (longest > maxDimension) maxDimension.toDouble() / longest else 1.0
        val outWidth = max(1, (baseWidth * scale).roundToInt())
        val outHeight = max(1, (baseHeight * scale).roundToInt())

        val out = IntArray(outWidth * outHeight)
        var index = 0
        for (oy in 0 until outHeight) {
            val vRatio = (oy + 0.5f) / outHeight
            for (ox in 0 until outWidth) {
                val uRatio = (ox + 0.5f) / outWidth
                val fx: Float
                val fy: Float
                when (rotation) {
                    // Rotating the source 90 degrees clockwise puts its
                    // bottom-left corner at the output's top-left.
                    90 -> {
                        fx = vRatio * width
                        fy = (1f - uRatio) * height
                    }
                    180 -> {
                        fx = (1f - uRatio) * width
                        fy = (1f - vRatio) * height
                    }
                    270 -> {
                        fx = (1f - vRatio) * width
                        fy = uRatio * height
                    }
                    else -> {
                        fx = uRatio * width
                        fy = vRatio * height
                    }
                }
                val luma = sampleLuma(y, yBase, yRowStride, yPixelStride, width, height, fx, fy)
                val cx = fx.toInt().coerceIn(0, chromaWidth - 1)
                val cy = fy.toInt().coerceIn(0, chromaHeight - 1)
                val uu = (u.get(uBase + cy * uRowStride + cx * uPixelStride).toInt() and 0xFF) - 128
                val vv = (v.get(vBase + cy * vRowStride + cx * vPixelStride).toInt() and 0xFF) - 128
                val r = ((256 * luma + 359 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((256 * luma - 88 * uu - 183 * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((256 * luma + 454 * uu + 128) shr 8).coerceIn(0, 255)
                out[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Triple(out, outWidth, outHeight)
    }

    /** Bilinear luma sample at a continuous pixel coordinate. */
    private fun sampleLuma(
        y: ByteBuffer,
        base: Int,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        fx: Float,
        fy: Float,
    ): Int {
        val x = (fx - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val yy = (fy - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(yy).toInt().coerceIn(0, height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val wx = x - x0
        val wy = yy - y0
        val topLeft = y.get(base + y0 * rowStride + x0 * pixelStride).toInt() and 0xFF
        val topRight = y.get(base + y0 * rowStride + x1 * pixelStride).toInt() and 0xFF
        val bottomLeft = y.get(base + y1 * rowStride + x0 * pixelStride).toInt() and 0xFF
        val bottomRight = y.get(base + y1 * rowStride + x1 * pixelStride).toInt() and 0xFF
        val top = topLeft + (topRight - topLeft) * wx
        val bottom = bottomLeft + (bottomRight - bottomLeft) * wx
        return (top + (bottom - top) * wy).roundToInt().coerceIn(0, 255)
    }

    private fun requirePlane(
        plane: ByteBuffer,
        name: String,
        rowStride: Int,
        pixelStride: Int,
        rows: Int,
        columns: Int,
    ) {
        require(rowStride > 0 && pixelStride > 0) { "$name plane strides must be positive" }
        val needed = if (rows <= 0 || columns <= 0) {
            0
        } else {
            (rows - 1) * rowStride + (columns - 1) * pixelStride + 1
        }
        require(plane.remaining() >= needed) {
            "$name plane buffer too small: needs $needed bytes for ${columns}x$rows at strides ($rowStride, $pixelStride), has ${plane.remaining()}"
        }
    }
}
