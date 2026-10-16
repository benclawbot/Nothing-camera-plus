package app.cameraplus.ui

import android.Manifest
import android.content.pm.PackageManager
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
import app.cameraplus.ui.theme.Black
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.White

/**
 * Entry point: gates the camera screen behind the camera permission.
 * Asks once automatically; afterwards the user can retry from this screen.
 */
@Composable
fun CameraPlusApp() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    if (granted) {
        CameraScreen()
    } else {
        PermissionScreen(onGrant = { requestPermission.launch(Manifest.permission.CAMERA) })
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
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
            text = "Camera access is required to open the viewfinder and take photos.",
            color = DimWhite,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onGrant) {
            Text(
                text = "GRANT ACCESS",
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
        }
    }
}
