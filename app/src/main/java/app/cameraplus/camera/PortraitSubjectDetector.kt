package app.cameraplus.camera

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector

/**
 * Best-effort, on-device face localisation for the software portrait fallback.
 *
 * This only finds a face rectangle from the captured RGB pixels; it does not
 * produce a depth map or claim subject segmentation. If detection is missing
 * or the platform detector fails, the caller gets the documented centred
 * ellipse instead of losing the capture.
 */
object PortraitSubjectDetector {

    data class Result(
        val shape: BokehShape,
        val faceDetected: Boolean,
    )

    /** Detects one face after a bounded downscale and returns a head/shoulders mask. */
    fun detectShape(pixels: IntArray, width: Int, height: Int, level: Float): Result {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(pixels.size >= width * height) { "pixel buffer too small" }
        val fallback = PortraitRenderer.defaultShape(width, height, level)
        var bitmap: Bitmap? = null
        return try {
            val (scaledPixels, scaledWidth, scaledHeight) = downscale(
                pixels,
                width,
                height,
                MAX_DETECTOR_DIMENSION,
            )
            bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.RGB_565)
            bitmap.setPixels(scaledPixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = FaceDetector(scaledWidth, scaledHeight, MAX_FACES).findFaces(bitmap, faces)
            val face = if (count > 0) faces.firstOrNull() else null
            if (face == null || face.confidence() < MIN_CONFIDENCE) {
                Result(fallback, faceDetected = false)
            } else {
                val midpoint = PointF()
                face.getMidPoint(midpoint)
                val shape = PortraitRenderer.faceGuidedShape(
                    width = scaledWidth,
                    height = scaledHeight,
                    faceCenterX = midpoint.x,
                    faceCenterY = midpoint.y,
                    eyeDistance = face.eyesDistance(),
                    level = level,
                )
                Result(shape, faceDetected = true)
            }
        } catch (_: Throwable) {
            // A face detector / bitmap allocation is optional enhancement;
            // preserve the photo path and use its bounded fallback mask.
            Result(fallback, faceDetected = false)
        } finally {
            bitmap?.recycle()
        }
    }

    private const val MAX_DETECTOR_DIMENSION = 640
    private const val MAX_FACES = 1
    private const val MIN_CONFIDENCE = 0.2f
}
