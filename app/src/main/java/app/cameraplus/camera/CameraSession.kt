package app.cameraplus.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.params.RggbChannelVector
import android.location.Location
import android.media.MediaActionSound
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import app.cameraplus.processing.CameraParameters
import app.cameraplus.processing.JpegProcessor
import app.cameraplus.processing.WatermarkRenderer
import app.cameraplus.settings.ProcessingSettings
import app.cameraplus.settings.WatermarkOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * Thin wrapper around CameraX: binds Preview + ImageCapture (or Recorder for
 * video) to a [PreviewView] for the given lifecycle, and saves media through
 * MediaStore (Pictures/Camera+ and Movies/Camera+).
 *
 * Instant-shutter behaviour (M2): when the device supports private
 * reprocessing, flash is off, RAW is off and no manual controls are active,
 * captures use the zero-shutter-lag stream, so the frame is taken from just
 * before the tap and processing happens afterwards. Anything that changes the
 * effective capture mode rebinds the session.
 *
 * Processing (M3): the neutral intent takes the fast path straight to
 * MediaStore; any non-neutral intent is applied in software to the frame in
 * memory on a background thread before the file is written. The preview
 * mirrors the same intent through a GL effect (see [LookEffect]).
 *
 * Video (M4): video mode binds a Recorder, applies the stabilization the
 * hardware supports, and exposes focal-length-derived zoom stops so lens
 * switching happens through zoom ratios — no session rebind, so recording
 * keeps running.
 *
 * Expert (M5) and RAW (M6): manual ISO/exposure/focus are mapped onto Camera2
 * capture request keys through Camera2 interop; RAW (DNG) output is available
 * where the hardware reports the raw capability, and it bypasses the
 * software pipeline.
 *
 * All public methods are main-thread only, matching where CameraX delivers
 * its callbacks via the main executor.
 */
class CameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor()
    private val qrExecutor: Executor = Executors.newSingleThreadExecutor()
    private val panoramaExecutor: Executor = Executors.newSingleThreadExecutor()

    private companion object {
        /** How long a plain tap-to-focus keeps its metering point. */
        const val FOCUS_AUTO_CANCEL_SECONDS = 5L

        /** Effectively "until released": a locked focus point. */
        const val LOCK_DURATION_SECONDS = 60L * 60L

        /** Used when the HAL does not report an AE compensation step. */
        const val DEFAULT_EV_STEP = 1f / 3f

        /** Above this size, expose a distinct MAX capture option. */
        const val STANDARD_PHOTO_PIXEL_THRESHOLD = 12_000_000L

        /** Re-meter only when the subject moved this fraction of the view. */
        const val TRACKING_METER_MOVE_FRACTION = 0.012f

        /** Minimum gap between metering actions while the subject is moving. */
        const val TRACKING_METER_INTERVAL_MS = 600L

        /** Keep-alive re-meter even while the subject is still. */
        const val TRACKING_METER_REFRESH_MS = 2_500L

        /** Below this correlation score the tracker is not trusted to meter. */
        const val TRACKING_LOST_CONFIDENCE = 0.45f

        const val TRACKING_TAG = "SubjectTracking"

        /** Debug-gated tag for Sensor-driven capture orientation. */
        const val ORIENTATION_TAG = "CaptureOrientation"

        /** Debug-gated lens-to-displayed-zoom mapping. */
        const val LENS_PLAN_TAG = "CameraLensPlan"

        /** Debug-gated tag for the live panorama frame stream. */
        const val PANORAMA_TAG = "PanoramaStream"

        /**
         * Minimum gap between analysis frames handed to the panorama stream.
         * The stitcher wants a healthy overlap between frames; a faster
         * stream would only produce near-duplicates that get rejected.
         */
        const val PANORAMA_STREAM_INTERVAL_MS = 120L
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var requestedExtensionMode: Int = ExtensionMode.NONE
    private var activeExtensionMode: Int = ExtensionMode.NONE
    private var suspended = false
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var panoramaAnalysis: ImageAnalysis? = null
    private var panoramaAnalyzer: PanoramaStreamAnalyzer? = null
    private var videoSnapshotSupported = false
    private var boundPreviewView: PreviewView? = null
    private var onZslChanged: (Boolean) -> Unit = {}
    private var onCapabilities: (SessionCapabilities) -> Unit = {}
    private var onQrCode: (String) -> Unit = {}
    private var onSubjectTracked: (Float, Float, Float) -> Unit = { _, _, _ -> }
    private var onSubjectTrackingCleared: () -> Unit = {}
    private var onSubjectTrackingUnavailable: () -> Unit = {}
    private val trackingExecutor: Executor = Executors.newSingleThreadExecutor()
    private val subjectAnalyzer = SubjectTrackingAnalyzer()
    private var subjectTrackingEnabled = false
    private var lastTrackingMeterAt = 0L
    private var lastTrackingMeterX = 0f
    private var lastTrackingMeterY = 0f
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    /**
     * Rounded device pose (clockwise degrees from natural portrait), kept up
     * to date by the orientation sensors while the session is bound. Every
     * still path combines it with the camera's `SENSOR_ORIENTATION` so saved
     * pictures are upright for how the phone was actually held, instead of a
     * fixed angle.
     */
    private var poseDegrees = 0
    private var orientationListening = false
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
            val nextPose = CaptureOrientation.roundPoseDegrees(orientation)
            if (nextPose == poseDegrees) return
            poseDegrees = nextPose
            if (Log.isLoggable(ORIENTATION_TAG, Log.DEBUG)) {
                Log.d(ORIENTATION_TAG, "pose=$nextPose; saved captures follow this pose")
            }
        }
    }
    private var mode = SessionMode.PHOTO
    private var flashChoice = FlashChoice.OFF
    private var screenFlash: ImageCapture.ScreenFlash? = null
    private var torchEnabled = false
    private var rawEnabled = false
    @Volatile private var mirrorCaptureEnabled = false
    private var shutterSoundEnabled = true
    private var shutterSound: MediaActionSound? = null
    @Volatile private var captureLocation: Location? = null
    @Volatile private var watermarkOptions = WatermarkOptions()
    private var stabilizationEnabled = true
    private var opticalStabilizationEnabled = false
    private var videoNoiseReductionEnabled = false
    private var videoQuality: VideoQuality = VideoQuality.FHD
    private var videoFrameRate: VideoFrameRate = VideoFrameRate.FPS_30
    private var photoAspectRatio = PhotoAspectRatio.FOUR_THREE
    private var photoResolution = PhotoResolution.STANDARD
    private var manualControls = ManualControls()
    private var macroEnabled = false
    private var zeroShutterLagSupported = false
    private var appliedCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY

    // v1.1 lens work: the bound camera is chosen explicitly (the HAL's first
    // back camera is not necessarily the main lens), and every zoom request is
    // a *displayed* zoom resolved onto (camera, ratio) by [LensPlan].
    private var selectedCameraId: String? = null
    private var pendingZoomRatio: Float? = null
    private var lensLayout: LensLayout? = null
    private var cameraFlags: Map<String, CameraFlags> = emptyMap()

    private var activeRecording: Recording? = null
    private var onVideoFinished: ((Uri) -> Unit)? = null
    private var onVideoError: ((Exception) -> Unit)? = null

    private var errorSink: (Exception) -> Unit = {}
    private var lookEnabled = false
    private val lookParameters = LookParameters()
    private var lookEffect: LookEffect? = null

    fun bind(
        previewView: PreviewView,
        onZslChanged: (Boolean) -> Unit = {},
        onCapabilities: (SessionCapabilities) -> Unit = {},
        onQrCode: (String) -> Unit = {},
        onSubjectTracked: (Float, Float, Float) -> Unit = { _, _, _ -> },
        onSubjectTrackingCleared: () -> Unit = {},
        onSubjectTrackingUnavailable: () -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        suspended = false
        boundPreviewView = previewView
        if (!orientationListening) {
            orientationListener.enable()
            orientationListening = true
        }
        this.onZslChanged = onZslChanged
        this.onCapabilities = onCapabilities
        this.onQrCode = onQrCode
        this.onSubjectTracked = onSubjectTracked
        this.onSubjectTrackingCleared = onSubjectTrackingCleared
        this.onSubjectTrackingUnavailable = onSubjectTrackingUnavailable
        subjectAnalyzer.onSelected = { x, y, score ->
            mainExecutor.execute { handleTrackedPosition(x, y, score) }
        }
        subjectAnalyzer.onTracked = { x, y, score ->
            mainExecutor.execute { handleTrackedPosition(x, y, score) }
        }
        subjectAnalyzer.onSelectFailed = {
            mainExecutor.execute {
                runCatching {
                    errorSink(IllegalStateException("Pick a more detailed area to track"))
                }
            }
        }
        errorSink = onError
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val extensionsFuture = ExtensionsManager.getInstanceAsync(context, provider)
                extensionsFuture.addListener({
                    extensionsManager = runCatching { extensionsFuture.get() }.getOrNull()
                    rebind()
                }, mainExecutor)
            } catch (t: Throwable) {
                onError(t.asException())
            }
        }, mainExecutor)
    }

    fun unbind() {
        suspended = true
        stopPanoramaStream()
        activeRecording?.close()
        activeRecording = null
        onVideoFinished = null
        onVideoError = null
        cameraProvider?.unbindAll()
        boundCamera = null
        imageCapture = null
        videoCapture = null
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        panoramaAnalysis = null
        panoramaAnalyzer = null
        shutterSound?.release()
        shutterSound = null
        if (orientationListening) {
            orientationListener.disable()
            orientationListening = false
        }
    }

    /**
     * Switches the session shape: PHOTO/EXPERT bind ImageCapture, VIDEO binds
     * the Recorder. Manual controls and RAW only shape still capture.
     */
    fun setMode(
        newMode: SessionMode,
        onError: (Exception) -> Unit = {},
        bindAfterChange: Boolean = true,
    ) {
        setProfile(newMode, requestedExtensionMode, onError, bindAfterChange, macroEnabled)
    }

    /** Atomically switches use-case shape and optional OEM extension profile. */
    fun setProfile(
        newMode: SessionMode,
        extensionMode: Int,
        onError: (Exception) -> Unit = {},
        bindAfterChange: Boolean = true,
        macroMode: Boolean = macroEnabled,
    ) {
        if (newMode == mode && extensionMode == requestedExtensionMode && macroMode == macroEnabled) return
        if (isRecording()) {
            onError(IllegalStateException("Stop recording before switching modes"))
            return
        }
        if (macroMode && onCapabilitiesState?.macroFocusSupported != true) {
            onError(IllegalStateException("This camera does not expose macro autofocus"))
            return
        }
        if (mode == SessionMode.PANORAMA && newMode != SessionMode.PANORAMA) {
            stopPanoramaStream()
        }
        mode = newMode
        requestedExtensionMode = if (newMode == SessionMode.VIDEO) ExtensionMode.NONE else extensionMode
        macroEnabled = newMode != SessionMode.VIDEO && macroMode
        if (newMode == SessionMode.VIDEO || requestedExtensionMode != ExtensionMode.NONE) {
            rawEnabled = false
            manualControls = ManualControls()
        }
        if (mode != SessionMode.VIDEO) torchEnabled = false
        if (bindAfterChange) rebind(onError)
    }

    /**
     * Requests an OEM-provided CameraX extension when one is available. The
     * rest of the app remains the clean-room fallback; this never copies or
     * depends on a manufacturer's private implementation.
     */
    fun setExtensionMode(extensionMode: Int, onError: (Exception) -> Unit = {}) {
        setProfile(mode, extensionMode, onError, macroMode = macroEnabled)
    }

    fun flipLens(onError: (Exception) -> Unit = {}) {
        if (isRecording()) {
            onError(IllegalStateException("Stop recording before switching cameras"))
            return
        }
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        // The other facing has its own lenses; let the plan choose its default.
        selectedCameraId = null
        pendingZoomRatio = null
        rebind(onError)
    }

    /** Selects a specific facing directly (shortcut launches); no-op when it is already bound. */
    fun setLensFacing(front: Boolean, onError: (Exception) -> Unit = {}) {
        val target = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (lensFacing != target) flipLens(onError)
    }

    fun setFlashChoice(choice: FlashChoice, onError: (Exception) -> Unit = {}) {
        if (choice == FlashChoice.SCREEN && screenFlash == null) {
            onError(IllegalStateException("Screen flash is unavailable in this window"))
            return
        }
        if (flashChoice == choice) return
        flashChoice = choice
        val desiredZsl = CaptureProfile.usesZeroShutterLag(
            zslSupported = zeroShutterLagSupported && requestedExtensionMode == ExtensionMode.NONE,
            flashEnabled = flashChoice != FlashChoice.OFF,
            rawEnabled = rawEnabled,
            manualControlsActive = !manualControls.isAuto(),
            highResolutionActive = photoResolution == PhotoResolution.MAXIMUM,
        )
        val appliedZsl = appliedCaptureMode == ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
        if (desiredZsl != appliedZsl) {
            // The effective capture mode changes with flash: rebind so the
            // session really runs in the mode the policy picked.
            rebind(onError)
        } else {
            imageCapture?.flashMode = flashMode()
        }
    }

    /** Backwards-compatible convenience for callers that need only off/on. */
    fun setFlashEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) =
        setFlashChoice(if (enabled) FlashChoice.ON else FlashChoice.OFF, onError)

    fun setScreenFlash(screenFlash: ImageCapture.ScreenFlash?) {
        this.screenFlash = screenFlash
        if (screenFlash == null && flashChoice == FlashChoice.SCREEN) {
            flashChoice = FlashChoice.OFF
            rebind()
        }
    }

    fun setPhotoAspectRatio(aspectRatio: PhotoAspectRatio, onError: (Exception) -> Unit = {}) {
        if (photoAspectRatio == aspectRatio) return
        photoAspectRatio = aspectRatio
        if (mode != SessionMode.VIDEO) rebind(onError)
    }

    fun setPhotoResolution(resolution: PhotoResolution, onError: (Exception) -> Unit = {}) {
        if (photoResolution == resolution) return
        photoResolution = resolution
        if (mode != SessionMode.VIDEO) rebind(onError)
    }

    fun setVideoFrameRate(
        requested: VideoFrameRate,
        onError: (Exception) -> Unit = {},
    ): VideoFrameRate {
        val supported = onCapabilitiesState?.supportedVideoFrameRates
        val resolved = if (supported == null || requested in supported) {
            requested
        } else {
            supported.firstOrNull { it == VideoFrameRate.FPS_30 } ?: supported.first()
        }
        if (resolved != videoFrameRate) {
            videoFrameRate = resolved
            if (mode == SessionMode.VIDEO) rebind(onError)
        }
        return resolved
    }

    fun videoFrameRate(): VideoFrameRate = videoFrameRate

    private var onCapabilitiesState: SessionCapabilities? = null

    /** Video-mode light: the steady torch rather than the still-capture flash. */
    fun setTorchEnabled(enabled: Boolean) {
        torchEnabled = enabled
        boundCamera?.cameraControl?.enableTorch(enabled)
    }

    /**
     * Updates the live preview look (M3.3): the same intent that shapes saved
     * stills is mirrored onto the viewfinder through a GL effect. Enabling or
     * disabling the effect changes the session shape, so it rebinds; strength
     * changes while active only update uniforms and apply to the next frame.
     */
    fun setLook(settings: ProcessingSettings, onError: (Exception) -> Unit = {}) {
        lookParameters.update(settings)
        val desired = !lookParameters.isNeutral()
        if (desired != lookEnabled) {
            lookEnabled = desired
            rebind(onError)
        }
    }

    /**
     * Enables the clearly simulated, face-independent portrait preview mask
     * when no CameraX BOKEH extension is active. The capture path uses the same
     * mask model; neither path claims depth data.
     */
    fun setPortraitBokehPreview(
        enabled: Boolean,
        level: Float,
        onError: (Exception) -> Unit = {},
    ) {
        lookParameters.portraitBokeh = if (
            enabled && requestedExtensionMode != ExtensionMode.BOKEH && activeExtensionMode != ExtensionMode.BOKEH
        ) {
            level.coerceIn(0f, 1f)
        } else {
            0f
        }
        val desired = !lookParameters.isNeutral()
        if (desired != lookEnabled) {
            lookEnabled = desired
            rebind(onError)
        }
    }

    private fun ensureLookEffect(): LookEffect {
        return lookEffect ?: LookEffect(lookParameters).also { effect ->
            effect.onError { throwable -> errorSink(throwable.asException()) }
            lookEffect = effect
        }
    }

    /**
     * Tap-to-focus / tap-to-meter: runs a combined AF+AE metering action around
     * the tapped point in preview-view coordinates. It auto-cancels, so the
     * camera returns to its own judgement after a few seconds; [lock] keeps it
     * until [releaseFocusLock].
     */
    fun focusAndMeterAt(x: Float, y: Float, lock: Boolean = false) {
        val previewView = boundPreviewView ?: return
        val camera = boundCamera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val builder = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
        if (lock) {
            builder.setAutoCancelDuration(LOCK_DURATION_SECONDS, TimeUnit.SECONDS)
        } else {
            builder.setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
        }
        camera.cameraControl.startFocusAndMetering(builder.build())
    }

    /** Drops a metering lock created with [focusAndMeterAt] and its lock flag. */
    fun releaseFocusLock() {
        boundCamera?.cameraControl?.cancelFocusAndMetering()
    }

    /**
     * Enables or disables subject tracking. Enabling rebinds so the analysis
     * stream is present; disabling removes it and clears any locked subject.
     */
    fun setSubjectTrackingEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (subjectTrackingEnabled == enabled) return
        subjectTrackingEnabled = enabled
        subjectAnalyzer.setTrackingEnabled(enabled)
        if (!enabled) clearSubjectTracking()
        rebind(onError)
    }

    /**
     * Locks the tracker onto the subject under preview-view pixels
     * ([x], [y]) and focuses there. From then on every analyzed position
     * moves the AF/AE metering point with the subject. Returns false while
     * the analysis stream is not delivering frames yet.
     */
    fun selectSubjectAt(x: Float, y: Float): Boolean {
        if (Log.isLoggable(TRACKING_TAG, Log.DEBUG)) {
            Log.d(
                TRACKING_TAG,
                "select-request view=($x,$y) enabled=$subjectTrackingEnabled " +
                    "analysis=${imageAnalysis != null} " +
                    "frame=${subjectAnalyzer.frameWidth}x${subjectAnalyzer.frameHeight}",
            )
        }
        if (!subjectTrackingEnabled || imageAnalysis == null) return false
        val previewView = boundPreviewView ?: return false
        val frameWidth = subjectAnalyzer.frameWidth
        val frameHeight = subjectAnalyzer.frameHeight
        if (frameWidth <= 0 || frameHeight <= 0) return false
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return false
        val visible = currentVisibleRect(frameWidth, frameHeight, viewWidth, viewHeight)
        val normalized = TrackingGeometry.viewToFrame(
            x, y, frameWidth, frameHeight, viewWidth, viewHeight, visible,
        )
        if (normalized.x !in 0f..1f || normalized.y !in 0f..1f) return false
        val density = context.resources.displayMetrics.density
        val scale = TrackingGeometry.scale(
            frameWidth, frameHeight, viewWidth, viewHeight, visible,
        )
        val halfFraction = ((SUBJECT_TRACKING_BOX_DP * density / 2f) / scale / frameWidth)
            .coerceIn(0.01f, 0.25f)
        subjectAnalyzer.pendingSelection =
            floatArrayOf(normalized.x, normalized.y, halfFraction)
        if (Log.isLoggable(TRACKING_TAG, Log.DEBUG)) {
            Log.d(
                TRACKING_TAG,
                "select view=($x,$y) frame=${frameWidth}x$frameHeight " +
                    "norm=(${normalized.x},${normalized.y})",
            )
        }
        // Immediate feedback: meter on the tap while the first frame locks.
        meteringForTracking(x, y)
        return true
    }

    /** Drops the tracked subject and its metering action; tracking stays armed. */
    fun clearSubjectTracking() {
        subjectAnalyzer.cancelSelection()
        boundCamera?.cameraControl?.cancelFocusAndMetering()
        lastTrackingMeterAt = 0L
        runCatching { onSubjectTrackingCleared() }
    }

    /**
     * The analysis frame is the upright sensor image after the shared zoom
     * crop (Camera2's SCALER_CROP_REGION covers every stream in the
     * session), so only the FILL_CENTER aspect crop remains to be applied.
     */
    private fun currentVisibleRect(
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): NormRect = TrackingGeometry.visibleRect(
        NormRect.FULL, frameWidth, frameHeight, viewWidth, viewHeight,
    )

    /** Builds the analysis use case that feeds [subjectAnalyzer]. */
    private fun buildTrackingAnalysis(): ImageAnalysis {
        subjectAnalyzer.mirrorHorizontally = lensFacing == CameraSelector.LENS_FACING_FRONT
        subjectAnalyzer.setTrackingEnabled(true)
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
            .also { it.setAnalyzer(trackingExecutor, subjectAnalyzer) }
    }

    /** Maps an analyzed position onto the view and keeps AF/AE on it. */
    private fun handleTrackedPosition(nx: Float, ny: Float, score: Float) {
        if (!subjectTrackingEnabled) return
        val previewView = boundPreviewView ?: return
        val frameWidth = subjectAnalyzer.frameWidth
        val frameHeight = subjectAnalyzer.frameHeight
        if (frameWidth <= 0 || frameHeight <= 0) return
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val visible = currentVisibleRect(frameWidth, frameHeight, viewWidth, viewHeight)
        val point = TrackingGeometry.frameToView(
            nx, ny, frameWidth, frameHeight, viewWidth, viewHeight, visible,
        )
        if (Log.isLoggable(TRACKING_TAG, Log.DEBUG)) {
            Log.d(TRACKING_TAG, "track view=(" + point.x + "," + point.y + ") score=" + score)
        }
        runCatching { onSubjectTracked(point.x, point.y, score) }
        // While the match is weak (subject occluded or gone), keep the last
        // good metering point instead of chasing a wrong region.
        if (score < TRACKING_LOST_CONFIDENCE && lastTrackingMeterAt != 0L) return
        val now = SystemClock.uptimeMillis()
        val moved = lastTrackingMeterAt == 0L ||
            hypot(point.x - lastTrackingMeterX, point.y - lastTrackingMeterY) >
            viewWidth * TRACKING_METER_MOVE_FRACTION
        val due = if (moved) {
            now - lastTrackingMeterAt >= TRACKING_METER_INTERVAL_MS
        } else {
            now - lastTrackingMeterAt >= TRACKING_METER_REFRESH_MS
        }
        if (due) meteringForTracking(point.x, point.y)
    }

    /** AF+AE metering on the tracked point, sized to the reticle. */
    private fun meteringForTracking(viewX: Float, viewY: Float) {
        val previewView = boundPreviewView ?: return
        val camera = boundCamera ?: return
        val density = context.resources.displayMetrics.density
        val point = previewView.meteringPointFactory.createPoint(
            viewX,
            viewY,
            SUBJECT_TRACKING_BOX_DP * density,
        )
        val builder = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
        // Replaced when the subject moves, cleared by the user: it must not
        // time out in the middle of a shot.
        builder.setAutoCancelDuration(LOCK_DURATION_SECONDS, TimeUnit.SECONDS)
        camera.cameraControl.startFocusAndMetering(builder.build())
        if (Log.isLoggable(TRACKING_TAG, Log.DEBUG)) {
            Log.d(TRACKING_TAG, "meter view=($viewX,$viewY)")
        }
        lastTrackingMeterAt = SystemClock.uptimeMillis()
        lastTrackingMeterX = viewX
        lastTrackingMeterY = viewY
    }

    /**
     * Brightness on the viewfinder (M11): the exposure-compensation index the
     * hardware accepts, clamped so a slider can never request something out of
     * range. Returns the index actually applied.
     */
    fun setExposureCompensationIndex(index: Int): Int {
        val camera = boundCamera ?: return 0
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        val target = index.coerceIn(range.lower, range.upper)
        camera.cameraControl.setExposureCompensationIndex(target)
        return target
    }

    /** The exposure-compensation index currently applied. */
    fun currentExposureCompensationIndex(): Int =
        boundCamera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0

    /**
     * Displayed zoom (v1.1): the caller speaks in the numbers the UI shows —
     * 0.6x, 1x, 3.5x, 20x — where 1x is the reference lens. The request is
     * resolved onto the camera that can produce it; switching to another
     * physical lens rebinds, everything else is a plain zoom-ratio change so
     * the transition stays instant. Returns the zoom actually applied.
     */
    fun setDisplayedZoom(
        displayed: Float,
        onError: (Exception) -> Unit = {},
    ): Float {
        val layout = lensLayout
        val camera = boundCamera
        if (layout == null || camera == null) return applyZoomRatio(displayed)
        val currentId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
        val target = layout.targetFor(displayed, currentId)
        if (Log.isLoggable(LENS_PLAN_TAG, Log.DEBUG)) {
            Log.d(
                LENS_PLAN_TAG,
                "request=$displayed current=$currentId -> camera=${target.cameraId} " +
                    "ratio=${target.zoomRatio} displayed=${target.displayedZoom} rebind=${target.needsRebind}",
            )
        }
        if (target.needsRebind) {
            selectedCameraId = target.cameraId
            pendingZoomRatio = target.zoomRatio
            rebind(onError)
        } else {
            pendingZoomRatio = null
            applyZoomRatio(target.zoomRatio)
        }
        return target.displayedZoom
    }

    /** Current displayed zoom, from the live zoom state. */
    fun currentDisplayedZoom(): Float {
        val camera = boundCamera ?: return 1f
        val layout = lensLayout ?: return 1f
        val ratio = camera.cameraInfo.zoomState.value?.zoomRatio ?: return 1f
        return layout.displayedZoomFor(
            Camera2CameraInfo.from(camera.cameraInfo).cameraId,
            ratio,
        )
    }

    /** 35 mm-equivalent focal at the current zoom, for capture metadata. */
    private fun currentEquivalentFocalMm(): Float? =
        lensLayout?.eqFocalMmFor(currentDisplayedZoom())

    /**
     * Static lens reporting (equivalent focal + aperture) for captures that
     * carry no EXIF of their own — the software merge paths (night, panorama)
     * whose output is a re-encoded ARGB frame.
     */
    private fun staticCameraParameters(): CameraParameters {
        val bound = boundCamera?.cameraInfo?.let { Camera2CameraInfo.from(it) }
        val aperture = bound
            ?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.firstOrNull()
        return CameraParameters(
            focalMm = currentEquivalentFocalMm(),
            aperture = aperture,
        )
    }

    /**
     * Applies a zoom *ratio* on the bound camera, clamped to its live range:
     * the HAL throws `IllegalArgumentException` on out-of-range requests and
     * each lens accepts a different range.
     */
    private fun applyZoomRatio(ratio: Float): Float {
        val camera = boundCamera ?: return 1f
        val state = camera.cameraInfo.zoomState.value ?: return 1f
        val target = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        camera.cameraControl.setZoomRatio(target)
        return target
    }

    /**
     * Zoom stop selection (M4): on multi-lens devices the platform resolves
     * the ratio onto a physical lens, so crossing a stop during recording is
     * a camera-managed transition instead of a session rebuild.
     *
     * The ratio is clamped to the bound camera's live `ZoomState`: the HAL
     * throws `IllegalArgumentException` on out-of-range requests, and the
     * usable range differs per lens (which is why the zoom pill is rebuilt
     * from capabilities after every bind).
     */
    fun setZoomRatio(
        ratio: Float,
        onError: (Exception) -> Unit = {},
    ): Float {
        val applied = applyZoomRatio(ratio)
        if (lensLayout == null && boundCamera != null) {
            onError(IllegalStateException("Zoom $ratio is not available on this lens"))
        }
        return applied
    }

    /**
     * Video resolution (v1.1). The request is clamped to what the device
     * reports as supported rather than failing the bind, and only takes effect
     * in video mode — switching it in photo mode is a no-op until the user
     * enters video.
     */
    fun setVideoQuality(
        quality: VideoQuality,
        onError: (Exception) -> Unit = {},
    ): VideoQuality {
        val resolved = if (boundCamera == null) {
            quality
        } else {
            VideoQuality.resolve(quality, supportedVideoQualities())
        }
        if (resolved != videoQuality) {
            videoQuality = resolved
            if (mode == SessionMode.VIDEO) rebind(onError)
        }
        return resolved
    }

    fun videoQuality(): VideoQuality = videoQuality

    /**
     * Video tiers the device can actually encode, read from the recorder's
     * own quality list so the picker never offers something unsupported.
     */
    private fun supportedVideoQualities(): Set<VideoQuality> {
        val info = boundCamera?.cameraInfo ?: return emptySet()
        return runCatching { QualitySelector.getSupportedQualities(info) }
            .getOrNull()
            .orEmpty()
            .mapNotNull { quality ->
                when (quality) {
                    Quality.HD -> VideoQuality.HD
                    Quality.FHD -> VideoQuality.FHD
                    Quality.UHD -> VideoQuality.UHD
                    else -> null
                }
            }
            .toSet()
    }

    fun setStabilizationEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (stabilizationEnabled == enabled) return
        if (isRecording()) {
            onError(IllegalStateException("Stop recording before changing stabilization"))
            return
        }
        stabilizationEnabled = enabled
        if (mode == SessionMode.VIDEO) rebind(onError)
    }

    fun setOpticalStabilizationEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (opticalStabilizationEnabled == enabled) return
        if (isRecording()) {
            onError(IllegalStateException("Stop recording before changing optical stabilization"))
            return
        }
        if (enabled && onCapabilitiesState?.opticalStabilizationSupported != true) {
            onError(IllegalStateException("Optical stabilization is not reported by this lens"))
            return
        }
        opticalStabilizationEnabled = enabled
        if (mode == SessionMode.VIDEO) rebind(onError)
    }

    fun setVideoNoiseReductionEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (videoNoiseReductionEnabled == enabled) return
        if (isRecording()) {
            onError(IllegalStateException("Stop recording before changing noise reduction"))
            return
        }
        if (enabled && onCapabilitiesState?.videoNoiseReductionSupported != true) {
            onError(IllegalStateException("The camera does not report the safe video noise-reduction mode"))
            return
        }
        videoNoiseReductionEnabled = enabled
        if (mode == SessionMode.VIDEO) rebind(onError)
    }

    fun setRawEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (rawEnabled == enabled) return
        rawEnabled = enabled
        if (mode != SessionMode.VIDEO) rebind(onError)
    }

    fun setMirrorCaptureEnabled(enabled: Boolean) {
        mirrorCaptureEnabled = enabled
    }

    fun setShutterSoundEnabled(enabled: Boolean) {
        shutterSoundEnabled = enabled
    }

    /** Explicit opt-in location metadata for still captures. */
    fun setCaptureLocation(location: Location?) {
        captureLocation = location?.let(::Location)
    }

    fun setWatermarkOptions(options: WatermarkOptions) {
        watermarkOptions = options.copy(authorName = options.authorName.take(32))
    }

    fun setManualControls(controls: ManualControls, onError: (Exception) -> Unit = {}) {
        if (manualControls == controls) return
        manualControls = controls
        if (mode != SessionMode.VIDEO) rebind(onError)
    }

    /** Requests Camera2's dedicated macro AF mode when the lens reports it. */
    fun setMacroEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (macroEnabled == enabled) return
        setProfile(mode, requestedExtensionMode, onError, macroMode = enabled)
    }

    /**
     * Captures a still. [settings] controls whether the file goes straight to
     * MediaStore (neutral intent) or through software processing first. Every
     * path makes the saved frame upright for the pose the phone was in when
     * the shutter fired: the pose is snapshotted here so a later rotation can
     * not skew the math. Neutral disk captures only re-encode when the pose
     * needs more than the pipeline already applied; RAW captures bypass the
     * pipeline entirely: the DNG is the product.
     */
    fun capture(
        settings: ProcessingSettings,
        onSaved: (Uri) -> Unit,
        onNote: (String) -> Unit = {},
        onError: (Exception) -> Unit,
    ) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera is not ready"))
            return
        }
        val uprightRotation = uprightCaptureRotation()
        when {
            rawEnabled -> captureRawToFile(capture, uprightRotation, onSaved, onError)
            settings == ProcessingSettings.NATURAL &&
                !watermarkOptions.enabled &&
                !shouldMirrorCapture() ->
                captureToFile(capture, uprightRotation, onSaved, onError)
            else ->
                captureToMemory(capture, uprightRotation, settings, onSaved, onNote, onError)
        }
    }

    /** A downsampled, upright ARGB frame for night/portrait software processing. */
    data class PixelFrame(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Captures one JPEG in memory, downsamples it before allocating pixel
     * buffers, applies the JPEG rotation, and hands an ARGB frame to [onFrame].
     * Used by the clean-room night/portrait modes; those deliberately
     * work at a bounded resolution rather than risking a 50 MP heap crash.
     */
    fun capturePixelFrame(
        maxDimension: Int,
        onFrame: (PixelFrame) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        require(maxDimension > 0) { "maxDimension must be positive" }
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera is not ready"))
            return
        }
        val uprightRotation = uprightCaptureRotation()
        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() = playShutterSound()

            override fun onCaptureSuccess(image: ImageProxy) {
                // The buffer may already carry part of the uprighting: the
                // reference device's HAL bakes CameraX's requested rotation
                // into the JPEG pixels, so only the pose difference is left.
                val deliveredRotation = image.imageInfo.rotationDegrees
                val captured = runCatching { image.toJpegBytes() }
                image.close()
                val jpeg = captured.getOrNull()
                val rotation = remainingRotation(uprightRotation, deliveredRotation)
                if (jpeg == null) {
                    onError(
                        captured.exceptionOrNull()?.asException()
                            ?: IllegalStateException("Capture did not produce a JPEG frame"),
                    )
                    return
                }
                val mirror = shouldMirrorCapture()
                ioExecutor.execute {
                    try {
                        val frame = decodePixelFrame(
                            jpeg,
                            rotation,
                            maxDimension,
                            mirrorHorizontal = mirror,
                        )
                            ?: throw IllegalStateException("Could not decode the captured frame")
                        mainExecutor.execute { onFrame(frame) }
                    } catch (t: Throwable) {
                        mainExecutor.execute { onError(t.asException()) }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        })
    }

    /** One converted, pose-upright frame of a live panorama sweep. */
    data class PanoramaFrame(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        /** Clockwise rotation that was applied to the sensor buffer. */
        val rotationDegrees: Int,
        val timestampNanos: Long,
    )

    /**
     * Attaches the live frame stream for a panorama sweep: every analysis
     * frame is converted to pose-upright ARGB (see [YuvFrameConverter]) and
     * handed to [onFrame] from the analysis thread.
     *
     * The rotation is frozen at the start of the sweep, so the whole sweep
     * shares one frame orientation even while the phone moves — the stitcher
     * needs that consistency. Returns `false` (through [onError] as well) when
     * the session is not bound in panorama shape, i.e. the caller switched
     * modes without rebinding.
     */
    fun startPanoramaStream(
        maxDimension: Int,
        onFrame: (PanoramaFrame) -> Unit,
        onError: (Exception) -> Unit,
    ): Boolean {
        require(maxDimension > 0) { "maxDimension must be positive" }
        val analysis = panoramaAnalysis
        if (analysis == null || imageAnalysis !== analysis) {
            onError(IllegalStateException("The panorama stream is not available on this camera"))
            return false
        }
        val uprightRotation = uprightCaptureRotation()
        val analyzer = PanoramaStreamAnalyzer(maxDimension, uprightRotation, onFrame, onError)
        panoramaAnalyzer = analyzer
        analysis.setAnalyzer(panoramaExecutor, analyzer)
        return true
    }

    /** Detaches the panorama analyzer; the use case itself stays bound. */
    fun stopPanoramaStream() {
        panoramaAnalyzer?.stop()
        panoramaAnalyzer = null
        panoramaAnalysis?.clearAnalyzer()
    }

    /**
     * The analysis-side half of the sweep: throttles the frame rate to
     * something the stitcher likes, converts each frame, and hands it over.
     *
     * Frames are dropped while a previous one is still being converted
     * ([busy]) and when the previous conversion is less than
     * [PANORAMA_STREAM_INTERVAL_MS] old, so a 30 fps stream cannot starve the
     * UI thread or the matcher. Every proxy is closed.
     */
    private inner class PanoramaStreamAnalyzer(
        private val maxDimension: Int,
        private val uprightRotation: Int,
        private val onFrame: (PanoramaFrame) -> Unit,
        private val onError: (Exception) -> Unit,
    ) : ImageAnalysis.Analyzer {

        private val busy = AtomicBoolean(false)
        @Volatile private var lastProcessedNanos = 0L
        @Volatile private var stopped = false

        @Synchronized
        fun stop() {
            stopped = true
        }

        @Synchronized
        override fun analyze(image: ImageProxy) {
            try {
                if (stopped) return
                val now = System.nanoTime()
                if (now - lastProcessedNanos < PANORAMA_STREAM_INTERVAL_MS * 1_000_000L) return
                if (!busy.compareAndSet(false, true)) return
                lastProcessedNanos = now

                // ImageAnalysis delivers sensor-oriented YUV pixels; unlike
                // ImageCapture, no rotation has been baked into this buffer.
                // Use the sweep's pose snapshot directly, rather than the
                // still path's HAL-compensation formula.
                val rotation = uprightRotation
                val planes = image.planes
                val converted = runCatching {
                    YuvFrameConverter.toArgb(
                        y = planes[0].buffer, yRowStride = planes[0].rowStride, yPixelStride = planes[0].pixelStride,
                        u = planes[1].buffer, uRowStride = planes[1].rowStride, uPixelStride = planes[1].pixelStride,
                        v = planes[2].buffer, vRowStride = planes[2].rowStride, vPixelStride = planes[2].pixelStride,
                        width = image.width,
                        height = image.height,
                        rotationDegrees = rotation,
                        maxDimension = maxDimension,
                    )
                }
                val (pixels, width, height) = converted.getOrElse { error ->
                    runCatching { onError(error.asException()) }
                    return
                }
                if (Log.isLoggable(PANORAMA_TAG, Log.DEBUG)) {
                    Log.d(
                        PANORAMA_TAG,
                        "frame ${width}x$height rotation=$rotation " +
                            "(sensor=${sensorOrientationDegrees()} delivered=${image.imageInfo.rotationDegrees})",
                    )
                }
                runCatching {
                    onFrame(PanoramaFrame(pixels, width, height, rotation, image.imageInfo.timestamp))
                }.onFailure { error ->
                    runCatching { onError(error.asException()) }
                }
            } finally {
                busy.set(false)
                image.close()
            }
        }
    }

    /** Saves a processed ARGB frame to Pictures/Camera+ as a JPEG. */
    fun saveArgbJpeg(
        pixels: IntArray,
        width: Int,
        height: Int,
        namePrefix: String,
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            onError(IllegalArgumentException("Invalid output frame dimensions"))
            return
        }
        val name = "${namePrefix}_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera+")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                onError(IllegalStateException("Could not create a MediaStore entry"))
                return
            }
        ioExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap = image
                image.setPixels(pixels, 0, width, 0, 0, width, height)
                WatermarkRenderer.draw(
                    image,
                    watermarkOptions,
                    captureLocation,
                    parameters = if (watermarkOptions.enabled) staticCameraParameters() else null,
                )
                val saved = context.contentResolver.openOutputStream(uri)?.use { out ->
                    image.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: false
                if (!saved) throw IllegalStateException("Could not encode the processed JPEG")
                writeLocationExif(uri)
                mainExecutor.execute { onSaved(uri) }
            } catch (t: Throwable) {
                context.contentResolver.delete(uri, null, null)
                mainExecutor.execute { onError(t.asException()) }
            } finally {
                bitmap?.recycle()
            }
        }
    }

    /**
     * Starts a video recording into Movies/Camera+. Returns `false` when the
     * session is not ready; validation and errors surface through [onError].
     */
    fun startRecording(
        recordAudio: Boolean,
        onFinished: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ): Boolean {
        val capture = videoCapture ?: run {
            onError(IllegalStateException("Video mode is not ready"))
            return false
        }
        if (activeRecording != null) return false
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "CameraPlus_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Camera+")
            captureLocation?.let { location ->
                put(MediaStore.Video.Media.LATITUDE, location.latitude)
                put(MediaStore.Video.Media.LONGITUDE, location.longitude)
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()

        onVideoFinished = onFinished
        onVideoError = onError
        val pending = capture.output
            .prepareRecording(context, options)
            .withAudioEnabled(recordAudio && hasAudioPermission())
        activeRecording = pending.start(mainExecutor) { event -> handleRecordEvent(event) }
        return true
    }

    fun stopRecording() {
        activeRecording?.stop()
    }

    fun isRecording(): Boolean = activeRecording != null

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun handleRecordEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        activeRecording = null
        val finished = onVideoFinished
        val failed = onVideoError
        onVideoFinished = null
        onVideoError = null
        if (event.hasError()) {
            event.outputResults?.outputUri?.let { uri ->
                context.contentResolver.delete(uri, null, null)
            }
            failed?.invoke(RecordingFailedException(event.error, event.cause))
        } else {
            event.outputResults?.outputUri?.let { uri -> finished?.invoke(uri) }
        }
    }

    /**
     * RAW capture (M6): a RAW+JPEG use case can only be saved through the
     * two-`OutputFileOptions` overload — the single-file overload throws for
     * `OUTPUT_FORMAT_RAW_JPEG`. CameraX then reports one `onImageSaved` per
     * file, so the callback is guarded to surface exactly one completion (and
     * one error) to the UI, and partial entries are cleaned up on failure.
     */
    private fun captureRawToFile(
        capture: ImageCapture,
        uprightRotation: Int,
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val name = "CameraPlus_${System.currentTimeMillis()}"
        val jpegValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera+")
        }
        val rawValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera+")
        }

        val rawOptions = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, rawValues)
            .setMetadata(captureMetadata())
            .build()
        val jpegOptions = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, jpegValues)
            .setMetadata(captureMetadata())
            .build()

        var completed = false
        var rawSaved = false
        var jpegSavedUri: Uri? = null
        var rawSavedUri: Uri? = null
        fun completeOnce(block: () -> Unit) {
            if (completed) return
            completed = true
            block()
        }

        capture.takePicture(
            rawOptions,
            jpegOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() = playShutterSound()

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // CameraX calls once per output. Wait until both files are
                    // confirmed before telling the UI the pair is complete;
                    // the returned gallery item must always be the JPEG.
                    when (outputFileResults.imageFormat) {
                        ImageFormat.RAW_SENSOR -> {
                            rawSavedUri = outputFileResults.savedUri
                            rawSaved = rawSavedUri != null
                        }
                        ImageFormat.JPEG -> jpegSavedUri = outputFileResults.savedUri
                    }
                    if (rawSaved && jpegSavedUri != null) {
                        bakeSavedJpeg(jpegSavedUri!!, uprightRotation) {
                            completeOnce { onSaved(jpegSavedUri!!) }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    jpegSavedUri?.let { context.contentResolver.delete(it, null, null) }
                    rawSavedUri?.let { context.contentResolver.delete(it, null, null) }
                    completeOnce { onError(exception) }
                }
            },
        )
    }

    private fun createEntry(): Pair<Uri, ContentValues>? {
        val name = "CameraPlus_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera+")
        }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values) ?: return null
        return uri to values
    }

    /**
     * Neutral disk path: CameraX writes the JPEG straight to MediaStore and
     * [bakeSavedJpeg] then makes it upright for the pose — usually without
     * touching the pixels, because the HAL already rotated the buffer toward
     * the app's portrait target. Mirrored or processed shots take
     * [captureToMemory] instead.
     */
    private fun captureToFile(
        capture: ImageCapture,
        uprightRotation: Int,
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val name = "CameraPlus_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera+")
        }
        val options = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(captureMetadata())
            .build()
        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() = playShutterSound()

            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val uri = outputFileResults.savedUri
                if (uri != null) {
                    bakeSavedJpeg(uri, uprightRotation) { onSaved(uri) }
                } else {
                    onError(IllegalStateException("CameraX returned no saved MediaStore URI"))
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        })
    }

    /**
     * Makes a JPEG that CameraX already wrote upright for the capture pose.
     *
     * The buffer may already carry part of the rotation — the reference
     * device's HAL bakes CameraX's requested rotation into the pixels — so
     * the file's own Exif rotation tells how much is left: usually nothing
     * from a portrait hold, in which case only the tag is aligned with the
     * pose. When rotation is left, the pixels are re-encoded upright; if the
     * frame is too large for that, the file keeps its pixels and gets the
     * matching Exif rotation instead. Mirrored files are left to the camera's
     * own Exif variants.
     */
    private fun bakeSavedJpeg(uri: Uri, uprightRotation: Int, onDone: () -> Unit) {
        ioExecutor.execute {
            runCatching {
                val delivered = savedExifRotationDegrees(uri)
                if (delivered < 0 || shouldMirrorCapture()) return@runCatching
                val rotation = remainingRotation(uprightRotation, delivered)
                if (rotation != 0) {
                    val resolver = context.contentResolver
                    val jpeg = resolver.openInputStream(uri)?.use { it.readBytes() }
                    val encoded = jpeg?.let { bytes ->
                        ByteArrayOutputStream().use { buffer ->
                            val baked = JpegProcessor.process(
                                bytes,
                                ProcessingSettings.NATURAL,
                                buffer,
                                rotationDegrees = rotation,
                            )
                            if (baked) buffer.toByteArray() else null
                        }
                    }
                    if (encoded != null) {
                        val stream = resolver.openOutputStream(uri, "wt")
                        if (stream != null) {
                            stream.use { it.write(encoded) }
                            writeLocationExif(uri)
                            return@runCatching
                        }
                    }
                }
                writeCaptureOrientationTag(uri, rotation)
            }
            mainExecutor.execute(onDone)
        }
    }

    /** Processed path: decode, apply the intent, encode, write. */
    private fun captureToMemory(
        capture: ImageCapture,
        uprightRotation: Int,
        settings: ProcessingSettings,
        onSaved: (Uri) -> Unit,
        onNote: (String) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val entry = createEntry() ?: run {
            onError(IllegalStateException("Could not create a MediaStore entry"))
            return
        }
        val (uri, _) = entry
        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() = playShutterSound()

            override fun onCaptureSuccess(image: ImageProxy) {
                // The delivered buffer may already be part-rotated toward the
                // app's portrait target; the delivered rotation metadata says
                // how much is missing for the capture pose.
                val deliveredRotation = image.imageInfo.rotationDegrees
                if (Log.isLoggable(ORIENTATION_TAG, Log.DEBUG)) {
                    Log.d(
                        ORIENTATION_TAG,
                        "in-memory ${image.width}x${image.height} " +
                            "deliveredRotation=$deliveredRotation",
                    )
                }
                val jpeg = image.toJpegBytes()
                image.close()
                if (jpeg == null) {
                    context.contentResolver.delete(uri, null, null)
                    onError(IllegalStateException("Capture did not produce a JPEG frame"))
                    return
                }
                val rotation = remainingRotation(uprightRotation, deliveredRotation)
                val resolver = context.contentResolver
                ioExecutor.execute {
                    try {
                        val processed = resolver.openOutputStream(uri)?.use { out ->
                            JpegProcessor.process(
                                jpeg,
                                settings,
                                out,
                                mirrorHorizontal = shouldMirrorCapture(),
                                rotationDegrees = rotation,
                                watermark = watermarkOptions,
                                location = captureLocation,
                                cameraParameters = if (
                                    watermarkOptions.enabled &&
                                    watermarkOptions.includeCameraParameters
                                ) {
                                    staticCameraParameters()
                                } else {
                                    null
                                },
                            )
                        } ?: throw IllegalStateException("Could not open the output stream")
                        if (!processed) {
                            // The look could not be applied (frame too large,
                            // low memory). Keep the shot, write the original.
                            resolver.openOutputStream(uri)?.use { it.write(jpeg) }
                                ?: throw IllegalStateException("Could not write the capture")
                            // Raw pipeline pixels went to disk: record the
                            // pose rotation in the tag instead.
                            writeCaptureOrientationTag(uri, rotation)
                            mainExecutor.execute {
                                onNote("Saved without optional processing/watermark — frame too large")
                            }
                        }
                        writeLocationExif(uri)
                        mainExecutor.execute { onSaved(uri) }
                    } catch (t: Throwable) {
                        resolver.delete(uri, null, null)
                        mainExecutor.execute { onError(t.asException()) }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                context.contentResolver.delete(uri, null, null)
                onError(exception)
            }
        })
    }

    private fun ImageProxy.toJpegBytes(): ByteArray? {
        if (format != ImageFormat.JPEG) return null
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun decodePixelFrame(
        jpeg: ByteArray,
        rotationDegrees: Int,
        maxDimension: Int,
        mirrorHorizontal: Boolean,
    ): PixelFrame? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        var bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
        try {
            val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
            if (normalizedRotation != 0 || mirrorHorizontal) {
                val matrix = Matrix().apply {
                    if (normalizedRotation != 0) postRotate(normalizedRotation.toFloat())
                    if (mirrorHorizontal) postScale(-1f, 1f)
                }
                val rotated = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true,
                )
                if (rotated !== bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest > maxDimension) {
                val scale = maxDimension.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return PixelFrame(pixels, bitmap.width, bitmap.height)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun rebind(onError: (Exception) -> Unit = {}) {
        if (suspended) return
        val provider = cameraProvider ?: return
        val previewView = boundPreviewView ?: return
        try {
            bindInternal(provider, previewView)
        } catch (t: Throwable) {
            onError(t.asException())
        }
    }

    private fun bindInternal(provider: ProcessCameraProvider, previewView: PreviewView) {
        suspended = false
        // A rebind tears the camera session down; the old subject template
        // and its metering action do not survive it.
        clearSubjectTracking()
        val layout = planLenses(provider)
        lensLayout = layout
        // Bind the lens the user is on, defaulting to the reference (main)
        // lens instead of "whatever the HAL lists first" (which is the 2x
        // telephoto on several phones — the "stuck at 2x, no 0.6x/1x" bug).
        val targetId = selectedCameraId ?: layout.referenceCameraId
        if (targetId != selectedCameraId) selectedCameraId = targetId
        if (rawEnabled && cameraFlags[targetId]?.raw != true) rawEnabled = false
        if (photoResolution == PhotoResolution.MAXIMUM &&
            (cameraFlags[targetId]?.maxJpegPixels ?: 0L) <= STANDARD_PHOTO_PIXEL_THRESHOLD
        ) {
            photoResolution = PhotoResolution.STANDARD
        }
        zeroShutterLagSupported = cameraFlags[targetId]?.zeroShutterLag ?: false
        val captureMode = if (mode == SessionMode.VIDEO) {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        } else {
            desiredCaptureMode()
        }

        val preview = Preview.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        val baseSelector = selectorForCamera(targetId)
        val desiredExtension = if (mode == SessionMode.VIDEO) {
            ExtensionMode.NONE
        } else {
            requestedExtensionMode
        }
        val manager = extensionsManager
        val extensionSelector = runCatching {
            if (desiredExtension != ExtensionMode.NONE && manager != null &&
                manager.isExtensionAvailable(baseSelector, desiredExtension)
            ) {
                manager.getExtensionEnabledCameraSelector(baseSelector, desiredExtension)
            } else {
                null
            }
        }.getOrNull()
        activeExtensionMode = if (extensionSelector != null) desiredExtension else ExtensionMode.NONE
        val selector = extensionSelector ?: baseSelector

        val useCases = mutableListOf<UseCase>(preview)
        var capture: ImageCapture? = null
        var video: VideoCapture<Recorder>? = null
        var analysis: ImageAnalysis? = null

        if (mode == SessionMode.VIDEO) {
            val supportedFrameRates = videoFrameRateOptions(provider, targetId)
            if (videoFrameRate !in supportedFrameRates) {
                videoFrameRate = supportedFrameRates.firstOrNull { it == VideoFrameRate.FPS_30 }
                    ?: supportedFrameRates.first()
            }
            val requested = when (videoQuality) {
                VideoQuality.HD -> Quality.HD
                VideoQuality.FHD -> Quality.FHD
                VideoQuality.UHD -> Quality.UHD
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        requested,
                        // Prefer the requested tier, step down to what the
                        // encoder actually accepts rather than failing.
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                    ),
                )
                .build()
            val stillCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // Still images are for this portrait-locked UI: pin the target
                // rotation so the pipeline's baked rotation is deterministic
                // (see [remainingRotation]).
                .setTargetRotation(Surface.ROTATION_0)
                .build()
                .also { image ->
                    image.setCropAspectRatio(Rational(16, 9))
                    image.flashMode = ImageCapture.FLASH_MODE_OFF
                }
            val videoBuilder = VideoCapture.Builder(recorder)
                .setVideoStabilizationEnabled(stabilizationEnabled)
                .setTargetRotation(Surface.ROTATION_0)
                .setTargetFrameRate(Range(videoFrameRate.fps, videoFrameRate.fps))
                .setMirrorMode(
                    if (mirrorCaptureEnabled) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY
                    else MirrorMode.MIRROR_MODE_OFF,
                )
            if (cameraFlags[targetId]?.opticalStabilization == true) {
                Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    if (opticalStabilizationEnabled) {
                        android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                    } else {
                        android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    },
                )
            }
            if (cameraFlags[targetId]?.noiseReduction == true) {
                Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    if (videoNoiseReductionEnabled) {
                        // Prefer the real-time mode. Some HALs advertise
                        // HIGH_QUALITY but stall or reject a video session
                        // when it is enabled alongside the encoder.
                        CaptureRequest.NOISE_REDUCTION_MODE_FAST
                    } else {
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    },
                )
            }
            val built = videoBuilder.build()
            video = built
            capture = stillCapture
            useCases += stillCapture
            useCases += built
            if (subjectTrackingEnabled) {
                val tracking = buildTrackingAnalysis()
                analysis = tracking
                useCases += tracking
            }
        } else if (mode == SessionMode.QR) {
            val built = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            built.setAnalyzer(qrExecutor, QrCodeAnalyzer { value ->
                mainExecutor.execute { onQrCode(value) }
            })
            analysis = built
            useCases += built
        } else if (mode == SessionMode.PANORAMA) {
            // Panorama is driven by a continuous YUV stream, not a sequence
            // of still captures. Keep this use case at a bounded, roughly
            // 4:3 size; the converter downsamples each accepted frame before
            // the CPU stitcher sees it.
            val built = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1440, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()
            panoramaAnalysis = built
            analysis = built
            useCases += built
        } else {
            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(captureMode)
                // Portrait-locked UI: see the comment on the video still
                // capture above.
                .setTargetRotation(Surface.ROTATION_0)
                .setOutputFormat(
                    if (rawEnabled && cameraFlags[targetId]?.raw == true) {
                        ImageCapture.OUTPUT_FORMAT_RAW_JPEG
                    } else {
                        ImageCapture.OUTPUT_FORMAT_JPEG
                    },
                )
            if (photoResolution == PhotoResolution.MAXIMUM) {
                captureBuilder.setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build(),
                )
            }
            applyManualControls(captureBuilder)
            val built = captureBuilder.build().also {
                it.setCropAspectRatio(Rational(photoAspectRatio.numerator, photoAspectRatio.denominator))
                it.flashMode = flashMode()
                if (flashChoice == FlashChoice.SCREEN) screenFlash?.let(it::setScreenFlash)
            }
            capture = built
            useCases += built
            if (subjectTrackingEnabled) {
                val tracking = buildTrackingAnalysis()
                analysis = tracking
                useCases += tracking
            }
        }

        provider.unbindAll()
        val useCaseGroup = buildUseCaseGroup(useCases)
        boundCamera = try {
            provider.bindToLifecycle(lifecycleOwner, selector, useCaseGroup)
        } catch (t: Throwable) {
            activeExtensionMode = ExtensionMode.NONE
            var recovered: Camera? = null
            if (analysis != null && mode == SessionMode.PANORAMA) {
                // Some cameras cannot host Preview + a 1440x1080 YUV stream.
                // Keep the viewfinder usable, but fail panorama honestly
                // rather than falling through to a second failing bind.
                provider.unbindAll()
                val previewOnly = buildUseCaseGroup(listOf(preview))
                recovered = runCatching {
                    provider.bindToLifecycle(lifecycleOwner, selector, previewOnly)
                }.getOrNull()
                if (recovered != null) {
                    analysis = null
                    panoramaAnalysis = null
                    runCatching {
                        errorSink(
                            IllegalStateException(
                                "Panorama's live frame stream is not supported with this camera",
                                t,
                            ),
                        )
                    }
                }
            }
            if (analysis != null && mode != SessionMode.PANORAMA) {
                // Subject tracking adds an analysis stream. If this camera
                // cannot host it alongside the current combination, keep the
                // camera usable and turn tracking off instead of failing.
                provider.unbindAll()
                val withoutTracking = useCases.filterNot { it === analysis }
                val trackingFreeConfig = buildUseCaseGroup(withoutTracking)
                recovered = runCatching {
                    provider.bindToLifecycle(lifecycleOwner, selector, trackingFreeConfig)
                }.getOrNull()
                if (recovered != null) {
                    analysis = null
                    subjectTrackingEnabled = false
                    subjectAnalyzer.setTrackingEnabled(false)
                    runCatching {
                        errorSink(
                            IllegalStateException(
                                "Subject tracking is not supported with this camera",
                                t,
                            ),
                        )
                    }
                    runCatching { onSubjectTrackingUnavailable() }
                }
            }
            if (recovered == null && mode == SessionMode.VIDEO && capture != null && video != null) {
                // Some camera/encoder combinations cannot bind preview + still
                // snapshot + video simultaneously. Preserve video by dropping
                // only the optional snapshot use case.
                provider.unbindAll()
                val videoOnlyConfig = buildUseCaseGroup(listOf(preview, video))
                capture = null
                analysis = null
                videoSnapshotSupported = false
                runCatching { errorSink(IllegalStateException("Still snapshots are unavailable in this video configuration", t)) }
                recovered = runCatching {
                    provider.bindToLifecycle(lifecycleOwner, selector, videoOnlyConfig)
                }.getOrElse {
                    selectedCameraId = null
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    videoOnlyConfig,
                    )
                }
            }
            if (recovered == null) {
                // The chosen camera id can disappear (privacy toggle,
                // concurrent use). Fall back to the platform default.
                selectedCameraId = null
                recovered = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    useCaseGroup,
                )
            }
            recovered
        }
        if (mode != SessionMode.QR && subjectTrackingEnabled && analysis == null) {
            // The analysis stream never made it into the session: leave the
            // camera usable, but be honest that tracking is off.
            subjectTrackingEnabled = false
            subjectAnalyzer.setTrackingEnabled(false)
            runCatching { onSubjectTrackingUnavailable() }
        }
        if (mode == SessionMode.VIDEO && capture != null) videoSnapshotSupported = true
        imageCapture = capture
        videoCapture = video
        imageAnalysis = analysis
        if (mode != SessionMode.PANORAMA || analysis == null) panoramaAnalysis = null
        appliedCaptureMode = captureMode
        if (torchEnabled && mode == SessionMode.VIDEO) {
            boundCamera?.cameraControl?.enableTorch(true)
        }
        pendingZoomRatio?.let { ratio -> applyZoomRatio(ratio) }
        pendingZoomRatio = null
        onZslChanged(capture != null && captureMode == ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
        publishCapabilities(provider)
    }

    /** Binds exactly one camera id (plus the facing), not "any camera facing". */
    /**
     * Binds preview and capture use cases to one shared crop rectangle. This
     * is the WYSIWYG contract: the pixels inside the preview viewport are the
     * pixels saved by ImageCapture (or encoded by VideoCapture), instead of a
     * full-screen preview that silently crops far tighter than a 4:3 photo.
     */
    private fun buildUseCaseGroup(useCases: List<UseCase>): UseCaseGroup {
        val baseAspect = if (mode == SessionMode.VIDEO) {
            Rational(16, 9)
        } else {
            Rational(photoAspectRatio.numerator, photoAspectRatio.denominator)
        }
        // Ratios are stored landscape-first (4:3 stills, 16:9 video), while
        // this activity and its viewfinder stay portrait-locked. Give CameraX
        // the corresponding portrait viewport ratio; device pose only affects
        // the saved still, never the preview surface.
        val portraitAspect = Rational(baseAspect.denominator, baseAspect.numerator)
        val viewPort = ViewPort.Builder(portraitAspect, Surface.ROTATION_0)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        return UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .apply {
                useCases.forEach(::addUseCase)
                if (lookEnabled) addEffect(ensureLookEffect())
            }
            .build()
    }

    private fun selectorForCamera(cameraId: String?): CameraSelector {
        val builder = CameraSelector.Builder().requireLensFacing(lensFacing)
        if (cameraId != null) {
            builder.addCameraFilter(
                CameraFilter { infos ->
                    infos.filter { info ->
                        Camera2CameraInfo.from(info).cameraId == cameraId
                    }
                },
            )
        }
        return builder.build()
    }

    /**
     * Reads what every camera of the current facing reports and hands it to
     * [LensPlan]: focal lengths, sensor size, zoom range, plus the per-camera
     * capability flags (zero shutter lag, RAW, AE step) that the rest of the
     * session needs for the camera it is about to bind.
     */
    private fun planLenses(provider: ProcessCameraProvider): LensLayout {
        val flags = mutableMapOf<String, CameraFlags>()
        val specs = mutableListOf<LensSpec>()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (Log.isLoggable(LENS_PLAN_TAG, Log.DEBUG)) {
            val allCameras = cameraManager.cameraIdList.joinToString { id ->
                runCatching {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.joinToString()
                    val size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val pixels = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val physicalIds = characteristics.physicalCameraIds
                    "$id:facing=$facing,focal=$focal,sensor=${size?.width}x${size?.height}," +
                        "pixels=${pixels?.width}x${pixels?.height},physical=$physicalIds"
                }.getOrDefault("$id:characteristics-unavailable")
            }
            Log.d(LENS_PLAN_TAG, "CameraManager IDs: $allCameras")
        }
        for (info in provider.availableCameraInfos) {
            val camera2 = Camera2CameraInfo.from(info)
            if (camera2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) != lensFacing) {
                continue
            }
            val focals = camera2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
            )?.toList()?.filter { it > 0f } ?: continue
            val sensor = camera2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
            ) ?: continue
            val pixels = camera2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE,
            )
            val zoom = info.zoomState.value
            val capabilities = camera2.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            ) ?: intArrayOf()
            flags[camera2.cameraId] = CameraFlags(
                zeroShutterLag = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING,
                ),
                raw = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW,
                ),
                // Guaranteed by the API; 90 is the phone-camera default and what
                // the reference device reports for its back cameras.
                sensorOrientation = camera2.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_ORIENTATION,
                ) ?: 90,
                evStep = camera2.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
                )?.toFloat() ?: DEFAULT_EV_STEP,
                maxJpegPixels = camera2.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                )?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L,
                opticalStabilization = camera2.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
                )?.contains(android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
                noiseReduction = camera2.getCameraCharacteristic(
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                )?.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) == true,
            )
            specs += LensSpec(
                cameraId = camera2.cameraId,
                focalsMm = focals,
                sensorWidthMm = sensor.width,
                sensorHeightMm = sensor.height,
                pixelCount = (pixels?.width?.toLong() ?: 0L) * (pixels?.height?.toLong() ?: 0L),
                minZoom = zoom?.minZoomRatio ?: 1f,
                maxZoom = zoom?.maxZoomRatio
                    ?: camera2.getCameraCharacteristic(
                        CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM,
                    )?.toFloat()?.coerceAtLeast(1f) ?: 1f,
            )
            if (Log.isLoggable(LENS_PLAN_TAG, Log.DEBUG)) {
                val physicalIds = runCatching {
                    cameraManager.getCameraCharacteristics(camera2.cameraId).physicalCameraIds
                }.getOrDefault(emptySet())
                val physicalSummary = physicalIds.joinToString { physicalId ->
                    runCatching {
                        val physical = cameraManager.getCameraCharacteristics(physicalId)
                        val focal = physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.joinToString()
                        val physicalSensor = physical.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        val physicalPixels = physical.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                        "$physicalId:focal=$focal,sensor=${physicalSensor?.width}x${physicalSensor?.height}," +
                            "pixels=${physicalPixels?.width}x${physicalPixels?.height}"
                    }.getOrDefault("$physicalId:characteristics-unavailable")
                }
                Log.d(LENS_PLAN_TAG, "camera=${camera2.cameraId} physicalIds=$physicalIds $physicalSummary")
            }
        }
        cameraFlags = flags
        if (specs.isEmpty()) return LensPlan.single("0", minZoom = 1f, maxZoom = 1f)
        val layout = LensPlan.build(specs)
        if (Log.isLoggable(LENS_PLAN_TAG, Log.DEBUG)) {
            Log.d(
                LENS_PLAN_TAG,
                "managerCameras=${cameraManager.cameraIdList.joinToString()} reference=${layout.referenceCameraId} " +
                    "stops=${layout.stops} optical=${layout.opticalStops} lenses=" +
                    specs.joinToString { spec ->
                        "${spec.cameraId}:focals=${spec.focalsMm},sensor=${spec.sensorWidthMm}x${spec.sensorHeightMm}," +
                            "pixels=${spec.pixelCount},zoom=${spec.minZoom}..${spec.maxZoom}"
                    },
            )
        }
        return layout
    }

    private fun videoFrameRateOptions(
        provider: ProcessCameraProvider,
        cameraId: String?,
    ): List<VideoFrameRate> {
        val info = provider.availableCameraInfos.firstOrNull { candidate ->
            Camera2CameraInfo.from(candidate).cameraId == cameraId
        } ?: return listOf(VideoFrameRate.FPS_30)
        val ranges = info.supportedFrameRateRanges
        return listOf(VideoFrameRate.FPS_30, VideoFrameRate.FPS_60)
            .filter { option -> ranges.any { option.fps in it.lower..it.upper } }
            .ifEmpty { listOf(VideoFrameRate.FPS_30) }
    }

    /** Manual values ride on the still capture requests through interop (M5). */
    private fun applyManualControls(builder: ImageCapture.Builder) {
        val controls = manualControls
        if (controls.isAuto() && !macroEnabled) return
        val extender = Camera2Interop.Extender(builder)
        if (macroEnabled && controls.focusDiopters == null) {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_MACRO,
            )
        }
        controls.colorTemperatureKelvin?.let { kelvin ->
            val gains = WhiteBalancePlan.gainsForKelvin(kelvin)
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_OFF,
            )
            extender.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            extender.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                RggbChannelVector(gains.red, gains.green, gains.green, gains.blue),
            )
        } ?: run {
            val awbMode = when (controls.whiteBalance) {
                WhiteBalancePreset.AUTO -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO
                WhiteBalancePreset.INCANDESCENT -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                WhiteBalancePreset.FLUORESCENT -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                WhiteBalancePreset.DAYLIGHT -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                WhiteBalancePreset.CLOUDY -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                WhiteBalancePreset.TWILIGHT -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
                WhiteBalancePreset.SHADE -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE
                WhiteBalancePreset.CUSTOM -> android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_OFF
            }
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        }
        controls.iso?.let { extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it) }
        controls.exposureTimeNs?.let {
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
        }
        controls.focusDiopters?.let {
            extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
    }

    /**
     * Publishes what the bound camera supports so the UI gates zoom stops,
     * RAW, stabilization and the manual ranges instead of guessing.
     */
    private fun publishCapabilities(provider: ProcessCameraProvider) {
        val bound = boundCamera?.cameraInfo?.let { Camera2CameraInfo.from(it) }
        fun <T> characteristic(key: CameraCharacteristics.Key<T>): T? =
            bound?.getCameraCharacteristic(key)

        val sensitivity = characteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = characteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minimumFocus = characteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val autofocusModes = characteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        val videoStabilization = characteristic(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
        )
        val opticalStabilization = characteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
        )
        val boundId = bound?.cameraId
        val flags = boundId?.let { cameraFlags[it] }
        val layout = lensLayout
        val liveZoom = boundCamera?.cameraInfo?.zoomState?.value
        val evRange = boundCamera?.cameraInfo?.exposureState?.exposureCompensationRange
        val videoRanges = boundCamera?.cameraInfo?.supportedFrameRateRanges.orEmpty()
        val supportedVideoFps = listOf(VideoFrameRate.FPS_30, VideoFrameRate.FPS_60)
            .filter { option -> videoRanges.any { option.fps in it.lower..it.upper } }
            .ifEmpty { listOf(VideoFrameRate.FPS_30) }
        val maxJpegPixels = flags?.maxJpegPixels ?: 0L

        val published = SessionCapabilities(
                zoomStops = layout?.stops ?: listOf(1f),
                zoomStopFocalMm = layout?.let { currentLayout ->
                    currentLayout.stops.map { stop -> currentLayout.eqFocalMmFor(stop) ?: 0f }
                } ?: emptyList(),
                referenceEqFocalMm = layout?.referenceEqFocalMm ?: 0f,
                opticalZoomStops = layout?.let { currentLayout ->
                    (currentLayout.lenses.map { it.baseStop } + currentLayout.opticalStops)
                        .distinct()
                        .sorted()
                } ?: listOf(1f),
                minDisplayedZoom = layout?.minDisplayedZoom
                    ?: liveZoom?.minZoomRatio
                    ?: 1f,
                maxDisplayedZoom = layout?.maxDisplayedZoom
                    ?: liveZoom?.maxZoomRatio
                    ?: 1f,
                // RAW follows the lens in use, not just the first back camera.
                rawSupported = flags?.raw == true,
                stabilization = StabilizationProfile.choose(
                    previewSupported = videoStabilization?.contains(
                        android.hardware.camera2.CameraMetadata
                            .CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION,
                    ) == true,
                    standardSupported = videoStabilization?.contains(
                        android.hardware.camera2.CameraMetadata
                            .CONTROL_VIDEO_STABILIZATION_MODE_ON,
                    ) == true,
                    opticalSupported = opticalStabilization?.contains(
                        android.hardware.camera2.CameraMetadata
                            .LENS_OPTICAL_STABILIZATION_MODE_ON,
                    ) == true,
                ),
                isoRange = sensitivity?.let { IntRange(it.lower, it.upper) },
                exposureRange = exposure?.let { LongRange(it.lower, it.upper) },
                minFocusDistance = minimumFocus?.takeIf { it > 0f },
                evRange = evRange?.let { IntRange(it.lower, it.upper) },
                evStep = flags?.evStep ?: DEFAULT_EV_STEP,
                supportedVideoFrameRates = supportedVideoFps,
                maximumPhotoResolutionSupported = maxJpegPixels > STANDARD_PHOTO_PIXEL_THRESHOLD,
                portraitExtensionAvailable = runCatching {
                    extensionsManager?.isExtensionAvailable(
                        selectorForCamera(layout?.referenceCameraId ?: boundId),
                        ExtensionMode.BOKEH,
                    ) == true
                }.getOrDefault(false),
                nightExtensionAvailable = runCatching {
                    extensionsManager?.isExtensionAvailable(
                        selectorForCamera(layout?.referenceCameraId ?: boundId),
                        ExtensionMode.NIGHT,
                    ) == true
                }.getOrDefault(false),
                hdrExtensionAvailable = runCatching {
                    extensionsManager?.isExtensionAvailable(
                        selectorForCamera(layout?.referenceCameraId ?: boundId),
                        ExtensionMode.HDR,
                    ) == true
                }.getOrDefault(false),
                faceRetouchExtensionAvailable = runCatching {
                    extensionsManager?.isExtensionAvailable(
                        selectorForCamera(layout?.referenceCameraId ?: boundId),
                        ExtensionMode.FACE_RETOUCH,
                    ) == true
                }.getOrDefault(false),
                extensionActive = activeExtensionMode != ExtensionMode.NONE,
                macroFocusSupported = autofocusModes?.contains(
                    android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_MACRO,
                ) == true,
                opticalStabilizationSupported = flags?.opticalStabilization == true,
                videoSnapshotSupported = mode == SessionMode.VIDEO && videoSnapshotSupported,
                videoNoiseReductionSupported = flags?.noiseReduction == true,
            )
        onCapabilitiesState = published
        onCapabilities(published)
    }

    private fun desiredCaptureMode(): Int =
        if (CaptureProfile.usesZeroShutterLag(
                zslSupported = zeroShutterLagSupported && requestedExtensionMode == ExtensionMode.NONE,
                flashEnabled = flashChoice != FlashChoice.OFF,
                rawEnabled = rawEnabled,
                manualControlsActive = !manualControls.isAuto(),
                highResolutionActive = photoResolution == PhotoResolution.MAXIMUM,
            )
        ) {
            ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
        } else {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }

    private fun flashMode(): Int = when (flashChoice) {
        FlashChoice.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashChoice.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashChoice.ON -> ImageCapture.FLASH_MODE_ON
        FlashChoice.SCREEN -> ImageCapture.FLASH_MODE_SCREEN
    }

    private fun shouldMirrorCapture(): Boolean =
        mirrorCaptureEnabled && lensFacing == CameraSelector.LENS_FACING_FRONT

    private fun captureMetadata(): ImageCapture.Metadata = ImageCapture.Metadata().apply {
        setReversedHorizontal(shouldMirrorCapture())
        captureLocation?.let { setLocation(it) }
    }

    /**
     * Clockwise rotation, in degrees, that turns a raw capture of the current
     * lens into an upright image for the pose the phone is being held in.
     *
     * The pose comes from the orientation sensors (rounded to 0/90/180/270)
     * and the camera's reported `SENSOR_ORIENTATION`; the app is
     * portrait-locked, so there is no display-rotation term. [CaptureOrientation]
     * holds the math, and the pose keeps updating while the session is bound,
     * so pictures taken in landscape or upside down are saved upright too.
     */
    private fun uprightCaptureRotation(): Int {
        val sensorOrientation = sensorOrientationDegrees()
        val rotation = CaptureOrientation.captureRotationDegrees(poseDegrees, sensorOrientation)
        if (Log.isLoggable(ORIENTATION_TAG, Log.DEBUG)) {
            Log.d(
                ORIENTATION_TAG,
                "pose=$poseDegrees sensorOrientation=$sensorOrientation rotation=$rotation",
            )
        }
        return rotation
    }

    /** The selected camera's reported `SENSOR_ORIENTATION`. */
    private fun sensorOrientationDegrees(): Int =
        selectedCameraId?.let { cameraFlags[it]?.sensorOrientation } ?: 90

    /**
     * Rotation still owed to a delivered capture: [uprightRotation] minus the
     * part the pipeline already applied. The in-memory path turns the buffer
     * toward the app's portrait target by `sensor - delivered` degrees (the
     * HAL bakes the requested rotation into the pixels); the delivered
     * rotation metadata reports the remainder, which still has to be applied
     * on top of those pixels.
     */
    private fun remainingRotation(uprightRotation: Int, deliveredRotationDegrees: Int): Int =
        CaptureOrientation.remainingRotationDegrees(
            uprightRotation,
            CaptureOrientation.pipelineAppliedRotationDegrees(
                sensorOrientationDegrees(),
                deliveredRotationDegrees,
            ),
        )

    private fun exifOrientationFor(rotationDegrees: Int): Int = when (
        ((rotationDegrees % 360) + 360) % 360
    ) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * The rotation encoded in a saved JPEG's Exif orientation tag, in
     * clockwise degrees (0 when absent or undefined). Returns -1 for the
     * mirrored variants: those files are left exactly as the camera wrote
     * them.
     */
    private fun savedExifRotationDegrees(uri: Uri): Int = runCatching {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                ExifInterface.ORIENTATION_FLIP_VERTICAL,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE,
                -> -1
                else -> 0
            }
        }
    }.getOrNull() ?: 0

    /**
     * Writes the Exif orientation tag that describes how much a saved JPEG's
     * own pixels still owe to the capture pose. Unlike a plain repair, this
     * replaces any value the pipeline wrote: the HAL leaves the tag undefined
     * when it bakes the rotation into the pixels, and CameraX's value would
     * describe the app's portrait target rather than how the phone was held.
     */
    private fun writeCaptureOrientationTag(uri: Uri, rotationDegrees: Int) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                val target = exifOrientationFor(rotationDegrees)
                val current = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
                if (current != target) {
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, target.toString())
                    exif.saveAttributes()
                }
            }
        }
    }

    private fun playShutterSound() {
        if (!shutterSoundEnabled) return
        runCatching {
            (shutterSound ?: MediaActionSound().also { sound ->
                sound.load(MediaActionSound.SHUTTER_CLICK)
                shutterSound = sound
            }).play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    private fun writeLocationExif(uri: Uri) {
        val location = captureLocation ?: return
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                exif.setLatLong(location.latitude, location.longitude)
                exif.saveAttributes()
            }
        }
    }

    private fun Throwable.asException(): Exception = this as? Exception ?: Exception(this)
}

/** Per-camera capability flags read at bind time. */
private data class CameraFlags(
    val zeroShutterLag: Boolean,
    val raw: Boolean,
    val sensorOrientation: Int,
    val evStep: Float,
    val maxJpegPixels: Long,
    val opticalStabilization: Boolean,
    val noiseReduction: Boolean,
)

/** Video finalize carried an error; the partial entry is deleted first. */
private class RecordingFailedException(code: Int, cause: Throwable?) :
    Exception("Video recording failed (code $code)", cause)
