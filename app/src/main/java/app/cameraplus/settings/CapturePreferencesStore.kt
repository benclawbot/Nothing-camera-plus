package app.cameraplus.settings

import android.content.Context
import app.cameraplus.camera.AntiBanding
import app.cameraplus.camera.FlashChoice
import app.cameraplus.camera.FocusTimeout
import app.cameraplus.camera.PhotoAspectRatio
import app.cameraplus.camera.PhotoResolution
import app.cameraplus.camera.SelfTimer
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
    val focusTimeout: FocusTimeout = FocusTimeout.OFF,
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
        focusTimeout = enumValue(prefs.getString(KEY_FOCUS_TIMEOUT, null), FocusTimeout.OFF),
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
            .putInt(KEY_TIME_LAPSE_SPEED, options.timeLapseSpeed)
            .putInt(KEY_SLOW_MOTION_FPS, options.slowMotionFps)
            .putString(KEY_WB, options.whiteBalance.name)
            .putInt(KEY_WB_KELVIN, options.whiteBalanceKelvin.coerceIn(2_000, 10_000))
            .putFloat(KEY_BOKEH, options.portraitBlurLevel.coerceIn(0f, 1f))
            .putBoolean(KEY_KEEP_EXPOSURE, options.keepExposure)
            .putInt(KEY_EXPOSURE_INDEX, options.exposureCompensationIndex)
            .putString(KEY_ANTI_BANDING, options.antiBanding.name)
            .putString(KEY_FOCUS_TIMEOUT, options.focusTimeout.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

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
        const val KEY_TIME_LAPSE_SPEED = "time_lapse_speed"
        const val KEY_SLOW_MOTION_FPS = "slow_motion_fps"
        const val KEY_WB = "white_balance"
        const val KEY_WB_KELVIN = "white_balance_kelvin"
        const val KEY_BOKEH = "portrait_blur_level"
        const val KEY_KEEP_EXPOSURE = "keep_exposure"
        const val KEY_EXPOSURE_INDEX = "exposure_index"
        const val KEY_ANTI_BANDING = "anti_banding"
        const val KEY_FOCUS_TIMEOUT = "focus_timeout"
        val TIME_LAPSE_SPEEDS = setOf(15, 30, 60, 120, 240, 480, 960)
        val SLOW_MOTION_RATES = setOf(120, 240, 480)
    }
}
