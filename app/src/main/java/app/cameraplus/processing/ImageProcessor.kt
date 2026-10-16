package app.cameraplus.processing

import app.cameraplus.settings.ProcessingSettings
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Software post-processing for captured stills, on plain ARGB pixel arrays.
 *
 * Everything here is deliberately free of Android types so the math is
 * JVM-testable; [JpegProcessor] provides the decode/encode glue.
 *
 * Step semantics (M3):
 *  - HDR strength lifts shadows and compresses highlights (single-frame tone
 *    mapping; multi-frame HDR needs a device pipeline).
 *  - Noise reduction blends with a local average.
 *  - Skin smoothing is the same blend, limited to skin-like colours.
 *  - Sharpening is an unsharp mask, applied last.
 */
object ImageProcessor {

    private const val NOISE_BLUR_RADIUS = 2
    private const val SKIN_BLUR_RADIUS = 3

    /** Applies the non-neutral parts of [settings] in place, in a fixed order. */
    fun apply(pixels: IntArray, width: Int, height: Int, settings: ProcessingSettings) {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(pixels.size >= width * height) { "pixel buffer too small" }

        if (settings.hdr > 0f) {
            toneCurve(pixels, width, height, settings.hdr)
        }
        if (settings.noiseReduction > 0f) {
            blendWithBoxBlur(
                pixels, width, height,
                radius = NOISE_BLUR_RADIUS,
                strength = settings.noiseReduction * 0.7f,
                selective = { _, _, _ -> true },
            )
        }
        if (settings.skinSmoothing > 0f) {
            blendWithBoxBlur(
                pixels, width, height,
                radius = SKIN_BLUR_RADIUS,
                strength = settings.skinSmoothing * 0.8f,
                selective = ::isSkinLike,
            )
        }
        if (settings.sharpening > 0f) {
            sharpen(pixels, width, height, settings.sharpening)
        }
    }

    /** Shadow lift + highlight compression as a per-channel curve. */
    private fun toneCurve(pixels: IntArray, width: Int, height: Int, strength: Float) {
        val gamma = 1f / (1f + strength.coerceIn(0f, 1f))
        val lut = IntArray(256) { v ->
            val lifted = 255f * (v / 255f).pow(gamma)
            (v + strength * (lifted - v)).roundToInt().coerceIn(0, 255)
        }
        for (i in 0 until width * height) {
            val p = pixels[i]
            pixels[i] = pack(
                lut[(p shr 16) and 0xFF],
                lut[(p shr 8) and 0xFF],
                lut[p and 0xFF],
                alphaOf(p),
            )
        }
    }

    /**
     * Blends each selected pixel with a local average. Uniform areas are
     * unchanged, so the step is a no-op on detail it cannot improve.
     */
    private fun blendWithBoxBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        strength: Float,
        selective: (r: Int, g: Int, b: Int) -> Boolean,
    ) {
        val blurred = boxBlur(pixels, width, height, radius)
        val blend = strength.coerceIn(0f, 1f)
        for (i in 0 until width * height) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (!selective(r, g, b)) continue
            val br = (blurred[i] shr 16) and 0xFF
            val bg = (blurred[i] shr 8) and 0xFF
            val bb = blurred[i] and 0xFF
            pixels[i] = pack(
                (r + blend * (br - r)).roundToInt().coerceIn(0, 255),
                (g + blend * (bg - g)).roundToInt().coerceIn(0, 255),
                (b + blend * (bb - b)).roundToInt().coerceIn(0, 255),
                alphaOf(p),
            )
        }
    }

    /** Unsharp mask: exaggerates the difference from the local average. */
    private fun sharpen(pixels: IntArray, width: Int, height: Int, strength: Float) {
        val blurred = boxBlur(pixels, width, height, 1)
        val amount = strength.coerceIn(0f, 1f) * 1.5f
        for (i in 0 until width * height) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            pixels[i] = pack(
                (r + amount * (r - ((blurred[i] shr 16) and 0xFF))).roundToInt().coerceIn(0, 255),
                (g + amount * (g - ((blurred[i] shr 8) and 0xFF))).roundToInt().coerceIn(0, 255),
                (b + amount * (b - (blurred[i] and 0xFF))).roundToInt().coerceIn(0, 255),
                alphaOf(p),
            )
        }
    }

    /** Separable box blur with edge clamping. */
    internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(radius >= 1) { "radius must be at least 1" }
        val horizontal = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                for (xx in maxOf(0, x - radius)..minOf(width - 1, x + radius)) {
                    val p = pixels[row + xx]
                    sumR += (p shr 16) and 0xFF
                    sumG += (p shr 8) and 0xFF
                    sumB += p and 0xFF
                    count++
                }
                horizontal[row + x] = pack(sumR / count, sumG / count, sumB / count, 0xFF)
            }
        }
        val output = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                for (yy in maxOf(0, y - radius)..minOf(height - 1, y + radius)) {
                    val p = horizontal[yy * width + x]
                    sumR += (p shr 16) and 0xFF
                    sumG += (p shr 8) and 0xFF
                    sumB += p and 0xFF
                    count++
                }
                output[y * width + x] = pack(sumR / count, sumG / count, sumB / count, 0xFF)
            }
        }
        return output
    }

    /**
     * Conservative skin-tone gate (the classical low-cost rule: warm,
     * red-dominant, bounded spread). Grey and saturated object colours it
     * cannot judge stay untouched by skin smoothing.
     */
    private fun isSkinLike(r: Int, g: Int, b: Int): Boolean {
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        return r > 95 && g > 40 && b > 20 &&
            spread > 15 && spread < 130 &&
            abs(r - g) > 15 && r > g && r > b
    }

    private fun alphaOf(p: Int): Int = p ushr 24

    private fun pack(r: Int, g: Int, b: Int, a: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}
