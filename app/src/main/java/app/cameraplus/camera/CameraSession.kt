package app.cameraplus.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.lifecycle.LifecycleOwner
import app.cameraplus.processing.JpegProcessor
import app.cameraplus.settings.ProcessingSettings
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    private companion object {
        /** How long a plain tap-to-focus keeps its metering point. */
        const val FOCUS_AUTO_CANCEL_SECONDS = 5L

        /** Effectively "until released": a locked focus point. */
        const val LOCK_DURATION_SECONDS = 60L * 60L

        /** Used when the HAL does not report an AE compensation step. */
        const val DEFAULT_EV_STEP = 1f / 3f
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var boundPreviewView: PreviewView? = null
    private var onZslChanged: (Boolean) -> Unit = {}
    private var onCapabilities: (SessionCapabilities) -> Unit = {}
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var mode = SessionMode.PHOTO
    private var flashEnabled = false
    private var torchEnabled = false
    private var rawEnabled = false
    private var stabilizationEnabled = true
    private var manualControls = ManualControls()
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
        onError: (Exception) -> Unit = {},
    ) {
        boundPreviewView = previewView
        this.onZslChanged = onZslChanged
        this.onCapabilities = onCapabilities
        errorSink = onError
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                rebind()
            } catch (t: Throwable) {
                onError(t.asException())
            }
        }, mainExecutor)
    }

    fun unbind() {
        activeRecording?.close()
        activeRecording = null
        onVideoFinished = null
        onVideoError = null
        cameraProvider?.unbindAll()
        boundCamera = null
        imageCapture = null
        videoCapture = null
    }

    /**
     * Switches the session shape: PHOTO/EXPERT bind ImageCapture, VIDEO binds
     * the Recorder. Manual controls and RAW only shape still capture.
     */
    fun setMode(newMode: SessionMode, onError: (Exception) -> Unit = {}) {
        if (newMode == mode) return
        if (isRecording()) {
            onError(IllegalStateException("Stop recording before switching modes"))
            return
        }
        mode = newMode
        if (mode != SessionMode.VIDEO) torchEnabled = false
        rebind(onError)
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

    fun setFlashEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (flashEnabled == enabled) return
        flashEnabled = enabled
        val desiredZsl = CaptureProfile.usesZeroShutterLag(
            zslSupported = zeroShutterLagSupported,
            flashEnabled = flashEnabled,
            rawEnabled = rawEnabled,
            manualControlsActive = !manualControls.isAuto(),
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

    fun setStabilizationEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (stabilizationEnabled == enabled) return
        if (isRecording()) {
            onError(IllegalStateException("Stop recording before changing stabilization"))
            return
        }
        stabilizationEnabled = enabled
        if (mode == SessionMode.VIDEO) rebind(onError)
    }

    fun setRawEnabled(enabled: Boolean, onError: (Exception) -> Unit = {}) {
        if (rawEnabled == enabled) return
        rawEnabled = enabled
        if (mode != SessionMode.VIDEO) rebind(onError)
    }

    fun setManualControls(controls: ManualControls, onError: (Exception) -> Unit = {}) {
        if (manualControls == controls) return
        manualControls = controls
        if (mode != SessionMode.VIDEO) rebind(onError)
    }

    /**
     * Captures a still. [settings] controls whether the frame goes straight
     * to MediaStore (neutral intent) or through software processing first.
     * RAW captures bypass the pipeline entirely: the DNG is the product.
     */
    fun capture(
        settings: ProcessingSettings,
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera is not ready"))
            return
        }
        when {
            rawEnabled -> captureRawToFile(capture, onSaved, onError)
            settings == ProcessingSettings.NATURAL -> captureToFile(capture, onSaved, onError)
            else -> captureToMemory(capture, settings, onSaved, onError)
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
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val jpegEntry = createEntry() ?: run {
            onError(IllegalStateException("Could not create a MediaStore entry"))
            return
        }
        val (jpegUri, jpegValues) = jpegEntry
        val rawName = "CameraPlus_${System.currentTimeMillis()}.dng"
        val rawValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, rawName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera+")
        }
        val rawUri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, rawValues)

        val rawOptions = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, rawUri ?: jpegUri, rawValues)
            .build()
        val jpegOptions = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, jpegUri, jpegValues)
            .build()

        var completed = false
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
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    completeOnce {
                        onSaved(outputFileResults.savedUri ?: jpegUri)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    context.contentResolver.delete(jpegUri, null, null)
                    rawUri?.let { uri -> context.contentResolver.delete(uri, null, null) }
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

    /** Fast path: the JPEG (and DNG, in RAW mode) goes straight to the file. */
    private fun captureToFile(
        capture: ImageCapture,
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val entry = createEntry() ?: run {
            onError(IllegalStateException("Could not create a MediaStore entry"))
            return
        }
        val (uri, values) = entry
        val options = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, uri, values)
            .build()
        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(outputFileResults.savedUri ?: uri)
            }

            override fun onError(exception: ImageCaptureException) {
                // Do not leave a dangling empty entry behind.
                context.contentResolver.delete(uri, null, null)
                onError(exception)
            }
        })
    }

    /** Processed path: decode, apply the intent, encode, write. */
    private fun captureToMemory(
        capture: ImageCapture,
        settings: ProcessingSettings,
        onSaved: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val entry = createEntry() ?: run {
            onError(IllegalStateException("Could not create a MediaStore entry"))
            return
        }
        val (uri, _) = entry
        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpeg = image.toJpegBytes()
                image.close()
                if (jpeg == null) {
                    context.contentResolver.delete(uri, null, null)
                    onError(IllegalStateException("Capture did not produce a JPEG frame"))
                    return
                }
                val resolver = context.contentResolver
                ioExecutor.execute {
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            JpegProcessor.process(jpeg, settings, out)
                        } ?: throw IllegalStateException("Could not open the output stream")
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

    private fun rebind(onError: (Exception) -> Unit = {}) {
        val provider = cameraProvider ?: return
        val previewView = boundPreviewView ?: return
        try {
            bindInternal(provider, previewView)
        } catch (t: Throwable) {
            onError(t.asException())
        }
    }

    private fun bindInternal(provider: ProcessCameraProvider, previewView: PreviewView) {
        val layout = planLenses(provider)
        lensLayout = layout
        // Bind the lens the user is on, defaulting to the reference (main)
        // lens instead of "whatever the HAL lists first" (which is the 2x
        // telephoto on several phones — the "stuck at 2x, no 0.6x/1x" bug).
        val targetId = selectedCameraId ?: layout.referenceCameraId
        if (targetId != selectedCameraId) selectedCameraId = targetId
        zeroShutterLagSupported = cameraFlags[targetId]?.zeroShutterLag ?: false
        val captureMode = desiredCaptureMode()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = selectorForCamera(targetId)

        val useCases = mutableListOf<UseCase>(preview)
        var capture: ImageCapture? = null
        var video: VideoCapture<Recorder>? = null

        if (mode == SessionMode.VIDEO) {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                    ),
                )
                .build()
            val built = VideoCapture.Builder(recorder)
                .setVideoStabilizationEnabled(stabilizationEnabled)
                .build()
            video = built
            useCases += built
        } else {
            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(captureMode)
                .setOutputFormat(
                    if (rawEnabled && cameraFlags[targetId]?.raw == true) {
                        ImageCapture.OUTPUT_FORMAT_RAW_JPEG
                    } else {
                        ImageCapture.OUTPUT_FORMAT_JPEG
                    },
                )
            applyManualControls(captureBuilder)
            val built = captureBuilder.build().also { it.flashMode = flashMode() }
            capture = built
            useCases += built
        }

        provider.unbindAll()
        val sessionConfig = SessionConfig.Builder(*useCases.toTypedArray()).apply {
            if (lookEnabled) addEffect(ensureLookEffect())
        }.build()
        boundCamera = try {
            provider.bindToLifecycle(lifecycleOwner, selector, sessionConfig)
        } catch (t: Throwable) {
            // The chosen camera id can disappear (privacy toggle, concurrent
            // use). Fall back to the platform default for this facing.
            selectedCameraId = null
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                sessionConfig,
            )
        }
        imageCapture = capture
        videoCapture = video
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
                evStep = camera2.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
                )?.toFloat() ?: DEFAULT_EV_STEP,
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
        }
        cameraFlags = flags
        if (specs.isEmpty()) return LensPlan.single("0", minZoom = 1f, maxZoom = 1f)
        return LensPlan.build(specs)
    }

    /** Manual values ride on the still capture requests through interop (M5). */
    private fun applyManualControls(builder: ImageCapture.Builder) {
        val controls = manualControls
        if (controls.isAuto()) return
        val extender = Camera2Interop.Extender(builder)
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

        onCapabilities(
            SessionCapabilities(
                zoomStops = layout?.stops ?: listOf(1f),
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
            ),
        )
    }

    private fun desiredCaptureMode(): Int =
        if (CaptureProfile.usesZeroShutterLag(
                zslSupported = zeroShutterLagSupported,
                flashEnabled = flashEnabled,
                rawEnabled = rawEnabled,
                manualControlsActive = !manualControls.isAuto(),
            )
        ) {
            ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
        } else {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }

    private fun flashMode(): Int = if (flashEnabled) {
        ImageCapture.FLASH_MODE_ON
    } else {
        ImageCapture.FLASH_MODE_OFF
    }

    private fun Throwable.asException(): Exception = this as? Exception ?: Exception(this)
}

/** Per-camera capability flags read at bind time. */
private data class CameraFlags(
    val zeroShutterLag: Boolean,
    val raw: Boolean,
    val evStep: Float,
)

/** Video finalize carried an error; the partial entry is deleted first. */
private class RecordingFailedException(code: Int, cause: Throwable?) :
    Exception("Video recording failed (code $code)", cause)
