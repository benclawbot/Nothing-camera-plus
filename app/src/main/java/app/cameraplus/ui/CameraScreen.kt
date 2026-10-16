package app.cameraplus.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cameraplus.camera.LensZoomPlan
import app.cameraplus.camera.ManualControlPlan
import app.cameraplus.camera.ManualControls
import app.cameraplus.camera.CameraSession
import app.cameraplus.camera.Camera2Recorder
import app.cameraplus.camera.CaptureTimer
import app.cameraplus.camera.NightMerger
import app.cameraplus.camera.Direction
import app.cameraplus.camera.PanoramaCapture
import app.cameraplus.camera.PortraitRenderer
import app.cameraplus.camera.SelfTimer
import app.cameraplus.camera.VideoQuality
import app.cameraplus.camera.SessionCapabilities
import app.cameraplus.camera.SessionMode
import app.cameraplus.camera.StabilizationLevel
import app.cameraplus.camera.StabilizationProfile
import app.cameraplus.settings.ProcessingSettingsStore
import app.cameraplus.processing.ImageProcessor
import app.cameraplus.ui.theme.Black
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.FaintWhite
import app.cameraplus.ui.theme.White
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Modes shown in the selector. Null session modes use a separate pipeline. */
enum class CameraMode(val label: String, val session: SessionMode?) {
    PHOTO("PHOTO", SessionMode.PHOTO),
    PORTRAIT("PORTRAIT", SessionMode.PHOTO),
    NIGHT("NIGHT", SessionMode.PHOTO),
    PANORAMA("PANORAMA", SessionMode.PHOTO),
    VIDEO("VIDEO", SessionMode.VIDEO),
    SLOW_MOTION("SLO-MO", null),
    TIME_LAPSE("TIME-LAPSE", null),
    EXPERT("EXPERT", SessionMode.EXPERT);

    val usesCamera2Recorder: Boolean
        get() = this == SLOW_MOTION || this == TIME_LAPSE
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = remember { CameraSession(context, lifecycleOwner) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val camera2TextureView = remember(context) { TextureView(context) }
    val coroutineScope = rememberCoroutineScope()

    val store = remember(context) { ProcessingSettingsStore(context) }
    var processing by remember { mutableStateOf(store.load()) }
    var showProcessing by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf(CompositionGuides.NONE) }
    val roll = rememberSensorRoll()
    var focusTarget by remember { mutableStateOf<Offset?>(null) }
    var focusLocked by remember { mutableStateOf(false) }

    // A metering ring that is not locked goes away on its own, like the
    // stock timer; a locked one stays until the next tap or mode switch.
    LaunchedEffect(focusTarget, focusLocked) {
        if (focusTarget != null && !focusLocked) {
            delay(6000)
            focusTarget = null
        }
    }

    var mode by remember { mutableStateOf(CameraMode.PHOTO) }
    var flashEnabled by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var inFlight by remember { mutableIntStateOf(0) }
    var shutterPulse by remember { mutableIntStateOf(0) }
    var processingCapture by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var bokehLevel by remember { mutableFloatStateOf(0.45f) }
    val panoramaCapture = remember { PanoramaCapture() }
    var panoramaProgress by remember { mutableStateOf(panoramaCapture.progress()) }
    var panoramaDirection by remember { mutableStateOf(Direction.RIGHT) }

    var capabilities by remember { mutableStateOf(SessionCapabilities()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pinching by remember { mutableStateOf(false) }
    var evIndex by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var specialStarting by remember { mutableStateOf(false) }
    var recordStartedAt by remember { mutableStateOf(0L) }
    var recordSeconds by remember { mutableStateOf(0) }
    var slowMotionRates by remember { mutableStateOf<List<Int>>(emptyList()) }
    var slowMotionFps by remember { mutableIntStateOf(120) }
    var timeLapseSpeed by remember { mutableIntStateOf(60) }
    var micEnabled by remember { mutableStateOf(true) }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var stabilizationEnabled by remember { mutableStateOf(true) }
    var videoQuality by remember { mutableStateOf(VideoQuality.FHD) }
    var selfTimer by remember { mutableStateOf(SelfTimer.OFF) }
    var timerDeadline by remember { mutableStateOf(0L) }
    var timerLeft by remember { mutableIntStateOf(0) }
    var pendingShot by remember { mutableStateOf(false) }
    var rawEnabled by remember { mutableStateOf(false) }
    var isoGauge by remember { mutableStateOf(0f) }
    var speedGauge by remember { mutableStateOf(0f) }
    var focusGauge by remember { mutableStateOf(0f) }

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
                status = if (uri != null) "Video saved" else "Recording did not produce a usable clip"
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

    fun bindCameraX() {
        session.bind(
            previewView = previewView,
            onZslChanged = { active ->
                status = if (active) "Instant shutter ready" else null
            },
            onCapabilities = { published ->
                capabilities = published
                if (!published.rawSupported) rawEnabled = false
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

    DisposableEffect(lifecycleOwner) {
        bindCameraX()
        onDispose {
            session.unbind()
            Thread { camera2Recorder.close() }.start()
        }
    }

    // Keep the viewfinder look in step with the processing intent (M3.3).
    LaunchedEffect(processing) {
        session.setLook(processing) { error ->
            status = error.message ?: "Could not apply the preview look"
        }
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

    var previousMode by remember { mutableStateOf(CameraMode.PHOTO) }
    LaunchedEffect(mode) {
        if (previousMode == CameraMode.PANORAMA && mode != CameraMode.PANORAMA) {
            panoramaCapture.reset()
            panoramaProgress = panoramaCapture.progress()
        }
        if (mode.usesCamera2Recorder) {
            // Camera2 and CameraX cannot own the camera at the same time.
            session.unbind()
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
            mode.session?.let { session.setMode(it, bindAfterChange = false) }
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
            onSaved = {
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
            onSaved = { finishModeCapture("$note · saved ${width}×$height") },
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
                var bokehApplied = true
                val rendered = try {
                    withContext(Dispatchers.Default) {
                        PortraitRenderer.blurBackground(
                            pixels = frame.pixels,
                            width = frame.width,
                            height = frame.height,
                            shape = PortraitRenderer.defaultShape(frame.width, frame.height, bokehLevel),
                            maxBlurRadius = PORTRAIT_BLUR_RADIUS,
                        ).also { ImageProcessor.apply(it, frame.width, frame.height, processing) }
                    }
                } catch (_: Throwable) {
                    bokehApplied = false
                    frame.pixels
                }
                saveModeCapture(
                    rendered,
                    frame.width,
                    frame.height,
                    "Portrait",
                    if (bokehApplied) "Simulated bokeh · no depth data" else "Bokeh skipped · original frame saved",
                )
            } catch (error: Throwable) {
                finishModeCapture(error.message ?: "Portrait capture failed")
            }
        }
    }

    fun capturePanoramaFrame() {
        if (processingCapture) return
        processingCapture = true
        inFlight++
        session.capturePixelFrame(
            maxDimension = PanoramaCapture.DEFAULT_MAX_DIMENSION,
            onFrame = { frame ->
                val previousCount = panoramaProgress.acceptedFrames
                val updated = panoramaCapture.addFrame(frame.pixels, frame.width, frame.height)
                panoramaProgress = updated
                finishModeCapture(
                    if (updated.acceptedFrames > previousCount) {
                        "Panorama frame ${updated.acceptedFrames}/${updated.maxFrames}"
                    } else {
                        "Panorama frame rejected · move more slowly"
                    },
                )
            },
            onError = { error -> finishModeCapture(error.message ?: "Panorama frame failed") },
        )
    }

    fun finishPanorama() {
        if (processingCapture) return
        processingCapture = true
        inFlight++
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    panoramaCapture.finish()?.also {
                        runCatching { ImageProcessor.apply(it.pixels, it.width, it.height, processing) }
                    }
                }
                panoramaProgress = panoramaCapture.progress()
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

    fun captureCurrentMode() {
        when (mode) {
            CameraMode.NIGHT -> captureNight()
            CameraMode.PORTRAIT -> capturePortrait()
            CameraMode.PANORAMA -> capturePanoramaFrame()
            else -> captureNow()
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
                }
            } catch (error: Exception) {
                status = error.message ?: "Could not start recording"
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
        if (mode.usesCamera2Recorder) {
            AndroidView(
                factory = { camera2TextureView },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
        }

        GuideOverlay(
            guides = guides,
            modifier = Modifier.fillMaxSize(),
        )
        LevelLine(
            rollDegrees = roll,
            modifier = Modifier.fillMaxSize(),
        )

        // M4: show what stabilization takes away while in video mode.
        if (mode == CameraMode.VIDEO && stabilizationEnabled) {
            val crop = StabilizationProfile.estimatedCrop(capabilities.stabilization)
            StabilizationCropOverlay(
                cropFraction = crop,
                label = "STABILIZED −${(crop * 100f).roundToInt()}%",
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!mode.usesCamera2Recorder) Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            focusTarget = offset
                            focusLocked = false
                            evIndex = session.currentExposureCompensationIndex()
                            session.focusAndMeterAt(offset.x, offset.y)
                        },
                        onLongPress = { offset ->
                            focusTarget = offset
                            focusLocked = true
                            session.focusAndMeterAt(offset.x, offset.y, lock = true)
                        },
                    )
                }
                .pointerInput(capabilities.maxDisplayedZoom) {
                    detectTransformGestures { _, _, gestureZoom, _ ->
                        // Gesture zoom is incremental, so this accumulates.
                        zoom = session.setDisplayedZoom(zoom * gestureZoom) { error ->
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
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = !mode.usesCamera2Recorder &&
                    mode != CameraMode.NIGHT && mode != CameraMode.PANORAMA,
                onClick = {
                    if (mode == CameraMode.VIDEO) {
                        val next = !torchEnabled
                        torchEnabled = next
                        session.setTorchEnabled(next)
                    } else {
                        val next = !flashEnabled
                        flashEnabled = next
                        session.setFlashEnabled(next) { error ->
                            status = error.message ?: "Could not toggle flash"
                        }
                    }
                },
            ) {
                val lightOn = if (mode == CameraMode.VIDEO) torchEnabled else flashEnabled
                Icon(
                    imageVector = if (lightOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    contentDescription = if (lightOn) "Turn light off" else "Turn light on",
                    tint = White,
                )
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
                IconButton(onClick = { showProcessing = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Processing settings",
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
                    .statusBarsPadding()
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
                    current = zoom,
                    zoomable = capabilities.maxDisplayedZoom > capabilities.minDisplayedZoom,
                    onSelect = { stop ->
                        zoom = session.setDisplayedZoom(stop) { error ->
                            status = error.message ?: "Could not switch lens"
                        }
                        pinching = false
                    },
                )
                if (capabilities.zoomStops.size > 1) Spacer(Modifier.height(14.dp))
            }

            when (mode) {
                CameraMode.VIDEO -> {
                    VideoChips(
                        stabilizationEnabled = stabilizationEnabled,
                        stabilization = capabilities.stabilization,
                        micEnabled = micEnabled,
                        quality = videoQuality,
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
                        onQualityClick = {
                            val next = videoQuality.next()
                            videoQuality = session.setVideoQuality(next) { error ->
                                status = error.message ?: "Could not change resolution"
                            }
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
                        onIsoChange = { isoGauge = it },
                        onSpeedChange = { speedGauge = it },
                        onFocusChange = { focusGauge = it },
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
                            text = "SIMULATED BOKEH · NO DEPTH DATA",
                            color = DimWhite,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Slider(
                            value = bokehLevel,
                            onValueChange = { bokehLevel = it },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.NIGHT -> {
                    Text(
                        text = "4-FRAME SOFTWARE MERGE · DOWNSAMPLED",
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
                            enabled = !processingCapture,
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
                        if (panoramaProgress.acceptedFrames >= 2) {
                            ToggleChip(
                                label = "FINISH",
                                active = false,
                                enabled = !processingCapture,
                                onClick = { finishPanorama() },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                CameraMode.SLOW_MOTION -> {
                    RateSelector(
                        label = "CAPTURE FPS",
                        choices = slowMotionRates,
                        selected = slowMotionFps,
                        format = { "${it}FPS" },
                        unavailableLabel = "SLOW MOTION NOT SUPPORTED",
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
                        onSelect = { timeLapseSpeed = it },
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }

            ModeSelector(
                selected = mode,
                onSelect = { selected ->
                    if (recording || specialStarting || processingCapture) {
                        status = "Stop recording before switching modes"
                    } else if (selected != mode) {
                        val previous = mode
                        mode = selected
                        if (!previous.usesCamera2Recorder && !selected.usesCamera2Recorder) {
                            selected.session?.let { sessionMode ->
                                session.setMode(
                                    newMode = sessionMode,
                                    onError = { error ->
                                        status = error.message ?: "Could not switch mode"
                                    },
                                )
                            }
                        }
                        if (selected != CameraMode.VIDEO) torchEnabled = false
                    }
                },
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
                    onClick = { showGallery = true },
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
                        enabled = !specialStarting || recording,
                        onClick = { toggleCamera2Recording() },
                    )
                } else {
                    ShutterButton(
                        capturing = inFlight > 0,
                        enabled = inFlight < MAX_IN_FLIGHT && !processingCapture,
                        pulse = shutterPulse,
                        onClick = {
                            // Feedback is immediate; the file write happens in
                            // the background and the shutter stays armed, so
                            // burst tapping feels instant like the stock app.
                            shutterPulse++
                            if (mode == CameraMode.PHOTO && selfTimer.enabled) {
                                if (timerDeadline == 0L) {
                                    inFlight++
                                    pendingShot = true
                                    timerDeadline = System.currentTimeMillis() +
                                        selfTimer.seconds * 1000L
                                }
                            } else if (mode == CameraMode.PHOTO || mode == CameraMode.EXPERT) {
                                inFlight++
                                captureNow()
                            } else {
                                captureCurrentMode()
                            }
                        },
                    )
                }
                IconCircle(
                    imageVector = Icons.Outlined.Cameraswitch,
                    contentDescription = "Switch camera",
                    enabled = !mode.usesCamera2Recorder &&
                        !(mode == CameraMode.PANORAMA && panoramaProgress.acceptedFrames > 0),
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
                onDismiss = { showProcessing = false },
            )
        }

        if (showGallery) {
            GalleryScreen(onDismiss = { showGallery = false })
        }
    }
}

/**
 * Lens stops plus the live zoom readout (v1.1).
 *
 * The stops are the physical lenses the plan found (0.6x, 1x, 2x, …) with a
 * short digital ladder; between them the value follows the pinch gesture, so
 * the number above the pill is always the zoom the camera is really at.
 * Hidden only when the camera offers no zoom range at all.
 */
@Composable
private fun ZoomPill(
    stops: List<Float>,
    current: Float,
    zoomable: Boolean,
    onSelect: (Float) -> Unit,
) {
    if (stops.isEmpty()) return
    val currentLabel = LensZoomPlan.stopLabel(current)
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
        if (stops.size < 2 && !zoomable) return@Column
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0x33000000))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stops.forEach { stop ->
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
                        text = LensZoomPlan.stopLabel(stop),
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
    micEnabled: Boolean,
    quality: VideoQuality,
    onStabToggle: () -> Unit,
    onMicToggle: () -> Unit,
    onQualityClick: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleChip(
            label = if (stabilization == StabilizationLevel.NONE) "STAB N/A" else "STAB",
            active = stabilizationEnabled && stabilization != StabilizationLevel.NONE,
            enabled = stabilization != StabilizationLevel.NONE,
            onClick = onStabToggle,
        )
        ToggleChip(
            label = "MIC",
            active = micEnabled,
            enabled = true,
            onClick = onMicToggle,
        )
        ToggleChip(
            label = quality.label,
            active = false,
            enabled = true,
            onClick = onQualityClick,
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
    onIsoChange: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFocusChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onRawToggle: () -> Unit,
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
        Spacer(Modifier.height(6.dp))
        ToggleChip(
            label = if (rawSupported) "RAW DNG" else "RAW N/A",
            active = rawEnabled,
            enabled = rawSupported,
            onClick = onRawToggle,
        )
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
        !enabled -> FaintWhite
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
                    .clickable { onSelect(mode) }
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
                color = FaintWhite,
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
                        enabled = true,
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
) {
    val color = if (enabled) White else FaintWhite
    val scale = if (pulse % 2 == 0) 1f else 0.92f
    Box(
        modifier = Modifier
            .size(78.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(enabled = enabled, onClick = onClick),
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
