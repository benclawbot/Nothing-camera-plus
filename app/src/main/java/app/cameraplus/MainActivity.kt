package app.cameraplus

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.cameraplus.ui.CameraPlusApp
import app.cameraplus.ui.CameraKeyEvents
import app.cameraplus.ui.theme.CameraPlusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraPlusTheme {
                CameraPlusApp()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shutterOnVolume = getSharedPreferences("capture_preferences", MODE_PRIVATE)
            .getBoolean("volume_shutter", false)
        if (shutterOnVolume && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            CameraKeyEvents.emitVolumeShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
