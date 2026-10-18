package app.cameraplus.settings

import android.content.Context
import app.cameraplus.camera.AntiBanding
import app.cameraplus.camera.BarcodeScanning
import app.cameraplus.camera.ExposureBracket
import app.cameraplus.camera.FlashChoice
import app.cameraplus.camera.FocusTimeout
import app.cameraplus.camera.PhotoAspectRatio
import app.cameraplus.camera.PhotoResolution
import app.cameraplus.camera.SelfTimer
import app.cameraplus.camera.StillQuality
import app.cameraplus.camera.VideoFrameRate
import app.cameraplus.camera.VideoQuality
import app.cameraplus.camera.WhiteBalancePreset
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A named bundle of the settings a photographer re-enters by hand every time
 * they change subject: a Nothing forum request ("save my settings as a preset").
 */
data class CameraPreset(
    val name: String,
    val capture: CapturePreferences = CapturePreferences(),
    val processing: ProcessingSettings = ProcessingSettings.NATURAL,
)

/**
 * Line-based text codec for presets, so the feature is testable on the JVM and
 * the stored blob stays readable.
 *
 * One preset per line: `name|captureFields|processingFields`, with each field
 * as `key=value` joined by commas. Every token is percent-escaped, so a preset
 * name may contain pipes, commas, equals signs or newlines. Decoding is
 * tolerant: unknown keys are ignored and missing keys fall back to the
 * preference defaults, which keeps an older or newer blob loadable.
 */
object PresetCodec {

    private const val PRESET_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = "|"
    private const val PAIR_SEPARATOR = ","
    private const val KEY_VALUE_SEPARATOR = "="

    fun encode(presets: List<CameraPreset>): String = presets.joinToString(PRESET_SEPARATOR) { preset ->
        listOf(
            escape(preset.name),
            encodeFields(captureFields(preset.capture)),
            encodeFields(processingFields(preset.processing)),
        ).joinToString(FIELD_SEPARATOR)
    }

    fun decode(text: String): List<CameraPreset> = text
        .split(PRESET_SEPARATOR)
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size < 3) return@mapNotNull null
            val name = unescape(parts[0]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            runCatching {
                CameraPreset(
                    name = name,
                    capture = captureFrom(decodeFields(parts[1])),
                    processing = processingFrom(decodeFields(parts[2])),
                )
            }.getOrNull()
        }

    private fun captureFields(c: CapturePreferences): Map<String, String> = mapOf(
        "flash" to c.flashChoice.name,
        "aspect" to c.photoAspectRatio.name,
        "resolution" to c.photoResolution.name,
        "videoQuality" to c.videoQuality.name,
        "videoFrameRate" to c.videoFrameRate.name,
        "selfTimer" to c.selfTimer.name,
        "selfTimerShots" to c.selfTimerShots.toString(),
        "mirror" to c.mirrorCapture.toString(),
        "shutterSound" to c.shutterSoundEnabled.toString(),
        "volumeShutter" to c.volumeKeyShutter.toString(),
        "touchShutter" to c.touchShutter.toString(),
        "tracking" to c.subjectTracking.toString(),
        "histogram" to c.histogramEnabled.toString(),
        "zebras" to c.zebrasEnabled.toString(),
        "peaking" to c.peakingEnabled.toString(),
        "photoHdr" to c.photoHdrEnabled.toString(),
        "timeLapse" to c.timeLapseSpeed.toString(),
        "slowMotion" to c.slowMotionFps.toString(),
        "whiteBalance" to c.whiteBalance.name,
        "whiteBalanceKelvin" to c.whiteBalanceKelvin.toString(),
        "bokeh" to c.portraitBlurLevel.toString(),
        "keepExposure" to c.keepExposure.toString(),
        "exposureIndex" to c.exposureCompensationIndex.toString(),
        "antiBanding" to c.antiBanding.name,
        "stillQuality" to c.stillQuality.name,
        "focusTimeout" to c.focusTimeout.name,
        "bracket" to c.exposureBracket.name,
        "ghost" to c.ghostImage.toString(),
        "focalMm" to c.defaultFocalMm.toString(),
        "voiceCountdown" to c.voiceCountdown.toString(),
        "barcodes" to c.barcodeScanning.name,
        "stripMetadata" to c.stripMetadata.toString(),
        "exifAuthor" to c.exifAuthor,
        "exifCopyright" to c.exifCopyright,
    )

    private fun captureFrom(fields: Map<String, String>): CapturePreferences {
        val defaults = CapturePreferences()
        return CapturePreferences(
            flashChoice = fields.enumOf("flash", defaults.flashChoice),
            photoAspectRatio = fields.enumOf("aspect", defaults.photoAspectRatio),
            photoResolution = fields.enumOf("resolution", defaults.photoResolution),
            videoQuality = fields.enumOf("videoQuality", defaults.videoQuality),
            videoFrameRate = fields.enumOf("videoFrameRate", defaults.videoFrameRate),
            selfTimer = fields.enumOf("selfTimer", defaults.selfTimer),
            selfTimerShots = fields.intOr("selfTimerShots", defaults.selfTimerShots)
                .coerceIn(1, 5),
            mirrorCapture = fields.boolOr("mirror", defaults.mirrorCapture),
            shutterSoundEnabled = fields.boolOr("shutterSound", defaults.shutterSoundEnabled),
            volumeKeyShutter = fields.boolOr("volumeShutter", defaults.volumeKeyShutter),
            touchShutter = fields.boolOr("touchShutter", defaults.touchShutter),
            subjectTracking = fields.boolOr("tracking", defaults.subjectTracking),
            histogramEnabled = fields.boolOr("histogram", defaults.histogramEnabled),
            zebrasEnabled = fields.boolOr("zebras", defaults.zebrasEnabled),
            peakingEnabled = fields.boolOr("peaking", defaults.peakingEnabled),
            photoHdrEnabled = fields.boolOr("photoHdr", defaults.photoHdrEnabled),
            timeLapseSpeed = fields.intOr("timeLapse", defaults.timeLapseSpeed),
            slowMotionFps = fields.intOr("slowMotion", defaults.slowMotionFps),
            whiteBalance = fields.enumOf("whiteBalance", defaults.whiteBalance),
            whiteBalanceKelvin = fields.intOr("whiteBalanceKelvin", defaults.whiteBalanceKelvin),
            portraitBlurLevel = fields.floatOr("bokeh", defaults.portraitBlurLevel)
                .coerceIn(0f, 1f),
            keepExposure = fields.boolOr("keepExposure", defaults.keepExposure),
            exposureCompensationIndex = fields.intOr(
                "exposureIndex",
                defaults.exposureCompensationIndex,
            ),
            antiBanding = fields.enumOf("antiBanding", defaults.antiBanding),
            stillQuality = fields.enumOf("stillQuality", defaults.stillQuality),
            focusTimeout = fields.enumOf("focusTimeout", defaults.focusTimeout),
            exposureBracket = fields.enumOf("bracket", defaults.exposureBracket),
            ghostImage = fields.boolOr("ghost", defaults.ghostImage),
            defaultFocalMm = fields.intOr("focalMm", defaults.defaultFocalMm),
            voiceCountdown = fields.boolOr("voiceCountdown", defaults.voiceCountdown),
            barcodeScanning = fields.enumOf("barcodes", defaults.barcodeScanning),
            stripMetadata = fields.boolOr("stripMetadata", defaults.stripMetadata),
            exifAuthor = fields["exifAuthor"].orEmpty().take(64),
            exifCopyright = fields["exifCopyright"].orEmpty().take(64),
        )
    }

    private fun processingFields(settings: ProcessingSettings): Map<String, String> = mapOf(
        "sharpening" to settings.sharpening.toString(),
        "noiseReduction" to settings.noiseReduction.toString(),
        "hdr" to settings.hdr.toString(),
        "skinSmoothing" to settings.skinSmoothing.toString(),
        "filter" to settings.filter.name,
    )

    private fun processingFrom(fields: Map<String, String>): ProcessingSettings {
        val defaults = ProcessingSettings.NATURAL
        return ProcessingSettings(
            sharpening = fields.floatOr("sharpening", defaults.sharpening).coerceIn(0f, 1f),
            noiseReduction = fields.floatOr("noiseReduction", defaults.noiseReduction)
                .coerceIn(0f, 1f),
            hdr = fields.floatOr("hdr", defaults.hdr).coerceIn(0f, 1f),
            skinSmoothing = fields.floatOr("skinSmoothing", defaults.skinSmoothing)
                .coerceIn(0f, 1f),
            filter = fields.enumOf("filter", defaults.filter),
        )
    }

    private fun encodeFields(fields: Map<String, String>): String = fields.entries
        .joinToString(PAIR_SEPARATOR) { (key, value) ->
            escape(key) + KEY_VALUE_SEPARATOR + escape(value)
        }

    private fun decodeFields(text: String): Map<String, String> = text
        .split(PAIR_SEPARATOR)
        .mapNotNull { pair ->
            val index = pair.indexOf(KEY_VALUE_SEPARATOR)
            if (index <= 0) return@mapNotNull null
            unescape(pair.substring(0, index)) to unescape(pair.substring(index + 1))
        }
        .toMap()

    private fun escape(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun unescape(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private inline fun <reified T : Enum<T>> Map<String, String>.enumOf(
        key: String,
        fallback: T,
    ): T = this[key]?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

    private fun Map<String, String>.boolOr(key: String, fallback: Boolean): Boolean =
        this[key]?.toBooleanStrictOrNull() ?: fallback

    private fun Map<String, String>.intOr(key: String, fallback: Int): Int =
        this[key]?.toIntOrNull() ?: fallback

    private fun Map<String, String>.floatOr(key: String, fallback: Float): Float =
        this[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback
}

/**
 * Named presets as a single encoded blob in SharedPreferences. All user data,
 * no network, no file writes outside the app's own store.
 */
class PresetsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): List<CameraPreset> = PresetCodec.decode(prefs.getString(KEY_PRESETS, null).orEmpty())

    fun save(presets: List<CameraPreset>) {
        prefs.edit().putString(KEY_PRESETS, PresetCodec.encode(presets)).apply()
    }

    private companion object {
        const val FILE_NAME = "cameraplus_presets"
        const val KEY_PRESETS = "presets"
    }
}
