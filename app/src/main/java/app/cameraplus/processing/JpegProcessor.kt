package app.cameraplus.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Location
import app.cameraplus.camera.AutoLevelPlan
import app.cameraplus.settings.ProcessingSettings
import app.cameraplus.settings.WatermarkOptions
import java.io.OutputStream

/**
 * Decodes a captured JPEG, applies [ProcessingSettings] through the pure
 * [ImageProcessor] math, and writes a re-encoded JPEG. Intended to run on a
 * background thread.
 *
 * Memory matters here: a 50 MP frame decoded to ARGB_8888 is 200 MB, and the
 * earlier version of this file made it far worse by copying the bitmap and
 * buffering a second encoded JPEG in memory. The bitmap is decoded mutable
 * (no copy), size is checked *before* full decode, and compression streams
 * directly to the output.
 *
 * Processing is best-effort by design: [process] returns false instead of
 * throwing when the frame is too large or the device refuses, so the caller
 * can save the untouched JPEG rather than losing the shot.
 */
object JpegProcessor {

    /** Ceiling for software color processing; larger stills are processed in tiles. */
    private const val MAX_COLOR_PROCESSING_PIXELS = 16_000_000L

    /** Below this, the simpler full-frame ImageProcessor path is cheaper. */
    private const val FULL_FRAME_COLOR_PIXELS = 8_000_000L

    /** Rows per tile when a normal 12 MP still would exceed the full-frame budget. */
    private const val COLOR_PROCESSING_TILE_ROWS = 128

    /** A watermark-only Canvas pass needs just the bitmap, so it can cover 12 MP JPEGs. */
    private const val MAX_BITMAP_ONLY_PIXELS = 24_000_000L
    private const val MAX_BITMAP_MIRROR_PIXELS = 12_000_000L

    private const val JPEG_QUALITY = 95

    /**
     * @return true when the frame was processed and written, false when the
     *         caller should write the original bytes instead.
     */
    fun process(
        jpegBytes: ByteArray,
        settings: ProcessingSettings,
        out: OutputStream,
        mirrorHorizontal: Boolean = false,
        rotationDegrees: Int = 0,
        autoLevelDegrees: Float = 0f,
        watermark: WatermarkOptions = WatermarkOptions(),
        location: Location? = null,
        cameraParameters: CameraParameters? = null,
    ): Boolean {
        val requiresColorPixels = settings != ProcessingSettings.NATURAL
        val maxPixels = when {
            requiresColorPixels -> MAX_COLOR_PROCESSING_PIXELS
            mirrorHorizontal -> MAX_BITMAP_MIRROR_PIXELS
            else -> MAX_BITMAP_ONLY_PIXELS
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > maxPixels
        ) {
            return false
        }
        var bitmap = try {
            decode(jpegBytes)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return false
        return try {
            // Captures arrive in sensor orientation; orient once, up front,
            // so the look passes, watermark and encode all work upright.
            bitmap = orient(bitmap, rotationDegrees, mirrorHorizontal)
            bitmap = autoLevel(bitmap, autoLevelDegrees)
            val width = bitmap.width
            val height = bitmap.height
            if (width.toLong() * height.toLong() > maxPixels) return false

            if (requiresColorPixels) {
                if (width.toLong() * height <= FULL_FRAME_COLOR_PIXELS) {
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    ImageProcessor.apply(pixels, width, height, settings)
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                } else {
                    // Read each tile plus its spatial-filter halo from an
                    // immutable source bitmap, and write only the core rows to
                    // the output. This keeps previewed sharpening/looks from
                    // silently disappearing on the device's standard 12 MP
                    // stills, without allocating several full-frame arrays.
                    val source = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false
                    try {
                        ImageProcessor.applyTiled(
                            width = width,
                            height = height,
                            settings = settings,
                            tileRows = COLOR_PROCESSING_TILE_ROWS,
                            readRow = { row, pixels, offset ->
                                source.getPixels(pixels, offset, width, 0, row, width, 1)
                            },
                            writeRows = { firstRow, rowCount, pixels, offset ->
                                bitmap.setPixels(pixels, offset, width, 0, firstRow, width, rowCount)
                            },
                        )
                    } finally {
                        source.recycle()
                    }
                }
            }
            // The device's EXIF carries exposure/ISO/aperture; the session's
            // parameters carry the 35 mm-equivalent focal (EXIF stores the
            // physical focal). Missing parts are simply left out.
            val parameters = cameraParameters?.let { reported ->
                val exif = JpegCameraParameters.read(jpegBytes)
                reported.copy(
                    aperture = exif.aperture ?: reported.aperture,
                    exposureSeconds = exif.exposureSeconds,
                    iso = exif.iso,
                )
            }
            WatermarkRenderer.draw(bitmap, watermark, location, parameters = parameters)

            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        } catch (t: Throwable) {
            // OutOfMemoryError included: the shot matters more than the look.
            false
        } finally {
            bitmap.recycle()
        }
    }

    /** Rotates (clockwise degrees) and mirrors in one pass when needed. */
    private fun orient(bitmap: Bitmap, rotationDegrees: Int, mirrorHorizontal: Boolean): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0 && !mirrorHorizontal) return bitmap
        val matrix = Matrix().apply {
            if (normalized != 0) postRotate(normalized.toFloat())
            if (mirrorHorizontal) postScale(-1f, 1f)
        }
        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (transformed !== bitmap) {
            bitmap.recycle()
            return transformed
        }
        return bitmap
    }

    /** Rotates and fills the original bounds, preserving the HDR/EXIF callers' order. */
    fun autoLevel(bitmap: Bitmap, correctionDegrees: Float): Bitmap {
        if (!correctionDegrees.isFinite() || kotlin.math.abs(correctionDegrees) < 0.05f) return bitmap
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        val scale = AutoLevelPlan.fillScale(bitmap.width, bitmap.height, correctionDegrees)
        canvas.translate(centerX, centerY)
        canvas.scale(scale, scale)
        canvas.rotate(correctionDegrees)
        canvas.translate(-centerX, -centerY)
        canvas.drawBitmap(
            bitmap,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        bitmap.recycle()
        return output
    }

    /** Mutable ARGB decode so the pixels can be edited in place. */
    private fun decode(jpegBytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
    }
}
