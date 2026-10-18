package app.cameraplus.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryChoiceTest {

    private val apps = listOf(
        SystemGallery.GalleryApp("com.google.android.apps.photos", "Photos"),
        SystemGallery.GalleryApp("com.nothing.gallery", "Nothing Gallery"),
    )

    @Test
    fun labelResolvesToItsPackage() {
        assertEquals(
            "com.nothing.gallery",
            SystemGallery.galleryPackageForLabel(apps, "Nothing Gallery"),
        )
        assertEquals(
            "com.google.android.apps.photos",
            SystemGallery.galleryPackageForLabel(apps, "Photos"),
        )
    }

    @Test
    fun automaticAndUnknownLabelsMeanNoPreference() {
        assertNull(SystemGallery.galleryPackageForLabel(apps, "Automatic"))
        assertNull(SystemGallery.galleryPackageForLabel(apps, "Not installed"))
        assertNull(SystemGallery.galleryPackageForLabel(emptyList(), "Photos"))
    }
}
