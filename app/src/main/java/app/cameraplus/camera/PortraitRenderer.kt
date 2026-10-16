package app.cameraplus.camera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The ellipse that portrait mode keeps sharp, in ratios of the frame so the
 * same shape survives a change of resolution.
 *
 * @property centerXRatio ellipse centre, 0 = left edge, 1 = right edge
 * @property centerYRatio ellipse centre, 0 = top edge, 1 = bottom edge
 * @property radiusXRatio ellipse radius across the frame width
 * @property radiusYRatio ellipse radius across the frame height
 * @property featherRatio width of the transition band, relative to the
 *   radius: `0.2` means full blur is reached at 1.2 radii
 */
data class BokehShape(
    val centerXRatio: Float,
    val centerYRatio: Float,
    val radiusXRatio: Float,
    val radiusYRatio: Float,
    val featherRatio: Float,
)

/**
 * Software bokeh for portrait mode, on plain ARGB pixel arrays.
 *
 * ## What this is
 *
 * A centre-weighted blur. The frame is blurred with two box passes (a cheap
 * stand-in for a Gaussian), and the blur is blended back in with a smooth
 * radial falloff: fully sharp inside [BokehShape]'s ellipse, fully blurred
 * outside its feather band, smooth in between.
 *
 * ## What this is not
 *
 * There is **no depth information anywhere in this code**. It never sees a
 * depth map, a disparity map, a focus distance or a segmentation. It cannot
 * tell a subject from the wall behind them; it is told, by the caller, where
 * to put an ellipse, and it does exactly that.
 *
 * The OEM pipeline does have the depth: a dual-camera baseline or a
 * depth-from-focus estimate, per-pixel, which is what lets Facula-style
 * portrait modes keep a shoulder sharp while blurring the wall a few
 * centimetres behind it, and why their light shapes (Velvet, Pentacle,
 * Snowflake) wrap around an actual person. Nothing here reproduces that.
 *
 * So, honestly: on a clean scene - one subject against plain background -
 * this looks fine. On a busy scene it is clearly worse than the vendor
 * pipeline, and no amount of tuning the ellipse fixes that, because the
 * missing thing is depth, not parameters. A hair edge, a hand in front of a
 * face, or a foreground object intruding into the frame will be blurred
 * anyway. The UI should label this a simulated blur rather than claim
 * portrait mode parity.
 *
 * Output is always fully opaque (alpha 0xFF), so a still that arrived with
 * stray alpha cannot leak transparent pixels into the result.
 *
 * The blur keeps its two passes in float channels, which costs about 24 bytes
 * per pixel of scratch. A 12 MP frame is ~290 MB, so portrait mode should hand
 * this a frame that has already been downscaled.
 *
 * Free of Android types, so the whole thing is JVM-testable.
 */
object PortraitRenderer {

    /**
     * Blurs everything outside [shape] and keeps the inside sharp, feathering
     * across the boundary instead of cutting it.
     *
     * [maxBlurRadius] is the box radius of each of the two passes, so the
     * effective blur is roughly twice as wide again. `0` (or less) means "no
     * blur at all" and the image is copied through with opaque alpha.
     *
     * Sampling near the frame edges clamps, so a large radius flattens the
     * outermost few pixels toward the edge colour rather than inventing
     * black. Keeps the subject ellipse intact when it is near the frame.
     */
    fun blurBackground(
        pixels: IntArray,
        width: Int,
        height: Int,
        shape: BokehShape,
        maxBlurRadius: Int,
    ): IntArray {
        require(width > 0 && height > 0) { "image must not be empty" }
        val count = width * height
        require(pixels.size >= count) { "pixel buffer too small" }

        val output = IntArray(count)
        if (maxBlurRadius < 1) {
            for (i in 0 until count) output[i] = opaque(pixels[i])
            return output
        }

        val blurred = gaussianBlur(pixels, width, height, maxBlurRadius)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val blend = blendFactor(shape, width, height, x, y)
                val p = pixels[i]
                val b = blurred[i]
                output[i] = pack(
                    lerpChannel((p shr 16) and 0xFF, (b shr 16) and 0xFF, blend),
                    lerpChannel((p shr 8) and 0xFF, (b shr 8) and 0xFF, blend),
                    lerpChannel(p and 0xFF, b and 0xFF, blend),
                    0xFF,
                )
            }
        }
        return output
    }

    /**
     * A subject ellipse for a given bokeh [level], `0..1`.
     *
     * Higher level means a larger in-focus ellipse and a tighter feather, i.e.
     * less of the frame gets blurred - which is the direction the slider on
     * the OEM portrait UI moves. The centre sits slightly above the middle of
     * the frame, because that is roughly where a head and shoulders land in
     * portrait framing. Out-of-range levels are clamped.
     *
     * Radii are ratios of width and height separately, so the ellipse follows
     * the frame aspect instead of becoming a circle in a 4:3 image.
     */
    fun defaultShape(width: Int, height: Int, level: Float): BokehShape {
        require(width > 0 && height > 0) { "image must not be empty" }
        val t = level.coerceIn(0f, 1f)
        return BokehShape(
            centerXRatio = 0.5f,
            centerYRatio = 0.45f,
            radiusXRatio = 0.30f + 0.18f * t,
            radiusYRatio = 0.34f + 0.20f * t,
            featherRatio = 0.30f - 0.16f * t,
        )
    }

    /**
     * How much of the blurred frame to mix in at (`x`, `y`): 0 inside the
     * subject ellipse, 1 outside the feather band, smoothstep between.
     *
     * Internal, but it is the honest description of what the composite does
     * and what the monotonicity test samples.
     */
    internal fun blendFactor(
        shape: BokehShape,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ): Float {
        val cx = shape.centerXRatio * width
        val cy = shape.centerYRatio * height
        val rx = max(1e-3f, shape.radiusXRatio * width)
        val ry = max(1e-3f, shape.radiusYRatio * height)
        val dx = (x + 0.5f - cx) / rx
        val dy = (y + 0.5f - cy) / ry
        val distance = sqrt(dx * dx + dy * dy)

        if (distance <= 1f) return 0f
        val feather = max(0f, shape.featherRatio)
        if (feather <= 0f) return 1f
        val t = ((distance - 1f) / feather).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Two separable box passes, which read as a Gaussian to the eye. */
    private fun gaussianBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val r = radius.coerceIn(1, max(width, height))
        val count = width * height
        val front = FloatArray(count * 3)
        val back = FloatArray(count * 3)

        // Floats, not 8-bit ints: averaging eight-bit values three times in a
        // row quantises shadows into visible steps, which is exactly the part
        // of the picture portrait mode is supposed to keep.
        for (i in 0 until count) {
            val p = pixels[i]
            front[3 * i] = ((p shr 16) and 0xFF).toFloat()
            front[3 * i + 1] = ((p shr 8) and 0xFF).toFloat()
            front[3 * i + 2] = (p and 0xFF).toFloat()
        }

        blurHorizontal(front, back, width, height, r)
        blurVertical(back, front, width, height, r)
        blurHorizontal(front, back, width, height, r)
        blurVertical(back, front, width, height, r)

        val out = IntArray(count)
        for (i in 0 until count) {
            out[i] = pack(
                front[3 * i].roundToInt(),
                front[3 * i + 1].roundToInt(),
                front[3 * i + 2].roundToInt(),
                0xFF,
            )
        }
        return out
    }

    /** Sliding window along x, edge clamped, running sums so it stays O(n). */
    private fun blurHorizontal(
        source: FloatArray,
        destination: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        for (y in 0 until height) {
            val row = y * width
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            val lastX = min(width - 1, radius)
            for (x in 0..lastX) {
                val i = 3 * (row + x)
                sumR += source[i]
                sumG += source[i + 1]
                sumB += source[i + 2]
            }
            var count = lastX + 1
            for (x in 0 until width) {
                val out = 3 * (row + x)
                destination[out] = sumR / count
                destination[out + 1] = sumG / count
                destination[out + 2] = sumB / count
                val removeAt = x - radius
                if (removeAt in 0 until width) {
                    val i = 3 * (row + removeAt)
                    sumR -= source[i]
                    sumG -= source[i + 1]
                    sumB -= source[i + 2]
                    count--
                }
                val addAt = x + radius + 1
                if (addAt in 0 until width) {
                    val i = 3 * (row + addAt)
                    sumR += source[i]
                    sumG += source[i + 1]
                    sumB += source[i + 2]
                    count++
                }
            }
        }
    }

    /** Same, down the columns. */
    private fun blurVertical(
        source: FloatArray,
        destination: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        for (x in 0 until width) {
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            val lastY = min(height - 1, radius)
            for (y in 0..lastY) {
                val i = 3 * (y * width + x)
                sumR += source[i]
                sumG += source[i + 1]
                sumB += source[i + 2]
            }
            var count = lastY + 1
            for (y in 0 until height) {
                val out = 3 * (y * width + x)
                destination[out] = sumR / count
                destination[out + 1] = sumG / count
                destination[out + 2] = sumB / count
                val removeAt = y - radius
                if (removeAt in 0 until height) {
                    val i = 3 * (removeAt * width + x)
                    sumR -= source[i]
                    sumG -= source[i + 1]
                    sumB -= source[i + 2]
                    count--
                }
                val addAt = y + radius + 1
                if (addAt in 0 until height) {
                    val i = 3 * (addAt * width + x)
                    sumR += source[i]
                    sumG += source[i + 1]
                    sumB += source[i + 2]
                    count++
                }
            }
        }
    }

    private fun lerpChannel(sharp: Int, blurred: Int, blend: Float): Int =
        (sharp + blend * (blurred - sharp)).roundToInt().coerceIn(0, 255)

    private fun opaque(p: Int): Int = pack(
        (p shr 16) and 0xFF,
        (p shr 8) and 0xFF,
        p and 0xFF,
        0xFF,
    )

    private fun pack(r: Int, g: Int, b: Int, a: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}