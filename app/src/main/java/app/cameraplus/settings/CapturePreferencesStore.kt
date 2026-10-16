package app.cameraplus.settings

import android.content.Context
import app.cameraplus.camera.FlashChoice
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
    val photoHdrEnabled: Boolean = false,
    val timeLapseSpeed: Int = 60,
    val slowMotionFps: Int = 120,
    val whiteBalance: WhiteBalancePreset = WhiteBalancePreset.AUTO,
    val whiteBalanceKelvin: Int = 5_500,
    val portraitBlurLevel: Float = 0.45f,
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
        photoHdrEnabled = prefs.getBoolean(KEY_HDR, false),
        timeLapseSpeed = prefs.getInt(KEY_TIME_LAPSE_SPEED, 60)
            .takeIf { it in TIME_LAPSE_SPEEDS } ?: 60,
        slowMotionFps = prefs.getInt(KEY_SLOW_MOTION_FPS, 120)
            .takeIf { it in SLOW_MOTION_RATES } ?: 120,
        whiteBalance = enumValue(prefs.getString(KEY_WB, null), WhiteBalancePreset.AUTO),
        whiteBalanceKelvin = prefs.getInt(KEY_WB_KELVIN, 5_500).coerceIn(2_000, 10_000),
        portraitBlurLevel = prefs.getFloat(KEY_BOKEH, 0.45f)
            .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.45f,
    )

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
            .putBoolean(KEY_HDR, options.photoHdrEnabled)
            .putInt(KEY_TIME_LAPSE_SPEED, options.timeLapseSpeed)
            .putInt(KEY_SLOW_MOTION_FPS, options.slowMotionFps)
            .putString(KEY_WB, options.whiteBalance.name)
            .putInt(KEY_WB_KELVIN, options.whiteBalanceKelvin.coerceIn(2_000, 10_000))
            .putFloat(KEY_BOKEH, options.portraitBlurLevel.coerceIn(0f, 1f))
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
        const val KEY_HDR = "photo_hdr"
        const val KEY_TIME_LAPSE_SPEED = "time_lapse_speed"
        const val KEY_SLOW_MOTION_FPS = "slow_motion_fps"
        const val KEY_WB = "white_balance"
        const val KEY_WB_KELVIN = "white_balance_kelvin"
        const val KEY_BOKEH = "portrait_blur_level"
        val TIME_LAPSE_SPEEDS = setOf(15, 30, 60, 120, 240, 480, 960)
        val SLOW_MOTION_RATES = setOf(120, 240, 480)
    }
}
