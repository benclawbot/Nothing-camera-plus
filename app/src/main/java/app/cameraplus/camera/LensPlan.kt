package app.cameraplus.camera

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A back-facing camera as the platform reports it, normalized for planning.
 *
 * [focalsMm] is the full focal-length list; logical multi-cameras report one
 * entry per physical lens behind them. [minZoom]/[maxZoom] are the ratio range
 * the camera accepts, which a logical camera widens to cover its lenses
 * (a range that starts below 1 already switches lenses by zooming).
 */
data class LensSpec(
    val cameraId: String,
    val focalsMm: List<Float>,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val pixelCount: Long,
    val minZoom: Float,
    val maxZoom: Float,
)

/** One camera with its zoom range expressed in *displayed* zoom terms. */
data class Lens(
    val cameraId: String,
    val baseStop: Float,
    val eqFocalMm: Float,
    val minZoom: Float,
    val maxZoom: Float,
) {
    val minDisplayedZoom: Float get() = baseStop * minZoom
    val maxDisplayedZoom: Float get() = baseStop * maxZoom
}

/** What a requested zoom means in platform terms: which camera, which ratio. */
data class LensTarget(
    val cameraId: String,
    val zoomRatio: Float,
    val displayedZoom: Float,
    val needsRebind: Boolean,
)

/**
 * The lens set of one facing direction, with the stop list the UI shows and
 * the mapping from a user-facing zoom value to (camera, ratio).
 */
data class LensLayout(
    val referenceCameraId: String,
    val lenses: List<Lens>,
    val stops: List<Float>,
    /** Extra physical focal stops exposed behind a single logical camera. */
    val opticalStops: List<Float> = emptyList(),
) {
    val minDisplayedZoom: Float get() = lenses.minOf { it.minDisplayedZoom }
    val maxDisplayedZoom: Float get() = lenses.maxOf { it.maxDisplayedZoom }

    fun clampDisplayed(value: Float): Float =
        value.coerceIn(minDisplayedZoom, maxDisplayedZoom)

    fun baseStopOf(cameraId: String?): Float =
        lenses.firstOrNull { it.cameraId == cameraId }?.baseStop ?: 1f

    fun displayedZoomFor(cameraId: String?, zoomRatio: Float): Float =
        baseStopOf(cameraId) * zoomRatio

    /** 35 mm-equivalent focal of the reference (1x) lens; the label basis. */
    val referenceEqFocalMm: Float
        get() = lenses.firstOrNull { it.cameraId == referenceCameraId }
            ?.eqFocalMm?.takeIf { it > 0f } ?: LensPlan.REFERENCE_EQ_FOCAL_MM

    /**
     * 35 mm-equivalent focal length served at [displayedZoom]: the serving
     * lens' equivalent focal scaled by its zoom ratio, so digital zoom counts.
     * The original prints this same number on its zoom slider ("%smm", with
     * the 43.27 mm full-frame diagonal used here). Null when the plan cannot
     * say — the caller falls back to the ratio label.
     */
    fun eqFocalMmFor(displayedZoom: Float): Float? {
        if (displayedZoom <= 0f || !displayedZoom.isFinite()) return null
        val target = targetFor(displayedZoom, null)
        val lens = lenses.firstOrNull { it.cameraId == target.cameraId } ?: return null
        return (lens.eqFocalMm * target.zoomRatio).takeIf { it > 0f && it.isFinite() }
    }

    /**
     * Resolves a requested displayed zoom onto a camera and ratio. The widest
     * camera that can still reach the requested zoom wins (least crop), and
     * the camera that is already bound wins ties, so normal zooming never
     * forces a session rebind. Values outside every lens' range clamp to the
     * nearest reachable extreme rather than failing.
     */
    fun targetFor(displayed: Float, currentCameraId: String?): LensTarget {
        val request = clampDisplayed(displayed)
        // A request that lands on a physical lens' own stop takes that lens at
        // its native ratio — the optical path — instead of cropping another
        // camera, which is what the stock 0.6x/1x/2x/3x buttons do. The
        // already-bound camera wins any tie, so nothing rebinds needlessly.
        val optical = lenses
            .filter { abs(it.baseStop - request) < OPTICAL_TOLERANCE }
            .minWithOrNull(preference(currentCameraId))
        // Between lens stops it is digital zoom: stay on the bound camera
        // when it can do it, else take the widest lens that still reaches.
        val reachable = lenses.filter { lens ->
            val ratio = request / lens.baseStop
            ratio >= lens.minZoom - EPSILON && ratio <= lens.maxZoom + EPSILON
        }
        val chosen = optical ?: reachable.minWithOrNull(preference(currentCameraId))
            ?: lenses.maxBy { it.maxDisplayedZoom }
        val ratio = (request / chosen.baseStop).coerceIn(chosen.minZoom, chosen.maxZoom)
        return LensTarget(
            cameraId = chosen.cameraId,
            zoomRatio = ratio,
            displayedZoom = chosen.baseStop * ratio,
            needsRebind = chosen.cameraId != currentCameraId,
        )
    }

    companion object {
        private const val EPSILON = 1e-3f
        private const val OPTICAL_TOLERANCE = 0.05f

        /**
         * Ordering used when several lenses can serve a request: the camera
         * that is already bound first (no needless rebind), then the widest
         * lens (least crop).
         *
         * Written explicitly so the priority is unambiguous: current camera
         * first, then the smallest (widest) base stop.
         */
        private fun preference(currentCameraId: String?): Comparator<Lens> =
            Comparator { a, b ->
                val aCurrent = if (a.cameraId == currentCameraId) 0 else 1
                val bCurrent = if (b.cameraId == currentCameraId) 0 else 1
                if (aCurrent != bCurrent) aCurrent - bCurrent else a.baseStop.compareTo(b.baseStop)
            }
    }
}

/**
 * Turns what the HAL reports into a [LensLayout] (v1.1 lens + zoom work).
 *
 * Two problems this solves, both visible on multi-lens phones:
 *
 *  - Binding `CameraSelector.requireLensFacing(BACK)` alone picks whichever
 *    back camera the HAL lists first, which on several phones is the
 *    telephoto — the app then opens at "2x" with no way back to 1x/0.6x. The
 *    reference (1x) lens is therefore *chosen*: the camera with the largest
 *    sensor, ties broken by the focal length closest to a 26 mm-equivalent
 *    field of view.
 *  - Zoom stops used to be raw focal ratios, which are meaningless to
 *    `setZoomRatio` (that is relative to the bound camera). Stops are now
 *    displayed zoom values (1x = the reference lens) and every request is
 *    resolved to the camera that can actually produce it.
 *
 * All math here is Android-free so it stays JVM-testable.
 */
object LensPlan {

    /** Field of view the stock 1x button represents, in 35 mm-equivalent mm. */
    const val REFERENCE_EQ_FOCAL_MM = 26f

    /** Diagonal of a 35 mm frame, used for the equivalent-focal conversion. */
    const val FULL_FRAME_DIAGONAL_MM = 43.2666f

    private val DIGITAL_LADDER = listOf(2f, 3f, 5f, 10f, 20f, 30f, 60f)

    fun eqFocalMm(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Float {
        val diagonal = hypot(sensorWidthMm, sensorHeightMm)
        if (focalMm <= 0f || diagonal <= 0f) return 0f
        return focalMm * FULL_FRAME_DIAGONAL_MM / diagonal
    }

    fun build(specs: List<LensSpec>): LensLayout {
        require(specs.isNotEmpty()) { "no cameras to plan" }
        val usable = specs.filter { it.focalsMm.any { f -> f > 0f } }
        require(usable.isNotEmpty()) { "no focal lengths reported" }

        val (referenceId, referenceFocal) = referenceLens(usable)
        val lenses = usable.map { spec ->
            val representative = spec.focalsMm
                .filter { it > 0f }
                .minBy { abs(it - referenceFocal) }
            Lens(
                cameraId = spec.cameraId,
                baseStop = roundStop(representative / referenceFocal),
                eqFocalMm = eqFocalMm(representative, spec.sensorWidthMm, spec.sensorHeightMm),
                minZoom = spec.minZoom.coerceAtLeast(0.01f),
                maxZoom = spec.maxZoom.coerceAtLeast(spec.minZoom.coerceAtLeast(0.01f)),
            )
        }.sortedBy { it.baseStop }

        // A CameraX logical multi-camera commonly reports one camera ID but
        // multiple focal lengths (ultrawide/main/tele behind it). Those focal
        // ratios are real selectable stops even though they are not separate
        // CameraInfo entries; CameraX resolves them with zoomRatio on that
        // same logical camera. Keep these distinct from lenses that require a
        // CameraSelector rebind.
        val opticalStops = usable
            .flatMap { spec -> spec.focalsMm.filter { it > 0f } }
            .map { roundStop(it / referenceFocal) }
            .filter { it > 0f }
            .distinct()
            .sorted()

        return LensLayout(
            referenceCameraId = referenceId,
            lenses = lenses,
            stops = stopsFor(lenses, opticalStops),
            opticalStops = opticalStops,
        )
    }

    /** Single-lens layout: no stops to switch, zoom is whatever the camera has. */
    fun single(cameraId: String, minZoom: Float, maxZoom: Float): LensLayout {
        val lens = Lens(
            cameraId = cameraId,
            baseStop = 1f,
            eqFocalMm = REFERENCE_EQ_FOCAL_MM,
            minZoom = minZoom.coerceAtLeast(0.01f),
            maxZoom = maxZoom.coerceAtLeast(minZoom.coerceAtLeast(0.01f)),
        )
        return LensLayout(referenceCameraId = cameraId, lenses = listOf(lens), stops = listOf(1f))
    }

    /**
     * The reference (1x) camera: biggest sensor first — that is the main lens
     * on every phone layout — then the focal closest to a 26 mm-equivalent
     * view for sensors of equal size.
     */
    private fun referenceLens(specs: List<LensSpec>): Pair<String, Float> {
        val largestSensor = specs.maxOf { it.pixelCount }
        val candidates = specs.filter { it.pixelCount == largestSensor }
        var bestId = candidates.first().cameraId
        var bestFocal = candidates.first().focalsMm.first { it > 0f }
        var bestDistance = Float.MAX_VALUE
        for (spec in candidates) {
            for (focal in spec.focalsMm.filter { it > 0f }) {
                val eq = eqFocalMm(focal, spec.sensorWidthMm, spec.sensorHeightMm)
                val distance = abs(eq - REFERENCE_EQ_FOCAL_MM)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestId = spec.cameraId
                    bestFocal = focal
                }
            }
        }
        return bestId to bestFocal
    }

    /**
     * Stop list for the UI: every physical lens (0.6x, 1x, 3x, …) plus a short
     * digital ladder between them, never more than a few extra entries so the
     * pill stays readable.
     */
    private fun stopsFor(lenses: List<Lens>, opticalStops: List<Float>): List<Float> {
        val physical = (lenses.map { roundStop(it.baseStop) } + opticalStops)
            .distinct()
            .filter { stop ->
                lenses.any { lens ->
                    val ratio = stop / lens.baseStop
                    ratio >= lens.minZoom - 1e-3f && ratio <= lens.maxZoom + 1e-3f
                }
            }
            .sorted()
        val maxZoom = lenses.maxOf { it.maxDisplayedZoom }
        val digital = DIGITAL_LADDER
            .filter { step -> step <= maxZoom + 0.01f && physical.none { abs(it - step) < 0.15f } }
        val stops = (physical + digital).distinct().sorted().toMutableList()
        if (stops.none { abs(it - 1f) < 0.01f }) stops.add(1f)
        return stops.sorted()
    }

    private fun roundStop(value: Float): Float =
        if (value <= 0f) 0f else (value * 10f).roundToInt() / 10f
}
