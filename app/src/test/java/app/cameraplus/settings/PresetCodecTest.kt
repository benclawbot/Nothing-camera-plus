package app.cameraplus.settings

import app.cameraplus.camera.AntiBanding
import app.cameraplus.camera.BarcodeScanning
import app.cameraplus.camera.ExposureBracket
import app.cameraplus.camera.FlashChoice
import app.cameraplus.camera.FocusTimeout
import app.cameraplus.camera.FocusBracket
import app.cameraplus.camera.PhotoAspectRatio
import app.cameraplus.camera.PhotoResolution
import app.cameraplus.camera.SelfTimer
import app.cameraplus.camera.StillQuality
import app.cameraplus.camera.WhiteBalancePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCodecTest {

    private val preset = CameraPreset(
        name = "Golden hour",
        capture = CapturePreferences(
            flashChoice = FlashChoice.AUTO,
            photoAspectRatio = PhotoAspectRatio.SIXTEEN_NINE,
            photoResolution = PhotoResolution.MAXIMUM,
            ultraHdrEnabled = true,
            autoLevelEnabled = true,
            selfTimer = SelfTimer.TEN_SECONDS,
            selfTimerShots = 3,
            mirrorCapture = true,
            histogramEnabled = true,
            zebrasEnabled = true,
            keepExposure = true,
            exposureCompensationIndex = -4,
            antiBanding = AntiBanding.HZ_50,
            stillQuality = StillQuality.HIGH,
            focusTimeout = FocusTimeout.FIFTEEN_SECONDS,
            exposureBracket = ExposureBracket.FIVE_SHOT,
            mergeExposureBracket = false,
            focusBracket = FocusBracket.FIVE_SHOT,
            focusAssistEnabled = true,
            pinnedSettings = listOf(PinnedSetting.SIZE, PinnedSetting.FLASH, PinnedSetting.LOOK),
            ghostImage = true,
            defaultFocalMm = 35,
            voiceCountdown = true,
            barcodeScanning = BarcodeScanning.ALL,
            stripMetadata = true,
            exifAuthor = "A. Photographer",
            exifCopyright = "(c) 2026",
            whiteBalance = WhiteBalancePreset.CLOUDY,
            whiteBalanceKelvin = 6_200,
            portraitBlurLevel = 0.7f,
            slowMotionFps = 240,
        ),
        processing = ProcessingSettings(
            sharpening = 0.25f,
            noiseReduction = 0.5f,
            hdr = 0.75f,
            skinSmoothing = 0.1f,
            filter = PhotoFilter.VINTAGE,
        ),
    )

    @Test
    fun roundTripsAPresetWithEveryFieldSet() {
        val decoded = PresetCodec.decode(PresetCodec.encode(listOf(preset)))
        assertEquals(listOf(preset), decoded)
    }

    @Test
    fun roundTripsSeveralPresets() {
        val second = preset.copy(name = "Night", capture = CapturePreferences(subjectTracking = true))
        val decoded = PresetCodec.decode(PresetCodec.encode(listOf(preset, second)))
        assertEquals(listOf(preset, second), decoded)
    }

    @Test
    fun aNameMayContainTheFieldAndPairSeparators() {
        val awkward = preset.copy(name = "pipes|commas,equals= newline\nand more")
        val decoded = PresetCodec.decode(PresetCodec.encode(listOf(awkward)))
        assertEquals("pipes|commas,equals= newline\nand more", decoded.single().name)
    }

    @Test
    fun emptyTextDecodesToNoPresets() {
        assertTrue(PresetCodec.decode("").isEmpty())
        assertTrue(PresetCodec.decode("\n\n").isEmpty())
    }

    @Test
    fun anUnknownKeyIsIgnoredAndAMissingKeyFallsBack() {
        // A blob written by a later build, and one written before a field existed.
        val forward = PresetCodec.encode(listOf(preset))
            .replace("bokeh=", "somethingNew=yes,bokeh=")
        assertEquals(listOf(preset), PresetCodec.decode(forward))

        val backwards =
            "Old|flash=ON,aspect=FOUR_THREE|sharpening=0.1,filter=VIVID"
        val decoded = PresetCodec.decode(backwards).single()
        assertEquals(FlashChoice.ON, decoded.capture.flashChoice)
        assertEquals(PhotoAspectRatio.FOUR_THREE, decoded.capture.photoAspectRatio)
        assertEquals(0.1f, decoded.processing.sharpening, 0.0001f)
        assertEquals(PhotoFilter.VIVID, decoded.processing.filter)
        // Untouched fields keep their defaults.
        assertEquals(FlashChoice.OFF, CapturePreferences().flashChoice)
        assertEquals(5_500, decoded.capture.whiteBalanceKelvin)
    }

    @Test
    fun aMalformedLineIsSkippedRatherThanFailingTheBlob() {
        val good = PresetCodec.encode(listOf(preset))
        val decoded = PresetCodec.decode("garbage\n$good\n||")
        assertEquals(1, decoded.size)
        assertEquals("Golden hour", decoded.single().name)
    }

    @Test
    fun anEmptyNameIsRejected() {
        assertTrue(PresetCodec.decode("|flash=OFF|filter=VIVID").isEmpty())
    }
}
