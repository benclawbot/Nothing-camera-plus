package app.cameraplus.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.graphics.Bitmap
import android.util.Log
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.camera.view.ScreenFlashView
import androidx.camera.extensions.ExtensionMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FilterCenterFocus
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cameraplus.camera.FlashChoice
import app.cameraplus.camera.PhotoAspectRatio
import app.cameraplus.camera.PhotoResolution
import app.cameraplus.camera.VideoFrameRate
import app.cameraplus.camera.ModeZoomPlan
import app.cameraplus.camera.ZoomUseCase
import app.cameraplus.camera.WhiteBalancePreset
import app.cameraplus.camera.LensZoomPlan
import app.cameraplus.camera.ManualControlPlan
import app.cameraplus.camera.LumaHistogram
import app.cameraplus.camera.ManualControls
import app.cameraplus.camera.OverlayMasks
import app.cameraplus.camera.CameraSession
import app.cameraplus.camera.Camera2Recorder
import app.cameraplus.camera.CaptureTimer
import app.cameraplus.camera.ContinuousShotPlan
import app.cameraplus.camera.NightMerger
import app.cameraplus.camera.Direction
import app.cameraplus.camera.PanoramaCapture
import app.cameraplus.camera.PanoramaGuide
import app.cameraplus.camera.PortraitRenderer
import app.cameraplus.camera.PortraitSubjectDetector
import app.cameraplus.camera.SelfTimer
import app.cameraplus.camera.VideoQuality
import app.cameraplus.camera.SessionCapabilities
import app.cameraplus.camera.SUBJECT_TRACKING_BOX_DP
import app.cameraplus.camera.SessionMode
import app.cameraplus.camera.StabilizationLevel
import app.cameraplus.camera.StabilizationProfile
import app.cameraplus.settings.ProcessingSettingsStore
import app.cameraplus.settings.CapturePreferences
import app.cameraplus.settings.CapturePreferencesStore
import app.cameraplus.settings.GalleryPreferenceStore
import app.cameraplus.settings.WatermarkOptions
import app.cameraplus.settings.WatermarkOptionsStore
import app.cameraplus.processing.ImageProcessor
import app.cameraplus.ui.theme.Black
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.FaintWhite
import app.cameraplus.ui.theme.White
import java.util.Locale
import android.net.Uri
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import app.cameraplus.media.MediaLibrary
import app.cameraplus.media.SystemGallery

/** Modes shown in the selector. Null session modes use a separate pipeline. */
enum class CameraMode(val label: String, val session: SessionMode?) {
    PHOTO("PHOTO", SessionMode.PHOTO),
    PORTRAIT("PORTRAIT", SessionMode.PHOTO),
    BEAUTY("BEAUTY", SessionMode.PHOTO),
    NIGHT("NIGHT", SessionMode.PHOTO),
    PANORAMA("PANORAMA", SessionMode.PANORAMA),
    MACRO("MACRO", SessionMode.PHOTO),
    QR("QR", SessionMode.QR),
    VIDEO("VIDEO", SessionMode.VIDEO),
    SLOW_MOTION("SLO-MO", null),
    TIME_LAPSE("TIME-LAPSE", null),
    EXPERT("EXPERT", SessionMode.EXPERT);

    val usesCamera2Recorder: Boolean
        get() = this == SLOW_MOTION || this == TIME_LAPSE
}

private fun extensionModeFor(mode: CameraMode, photoHdrEnabled: Boolean): Int = when {
    mode == CameraMode.PORTRAIT -> ExtensionMode.BOKEH
    mode == CameraMode.BEAUTY -> ExtensionMode.FACE_RETOUCH
    mode == CameraMode.NIGHT -> ExtensionMode.NIGHT
    mode == CameraMode.PHOTO && photoHdrEnabled -> ExtensionMode.HDR
    else -> ExtensionMode.NONE
}

/** Modes where the CameraX still/video pipeline can host the analyzer. */
private fun CameraMode.supportsSubjectTracking(): Boolean =
    !usesCamera2Recorder && this != CameraMode.PANORAMA &&
        this != CameraMode.QR && this != CameraMode.EXPERT

private fun CameraMode.zoomUseCase(): ZoomUseCase = when (this) {
    CameraMode.PORTRAIT, CameraMode.BEAUTY -> ZoomUseCase.PORTRAIT
    CameraMode.NIGHT -> ZoomUseCase.NIGHT
    CameraMode.SLOW_MOTION -> ZoomUseCase.SLOW_MOTION
    CameraMode.TIME_LAPSE -> ZoomUseCase.TIME_LAPSE
    CameraMode.PANORAMA -> ZoomUseCase.PANORAMA
    CameraMode.MACRO -> ZoomUseCase.MACRO
    CameraMode.VIDEO -> ZoomUseCase.VIDEO
    CameraMode.EXPERT -> ZoomUseCase.EXPERT
    CameraMode.PHOTO, CameraMode.QR -> ZoomUseCase.PHOTO
}

@Composable
fun CameraScreen(
    shortcut: ModeShortcut.Selection? = null,
    onShortcutConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = remember { CameraSession(context, lifecycleOwner) }
    val previewView = remember {
        PreviewView(context).apply {
            // The surface is sized to the selected crop ratio below, so fill
            // does not stretch or crop the CameraX viewport.
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val camera2TextureView = remember(context) { TextureView(context) }
    var previewViewReady by remember { mutableStateOf(false) }
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val screenFlashView = remember(context) { ScreenFlashView(context) }
    val coroutineScope = rememberCoroutineScope()

    val store = remember(context) { ProcessingSettingsStore(context) }
    val capturePreferencesStore = remember(context) { CapturePreferencesStore(context) }
    val capturePreferences = remember(capturePreferencesStore) { capturePreferencesStore.load() }
    val watermarkStore = remember(context) { WatermarkOptionsStore(context) }
    val galleryStore = remember(context) { GalleryPreferenceStore(context) }
    var processing by remember { mutableStateOf(store.load()) }
    var watermark by remember { mutableStateOf(watermarkStore.load()) }
    var preferredGallery by remember { mutableStateOf(galleryStore.load()) }
    val galleryApps = remember(context) { SystemGallery.installedGalleries(context) }
    var showProcessing by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf(CompositionGuides.NONE) }
    val roll = rememberSensorRoll()
    var focusTarget by remember { mutableStateOf<Offset?>(null) }
    var focusLocked by remember { mutableStateOf(false) }
    var qrResult by remember { mutableStateOf<String?>(null) }

    // Subject tracking: armed from the top bar; the next tap locks the
    // analyzer onto that subject and the reticle follows it.
    var trackingArmed by remember { mutableStateOf(capturePreferences.subjectTracking) }
    var trackingPoint by remember { mutableStateOf<Offset?>(null) }
    var trackingConfidence by remember { mutableFloatStateOf(1f) }
    var histogram by remember { mutableStateOf<LumaHistogram?>(null) }
    var overlayMasks by remember { mutableStateOf(OverlayMasks.EMPTY) }
    var histogramArmed by remember { mutableStateOf(capturePreferences.histogramEnabled) }
    var zebrasArmed by remember { mutableStateOf(capturePreferences.zebrasEnabled) }
    var peakingArmed by remember { mutableStateOf(capturePreferences.peakingEnabled) }

    // A metering ring that is not locked goes away on its own, like the
    // stock timer; a locked one stays until the next tap or mode switch.
    LaunchedEffect(focusTarget, focusLocked) {
        if (focusTarget != null && !focusLocked) {
            delay(6000)
            focusTarget = null
        }
    }

    var mode by remember { mutableStateOf(CameraMode.PHOTO) }
    var flashChoice by remember { mutableStateOf(capturePreferences.flashChoice) }
    var screenFlashAvailable by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var inFlight by remember { mutableIntStateOf(0) }
    var shutterPulse by remember { mutableIntStateOf(0) }
    var processingCapture by remember { mutableStateOf(false) }
    var burstShots by remember { mutableIntStateOf(0) }
    var burstActive by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastMediaUri by remember { mutableStateOf<Uri?>(null) }
    var dismissedQrValue by remember { mutableStateOf<String?>(null) }
    var bokehLevel by remember { mutableFloatStateOf(capturePreferences.portraitBlurLevel) }
    val panoramaCapture = remember { PanoramaCapture() }
    var panoramaProgress by remember { mutableStateOf(panoramaCapture.progress()) }
    var panoramaDirection by remember { mutableStateOf(Direction.RIGHT) }
    var panoramaStreaming by remember { mutableStateOf(false) }
    var panoramaPreview by remember { mutableStateOf<Bitmap?>(null) }
    val panoramaGuide = remember(panoramaDirection) {
        PanoramaGuide(forwardSign = if (panoramaDirection == Direction.RIGHT) -1f else 1f)
    }
    var panoramaGuideState by remember { mutableStateOf(panoramaGuide.state()) }

    // The analyzer only runs in modes backed by the CameraX still/video
    // pipeline; the armed preference survives a detour through other modes.
    LaunchedEffect(mode, trackingArmed) {
        val wanted = trackingArmed && mode.supportsSubjectTracking()
        session.setSubjectTrackingEnabled(wanted) { error ->
            status = error.message ?: "Could not change subject tracking"
        }
        if (!wanted) {
            trackingPoint = null
            trackingConfidence = 1f
        }
    }

    // The viewfinder readouts ride the same analysis stream, so they follow the
    // mode and the expert preferences the same way tracking does.
    LaunchedEffect(mode, histogramArmed, zebrasArmed, peakingArmed) {
        val expert = mode == CameraMode.EXPERT
        session.setViewfinderOverlays(
            histogram = histogramArmed && expert,
            zebras = zebrasArmed && expert,
            peaking = peakingArmed && expert,
        ) { error ->
            status = error.message ?: "Could not change the viewfinder readouts"
        }
        if (!expert) {
            histogram = null
            overlayMasks = OverlayMasks.EMPTY
        }
    }

    var capabilities by remember { mutableStateOf(SessionCapabilities()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pinching by remember { mutableStateOf(false) }
    var evIndex by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var specialStarting by remember { mutableStateOf(false) }
    var camera2PreviewVisible by remember { mutableStateOf(false) }
    var camera2PreviewNeedsRestore by remember { mutableStateOf(false) }
    var recordStartedAt by remember { mutableStateOf(0L) }
    var recordSeconds by remember { mutableStateOf(0) }
    var slowMotionRates by remember { mutableStateOf<List<Int>>(emptyList()) }
    var slowMotionFps by remember { mutableIntStateOf(capturePreferences.slowMotionFps) }
    var timeLapseSpeed by remember { mutableIntStateOf(capturePreferences.timeLapseSpeed) }
    var micEnabled by remember { mutableStateOf(true) }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var geotagEnabled by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var stabilizationEnabled by remember { mutableStateOf(true) }
    var opticalStabilizationEnabled by remember { mutableStateOf(false) }
    var videoNoiseReductionEnabled by remember { mutableStateOf(false) }
    var videoQuality by remember { mutableStateOf(capturePreferences.videoQuality) }
    var videoFrameRate by remember { mutableStateOf(capturePreferences.videoFrameRate) }
    var photoAspectRatio by remember { mutableStateOf(capturePreferences.photoAspectRatio) }
    val previewAspectRatio = if (
        mode == CameraMode.VIDEO || mode == CameraMode.SLOW_MOTION || mode == CameraMode.TIME_LAPSE
    ) {
        9f / 16f
    } else {
        photoAspectRatio.denominator.toFloat() / photoAspectRatio.numerator.toFloat()
    }
    var photoResolution by remember { mutableStateOf(capturePreferences.photoResolution) }
    var photoHdrEnabled by remember { mutableStateOf(capturePreferences.photoHdrEnabled) }
    var mirrorCapture by remember { mutableStateOf(capturePreferences.mirrorCapture) }
    var shutterSoundEnabled by remember { mutableStateOf(capturePreferences.shutterSoundEnabled) }
    var volumeKeyShutter by remember { mutableStateOf(capturePreferences.volumeKeyShutter) }
    var touchShutter by remember { mutableStateOf(capturePreferences.touchShutter) }
    var selfTimer by remember { mutableStateOf(capturePreferences.selfTimer) }
    var timerDeadline by remember { mutableStateOf(0L) }
    var timerLeft by remember { mutableIntStateOf(0) }
    var pendingShot by remember { mutableStateOf(false) }
    var rawEnabled by remember { mutableStateOf(false) }
    var isoGauge by remember { mutableStateOf(0f) }
    var speedGauge by remember { mutableStateOf(0f) }
    var focusGauge by remember { mutableStateOf(0f) }
    var whiteBalance by remember { mutableStateOf(capturePreferences.whiteBalance) }
    var whiteBalanceKelvin by remember { mutableIntStateOf(capturePreferences.whiteBalanceKelvin) }
    val modeZoomMaximum = ModeZoomPlan.maximum(mode.zoomUseCase(), capabilities.maxDisplayedZoom)

    val camera2Recorder = remember(context, camera2TextureView) {
        Camera2Recorder(
            context = context,
            textureView = camera2TextureView,
            onError = { error -> status = error.message ?: "Camera2 recording failed" },
            onStateChanged = { active ->
                recording = active
                if (active) {
                    recordStartedAt = System.currentTimeMillis()
                    status = if (mode == CameraMode.SLOW_MOTION) {
                        "Slow motion recording"
                    } else {
                        "Time lapse recording"
                    }
                } else {
                    recordStartedAt = 0L
                }
            },
            onClipFinished = { uri ->
                if (uri != null) lastMediaUri = uri
                status = if (uri != null) "Video saved" else "Recording did not produce a usable clip"
                // This callback runs after Camera2Recorder has released the
                // camera/session/surface; ask the effect below to resume the
                // live CameraX preview regardless of the current mode. The
                // effect itself checks whether a special mode is still active.
                camera2PreviewNeedsRestore = true
                if (Log.isLoggable(CAMERA2_PREVIEW_TAG, Log.DEBUG)) {
                    Log.d(CAMERA2_PREVIEW_TAG, "clip finished; requesting preview restore (uri=$uri)")
                }
            },
        )
    }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioGranted = granted
        micEnabled = granted
        status = if (granted) {
            "Microphone ready"
        } else {
            "Microphone denied — videos save without audio"
        }
    }

    fun updateCaptureLocation(location: Location?) {
        currentLocation = location?.let(::Location)
        session.setCaptureLocation(currentLocation)
        camera2Recorder.setLocation(currentLocation)
        if (location == null && geotagEnabled) {
            status = "Location enabled · waiting for a location fix"
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        geotagEnabled = granted
        if (granted) {
            requestCurrentLocation(context) { location ->
                if (geotagEnabled) {
                    updateCaptureLocation(location)
                    if (location != null) status = "Geotagging enabled"
                }
            }
        } else {
            updateCaptureLocation(null)
            status = "Location permission denied"
        }
    }

    fun toggleGeotag() {
        if (geotagEnabled) {
            geotagEnabled = false
            updateCaptureLocation(null)
            status = "Geotagging off"
            return
        }
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            geotagEnabled = true
            requestCurrentLocation(context) { location ->
                if (geotagEnabled) {
                    updateCaptureLocation(location)
                    if (location != null) status = "Geotagging enabled"
                }
            }
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }

    fun applyCapturePreferences(options: CapturePreferences) {
        flashChoice = options.flashChoice
        photoAspectRatio = options.photoAspectRatio
        photoResolution = if (capabilities.maximumPhotoResolutionSupported) {
            options.photoResolution
        } else {
            PhotoResolution.STANDARD
        }
        videoQuality = options.videoQuality
        videoFrameRate = options.videoFrameRate
        selfTimer = options.selfTimer
        mirrorCapture = options.mirrorCapture
        shutterSoundEnabled = options.shutterSoundEnabled
        volumeKeyShutter = options.volumeKeyShutter
        touchShutter = options.touchShutter
        histogramArmed = options.histogramEnabled
        zebrasArmed = options.zebrasEnabled
        peakingArmed = options.peakingEnabled
        photoHdrEnabled = options.photoHdrEnabled && capabilities.hdrExtensionAvailable
        timeLapseSpeed = options.timeLapseSpeed
        slowMotionFps = options.slowMotionFps
        session.setFlashChoice(flashChoice) { error -> status = error.message }
        session.setPhotoAspectRatio(photoAspectRatio) { error -> status = error.message }
        session.setPhotoResolution(photoResolution) { error -> status = error.message }
        videoQuality = session.setVideoQuality(videoQuality) { error -> status = error.message }
        videoFrameRate = session.setVideoFrameRate(videoFrameRate) { error -> status = error.message }
        session.setMirrorCaptureEnabled(mirrorCapture)
        session.setShutterSoundEnabled(shutterSoundEnabled)
    }

    fun bindCameraX() {
        screenFlashView.setScreenFlashWindow(findActivity(context)?.window)
        val availableScreenFlash = screenFlashView.screenFlash
        screenFlashAvailable = availableScreenFlash != null
        session.setScreenFlash(availableScreenFlash)
        if (flashChoice == FlashChoice.SCREEN && availableScreenFlash == null) {
            flashChoice = FlashChoice.OFF
        }
        session.setFlashChoice(flashChoice)
        session.setPhotoAspectRatio(photoAspectRatio)
        session.setPhotoResolution(photoResolution)
        session.setVideoQuality(videoQuality)
        session.setVideoFrameRate(videoFrameRate)
        session.setMirrorCaptureEnabled(mirrorCapture)
        session.setShutterSoundEnabled(shutterSoundEnabled)
        session.setWatermarkOptions(watermark)
        session.setCaptureLocation(currentLocation)
        session.setExtensionMode(extensionModeFor(mode, photoHdrEnabled))
        // The session must already know whether to include the tracking
        // analyzer when it binds.
        session.setSubjectTrackingEnabled(trackingArmed && mode.supportsSubjectTracking())
        session.setViewfinderOverlays(
            histogram = histogramArmed && mode == CameraMode.EXPERT,
            zebras = zebrasArmed && mode == CameraMode.EXPERT,
            peaking = peakingArmed && mode == CameraMode.EXPERT,
        )
        session.bind(
            previewView = previewView,
            onQrCode = { value ->
                if (value != dismissedQrValue) {
                    dismissedQrValue = null
                    qrResult = value
                    status = "QR code detected"
                }
            },
            onSubjectTracked = { x, y, confidence ->
                trackingPoint = Offset(x, y)
                trackingConfidence = confidence
            },
            onSubjectTrackingCleared = {
                trackingPoint = null
            },
            onSubjectTrackingUnavailable = {
                trackingArmed = false
                trackingPoint = null
                capturePreferencesStore.setSubjectTracking(false)
            },
            onHistogram = { stats -> histogram = stats },
            onOverlays = { masks -> overlayMasks = masks },
            onViewfinderUnavailable = {
                histogramArmed = false
                zebrasArmed = false
                peakingArmed = false
                histogram = null
                overlayMasks = OverlayMasks.EMPTY
                capturePreferencesStore.setHistogram(false)
                capturePreferencesStore.setZebras(false)
                capturePreferencesStore.setPeaking(false)
            },
            onZslChanged = { active ->
                status = if (active) "Instant shutter ready" else null
            },
            onCapabilities = { published ->
                capabilities = published
                if (!published.rawSupported) rawEnabled = false
                if (videoFrameRate !in published.supportedVideoFrameRates) {
                    videoFrameRate = published.supportedVideoFrameRates.first()
                }
                if (!published.maximumPhotoResolutionSupported) {
                    photoResolution = PhotoResolution.STANDARD
                }
                if (!published.hdrExtensionAvailable) photoHdrEnabled = false
                // Reflect what the session actually bound (a lens switch
                // rebinds; the zoom follows the new lens) instead of
                // snapping back to 1x like the old stop-based code did.
                zoom = session.currentDisplayedZoom()
            },
            onError = { error ->
                status = error.message ?: "Camera unavailable"
            },
        )
    }

    // Camera2 owns the camera while these special modes record. Once its
    // MediaRecorder session closes, its TextureView otherwise keeps showing
    // the final buffered frame. Rebind CameraX's live preview into PreviewView
    // while keeping the selected mode and its controls in place.
    LaunchedEffect(camera2PreviewNeedsRestore, mode, recording, specialStarting) {
        if (!camera2PreviewNeedsRestore) return@LaunchedEffect
        if (!mode.usesCamera2Recorder) {
            camera2PreviewNeedsRestore = false
            return@LaunchedEffect
        }
        if (recording || specialStarting) return@LaunchedEffect
        if (Log.isLoggable(CAMERA2_PREVIEW_TAG, Log.DEBUG)) {
            Log.d(CAMERA2_PREVIEW_TAG, "restoring live CameraX preview in ${mode.name}")
        }
        camera2PreviewVisible = false
        delay(100)
        session.setProfile(
            newMode = SessionMode.PHOTO,
            extensionMode = ExtensionMode.NONE,
            bindAfterChange = false,
            macroMode = false,
        )
        bindCameraX()
        // Do not clear this keyed state until after the suspend/bind above:
        // changing a LaunchedEffect key earlier cancels its own coroutine.
        camera2PreviewNeedsRestore = false
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            session.unbind()
            screenFlashView.setScreenFlashWindow(null)
            Thread { camera2Recorder.close() }.start()
        }
    }

    LaunchedEffect(previewViewReady) {
        if (previewViewReady) bindCameraX()
    }

    // Keep the viewfinder look in step with the processing intent (M3.3).
    LaunchedEffect(processing) {
        session.setLook(processing) { error ->
            status = error.message ?: "Could not apply the preview look"
        }
    }

    LaunchedEffect(mode, bokehLevel, capabilities.extensionActive) {
        session.setPortraitBokehPreview(
            enabled = mode == CameraMode.PORTRAIT && !capabilities.extensionActive,
            level = bokehLevel,
        ) { error ->
            status = error.message ?: "Could not update portrait preview"
        }
    }

    LaunchedEffect(mode, photoHdrEnabled) {
        if (mode == CameraMode.PHOTO) {
            session.setExtensionMode(extensionModeFor(mode, photoHdrEnabled)) { error ->
                status = error.message ?: "Could not apply the hardware photo extension"
            }
        }
    }

    LaunchedEffect(watermark) {
        watermarkStore.save(watermark)
        session.setWatermarkOptions(watermark)
    }

    LaunchedEffect(preferredGallery) {
        galleryStore.save(preferredGallery)
    }

    LaunchedEffect(
        flashChoice,
        photoAspectRatio,
        photoResolution,
        videoQuality,
        videoFrameRate,
        selfTimer,
        mirrorCapture,
        shutterSoundEnabled,
        volumeKeyShutter,
        touchShutter,
        photoHdrEnabled,
        timeLapseSpeed,
        slowMotionFps,
        whiteBalance,
        whiteBalanceKelvin,
        bokehLevel,
    ) {
        capturePreferencesStore.save(
            CapturePreferences(
                flashChoice = flashChoice,
                photoAspectRatio = photoAspectRatio,
                photoResolution = photoResolution,
                videoQuality = videoQuality,
                videoFrameRate = videoFrameRate,
                selfTimer = selfTimer,
                mirrorCapture = mirrorCapture,
                shutterSoundEnabled = shutterSoundEnabled,
                volumeKeyShutter = volumeKeyShutter,
                touchShutter = touchShutter,
                photoHdrEnabled = photoHdrEnabled,
                timeLapseSpeed = timeLapseSpeed,
                slowMotionFps = slowMotionFps,
                whiteBalance = whiteBalance,
                whiteBalanceKelvin = whiteBalanceKelvin,
                portraitBlurLevel = bokehLevel,
            ),
        )
    }

    LaunchedEffect(status) {
        if (status != null) {
            delay(2400)
            status = null
        }
    }

    LaunchedEffect(mode) {
        if (mode == CameraMode.VIDEO && micEnabled && !audioGranted) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(mode, modeZoomMaximum, capabilities.minDisplayedZoom) {
        if (!mode.usesCamera2Recorder && zoom > modeZoomMaximum) {
            zoom = session.setDisplayedZoom(modeZoomMaximum)
        }
    }

    var previousMode by remember { mutableStateOf(CameraMode.PHOTO) }
    LaunchedEffect(mode) {
        // A decoded link is a one-shot action, not a sticky overlay when the
        // user changes modes or returns from an external browser.
        qrResult = null
        dismissedQrValue = null
        if (previousMode == CameraMode.PANORAMA && mode != CameraMode.PANORAMA) {
            session.stopPanoramaStream()
            panoramaStreaming = false
            panoramaCapture.reset()
            panoramaProgress = panoramaCapture.progress()
            panoramaPreview = null
            panoramaGuide.reset()
            panoramaGuideState = panoramaGuide.state()
        }
        if (mode.usesCamera2Recorder) {
            // Camera2 and CameraX cannot own the camera at the same time.
            session.unbind()
            camera2PreviewVisible = true
            if (mode == CameraMode.SLOW_MOTION) {
                slowMotionRates = withContext(Dispatchers.IO) {
                    camera2Recorder.supportedSlowMotionRates()
                }
                if (slowMotionRates.isNotEmpty() && slowMotionFps !in slowMotionRates) {
                    slowMotionFps = slowMotionRates.first()
                }
            }
        } else if (previousMode.usesCamera2Recorder) {
            withContext(Dispatchers.IO) { camera2Recorder.stop() }
            camera2PreviewVisible = false
            camera2PreviewNeedsRestore = false
            mode.session?.let {
                session.setProfile(
                    newMode = it,
                    extensionMode = extensionModeFor(mode, photoHdrEnabled),
                    bindAfterChange = false,
                    macroMode = mode == CameraMode.MACRO,
                )
            }
            bindCameraX()
        }
        previousMode = mode
    }

    LaunchedEffect(recording) {
        while (recording) {
            recordSeconds = ((System.currentTimeMillis() - recordStartedAt) / 1000L)
                .toInt()
                .coerceAtLeast(0)
            delay(500)
        }
        if (!recording) recordSeconds = 0
    }

    // Fires one still through the session, with the current look applied.
    fun captureNow() {
        session.capture(
            settings = processing,
            onSaved = { uri ->
                lastMediaUri = uri
                inFlight--
                status = "Saved"
            },
            onNote = { note -> status = note },
            onError = { error ->
                inFlight--
                status = error.message ?: "Capture failed"
            },
        )
    }

    suspend fun capturePixelFrame(maxDimension: Int): CameraSession.PixelFrame =
        suspendCancellableCoroutine { continuation ->
            session.capturePixelFrame(
                maxDimension = maxDimension,
                onFrame = { frame ->
                    if (continuation.isActive) continuation.resume(frame)
                },
                onError = { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                },
            )
        }

    fun finishModeCapture(note: String) {
        inFlight = (inFlight - 1).coerceAtLeast(0)
        processingCapture = false
        status = note
    }

    fun saveModeCapture(pixels: IntArray, width: Int, height: Int, name: String, note: String) {
        session.saveArgbJpeg(
            pixels = pixels,
            width = width,
            height = height,
            namePrefix = name,
            onSaved = { uri ->
                lastMediaUri = uri
                finishModeCapture("$note · saved ${width}×$height")
            },
            onError = { error ->
                finishModeCapture(error.message ?: "Could not save $name capture")
            },
        )
    }

    fun captureNight() {
        if (processingCapture) return
        processingCapture = true
        inFlight++
        coroutineScope.launch {
            try {
                val frames = ArrayList<CameraSession.PixelFrame>(NIGHT_FRAME_COUNT)
                repeat(NIGHT_FRAME_COUNT) { index ->
                    frames += capturePixelFrame(MODE_CAPTURE_MAX_DIMENSION)
                    if (index < NIGHT_FRAME_COUNT - 1) delay(NIGHT_FRAME_INTERVAL_MS)
                }
                val first = frames.first()
                val merged = runCatching {
                    withContext(Dispatchers.Default) {
                        NightMerger.merge(
                            frames = frames.map { it.pixels },
                            width = first.width,
                            height = first.height,
                            maxShift = maxOf(first.width, first.height) / 8,
                        )
                    }
                }.getOrNull()
                val pixels = merged?.pixels ?: first.pixels
                val unprocessedFallback = if (merged != null) pixels.copyOf() else first.pixels
                val processed = runCatching {
                    withContext(Dispatchers.Default) {
                        ImageProcessor.apply(pixels, first.width, first.height, processing)
                    }
                }.isSuccess
                val outputPixels = if (processed) pixels else unprocessedFallback
                val note = if (merged == null) {
                    "Night: alignment unavailable; single downsampled frame"
                } else if (!processed) {
                    "Night: merge saved; optional processing skipped"
                } else {
                    "Night: merged ${merged.usedFrames}/$NIGHT_FRAME_COUNT downsampled frames"
                }
                saveModeCapture(outputPixels, first.width, first.height, "Night", note)
            } catch (error: Throwable) {
                finishModeCapture(error.message ?: "Night capture failed")
            }
        }
    }

    fun capturePortrait() {
        if (processingCapture) return
        processingCapture = true
        inFlight++
        coroutineScope.launch {
            try {
                val frame = capturePixelFrame(MODE_CAPTURE_MAX_DIMENSION)
                val (rendered, faceDetected) = try {
                    withContext(Dispatchers.Default) {
                        val subject = PortraitSubjectDetector.detectShape(
                            pixels = frame.pixels,
                            width = frame.width,
                            height = frame.height,
                            level = bokehLevel,
                        )
                        val output = PortraitRenderer.blurBackground(
                            pixels = frame.pixels,
                            width = frame.width,
                            height = frame.height,
                            shape = subject.shape,
                            maxBlurRadius = PORTRAIT_BLUR_RADIUS,
                        ).also { ImageProcessor.apply(it, frame.width, frame.height, processing) }
                        output to subject.faceDetected
                    }
                } catch (_: Throwable) {
                    frame.pixels to false
                }
                saveModeCapture(
                    rendered,
                    frame.width,
                    frame.height,
                    "Portrait",
                    if (faceDetected) {
                        "Face-guided software bokeh · no depth data"
                    } else {
                        "Centre-guided software bokeh · no face or depth data"
                    },
                )
            } catch (error: Throwable) {
                finishModeCapture(error.message ?: "Portrait capture failed")
            }
        }
    }

    fun captureBeauty() {
        if (processingCapture) return
        processingCapture = true
        inFlight++
        coroutineScope.launch {
            try {
                val frame = capturePixelFrame(MODE_CAPTURE_MAX_DIMENSION)
                val pixels = frame.pixels
                val strength = if (processing.skinSmoothing > 0f) processing.skinSmoothing else 0.55f
                withContext(Dispatchers.Default) {
                    ImageProcessor.apply(
                        pixels,
                        frame.width,
                        frame.height,
                        processing.copy(skinSmoothing = strength),
                    )
                }
                saveModeCapture(
                    pixels,
                    frame.width,
                    frame.height,
                    "Beauty",
                    "Software skin smoothing · no face reshaping",
                )
            } catch (error: Throwable) {
                finishModeCapture(error.message ?: "Beauty capture failed")
            }
        }
    }

    fun capturePanoramaFrame() {
        if (processingCapture || panoramaStreaming) return
        panoramaCapture.reset()
        panoramaProgress = panoramaCapture.progress()
        panoramaPreview = null
        panoramaGuide.reset()
        panoramaGuideState = panoramaGuide.state()
        val expectedDirection = panoramaDirection.canvasShiftSign
        val directionLabel = panoramaDirection.name.lowercase(Locale.US)
        val started = session.startPanoramaStream(
            maxDimension = PanoramaCapture.DEFAULT_MAX_DIMENSION,
            onFrame = { frame ->
                val previousCount = panoramaCapture.frameCount
                val updated = panoramaCapture.addFrame(
                    frame.pixels,
                    frame.width,
                    frame.height,
                    expectedDirection = expectedDirection,
                )
                val preview = if (updated.acceptedFrames > previousCount) {
                    panoramaCapture.previewBitmap(PanoramaCapture.PREVIEW_MAX_WIDTH)
                } else {
                    null
                }
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    panoramaProgress = updated
                    if (preview != null) panoramaPreview = preview
                    status = when {
                        updated.lastRejection == PanoramaCapture.Rejection.WRONG_DIRECTION -> "Wrong way · turn $directionLabel"
                        updated.lastRejection == PanoramaCapture.Rejection.NO_MOVEMENT -> "Keep panning slowly"
                        updated.acceptedFrames == 1 -> "Keep panning · image building"
                        else -> "${updated.acceptedFrames} frames · ${updated.estimatedWidth}×${updated.estimatedHeight}"
                    }
                }
            },
            onError = { error ->
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    status = error.message ?: "Panorama frame failed"
                }
            },
        )
        if (started) {
            panoramaStreaming = true
            status = "Pan slowly $directionLabel · keep level"
        }
    }

    fun finishPanorama() {
        if (processingCapture) return
        session.stopPanoramaStream()
        panoramaStreaming = false
        val capturedProgress = panoramaCapture.progress()
        panoramaProgress = capturedProgress
        if (capturedProgress.acceptedFrames < 2) {
            panoramaCapture.reset()
            panoramaProgress = panoramaCapture.progress()
            panoramaPreview = null
            status = "Panorama needs at least two aligned frames"
            return
        }
        processingCapture = true
        inFlight++
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    panoramaCapture.finish()?.also {
                        runCatching { ImageProcessor.apply(it.pixels, it.width, it.height, processing) }
                    }
                }
                panoramaProgress = capturedProgress
                if (result == null) {
                    finishModeCapture("Panorama needs at least two aligned frames")
                    return@launch
                }
                saveModeCapture(
                    result.pixels,
                    result.width,
                    result.height,
                    "Panorama",
                    "Software panorama · translation-only stitch",
                )
            } catch (error: Throwable) {
                finishModeCapture(error.message ?: "Panorama stitch failed")
            }
        }
    }

    DisposableEffect(panoramaStreaming, panoramaGuide, sensorManager) {
        if (!panoramaStreaming) {
            onDispose { }
        } else {
            val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_GYROSCOPE -> panoramaGuide.onGyro(
                            event.values[0], event.values[1], event.values[2], event.timestamp,
                        )
                        Sensor.TYPE_ACCELEROMETER -> panoramaGuide.onAccelerometer(
                            event.values[0], event.values[1], event.values[2], event.timestamp,
                        )
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (gyro != null) sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
            if (accelerometer != null) {
                sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            }
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    LaunchedEffect(panoramaStreaming, panoramaGuide, panoramaProgress.isFull) {
        if (!panoramaStreaming) {
            panoramaGuideState = panoramaGuide.state()
            return@LaunchedEffect
        }
        while (panoramaStreaming) {
            panoramaGuideState = panoramaGuide.state()
            if (panoramaProgress.isFull ||
                (panoramaProgress.acceptedFrames >= 2 && panoramaGuideState.sweepProgress >= 1f)
            ) {
                finishPanorama()
                break
            }
            delay(100)
        }
    }

    fun captureCurrentMode() {
        when (mode) {
            CameraMode.NIGHT -> if (capabilities.extensionActive) captureNow() else captureNight()
            CameraMode.PORTRAIT -> if (capabilities.extensionActive) captureNow() else capturePortrait()
            CameraMode.BEAUTY -> if (capabilities.extensionActive) captureNow() else captureBeauty()
            CameraMode.PANORAMA -> if (panoramaStreaming) finishPanorama() else capturePanoramaFrame()
            CameraMode.QR -> Unit
            else -> captureNow()
        }
    }

    fun pressStillShutter() {
        if (processingCapture || inFlight >= MAX_IN_FLIGHT) return
        shutterPulse++
        if (mode == CameraMode.PHOTO && selfTimer.enabled) {
            if (timerDeadline == 0L) {
                inFlight++
                pendingShot = true
                timerDeadline = System.currentTimeMillis() + selfTimer.seconds * 1000L
            }
        } else if (mode == CameraMode.PHOTO || mode == CameraMode.EXPERT) {
            inFlight++
            captureNow()
        } else {
            captureCurrentMode()
        }
    }

    /** Applies a mode change to the session; shared by the carousel and shortcut launches. */
    fun switchMode(selected: CameraMode) {
        if (recording || specialStarting || processingCapture || panoramaStreaming) {
            status = "Stop recording before switching modes"
        } else if (selected == CameraMode.MACRO && !capabilities.macroFocusSupported) {
            status = "This camera does not expose macro autofocus"
        } else if (selected != mode) {
            val previous = mode
            mode = selected
            if (!previous.usesCamera2Recorder && !selected.usesCamera2Recorder) {
                if (extensionModeFor(selected, photoHdrEnabled) != ExtensionMode.NONE) {
                    rawEnabled = false
                    isoGauge = 0f
                    speedGauge = 0f
                    focusGauge = 0f
                }
                selected.session?.let { sessionMode ->
                    session.setProfile(
                        newMode = sessionMode,
                        extensionMode = extensionModeFor(selected, photoHdrEnabled),
                        macroMode = selected == CameraMode.MACRO,
                        onError = { error ->
                            status = error.message ?: "Could not switch mode"
                        },
                    )
                }
                if (selected == CameraMode.VIDEO) rawEnabled = false
            }
            if (selected != CameraMode.VIDEO) torchEnabled = false
        }
    }

    // Launcher shortcuts and external launches: apply the requested mode and
    // facing once, then clear the request in the activity.
    LaunchedEffect(shortcut) {
        val selection = shortcut ?: return@LaunchedEffect
        switchMode(selection.mode)
        selection.facing?.let { facing ->
            session.setLensFacing(facing == ModeShortcut.Facing.FRONT) { error ->
                status = error.message ?: "Could not switch camera"
            }
        }
        onShortcutConsumed()
    }

    // Continuous shot: holding the shutter keeps queueing stills until the
    // burst cap or release (clean-room hold-shutter behavior; see
    // ContinuousShotPlan). The self-timer and the special pipelines keep
    // their own semantics and refuse the burst with a status note.
    fun burstEligible(): Boolean =
        (mode == CameraMode.PHOTO || mode == CameraMode.EXPERT) &&
            !selfTimer.enabled && !processingCapture

    fun burstSummary(shots: Int): String =
        "Burst: $shots ${if (shots == 1) "shot" else "shots"}"

    fun startBurst() {
        if (!burstEligible()) {
            status = if (selfTimer.enabled) {
                "Continuous shot is off with the self-timer"
            } else {
                "Continuous shot works in PHOTO and EXPERT"
            }
            return
        }
        if (burstActive) return
        burstActive = true
        burstShots = 0
        shutterPulse++
        coroutineScope.launch {
            while (isActive && ContinuousShotPlan.shouldContinue(burstActive, burstShots)) {
                if (!burstEligible()) break
                if (ContinuousShotPlan.canTrigger(inFlight, MAX_IN_FLIGHT)) {
                    inFlight++
                    burstShots++
                    shutterPulse++
                    status = "BURST $burstShots/${ContinuousShotPlan.MAX_SHOTS}"
                    captureNow()
                }
                delay(ContinuousShotPlan.TRIGGER_INTERVAL_MS)
            }
            if (burstShots > 0) status = burstSummary(burstShots)
            burstActive = false
        }
    }

    fun stopBurst() {
        if (burstActive && burstShots > 0) status = burstSummary(burstShots)
        burstActive = false
    }

    LaunchedEffect(volumeKeyShutter) {
        if (volumeKeyShutter) {
            CameraKeyEvents.volumeShutter.collect {
                if (mode != CameraMode.VIDEO && !mode.usesCamera2Recorder && mode != CameraMode.QR) {
                    pressStillShutter()
                }
            }
        }
    }

    fun toggleCamera2Recording() {
        if (recording) {
            coroutineScope.launch { withContext(Dispatchers.IO) { camera2Recorder.stop() } }
            return
        }
        if (specialStarting) return
        specialStarting = true
        coroutineScope.launch {
            try {
                if (!camera2PreviewVisible) {
                    session.unbind()
                    camera2PreviewVisible = true
                }
                val uri = when (mode) {
                    CameraMode.SLOW_MOTION -> camera2Recorder.startSlowMotion(
                        fps = slowMotionFps,
                        audioEnabled = false,
                    )
                    CameraMode.TIME_LAPSE -> camera2Recorder.startTimeLapse(
                        intervalSeconds = timeLapseSpeed / 30f,
                        audioEnabled = false,
                    )
                    else -> null
                }
                if (uri != null) {
                    recordStartedAt = System.currentTimeMillis()
                    status = if (mode == CameraMode.SLOW_MOTION) {
                        "Recording ${slowMotionFps}fps slow motion"
                    } else {
                        "Recording time lapse · X$timeLapseSpeed"
                    }
                } else {
                    camera2PreviewNeedsRestore = true
                }
            } catch (error: Exception) {
                status = error.message ?: "Could not start recording"
                camera2PreviewNeedsRestore = true
            } finally {
                specialStarting = false
            }
        }
    }

    // Self-timer countdown; zero means idle.
    LaunchedEffect(timerDeadline) {
        if (timerDeadline == 0L) {
            timerLeft = 0
            return@LaunchedEffect
        }
        while (true) {
            val left = CaptureTimer.remaining(
                seconds = selfTimer.seconds,
                startedAtMillis = timerDeadline - selfTimer.seconds * 1000L,
                nowMillis = System.currentTimeMillis(),
            )
            timerLeft = left
            if (left <= 0) {
                timerDeadline = 0L
                if (pendingShot) {
                    pendingShot = false
                    captureNow()
                }
                break
            }
            delay(200)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        val previewFrameModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(previewAspectRatio)
            .align(Alignment.TopCenter)
            .offset(y = 100.dp)
        if (mode.usesCamera2Recorder && camera2PreviewVisible) {
            AndroidView(
                factory = { camera2TextureView },
                modifier = previewFrameModifier,
            )
        } else {
            AndroidView(
                factory = { previewView },
                modifier = previewFrameModifier.onGloballyPositioned { coordinates ->
                    previewViewReady = coordinates.size.width > 0 && coordinates.size.height > 0
                },
            )
        }
        AndroidView(
            factory = { screenFlashView },
            modifier = previewFrameModifier,
        )

        if (mode == CameraMode.QR) {
            qrResult?.let { result ->
                Text(
                    text = result,
                    color = White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(Color(0xCC000000))
                        .clickable {
                            dismissedQrValue = result
                            qrResult = null
                            openQrResult(context, result) { note -> status = note }
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }

        GuideOverlay(
            guides = guides,
            modifier = previewFrameModifier,
        )
        LevelLine(
            rollDegrees = roll,
            modifier = previewFrameModifier,
        )

        if (mode == CameraMode.PANORAMA) {
            val guideText = when (panoramaGuideState.hint) {
                PanoramaGuide.Hint.READY -> "START SWEEP"
                PanoramaGuide.Hint.PAN -> "FOLLOW THE LINE · KEEP LEVEL"
                PanoramaGuide.Hint.TOO_FAST -> "SLOW DOWN"
                PanoramaGuide.Hint.WRONG_WAY -> "TURN ${panoramaDirection.name}"
                PanoramaGuide.Hint.HOLD_LEVEL -> "HOLD LEVEL"
                PanoramaGuide.Hint.STILL -> "KEEP MOVING SLOWLY"
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 26.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xD9000000))
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = if (panoramaStreaming) guideText else "TAP SHUTTER · SWEEP ${panoramaDirection.name}",
                    color = White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
                panoramaPreview?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Live panorama preview strip",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .width(300.dp)
                            .height(76.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
                Canvas(modifier = Modifier.width(300.dp).height(22.dp)) {
                    val y = size.height / 2f
                    drawLine(
                        color = White.copy(alpha = 0.75f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx(),
                    )
                    val progress = if (panoramaStreaming) panoramaGuideState.sweepProgress else 0f
                    val x = size.width * progress
                    drawCircle(color = Black, radius = 8.dp.toPx(), center = Offset(x, y))
                    drawCircle(color = White, radius = 6.dp.toPx(), center = Offset(x, y))
                }
                Text(
                    text = if (panoramaStreaming) {
                        "${(panoramaGuideState.sweepProgress * 100).roundToInt()}% · " +
                            "${panoramaProgress.acceptedFrames}/${panoramaProgress.maxFrames} FRAMES"
                    } else {
                        "${panoramaProgress.acceptedFrames}/${panoramaProgress.maxFrames} FRAMES"
                    },
                    color = White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // M4: show what stabilization takes away while in video mode.
        if (mode == CameraMode.VIDEO && stabilizationEnabled) {
            val crop = StabilizationProfile.estimatedCrop(capabilities.stabilization)
            StabilizationCropOverlay(
                cropFraction = crop,
                label = "STABILIZED −${(crop * 100f).roundToInt()}%",
                modifier = previewFrameModifier,
            )
        }

        // QR mode has its own clickable decoded-result chip above. Leaving
        // the generic full-screen focus/tap layer over it intercepts taps and
        // makes a decoded web link appear to do nothing.
        if (!mode.usesCamera2Recorder && mode != CameraMode.QR) Box(
            modifier = Modifier
                .then(previewFrameModifier)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            when {
                                trackingArmed && mode.supportsSubjectTracking() -> {
                                    focusTarget = null
                                    focusLocked = false
                                    if (session.selectSubjectAt(offset.x, offset.y)) {
                                        trackingPoint = offset
                                        trackingConfidence = 1f
                                        status = "Tracking subject"
                                    } else {
                                        status = "Tracking is starting — tap again"
                                    }
                                }
                                touchShutter -> {
                                    focusTarget = null
                                    pressStillShutter()
                                }
                                else -> {
                                    focusTarget = offset
                                    focusLocked = false
                                    evIndex = session.currentExposureCompensationIndex()
                                    session.focusAndMeterAt(offset.x, offset.y)
                                }
                            }
                        },
                        onLongPress = { offset ->
                            if (trackingArmed && mode.supportsSubjectTracking()) {
                                session.clearSubjectTracking()
                                trackingPoint = null
                                status = "Tracking cleared"
                            } else {
                                focusTarget = offset
                                focusLocked = true
                                session.focusAndMeterAt(offset.x, offset.y, lock = true)
                            }
                        },
                    )
                }
                .pointerInput(capabilities.maxDisplayedZoom) {
                detectTransformGestures { _, _, gestureZoom, _ ->
                    // Gesture zoom is incremental, so this accumulates.
                    val target = (zoom * gestureZoom)
                        .coerceIn(capabilities.minDisplayedZoom, modeZoomMaximum)
                    zoom = session.setDisplayedZoom(target) { error ->
                            status = error.message ?: "Could not zoom"
                        }
                        pinching = true
                    }
                },
        ) {
            focusTarget?.let { point ->
                val density = LocalDensity.current
                val ringHalfPx = with(density) { RING_SIZE_HALF.toPx() }
                val sliderHalfPx = with(density) { SLIDER_HEIGHT_HALF.toPx() }
                val sliderSidePx = with(density) { SLIDER_SIDE_GAP.toPx() }
                FocusRing(
                    locked = focusLocked,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                (point.x - ringHalfPx).roundToInt(),
                                (point.y - ringHalfPx).roundToInt(),
                            )
                        },
                )
                BrightnessSlider(
                    index = evIndex,
                    indexRange = capabilities.evRange,
                    evStep = capabilities.evStep,
                    onChange = { evIndex = session.setExposureCompensationIndex(it) },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                (point.x + sliderSidePx).roundToInt(),
                                (point.y - sliderHalfPx).roundToInt(),
                            )
                        },
                )
            }
            if (trackingArmed && mode.supportsSubjectTracking()) {
                trackingPoint?.let { point ->
                    val density = LocalDensity.current
                    val halfPx = with(density) { (SUBJECT_TRACKING_BOX_DP / 2f).dp.toPx() }
                    TrackingReticle(
                        confidence = trackingConfidence,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                IntOffset(
                                    (point.x - halfPx).roundToInt(),
                                    (point.y - halfPx).roundToInt(),
                                )
                            },
                    )
                }
            }
            if (mode == CameraMode.EXPERT && histogramArmed) {
                histogram?.let { stats ->
                    HistogramOverlay(
                        histogram = stats,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 64.dp, end = 12.dp),
                    )
                }
            }
            if (mode == CameraMode.EXPERT && zebrasArmed) {
                ZebraOverlay(masks = overlayMasks, modifier = Modifier.fillMaxSize())
            }
            if (mode == CameraMode.EXPERT && peakingArmed) {
                PeakingOverlay(masks = overlayMasks, modifier = Modifier.fillMaxSize())
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = !mode.usesCamera2Recorder &&
                    mode != CameraMode.NIGHT && mode != CameraMode.PANORAMA && mode != CameraMode.QR,
                onClick = {
                    if (mode == CameraMode.VIDEO) {
                        val next = !torchEnabled
                        torchEnabled = next
                        session.setTorchEnabled(next)
                    } else {
                        flashChoice = flashChoice.next(screenFlashAvailable)
                        session.setFlashChoice(flashChoice) { error ->
                            status = error.message ?: "Could not change flash mode"
                        }
                    }
                },
            ) {
                val image = when {
                    mode == CameraMode.VIDEO -> if (torchEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff
                    flashChoice == FlashChoice.AUTO -> Icons.Outlined.FlashAuto
                    flashChoice == FlashChoice.OFF -> Icons.Outlined.FlashOff
                    else -> Icons.Outlined.FlashOn
                }
                Icon(
                    imageVector = image,
                    contentDescription = if (mode == CameraMode.VIDEO) {
                        if (torchEnabled) "Turn torch off" else "Turn torch on"
                    } else {
                        "Flash ${flashChoice.label}"
                    },
                    tint = White,
                )
            }
            if (mode.supportsSubjectTracking()) {
                IconButton(
                    enabled = !recording && !specialStarting,
                    onClick = {
                        val next = !trackingArmed
                        trackingArmed = next
                        trackingPoint = null
                        trackingConfidence = 1f
                        capturePreferencesStore.setSubjectTracking(next)
                        status = if (next) {
                            "Subject tracking on — tap the subject"
                        } else {
                            "Subject tracking off"
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FilterCenterFocus,
                        contentDescription = if (trackingArmed) {
                            "Subject tracking on"
                        } else {
                            "Subject tracking off"
                        },
                        tint = if (trackingArmed) Black else White,
                        modifier = if (trackingArmed) {
                            Modifier
                                .clip(CircleShape)
                                .background(Color(0xE6FFFFFF))
                                .padding(6.dp)
                        } else {
                            Modifier
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mode == CameraMode.PHOTO) {
                    ToggleChip(
                        label = "TIMER ${selfTimer.label}",
                        active = selfTimer.enabled,
                        enabled = true,
                        onClick = {
                            selfTimer = selfTimer.next()
                            if (!selfTimer.enabled && timerDeadline != 0L) {
                                // Cancel a running countdown: give the shot back.
                                timerDeadline = 0L
                                timerLeft = 0
                                pendingShot = false
                            }
                        },
                    )
                }
                IconButton(onClick = { guides = guides.next() }) {
                    Icon(
                        imageVector = Icons.Outlined.GridOn,
                        contentDescription = "Cycle composition guides",
                        tint = White,
                    )
                }
                IconButton(
                    enabled = !recording && !specialStarting,
                    onClick = { showProcessing = true },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Camera settings",
                        tint = White,
                    )
                }
            }
        }

        if (timerLeft > 0) {
            Text(
                text = String.format(Locale.US, "%d", timerLeft),
                color = White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color(0x59000000))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        if (recording) {
            Text(
                text = String.format(
                    Locale.US,
                    "● REC %02d:%02d",
                    recordSeconds / 60,
                    recordSeconds % 60,
                ),
                color = White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = 56.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        status?.let { message ->
            Text(
                text = message,
                color = White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 240.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!mode.usesCamera2Recorder) {
                ZoomPill(
                    stops = capabilities.zoomStops,
                    stopFocalMm = capabilities.zoomStopFocalMm,
                    modeMaximum = modeZoomMaximum,
                    opticalStops = capabilities.opticalZoomStops,
                    current = zoom,
                    zoomable = modeZoomMaximum > capabilities.minDisplayedZoom,
                    referenceEqFocalMm = capabilities.referenceEqFocalMm,
                    onSelect = { stop ->
                        zoom = session.setDisplayedZoom(stop) { error ->
                            status = error.message ?: "Could not switch lens"
                        }
                        pinching = false
                    },
                )
                if (capabilities.zoomStops.any { it <= modeZoomMaximum } &&
                    capabilities.zoomStops.filter { it <= modeZoomMaximum }.size > 1
                ) Spacer(Modifier.height(14.dp))
            }

            when (mode) {
                CameraMode.VIDEO -> {
                    VideoChips(
                        stabilizationEnabled = stabilizationEnabled,
                        stabilization = capabilities.stabilization,
                        opticalStabilizationEnabled = opticalStabilizationEnabled,
                        opticalStabilizationSupported = capabilities.opticalStabilizationSupported,
                        videoNoiseReductionEnabled = videoNoiseReductionEnabled,
                        videoNoiseReductionSupported = capabilities.videoNoiseReductionSupported,
                        micEnabled = micEnabled,
                        frameRate = videoFrameRate,
                        supportedFrameRates = capabilities.supportedVideoFrameRates,
                        videoSnapshotSupported = capabilities.videoSnapshotSupported,
                        onStabToggle = {
                            val next = !stabilizationEnabled
                            stabilizationEnabled = next
                            status = if (next) {
                                "Stabilization: ${capabilities.stabilization.label}"
                            } else {
                                "Stabilization off"
                            }
                            session.setStabilizationEnabled(next) { error ->
                                status = error.message ?: "Could not change stabilization"
                            }
                        },
                        onOisToggle = {
                            val next = !opticalStabilizationEnabled
                            opticalStabilizationEnabled = next
                            session.setOpticalStabilizationEnabled(next) { error ->
                                status = error.message ?: "Could not change OIS"
                            }
                        },
                        onNoiseReductionToggle = {
                            val next = !videoNoiseReductionEnabled
                            val previous = videoNoiseReductionEnabled
                            videoNoiseReductionEnabled = next
                            session.setVideoNoiseReductionEnabled(next) { error ->
                                videoNoiseReductionEnabled = previous
                                status = error.message ?: "Could not change video NR"
                            }
                        },
                        onActionToggle = {
                            val actionOn = videoFrameRate == VideoFrameRate.FPS_60 && stabilizationEnabled
                            if (actionOn) {
                                videoFrameRate = session.setVideoFrameRate(VideoFrameRate.FPS_30)
                                stabilizationEnabled = false
                                session.setStabilizationEnabled(false) { error ->
                                    status = error.message ?: "Could not disable stabilization"
                                }
                            } else {
                                videoFrameRate = session.setVideoFrameRate(VideoFrameRate.FPS_60) { error ->
                                    status = error.message ?: "60fps is not supported"
                                }
                                stabilizationEnabled = true
                                session.setStabilizationEnabled(true) { error ->
                                    status = error.message ?: "Could not enable stabilization"
                                }
                            }
                        },
                        onMicToggle = {
                            if (micEnabled) {
                                micEnabled = false
                                status = "Microphone off"
                            } else if (audioGranted) {
                                micEnabled = true
                                status = "Microphone on"
                            } else {
                                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        recording = recording,
                        onSnapshot = {
                            inFlight++
                            session.capture(
                                settings = processing,
                                onSaved = { uri ->
                                    lastMediaUri = uri
                                    inFlight--
                                    status = "Video snapshot saved"
                                },
                                onNote = { note -> status = note },
                                onError = { error ->
                                    inFlight--
                                    status = error.message ?: "Video snapshot failed"
                                },
                            )
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.EXPERT -> {
                    ExpertControls(
                        isoGauge = isoGauge,
                        speedGauge = speedGauge,
                        focusGauge = focusGauge,
                        isoRange = capabilities.isoRange,
                        exposureRange = capabilities.exposureRange,
                        minFocusDistance = capabilities.minFocusDistance,
                        rawEnabled = rawEnabled,
                        rawSupported = capabilities.rawSupported,
                        histogramEnabled = histogramArmed,
                        zebrasEnabled = zebrasArmed,
                        peakingEnabled = peakingArmed,
                        whiteBalance = whiteBalance,
                        whiteBalanceKelvin = whiteBalanceKelvin,
                        onIsoChange = { isoGauge = it },
                        onSpeedChange = { speedGauge = it },
                        onFocusChange = { focusGauge = it },
                        onWhiteBalanceChange = { whiteBalance = it },
                        onWhiteBalanceKelvinChange = { whiteBalanceKelvin = it },
                        onCommit = {
                            session.setManualControls(
                                ManualControls(
                                    iso = capabilities.isoRange?.let {
                                        ManualControlPlan.isoFor(isoGauge, it)
                                    },
                                    exposureTimeNs = capabilities.exposureRange?.let {
                                        ManualControlPlan.exposureFor(speedGauge, it)
                                    },
                                    focusDiopters = capabilities.minFocusDistance?.let {
                                        ManualControlPlan.focusFor(focusGauge, it)
                                    },
                                    whiteBalance = whiteBalance,
                                    colorTemperatureKelvin = whiteBalanceKelvin.takeIf {
                                        whiteBalance == WhiteBalancePreset.CUSTOM
                                    },
                                ),
                            ) { error ->
                                status = error.message ?: "Could not apply manual controls"
                            }
                        },
                        onRawToggle = {
                            val next = !rawEnabled
                            rawEnabled = next
                            status = if (next) "RAW on — saved as DNG + JPEG" else "RAW off"
                            session.setRawEnabled(next) { error ->
                                status = error.message ?: "Could not change RAW mode"
                            }
                        },
                        onHistogramToggle = {
                            val next = !histogramArmed
                            histogramArmed = next
                            capturePreferencesStore.setHistogram(next)
                            status = if (next) "Histogram on" else null
                        },
                        onZebrasToggle = {
                            val next = !zebrasArmed
                            zebrasArmed = next
                            capturePreferencesStore.setZebras(next)
                            status = if (next) "Zebra stripes on" else null
                        },
                        onPeakingToggle = {
                            val next = !peakingArmed
                            peakingArmed = next
                            capturePreferencesStore.setPeaking(next)
                            status = if (next) "Focus peaking on" else null
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.PHOTO -> Unit

                CameraMode.PORTRAIT -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(280.dp),
                    ) {
                        Text(
                            text = if (capabilities.extensionActive) {
                                "CAMERA HARDWARE BOKEH"
                            } else {
                                "SOFTWARE BOKEH · DEPTH NOT EXPOSED"
                            },
                            color = DimWhite,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (!capabilities.extensionActive) {
                            Slider(
                                value = bokehLevel,
                                onValueChange = { bokehLevel = it },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.BEAUTY -> {
                    Text(
                        text = if (capabilities.extensionActive) {
                            "CAMERA FACE-RETOUCH EXTENSION"
                        } else {
                            "SOFTWARE SKIN SMOOTHING · NO FACE SHAPING"
                        },
                        color = DimWhite,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.NIGHT -> {
                    Text(
                        text = if (capabilities.extensionActive) {
                            "CAMERA HARDWARE NIGHT"
                        } else {
                            "4-FRAME SOFTWARE MERGE · DOWNSAMPLED"
                        },
                        color = DimWhite,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.PANORAMA -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToggleChip(
                            label = "SWEEP ${panoramaDirection.name}",
                            active = true,
                            enabled = !processingCapture && !panoramaStreaming,
                            onClick = {
                                panoramaDirection = panoramaDirection.opposite
                            },
                        )
                        Text(
                            text = "${panoramaProgress.acceptedFrames}/${panoramaProgress.maxFrames} FRAMES",
                            color = DimWhite,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (panoramaStreaming || panoramaProgress.acceptedFrames >= 2) {
                            ToggleChip(
                                label = if (panoramaStreaming) "STOP & SAVE" else "FINISH",
                                active = panoramaStreaming,
                                enabled = !processingCapture,
                                onClick = { finishPanorama() },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.MACRO -> {
                    Text(
                        text = "CAMERA2 MACRO AUTOFOCUS · MIN FOCUS ${capabilities.minFocusDistance ?: 0f}D",
                        color = DimWhite,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.QR -> {
                    Text(
                        text = qrResult?.let { "CODE FOUND · TAP TO OPEN/COPY" } ?: "POINT AT A QR CODE",
                        color = DimWhite,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.SLOW_MOTION -> {
                    RateSelector(
                        label = "CAPTURE FPS",
                        choices = slowMotionRates,
                        selected = slowMotionFps,
                        format = { "${it}FPS" },
                        unavailableLabel = "SLOW MOTION NOT SUPPORTED",
                        enabled = !recording && !specialStarting,
                        onSelect = { slowMotionFps = it },
                    )
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.TIME_LAPSE -> {
                    RateSelector(
                        label = "SPEED",
                        choices = TIME_LAPSE_SPEEDS,
                        selected = timeLapseSpeed,
                        format = { "X$it" },
                        unavailableLabel = "TIME LAPSE",
                        enabled = !recording && !specialStarting,
                        onSelect = { timeLapseSpeed = it },
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }

            ModeSelector(
                selected = mode,
                macroSupported = capabilities.macroFocusSupported,
                onSelect = { selected -> switchMode(selected) },
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircle(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = "Gallery",
                    onClick = {
                        coroutineScope.launch {
                            // Prefer what this session just saved; otherwise
                            // the newest Camera+ item on disk.
                            val target = lastMediaUri
                                ?: MediaLibrary.recent(context, limit = 1).firstOrNull()?.uri
                            if (!SystemGallery.open(context, target, preferredPackage = preferredGallery)) {
                                // No gallery app on this device; keep ours.
                                showGallery = true
                            }
                        }
                    },
                )
                if (mode == CameraMode.VIDEO) {
                    RecordButton(
                        recording = recording,
                        enabled = true,
                        onClick = {
                            if (!recording) {
                                if (micEnabled && !audioGranted) {
                                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    status = "Allow the microphone, then tap again"
                                } else {
                                    val started = session.startRecording(
                                        recordAudio = micEnabled,
                                        onFinished = {
                                            recording = false
                                            status = "Video saved"
                                        },
                                        onError = { error ->
                                            recording = false
                                            status = error.message ?: "Recording failed"
                                        },
                                    )
                                    if (started) {
                                        recordStartedAt = System.currentTimeMillis()
                                        recordSeconds = 0
                                        recording = true
                                    }
                                }
                            } else {
                                session.stopRecording()
                            }
                        },
                    )
                } else if (mode.usesCamera2Recorder) {
                    RecordButton(
                        recording = recording,
                        enabled = (!specialStarting || recording) &&
                            (mode != CameraMode.SLOW_MOTION || slowMotionRates.isNotEmpty()),
                        onClick = { toggleCamera2Recording() },
                    )
                } else if (mode == CameraMode.QR) {
                    Text(
                        text = if (qrResult == null) "SCANNING" else "SCANNED",
                        color = White,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    ShutterButton(
                        capturing = inFlight > 0 || panoramaStreaming,
                        enabled = (inFlight < MAX_IN_FLIGHT || panoramaStreaming) && !processingCapture,
                        pulse = shutterPulse,
                        onClick = { pressStillShutter() },
                        onBurstStart = { startBurst() },
                        onBurstStop = { stopBurst() },
                    )
                }
                IconCircle(
                    imageVector = Icons.Outlined.Cameraswitch,
                    contentDescription = "Switch camera",
                    enabled = !mode.usesCamera2Recorder &&
                        !(mode == CameraMode.PANORAMA &&
                            (panoramaStreaming || panoramaProgress.acceptedFrames > 0)),
                    onClick = {
                        if (recording) {
                            status = "Stop recording to switch cameras"
                        } else {
                            session.flipLens { error ->
                                status = error.message ?: "Could not switch camera"
                            }
                        }
                    },
                )
            }
        }

        if (showProcessing) {
            ProcessingSheet(
                settings = processing,
                onSettingsChange = { updated ->
                    processing = updated
                    store.save(updated)
                },
                capturePreferences = CapturePreferences(
                    flashChoice = flashChoice,
                    photoAspectRatio = photoAspectRatio,
                    photoResolution = photoResolution,
                    videoQuality = videoQuality,
                    videoFrameRate = videoFrameRate,
                    selfTimer = selfTimer,
                    mirrorCapture = mirrorCapture,
                    shutterSoundEnabled = shutterSoundEnabled,
                    volumeKeyShutter = volumeKeyShutter,
                    touchShutter = touchShutter,
                    photoHdrEnabled = photoHdrEnabled,
                    timeLapseSpeed = timeLapseSpeed,
                    slowMotionFps = slowMotionFps,
                    whiteBalance = whiteBalance,
                    whiteBalanceKelvin = whiteBalanceKelvin,
                    portraitBlurLevel = bokehLevel,
                ),
                supportedVideoFrameRates = capabilities.supportedVideoFrameRates,
                maximumPhotoResolutionSupported = capabilities.maximumPhotoResolutionSupported,
                screenFlashSupported = screenFlashAvailable,
                hdrExtensionAvailable = capabilities.hdrExtensionAvailable,
                onCapturePreferencesChange = ::applyCapturePreferences,
                watermark = watermark,
                onWatermarkChange = { watermark = it },
                galleryApps = galleryApps,
                preferredGalleryPackage = preferredGallery,
                onPreferredGalleryChange = { preferredGallery = it },
                geotagEnabled = geotagEnabled,
                onGeotagToggle = ::toggleGeotag,
                onDismiss = { showProcessing = false },
            )
        }

        if (showGallery) {
            GalleryScreen(onDismiss = { showGallery = false })
        }
    }
}

/**
 * Lens stops plus the live zoom readout (v1.1; mm labels added 2026-10-18).
 *
 * The stops are the physical lenses the plan found (0.6x, 1x, 2x, …) with a
 * short digital ladder. Chips are labelled with their 35 mm-equivalent focal
 * length ("24mm") like the original's zoom indicator; the live readout keeps
 * the ratio and adds the equivalent focal while pinching. Hidden only when
 * the camera offers no zoom range at all.
 */
@Composable
private fun ZoomPill(
    stops: List<Float>,
    stopFocalMm: List<Float>,
    modeMaximum: Float,
    opticalStops: List<Float>,
    current: Float,
    zoomable: Boolean,
    referenceEqFocalMm: Float,
    onSelect: (Float) -> Unit,
) {
    val visible = stops.mapIndexedNotNull { index, stop ->
        if (stop > modeMaximum + 0.01f) null else stop to (stopFocalMm.getOrNull(index) ?: 0f)
    }
    if (visible.isEmpty()) return
    val currentFocal = LensZoomPlan.focalLabel(referenceEqFocalMm * current)
    val currentLabel = LensZoomPlan.stopLabel(current) +
        (currentFocal?.let { " · $it" } ?: "") +
        if (opticalStops.any { abs(it - current) < 0.05f }) " · LENS" else " · DIGITAL"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (zoomable) {
            Text(
                text = currentLabel,
                color = White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .clip(CircleShape)
                    .background(Color(0x59000000))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        if (visible.size < 2 && !zoomable) return@Column
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0x33000000))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visible.forEach { (stop, focalMm) ->
                val active = abs(stop - current) < 0.02f
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (active) Color(0x66FFFFFF) else Color.Transparent)
                        .clickable { onSelect(stop) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = LensZoomPlan.focalLabel(focalMm) ?: LensZoomPlan.stopLabel(stop),
                        color = if (active) White else DimWhite,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** Video-mode chips: stabilization indication/toggle and microphone (M4). */
@Composable
private fun VideoChips(
    stabilizationEnabled: Boolean,
    stabilization: StabilizationLevel,
    opticalStabilizationEnabled: Boolean,
    opticalStabilizationSupported: Boolean,
    videoNoiseReductionEnabled: Boolean,
    videoNoiseReductionSupported: Boolean,
    micEnabled: Boolean,
    frameRate: VideoFrameRate,
    supportedFrameRates: List<VideoFrameRate>,
    videoSnapshotSupported: Boolean,
    recording: Boolean,
    onStabToggle: () -> Unit,
    onOisToggle: () -> Unit,
    onNoiseReductionToggle: () -> Unit,
    onActionToggle: () -> Unit,
    onMicToggle: () -> Unit,
    onSnapshot: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleChip(
            label = if (stabilization == StabilizationLevel.NONE) "STAB N/A" else "STAB",
            active = stabilizationEnabled && stabilization != StabilizationLevel.NONE,
            enabled = !recording && stabilization != StabilizationLevel.NONE,
            onClick = onStabToggle,
        )
        ToggleChip(
            label = "MIC",
            active = micEnabled,
            enabled = !recording,
            onClick = onMicToggle,
        )
        ToggleChip(
            label = "OIS",
            active = opticalStabilizationEnabled,
            enabled = !recording && opticalStabilizationSupported,
            onClick = onOisToggle,
        )
        ToggleChip(
            label = "NR",
            active = videoNoiseReductionEnabled,
            enabled = !recording && videoNoiseReductionSupported,
            onClick = onNoiseReductionToggle,
        )
        ToggleChip(
            label = "ACTION",
            active = frameRate == VideoFrameRate.FPS_60 && stabilizationEnabled,
            enabled = !recording && VideoFrameRate.FPS_60 in supportedFrameRates,
            onClick = onActionToggle,
        )
        ToggleChip(
            label = "SNAP",
            active = false,
            enabled = recording && videoSnapshotSupported,
            onClick = onSnapshot,
        )
    }
}

/** Expert mode (M5/M6): manual ISO/speed/focus gauges plus the RAW toggle. */
@Composable
private fun ExpertControls(
    isoGauge: Float,
    speedGauge: Float,
    focusGauge: Float,
    isoRange: IntRange?,
    exposureRange: LongRange?,
    minFocusDistance: Float?,
    rawEnabled: Boolean,
    rawSupported: Boolean,
    histogramEnabled: Boolean,
    zebrasEnabled: Boolean,
    peakingEnabled: Boolean,
    whiteBalance: WhiteBalancePreset,
    whiteBalanceKelvin: Int,
    onIsoChange: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFocusChange: (Float) -> Unit,
    onWhiteBalanceChange: (WhiteBalancePreset) -> Unit,
    onWhiteBalanceKelvinChange: (Int) -> Unit,
    onCommit: () -> Unit,
    onRawToggle: () -> Unit,
    onHistogramToggle: () -> Unit,
    onZebrasToggle: () -> Unit,
    onPeakingToggle: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MiniSlider(
                label = "ISO",
                value = isoGauge,
                valueText = isoText(isoGauge, isoRange),
                enabled = isoRange != null,
                onValueChange = onIsoChange,
                onCommit = onCommit,
            )
            MiniSlider(
                label = "SPEED",
                value = speedGauge,
                valueText = speedText(speedGauge, exposureRange),
                enabled = exposureRange != null,
                onValueChange = onSpeedChange,
                onCommit = onCommit,
            )
            MiniSlider(
                label = "FOCUS",
                value = focusGauge,
                valueText = focusText(focusGauge, minFocusDistance),
                enabled = minFocusDistance != null,
                onValueChange = onFocusChange,
                onCommit = onCommit,
            )
        }
        Row(
            modifier = Modifier
                .width(320.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            WhiteBalancePreset.entries.forEach { preset ->
                ToggleChip(
                    label = preset.label,
                    active = whiteBalance == preset,
                    enabled = true,
                    onClick = {
                        onWhiteBalanceChange(preset)
                        onCommit()
                    },
                )
            }
        }
        if (whiteBalance == WhiteBalancePreset.CUSTOM) {
            Row(
                modifier = Modifier.width(320.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${whiteBalanceKelvin}K",
                    color = White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = whiteBalanceKelvin.toFloat(),
                    onValueChange = {
                        onWhiteBalanceKelvinChange(it.toInt())
                        onWhiteBalanceChange(WhiteBalancePreset.CUSTOM)
                    },
                    onValueChangeFinished = onCommit,
                    valueRange = 2_000f..10_000f,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ToggleChip(
            label = if (rawSupported) "RAW DNG" else "RAW N/A",
            active = rawEnabled,
            enabled = rawSupported,
            onClick = onRawToggle,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .width(320.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ToggleChip(
                label = "HISTOGRAM",
                active = histogramEnabled,
                enabled = true,
                onClick = onHistogramToggle,
            )
            ToggleChip(
                label = "ZEBRAS",
                active = zebrasEnabled,
                enabled = true,
                onClick = onZebrasToggle,
            )
            ToggleChip(
                label = "PEAKING",
                active = peakingEnabled,
                enabled = true,
                onClick = onPeakingToggle,
            )
        }
    }
}

@Composable
private fun MiniSlider(
    label: String,
    value: Float,
    valueText: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(104.dp),
    ) {
        Text(
            text = label,
            color = DimWhite,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onCommit,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
        )
        Text(
            text = valueText,
            color = White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ToggleChip(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> Color(0x14FFFFFF)
        active -> Color(0xE6FFFFFF)
        else -> Color(0x33000000)
    }
    val textColor = when {
        !enabled -> White
        active -> Black
        else -> White
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ModeSelector(
    selected: CameraMode,
    macroSupported: Boolean,
    onSelect: (CameraMode) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        CameraMode.entries.forEach { mode ->
            val active = mode == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(enabled = mode != CameraMode.MACRO || macroSupported) {
                        onSelect(mode)
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    text = mode.label,
                    color = if (active) White else DimWhite,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(if (active) White else Color.Transparent, CircleShape),
                )
            }
        }
    }
}

/** Picker shared by the high-speed fps and time-lapse speed ladders. */
@Composable
private fun RateSelector(
    label: String,
    choices: List<Int>,
    selected: Int,
    format: (Int) -> String,
    unavailableLabel: String,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = DimWhite,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        if (choices.isEmpty()) {
            Text(
                text = unavailableLabel,
                color = White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Row(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                choices.forEach { choice ->
                    ToggleChip(
                        label = format(choice),
                        active = selected == choice,
                        enabled = enabled,
                        onClick = { onSelect(choice) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(
    capturing: Boolean,
    enabled: Boolean,
    pulse: Int,
    onClick: () -> Unit,
    onBurstStart: () -> Unit,
    onBurstStop: () -> Unit,
) {
    val color = if (enabled) White else FaintWhite
    val scale = if (pulse % 2 == 0) 1f else 0.92f
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentBurstStart by rememberUpdatedState(onBurstStart)
    val currentBurstStop by rememberUpdatedState(onBurstStop)
    Box(
        modifier = Modifier
            .size(78.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(Unit) {
                // Tap captures; holding past the long-press timeout runs the
                // continuous shot until the finger lifts. Detection stays
                // live for the whole gesture (the button's enabled state can
                // change mid-burst without cancelling the release).
                detectTapGestures(
                    onTap = { if (currentEnabled) currentOnClick() },
                    onLongPress = { currentBurstStart() },
                    onPress = {
                        tryAwaitRelease()
                        currentBurstStop()
                    },
                )
            }
            .semantics {
                semanticsOnClick(label = "Shutter") {
                    if (currentEnabled) currentOnClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 3.dp.toPx()
            val ringRadius = size.minDimension / 2f - strokeWidth / 2f
            drawCircle(color = color, radius = ringRadius, style = Stroke(width = strokeWidth))
            val dotRadius = if (capturing) size.minDimension * 0.28f else size.minDimension * 0.40f
            drawCircle(color = color, radius = dotRadius)
        }
    }
}

@Composable
private fun RecordButton(
    recording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (enabled) White else FaintWhite
    Box(
        modifier = Modifier
            .size(78.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 3.dp.toPx()
            val ringRadius = size.minDimension / 2f - strokeWidth / 2f
            drawCircle(color = color, radius = ringRadius, style = Stroke(width = strokeWidth))
            if (recording) {
                val side = size.minDimension * 0.34f
                drawRoundRect(
                    color = color,
                    topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side * 0.22f),
                )
            } else {
                drawCircle(color = color, radius = size.minDimension * 0.40f)
            }
        }
    }
}

@Composable
private fun IconCircle(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0x1AFFFFFF))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = White,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun isoText(gauge: Float, range: IntRange?): String {
    if (range == null) return "N/A"
    val value = ManualControlPlan.isoFor(gauge, range)
    return if (value == null) "AUTO" else "ISO $value"
}

private fun speedText(gauge: Float, range: LongRange?): String {
    if (range == null) return "N/A"
    val exposure = ManualControlPlan.exposureFor(gauge, range) ?: return "AUTO"
    val denominator = (1_000_000_000L / exposure).coerceAtLeast(1L)
    return "1/$denominator"
}

private fun focusText(gauge: Float, minimumFocusDistance: Float?): String {
    if (minimumFocusDistance == null) return "N/A"
    val diopters = ManualControlPlan.focusFor(gauge, minimumFocusDistance) ?: return "AUTO"
    val meters = if (diopters > 0f) 1f / diopters else Float.POSITIVE_INFINITY
    return String.format(Locale.US, "%.2fm", meters)
}

/** Requests one current fix without starting continuous background tracking. */
private fun requestCurrentLocation(context: android.content.Context, onResult: (Location?) -> Unit) {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) {
        onResult(null)
        return
    }
    val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
    val provider = listOfNotNull(
        if (fine) LocationManager.GPS_PROVIDER else null,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    ).firstOrNull { name -> runCatching { manager.isProviderEnabled(name) }.getOrDefault(false) }
    if (provider == null) {
        onResult(lastKnownLocation(manager))
        return
    }
    runCatching {
        manager.getCurrentLocation(
            provider,
            null,
            ContextCompat.getMainExecutor(context),
            Consumer { location -> onResult(location ?: lastKnownLocation(manager)) },
        )
    }.onFailure { onResult(lastKnownLocation(manager)) }
}

private fun openQrResult(
    context: android.content.Context,
    value: String,
    onStatus: (String) -> Unit = {},
) {
    val content = value.trim()
    val parsed = runCatching { Uri.parse(content) }.getOrNull()
    val uri = when {
        parsed?.scheme in setOf("https", "http", "mailto", "tel") -> parsed
        parsed?.scheme.isNullOrBlank() && WEB_ADDRESS.matches(content) -> Uri.parse("https://$content")
        else -> null
    }
    if (uri != null) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (opened) {
            onStatus("Opening link")
            return
        }
        copyQrContent(context, content)
        onStatus("Could not open link · copied to clipboard")
        Toast.makeText(context, "Could not open link · copied", Toast.LENGTH_SHORT).show()
        return
    }
    copyQrContent(context, content)
    onStatus("QR content copied")
    Toast.makeText(context, "QR content copied", Toast.LENGTH_SHORT).show()
}

private fun copyQrContent(context: android.content.Context, value: String) {
    runCatching {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("QR code", value))
    }
}

private val WEB_ADDRESS = Regex(
    "^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,}(?::[0-9]+)?(?:/[^\\s]*)?$",
)

private fun findActivity(context: android.content.Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}

private fun lastKnownLocation(manager: LocationManager): Location? =
    runCatching {
        manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()

/** Layout constants for the focus ring and the brightness slider. */
private val RING_SIZE_HALF = 27.dp
private val SLIDER_HEIGHT_HALF = 95.dp
private val SLIDER_SIDE_GAP = 64.dp

/** How many captures may be writing at once before the shutter stops arming. */
private const val MAX_IN_FLIGHT = 4
private const val MODE_CAPTURE_MAX_DIMENSION = 1600
private const val NIGHT_FRAME_COUNT = 4
private const val NIGHT_FRAME_INTERVAL_MS = 120L
private const val PORTRAIT_BLUR_RADIUS = 18

/** Stock time-lapse speed choices, expressed as multiples of normal playback. */
private val TIME_LAPSE_SPEEDS = listOf(15, 30, 60, 120, 240, 480, 960)
private const val CAMERA2_PREVIEW_TAG = "Camera2Preview"
