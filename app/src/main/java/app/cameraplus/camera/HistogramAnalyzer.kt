package app.cameraplus.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * ImageAnalysis analyzer for the expert-mode histogram.
 *
 * Reuses [LumaExtractor]'s upright luma frame and folds it into a
 * [LumaHistogram] at roughly 10 Hz — faster than the overlay can show and much
 * cheaper than running the statistics over every frame.
 *
 * The frame is never closed here: the stream's owner ([FanOutAnalyzer]) closes
 * it once every consumer has seen it.
 */
class HistogramAnalyzer : ImageAnalysis.Analyzer {

    @Volatile
    var enabled: Boolean = false

    /** Invoked on the analysis thread with the latest statistics. */
    var onHistogram: ((LumaHistogram) -> Unit)? = null

    private var frame: GrayFrame? = null
    private var counter = 0

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled) return
            counter++
            if (counter % FRAME_SKIP != 0) return
            val gray = LumaExtractor.extract(
                image = image,
                targetWidth = TARGET_WIDTH,
                mirror = false,
                reuse = frame,
            ) ?: return
            frame = gray
            onHistogram?.invoke(LumaHistogram.of(gray.data))
        } catch (_: Throwable) {
            // A dropped analysis frame must never take the camera down; the
            // next frame simply tries again.
        }
    }

    private companion object {
        /** A histogram only needs the broad tonal shape, not detail. */
        const val TARGET_WIDTH = 256

        /** Every third frame: ~10 Hz at 30 fps, plenty for a readout. */
        const val FRAME_SKIP = 3
    }
}

/**
 * Feeds one [ImageAnalysis] frame to several analyzers and closes it once,
 * after the last of them has seen it.
 *
 * CameraX is happiest with a single analysis use case per session, so subject
 * tracking and the expert histogram share this stream instead of competing for
 * a second one. Consumers must leave closing the frame to the fan-out.
 */
class FanOutAnalyzer(
    private val analyzers: List<ImageAnalysis.Analyzer>,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            analyzers.forEach { analyzer -> runCatching { analyzer.analyze(image) } }
        } finally {
            image.close()
        }
    }
}
