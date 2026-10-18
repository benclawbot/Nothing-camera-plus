package app.cameraplus.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * The viewfinder's analysis consumer: one luma extraction per frame, feeding
 * the expert histogram and the exposure/focus overlays (zebra stripes and focus
 * peaking).
 *
 * [LumaExtractor] does rotation and downsampling in a single pass, so the
 * statistics and the overlay masks share that work, and the readouts are
 * refreshed at roughly 10 Hz — faster than any of them can be read.
 *
 * The frame is never closed here: the stream's owner ([FanOutAnalyzer]) closes
 * it once every consumer has seen it.
 */
class ViewfinderAnalyzer : ImageAnalysis.Analyzer {

    @Volatile
    var histogramEnabled: Boolean = false

    @Volatile
    var zebrasEnabled: Boolean = false

    @Volatile
    var peakingEnabled: Boolean = false

    /** Invoked on the analysis thread with the latest statistics. */
    var onHistogram: ((LumaHistogram) -> Unit)? = null

    /** Invoked on the analysis thread with the latest overlay masks. */
    var onOverlays: ((OverlayMasks) -> Unit)? = null

    private var frame: GrayFrame? = null
    private var counter = 0

    override fun analyze(image: ImageProxy) {
        try {
            if (!histogramEnabled && !zebrasEnabled && !peakingEnabled) return
            counter++
            if (counter % FRAME_SKIP != 0) return
            val gray = LumaExtractor.extract(
                image = image,
                targetWidth = TARGET_WIDTH,
                mirror = false,
                reuse = frame,
            ) ?: return
            frame = gray
            if (histogramEnabled) onHistogram?.invoke(LumaHistogram.of(gray.data))
            if (zebrasEnabled || peakingEnabled) {
                onOverlays?.invoke(
                    OverlayMasks.of(
                        luma = gray.data,
                        width = gray.width,
                        height = gray.height,
                        highlights = zebrasEnabled,
                        shadows = zebrasEnabled,
                        peaking = peakingEnabled,
                    ),
                )
            }
        } catch (_: Throwable) {
            // A dropped analysis frame must never take the camera down; the
            // next frame simply tries again.
        }
    }

    private companion object {
        /** A histogram or zebra map only needs the broad tonal shape. */
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
 * tracking and the viewfinder readouts share this stream instead of competing
 * for a second one. Consumers must leave closing the frame to the fan-out.
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
