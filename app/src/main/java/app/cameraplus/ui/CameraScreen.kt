package app.cameraplus.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cameraplus.camera.LensZoomPlan
import app.cameraplus.camera.ManualControlPlan
import app.cameraplus.camera.ManualControls
import app.cameraplus.camera.CameraSession
import app.cameraplus.camera.SessionCapabilities
import app.cameraplus.camera.SessionMode
import app.cameraplus.camera.StabilizationLevel
import app.cameraplus.camera.StabilizationProfile
import app.cameraplus.settings.ProcessingSettingsStore
import app.cameraplus.ui.theme.Black
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.FaintWhite
import app.cameraplus.ui.theme.White
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Modes shown in the selector. Each maps onto a session shape. */
enum class CameraMode(val label: String, val session: SessionMode) {
    PHOTO("PHOTO", SessionMode.PHOTO),
    VIDEO("VIDEO", SessionMode.VIDEO),
    EXPERT("EXPERT", SessionMode.EXPERT),
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

    val store = remember(context) { ProcessingSettingsStore(context) }
    var processing by remember { mutableStateOf(store.load()) }
    var showProcessing by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf(CompositionGuides.NONE) }
    val roll = rememberSensorRoll()
    var focusRipple by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(focusRipple) {
        if (focusRipple != null) {
            delay(600)
            focusRipple = null
        }
    }

    var mode by remember { mutableStateOf(CameraMode.PHOTO) }
    var flashEnabled by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var inFlight by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    var capabilities by remember { mutableStateOf(SessionCapabilities()) }
    var zoom by remember { mutableStateOf(1f) }
    var recording by remember { mutableStateOf(false) }
    var recordStartedAt by remember { mutableStateOf(0L) }
    var recordSeconds by remember { mutableStateOf(0) }
    var micEnabled by remember { mutableStateOf(true) }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var stabilizationEnabled by remember { mutableStateOf(true) }
    var rawEnabled by remember { mutableStateOf(false) }
    var isoGauge by remember { mutableStateOf(0f) }
    var speedGauge by remember { mutableStateOf(0f) }
    var focusGauge by remember { mutableStateOf(0f) }

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

    DisposableEffect(lifecycleOwner) {
        session.bind(
            previewView = previewView,
            onZslChanged = { active ->
                status = if (active) "Instant shutter ready" else null
            },
            onCapabilities = { published ->
                capabilities = published
                if (published.zoomStops.none { abs(it - zoom) < 0.01f }) zoom = 1f
            },
            onError = { error ->
                status = error.message ?: "Camera unavailable"
            },
        )
        onDispose { session.unbind() }
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

    LaunchedEffect(recording) {
        while (recording) {
            recordSeconds = ((System.currentTimeMillis() - recordStartedAt) / 1000L)
                .toInt()
                .coerceAtLeast(0)
            delay(500)
        }
        if (!recording) recordSeconds = 0
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusRipple = offset
                        session.focusAndMeterAt(offset.x, offset.y)
                    }
                },
        ) {
            focusRipple?.let { point ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = White,
                        radius = 26.dp.toPx(),
                        center = point,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
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
            ZoomPill(
                stops = capabilities.zoomStops,
                selected = zoom,
                onSelect = { stop ->
                    zoom = stop
                    session.setZoomRatio(stop)
                },
            )
            if (capabilities.zoomStops.size > 1) Spacer(Modifier.height(14.dp))

            when (mode) {
                CameraMode.VIDEO -> {
                    VideoChips(
                        stabilizationEnabled = stabilizationEnabled,
                        stabilization = capabilities.stabilization,
                        micEnabled = micEnabled,
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
            }

            ModeSelector(
                selected = mode,
                onSelect = { selected ->
                    if (recording) {
                        status = "Stop recording before switching modes"
                    } else if (selected != mode) {
                        mode = selected
                        session.setMode(selected.session) { error ->
                            status = error.message ?: "Could not switch mode"
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
                    onClick = { status = "Gallery view is not part of this build" },
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
                } else {
                    ShutterButton(
                        capturing = inFlight > 0,
                        enabled = true,
                        onClick = {
                            // Captures queue: the shutter stays armed while
                            // previous frames are still being written.
                            inFlight++
                            session.capture(
                                settings = processing,
                                onSaved = {
                                    inFlight--
                                    status = "Saved"
                                },
                                onError = { error ->
                                    inFlight--
                                    status = error.message ?: "Capture failed"
                                },
                            )
                        },
                    )
                }
                IconCircle(
                    imageVector = Icons.Outlined.Cameraswitch,
                    contentDescription = "Switch camera",
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
    }
}

/** Lens/zoom stops derived from the physical focals (M4). Hidden on single-lens. */
@Composable
private fun ZoomPill(
    stops: List<Float>,
    selected: Float,
    onSelect: (Float) -> Unit,
) {
    if (stops.size < 2) return
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x33000000))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stops.forEach { stop ->
            val active = abs(stop - selected) < 0.01f
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

/** Video-mode chips: stabilization indication/toggle and microphone (M4). */
@Composable
private fun VideoChips(
    stabilizationEnabled: Boolean,
    stabilization: StabilizationLevel,
    micEnabled: Boolean,
    onStabToggle: () -> Unit,
    onMicToggle: () -> Unit,
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
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
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

@Composable
private fun ShutterButton(
    capturing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (enabled) White else FaintWhite
    Box(
        modifier = Modifier
            .size(78.dp)
            .clickable(enabled = enabled && !capturing, onClick = onClick),
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
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0x1AFFFFFF))
            .clickable(onClick = onClick),
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
