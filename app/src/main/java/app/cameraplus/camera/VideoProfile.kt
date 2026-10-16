package app.cameraplus.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Video stabilization policy (M4).
 *
 * CameraX requests platform stabilization when the session asks for it; which
 * mechanism the hardware actually uses is decided here so the UI can be
 * honest about it: preview stabilization (preview + recording, the strongest
 * mode public APIs expose) > standard video stabilization > optical only >
 * nothing.
 */
enum class StabilizationLevel(val label: String) {
    PREVIEW("PREVIEW"),
    STANDARD("STANDARD"),
    OPTICAL("OIS"),
    NONE("OFF"),
}

object StabilizationProfile {

    fun choose(
        previewSupported: Boolean,
        standardSupported: Boolean,
        opticalSupported: Boolean,
    ): StabilizationLevel = when {
        previewSupported -> StabilizationLevel.PREVIEW
        standardSupported -> StabilizationLevel.STANDARD
        opticalSupported -> StabilizationLevel.OPTICAL
        else -> StabilizationLevel.NONE
    }

    /**
     * Rough fraction of the frame that stabilization takes away. The real
     * crop is camera-managed and not exposed through public APIs, so these
     * are estimates for the honest "what you lose" overlay in video mode.
     */
    fun estimatedCrop(level: StabilizationLevel): Float = when (level) {
        StabilizationLevel.PREVIEW -> 0.15f
        StabilizationLevel.STANDARD -> 0.07f
        StabilizationLevel.OPTICAL -> 0.03f
        StabilizationLevel.NONE -> 0f
    }
}

/**
 * Lens/zoom plan (M4): zoom stops for the bag of physical lenses behind the
 * active camera, expressed relative to the reference (main) lens. Selecting a
 * stop uses zoom-ratio switching, which the platform resolves to a physical
 * lens transition without a session rebind — so recording keeps running and
 * focus/exposure transitions stay smooth.
 */
object LensZoomPlan {

    fun zoomStops(mainFocalMm: Float, allFocalsMm: List<Float>): List<Float> {
        if (mainFocalMm <= 0f) return listOf(1f)
        val stops = allFocalsMm
            .asSequence()
            .filter { it > 0f }
            .map { it / mainFocalMm }
            .map { (it * 10f).roundToInt() / 10f }
            .filter { it in 0.3f..10f }
            .distinct()
            .toMutableList()
        if (stops.none { abs(it - 1f) < 0.06f }) stops += 1f
        return stops.sorted()
    }

    /** UI label, e.g. "0.5x" for an ultrawide stop and "2x" for a telephoto. */
    fun stopLabel(stop: Float): String = when {
        stop % 1f == 0f -> String.format(Locale.US, "%dx", stop.toInt())
        else -> String.format(Locale.US, "%.1fx", stop)
    }
}

/** Per-mode ceilings from the original camera, bounded by the lens/hardware. */
enum class ZoomUseCase { PHOTO, VIDEO, EXPERT, PORTRAIT, NIGHT, SLOW_MOTION, TIME_LAPSE, PANORAMA, MACRO }

object ModeZoomPlan {
    fun maximum(useCase: ZoomUseCase, hardwareMaximum: Float): Float {
        val modeMaximum = when (useCase) {
            ZoomUseCase.PHOTO, ZoomUseCase.VIDEO, ZoomUseCase.EXPERT -> Float.POSITIVE_INFINITY
            ZoomUseCase.PORTRAIT -> 4f
            ZoomUseCase.NIGHT -> 30f
            ZoomUseCase.SLOW_MOTION -> 2f
            ZoomUseCase.TIME_LAPSE -> 10f
            ZoomUseCase.PANORAMA -> 1f
            ZoomUseCase.MACRO -> 4f
        }
        return hardwareMaximum.coerceAtLeast(1f).coerceAtMost(modeMaximum)
    }
}
