package app.cameraplus.processing

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Reads the still's own EXIF for the watermark parameters line.
 *
 * Device JPEGs carry exposure time, F-number and ISO when the HAL writes them
 * (the usual case for camera stills); reading the frame that is about to be
 * saved means the stamp describes the shot that was actually taken. Focal
 * length is deliberately not read here: EXIF stores the physical focal, and
 * the watermark shows the 35 mm-equivalent value, which the session knows.
 */
object JpegCameraParameters {

    fun read(jpegBytes: ByteArray): CameraParameters {
        if (jpegBytes.isEmpty()) return CameraParameters()
        val exif = runCatching { ExifInterface(ByteArrayInputStream(jpegBytes)) }.getOrNull()
            ?: return CameraParameters()
        val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
        return CameraParameters(
            aperture = aperture.takeIf { it > 0.0 }?.toFloat(),
            exposureSeconds = exposure.takeIf { it > 0.0 },
            iso = iso.takeIf { it > 0 },
        )
    }
}
