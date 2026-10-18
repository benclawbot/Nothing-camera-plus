package app.cameraplus.camera

import kotlin.math.roundToInt

/**
 * RAW policy. The original pairs a DNG with a JPEG; RAW-only is the addition
 * other camera apps offer (Open Camera) for people who develop their own files
 * and do not want the companion JPEG.
 */
enum class RawMode(val label: String) {
    OFF("RAW"),
    RAW_JPEG("RAW+JPG"),
    RAW_ONLY("RAW ONLY");

    fun next(): RawMode = entries[(ordinal + 1) % entries.size]

    val enabled: Boolean get() = this != OFF
}

/**
 * Exposure bracketing (AE bracket): one shutter press, several exposures of the
 * same frame. Open Camera and Camera FV-5 both ship it, and the Nothing forum
 * thread suggests bracketing around the stock app's inconsistent HDR.
 */
enum class ExposureBracket(val label: String, val evOffsets: List<Float>) {
    OFF("OFF", emptyList()),
    THREE_SHOT("3 SHOT ±2 EV", listOf(-2f, 0f, 2f)),
    FIVE_SHOT("5 SHOT ±2 EV", listOf(-2f, -1f, 0f, 1f, 2f));

    val enabled: Boolean get() = evOffsets.isNotEmpty()
}

/** Turns EV offsets into exposure-compensation index offsets. */
object ExposureBracketPlan {

    /**
     * [evStep] is the camera's own compensation step (EV per index, from
     * `ExposureState`). Offsets that land on the same index are folded away, so
     * a camera with a coarse step does not fire two identical shots.
     */
    fun indexOffsets(evOffsets: List<Float>, evStep: Float): List<Int> {
        if (evOffsets.isEmpty() || !evStep.isFinite() || evStep <= 0f) return emptyList()
        return evOffsets.map { ev -> (ev / evStep).roundToInt() }.distinct()
    }
}
