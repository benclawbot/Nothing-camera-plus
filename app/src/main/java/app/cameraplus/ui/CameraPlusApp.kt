package app.cameraplus.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cameraplus.ui.theme.Black
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.White

/**
 * Entry point: gates the camera screen behind the camera permission.
 * Asks once automatically; afterwards the user can retry from this screen.
 */
@Composable
fun CameraPlusApp(
    shortcut: ModeShortcut.Selection? = null,
    onShortcutConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        permanentlyDenied = !result && activity?.let {
            !it.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        } == true
    }

    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                granted = nowGranted
                if (nowGranted) permanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    if (granted) {
        CameraScreen(shortcut = shortcut, onShortcutConsumed = onShortcutConsumed)
    } else {
        PermissionScreen(
            permanentlyDenied = permanentlyDenied,
            onGrant = {
                if (permanentlyDenied && activity != null) {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        },
                    )
                } else {
                    requestPermission.launch(Manifest.permission.CAMERA)
                }
            },
        )
    }
}

@Composable
private fun PermissionScreen(permanentlyDenied: Boolean, onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "CAMERA+",
            color = White,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 6.sp,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (permanentlyDenied) {
                "Camera access is turned off for Camera+. Open App Settings and allow Camera to use the viewfinder."
            } else {
                "Camera access is required to show the viewfinder and take photos. You can grant it just for this app."
            },
            color = DimWhite,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onGrant) {
            Text(
                text = if (permanentlyDenied) "OPEN APP SETTINGS" else "GRANT CAMERA ACCESS",
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
