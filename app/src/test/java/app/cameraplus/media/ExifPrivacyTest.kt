package app.cameraplus.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifPrivacyTest {

    @Test
    fun stripsEveryGpsTagItKnows() {
        val gps = ExifPrivacy.STRIP_TAG_NAMES.filter { it.startsWith("GPS") }
        assertTrue(gps.size >= 10)
        assertTrue("GPSLatitude" in gps)
        assertTrue("GPSLongitude" in gps)
        assertTrue("GPSAltitude" in gps)
    }

    @Test
    fun stripsTheDeviceAndLensIdentity() {
        val tags = ExifPrivacy.STRIP_TAG_NAMES
        assertTrue("Make" in tags)
        assertTrue("Model" in tags)
        assertTrue("Software" in tags)
        assertTrue("LensModel" in tags)
        assertTrue("BodySerialNumber" in tags)
        assertTrue("LensSerialNumber" in tags)
    }

    @Test
    fun keepsTheTagsThatDescribeTheShot() {
        val tags = ExifPrivacy.STRIP_TAG_NAMES
        assertFalse("Orientation" in tags)
        assertFalse("DateTimeOriginal" in tags)
        assertFalse("ExposureTime" in tags)
        assertFalse("FNumber" in tags)
        assertFalse("ISOSpeedRatings" in tags)
        assertFalse("FocalLength" in tags)
    }

    @Test
    fun theAuthorTagsAreTheOnesTheLibraryExpects() {
        assertEquals("Artist", ExifPrivacy.TAG_ARTIST)
        assertEquals("Copyright", ExifPrivacy.TAG_COPYRIGHT)
    }

    @Test
    fun theStripListHasNoBlanksOrDuplicates() {
        val tags = ExifPrivacy.STRIP_TAG_NAMES
        assertTrue(tags.none { it.isBlank() })
        assertEquals(tags.size, tags.distinct().size)
    }
}
