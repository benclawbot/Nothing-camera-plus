package app.cameraplus.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraParametersTest {

    @Test
    fun formatsLikeTheOriginalWatermarkPreview() {
        assertEquals(
            "24MM F/1.9 1/50S ISO 123",
            CameraParameters(focalMm = 24f, aperture = 1.9f, exposureSeconds = 0.02, iso = 123)
                .formatted(),
        )
    }

    @Test
    fun roundsFocalAndAperture() {
        assertEquals("23MM F/2.0", CameraParameters(focalMm = 23.4f, aperture = 1.98f).formatted())
    }

    @Test
    fun formatsFastAndLongExposures() {
        assertEquals("1/1000S", CameraParameters(exposureSeconds = 0.001).formatted())
        assertEquals("2S", CameraParameters(exposureSeconds = 2.0).formatted())
        assertEquals("1.5S", CameraParameters(exposureSeconds = 1.5).formatted())
    }

    @Test
    fun omitsUnknownParts() {
        assertEquals("24MM", CameraParameters(focalMm = 24f).formatted())
        assertEquals("ISO 400", CameraParameters(iso = 400).formatted())
        assertEquals("24MM F/1.9", CameraParameters(focalMm = 24f, aperture = 1.9f).formatted())
    }

    @Test
    fun formatsNothingWhenNothingIsKnown() {
        assertNull(CameraParameters().formatted())
        assertNull(CameraParameters(focalMm = 0f, aperture = -1f, iso = 0).formatted())
        assertNull(CameraParameters(exposureSeconds = 0.0).formatted())
        assertNull(CameraParameters(focalMm = Float.NaN, exposureSeconds = Double.NaN).formatted())
    }
}
