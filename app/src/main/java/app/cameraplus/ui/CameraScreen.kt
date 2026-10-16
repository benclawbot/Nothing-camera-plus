package app.cameraplus.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cameraplus.camera.CameraSession
import app.cameraplus.settings.ProcessingSettingsStore
import app.cameraplus.ui.theme.Black
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.FaintWhite
import app.cameraplus.ui.theme.White
import kotlinx.coroutines.delay

/** Modes shown in the selector. Only PHOTO is wired to a pipeline for now. */
enum class CameraMode(val label: String) {
    PHOTO("PHOTO"),
    VIDEO("VIDEO"),
    PORTRAIT("PORTRAIT"),
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

    var mode by remember { mutableStateOf(CameraMode.PHOTO) }
    var flashEnabled by remember { mutableStateOf(false) }
    var inFlight by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        session.bind(
            previewView = previewView,
            onZslChanged = { active ->
                status = if (active) "Instant shutter ready" else null
            },
            onError = { error ->
                status = error.message ?: "Camera unavailable"
            },
        )
        onDispose { session.unbind() }
    }

    LaunchedEffect(status) {
        if (status != null) {
            delay(2400)
            status = null
        }
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
                    val next = !flashEnabled
                    flashEnabled = next
                    session.setFlashEnabled(next) { error ->
                        status = error.message ?: "Could not toggle flash"
                    }
                },
            ) {
                Icon(
                    imageVector = if (flashEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    contentDescription = if (flashEnabled) "Turn flash off" else "Turn flash on",
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

        status?.let { message ->
            Text(
                text = message,
                color = White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 216.dp)
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
            ModeSelector(selected = mode, onSelect = { mode = it })
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
                    onClick = { status = "Gallery view arrives in a later milestone" },
                )
                ShutterButton(
                    capturing = inFlight > 0,
                    enabled = mode == CameraMode.PHOTO,
                    onClick = {
                        if (mode == CameraMode.PHOTO) {
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
                        }
                    },
                )
                IconCircle(
                    imageVector = Icons.Outlined.Cameraswitch,
                    contentDescription = "Switch camera",
                    onClick = {
                        session.flipLens { error ->
                            status = error.message ?: "Could not switch camera"
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
