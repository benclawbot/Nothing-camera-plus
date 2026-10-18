package app.cameraplus.settings

import android.content.Context
import app.cameraplus.camera.AntiBanding
import app.cameraplus.camera.BarcodeScanning
import app.cameraplus.camera.ExposureBracket
import app.cameraplus.camera.FlashChoice
import app.cameraplus.camera.FocalPresets
import app.cameraplus.camera.FocusTimeout
import app.cameraplus.camera.FocusBracket
import app.cameraplus.camera.PhotoAspectRatio
import app.cameraplus.camera.PhotoResolution
import app.cameraplus.camera.SelfTimer
import app.cameraplus.camera.StillQuality
import app.cameraplus.camera.VideoFrameRate
import app.cameraplus.camera.VideoQuality
import app.cameraplus.camera.WhiteBalancePreset

/** Non-sensitive user capture choices persisted across launches. */
data class CapturePreferences(
    val flashChoice: FlashChoice = FlashChoice.OFF,
    val photoAspectRatio: PhotoAspectRatio = PhotoAspectRatio.FOUR_THREE,
    val photoResolution: PhotoResolution = PhotoResolution.STANDARD,
    val videoQuality: VideoQuality = VideoQuality.FHD,
    val videoFrameRate: VideoFrameRate = VideoFrameRate.FPS_30,
    val selfTimer: SelfTimer = SelfTimer.OFF,
    val mirrorCapture: Boolean = false,
    val shutterSoundEnabled: Boolean = true,
    val volumeKeyShutter: Boolean = false,
    val touchShutter: Boolean = false,
    val subjectTracking: Boolean = false,
    val histogramEnabled: Boolean = false,
    val zebrasEnabled: Boolean = false,
    val peakingEnabled: Boolean = false,
    val photoHdrEnabled: Boolean = false,
    /** Android Ultra HDR JPEG capture, when supported by CameraX and the lens. */
    val ultraHdrEnabled: Boolean = false,
    /** Correct small horizon tilt in processed JPEG output. */
    val autoLevelEnabled: Boolean = false,
    val timeLapseSpeed: Int = 60,
    val slowMotionFps: Int = 120,
    val whiteBalance: WhiteBalancePreset = WhiteBalancePreset.AUTO,
    val whiteBalanceKelvin: Int = 5_500,
    val portraitBlurLevel: Float = 0.45f,
    /** Sticky exposure: keep [exposureCompensationIndex] across shots and binds. */
    val keepExposure: Boolean = false,
    /** Last exposure-compensation index the user dialled in. */
    val exposureCompensationIndex: Int = 0,
    val antiBanding: AntiBanding = AntiBanding.AUTO,
    /** Still-processing quality: FAST keeps the instant shutter, HIGH leaves it. */
    val stillQuality: StillQuality = StillQuality.FAST,
    val focusTimeout: FocusTimeout = FocusTimeout.OFF,
    val exposureBracket: ExposureBracket = ExposureBracket.OFF,
    /** Merge AE bracket frames into one bounded-resolution SDR HDR result. */
    val mergeExposureBracket: Boolean = true,
    val focusBracket: FocusBracket = FocusBracket.OFF,
    val focusAssistEnabled: Boolean = false,
    /** Onion skin: hold the last shot over the viewfinder at low opacity. */
    val ghostImage: Boolean = false,
    /** 35 mm-equivalent default focal (0 = the lens's own wide stop). */
    val defaultFocalMm: Int = 0,
    /** Shots the self-timer fires once its countdown ends (1-5). */
    val selfTimerShots: Int = 1,
    val voiceCountdown: Boolean = false,
    val barcodeScanning: BarcodeScanning = BarcodeScanning.QR_ONLY,
    /** Remove GPS and device-identity tags from saved stills. */
    val stripMetadata: Boolean = false,
    val exifAuthor: String = "",
    val exifCopyright: String = "",
    /** Up to four one-tap controls shown beside the viewfinder. */
    val pinnedSettings: List<PinnedSetting> = emptyList(),
)

class CapturePreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("capture_preferences", Context.MODE_PRIVATE)

    fun load(): CapturePreferences = CapturePreferences(
        flashChoice = enumValue(prefs.getString(KEY_FLASH, null), FlashChoice.OFF),
        photoAspectRatio = enumValue(prefs.getString(KEY_ASPECT, null), PhotoAspectRatio.FOUR_THREE),
        photoResolution = enumValue(prefs.getString(KEY_RESOLUTION, null), PhotoResolution.STANDARD),
        videoQuality = enumValue(prefs.getString(KEY_QUALITY, null), VideoQuality.FHD),
        videoFrameRate = enumValue(prefs.getString(KEY_FPS, null), VideoFrameRate.FPS_30),
        selfTimer = enumValue(prefs.getString(KEY_TIMER, null), SelfTimer.OFF),
        mirrorCapture = prefs.getBoolean(KEY_MIRROR, false),
        shutterSoundEnabled = prefs.getBoolean(KEY_SOUND, true),
        volumeKeyShutter = prefs.getBoolean(KEY_VOLUME_SHUTTER, false),
        touchShutter = prefs.getBoolean(KEY_TOUCH_SHUTTER, false),
        subjectTracking = prefs.getBoolean(KEY_SUBJECT_TRACKING, false),
        histogramEnabled = prefs.getBoolean(KEY_HISTOGRAM, false),
        zebrasEnabled = prefs.getBoolean(KEY_ZEBRAS, false),
        peakingEnabled = prefs.getBoolean(KEY_PEAKING, false),
        photoHdrEnabled = prefs.getBoolean(KEY_HDR, false),
        ultraHdrEnabled = prefs.getBoolean(KEY_ULTRA_HDR, false),
        autoLevelEnabled = prefs.getBoolean(KEY_AUTO_LEVEL, false),
        timeLapseSpeed = prefs.getInt(KEY_TIME_LAPSE_SPEED, 60)
            .takeIf { it in TIME_LAPSE_SPEEDS } ?: 60,
        slowMotionFps = prefs.getInt(KEY_SLOW_MOTION_FPS, 120)
            .takeIf { it in SLOW_MOTION_RATES } ?: 120,
        whiteBalance = enumValue(prefs.getString(KEY_WB, null), WhiteBalancePreset.AUTO),
        whiteBalanceKelvin = prefs.getInt(KEY_WB_KELVIN, 5_500).coerceIn(2_000, 10_000),
        portraitBlurLevel = prefs.getFloat(KEY_BOKEH, 0.45f)
            .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.45f,
        keepExposure = prefs.getBoolean(KEY_KEEP_EXPOSURE, false),
        exposureCompensationIndex = prefs.getInt(KEY_EXPOSURE_INDEX, 0),
        antiBanding = enumValue(prefs.getString(KEY_ANTI_BANDING, null), AntiBanding.AUTO),
        stillQuality = enumValue(prefs.getString(KEY_STILL_QUALITY, null), StillQuality.FAST),
        focusTimeout = enumValue(prefs.getString(KEY_FOCUS_TIMEOUT, null), FocusTimeout.OFF),
        exposureBracket = enumValue(prefs.getString(KEY_BRACKET, null), ExposureBracket.OFF),
        mergeExposureBracket = prefs.getBoolean(KEY_MERGE_BRACKET, true),
        focusBracket = enumValue(prefs.getString(KEY_FOCUS_BRACKET, null), FocusBracket.OFF),
        focusAssistEnabled = prefs.getBoolean(KEY_FOCUS_ASSIST, false),
        ghostImage = prefs.getBoolean(KEY_GHOST, false),
        defaultFocalMm = prefs.getInt(KEY_DEFAULT_FOCAL, 0)
            .takeIf { it in FOCAL_PRESETS } ?: 0,
        selfTimerShots = prefs.getInt(KEY_TIMER_SHOTS, 1).coerceIn(1, MAX_TIMER_SHOTS),
        voiceCountdown = prefs.getBoolean(KEY_VOICE_COUNTDOWN, false),
        barcodeScanning = enumValue(
            prefs.getString(KEY_BARCODE, null),
            BarcodeScanning.QR_ONLY,
        ),
        stripMetadata = prefs.getBoolean(KEY_STRIP_METADATA, false),
        exifAuthor = prefs.getString(KEY_EXIF_AUTHOR, "").orEmpty().take(64),
        exifCopyright = prefs.getString(KEY_EXIF_COPYRIGHT, "").orEmpty().take(64),
        pinnedSettings = loadPinnedSettings(),
    )

    /** Persists only the tracking toggle; the rest of the sheet is untouched. */
    fun setSubjectTracking(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUBJECT_TRACKING, enabled).apply()
    }

    /** Persists only the expert histogram toggle, like [setSubjectTracking]. */
    fun setHistogram(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HISTOGRAM, enabled).apply()
    }

    /** Persists only the zebra-stripe toggle. */
    fun setZebras(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ZEBRAS, enabled).apply()
    }

    /** Persists only the focus-peaking toggle. */
    fun setPeaking(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PEAKING, enabled).apply()
    }

    /**
     * Persists only the exposure-compensation index, so the sticky-exposure
     * preference has a value to re-apply on the next launch.
     */
    fun setExposureCompensation(index: Int) {
        prefs.edit().putInt(KEY_EXPOSURE_INDEX, index).apply()
    }

    fun save(options: CapturePreferences) {
        prefs.edit()
            .putString(KEY_FLASH, options.flashChoice.name)
            .putString(KEY_ASPECT, options.photoAspectRatio.name)
            .putString(KEY_RESOLUTION, options.photoResolution.name)
            .putString(KEY_QUALITY, options.videoQuality.name)
            .putString(KEY_FPS, options.videoFrameRate.name)
            .putString(KEY_TIMER, options.selfTimer.name)
            .putBoolean(KEY_MIRROR, options.mirrorCapture)
            .putBoolean(KEY_SOUND, options.shutterSoundEnabled)
            .putBoolean(KEY_VOLUME_SHUTTER, options.volumeKeyShutter)
            .putBoolean(KEY_TOUCH_SHUTTER, options.touchShutter)
            .putBoolean(KEY_SUBJECT_TRACKING, options.subjectTracking)
            .putBoolean(KEY_HISTOGRAM, options.histogramEnabled)
            .putBoolean(KEY_ZEBRAS, options.zebrasEnabled)
            .putBoolean(KEY_PEAKING, options.peakingEnabled)
            .putBoolean(KEY_HDR, options.photoHdrEnabled)
            .putBoolean(KEY_ULTRA_HDR, options.ultraHdrEnabled)
            .putBoolean(KEY_AUTO_LEVEL, options.autoLevelEnabled)
            .putInt(KEY_TIME_LAPSE_SPEED, options.timeLapseSpeed)
            .putInt(KEY_SLOW_MOTION_FPS, options.slowMotionFps)
            .putString(KEY_WB, options.whiteBalance.name)
            .putInt(KEY_WB_KELVIN, options.whiteBalanceKelvin.coerceIn(2_000, 10_000))
            .putFloat(KEY_BOKEH, options.portraitBlurLevel.coerceIn(0f, 1f))
            .putBoolean(KEY_KEEP_EXPOSURE, options.keepExposure)
            .putInt(KEY_EXPOSURE_INDEX, options.exposureCompensationIndex)
            .putString(KEY_ANTI_BANDING, options.antiBanding.name)
            .putString(KEY_STILL_QUALITY, options.stillQuality.name)
            .putString(KEY_FOCUS_TIMEOUT, options.focusTimeout.name)
            .putString(KEY_BRACKET, options.exposureBracket.name)
            .putBoolean(KEY_MERGE_BRACKET, options.mergeExposureBracket)
            .putString(KEY_FOCUS_BRACKET, options.focusBracket.name)
            .putBoolean(KEY_FOCUS_ASSIST, options.focusAssistEnabled)
            .putBoolean(KEY_GHOST, options.ghostImage)
            .putInt(KEY_DEFAULT_FOCAL, options.defaultFocalMm.takeIf { it in FOCAL_PRESETS } ?: 0)
            .putInt(KEY_TIMER_SHOTS, options.selfTimerShots.coerceIn(1, MAX_TIMER_SHOTS))
            .putBoolean(KEY_VOICE_COUNTDOWN, options.voiceCountdown)
            .putString(KEY_BARCODE, options.barcodeScanning.name)
            .putBoolean(KEY_STRIP_METADATA, options.stripMetadata)
            .putString(KEY_EXIF_AUTHOR, options.exifAuthor.take(64))
            .putString(KEY_EXIF_COPYRIGHT, options.exifCopyright.take(64))
            .putString(
                KEY_PINNED_SETTINGS,
                options.pinnedSettings
                    .filter { it != PinnedSetting.NONE }
                    .distinct()
                    .take(MAX_PINNED_SETTINGS)
                    .joinToString(PINNED_SEPARATOR) { it.name },
            )
            // Keep the first pin for downgrade compatibility with older builds.
            .putString(
                KEY_PINNED_SETTING,
                options.pinnedSettings.firstOrNull { it != PinnedSetting.NONE }?.name
                    ?: PinnedSetting.NONE.name,
            )
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

    private fun loadPinnedSettings(): List<PinnedSetting> {
        val saved = prefs.getString(KEY_PINNED_SETTINGS, null)
        val names = saved?.split(PINNED_SEPARATOR)
            ?: listOfNotNull(prefs.getString(KEY_PINNED_SETTING, null))
        return names.mapNotNull { name -> PinnedSetting.entries.firstOrNull { it.name == name } }
            .filter { it != PinnedSetting.NONE }
            .distinct()
            .take(MAX_PINNED_SETTINGS)
    }

    private companion object {
        const val KEY_FLASH = "flash"
        const val KEY_ASPECT = "aspect"
        const val KEY_RESOLUTION = "photo_resolution"
        const val KEY_QUALITY = "video_quality"
        const val KEY_FPS = "video_fps"
        const val KEY_TIMER = "self_timer"
        const val KEY_MIRROR = "mirror"
        const val KEY_SOUND = "shutter_sound"
        const val KEY_VOLUME_SHUTTER = "volume_shutter"
        const val KEY_TOUCH_SHUTTER = "touch_shutter"
        const val KEY_SUBJECT_TRACKING = "subject_tracking"
        const val KEY_HISTOGRAM = "histogram"
        const val KEY_ZEBRAS = "zebras"
        const val KEY_PEAKING = "peaking"
        const val KEY_HDR = "photo_hdr"
        const val KEY_ULTRA_HDR = "ultra_hdr"
        const val KEY_AUTO_LEVEL = "auto_level"
        const val KEY_TIME_LAPSE_SPEED = "time_lapse_speed"
        const val KEY_SLOW_MOTION_FPS = "slow_motion_fps"
        const val KEY_WB = "white_balance"
        const val KEY_WB_KELVIN = "white_balance_kelvin"
        const val KEY_BOKEH = "portrait_blur_level"
        const val KEY_KEEP_EXPOSURE = "keep_exposure"
        const val KEY_EXPOSURE_INDEX = "exposure_index"
        const val KEY_ANTI_BANDING = "anti_banding"
        const val KEY_STILL_QUALITY = "still_quality"
        const val KEY_FOCUS_TIMEOUT = "focus_timeout"
        const val KEY_BRACKET = "exposure_bracket"
        const val KEY_MERGE_BRACKET = "merge_exposure_bracket"
        const val KEY_FOCUS_BRACKET = "focus_bracket"
        const val KEY_FOCUS_ASSIST = "focus_assist"
        const val KEY_GHOST = "ghost_image"
        const val KEY_DEFAULT_FOCAL = "default_focal_mm"
        const val KEY_TIMER_SHOTS = "self_timer_shots"
        const val KEY_VOICE_COUNTDOWN = "voice_countdown"
        const val KEY_BARCODE = "barcode_scanning"
        const val KEY_STRIP_METADATA = "strip_metadata"
        const val KEY_EXIF_AUTHOR = "exif_author"
        const val KEY_EXIF_COPYRIGHT = "exif_copyright"
        const val KEY_PINNED_SETTING = "pinned_setting"
        const val KEY_PINNED_SETTINGS = "pinned_settings"
        const val PINNED_SEPARATOR = "|"
        const val MAX_PINNED_SETTINGS = 4
        val TIME_LAPSE_SPEEDS = setOf(15, 30, 60, 120, 240, 480, 960)
        val SLOW_MOTION_RATES = setOf(120, 240, 480)

        /** Focal presets the setting accepts, plus 0 for the lens's own 1x. */
        val FOCAL_PRESETS = setOf(0) + FocalPresets.MILLIMETRES

        /** Upper bound for a self-timer series. */
        const val MAX_TIMER_SHOTS = 5
    }
}
