package app.cameraplus.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cameraplus.camera.LevelMath

/** Composition guides, cycled from the top bar. Off by default. */
enum class CompositionGuides {
    NONE,
    THIRDS,
    CENTER,
    DIAGONALS;

    fun next(): CompositionGuides = entries[(ordinal + 1) % entries.size]
}

/** Draws the selected composition guide over the viewfinder. */
@Composable
fun GuideOverlay(guides: CompositionGuides, modifier: Modifier = Modifier) {
    if (guides == CompositionGuides.NONE) return
    Canvas(modifier = modifier) {
        val stroke = 1.dp.toPx()
        val lineColor = Color(0x33FFFFFF)
        when (guides) {
            CompositionGuides.THIRDS -> {
                val thirdX = size.width / 3f
                val twoThirdX = size.width * 2f / 3f
                val thirdY = size.height / 3f
                val twoThirdY = size.height * 2f / 3f
                drawLine(lineColor, Offset(thirdX, 0f), Offset(thirdX, size.height), stroke)
                drawLine(lineColor, Offset(twoThirdX, 0f), Offset(twoThirdX, size.height), stroke)
                drawLine(lineColor, Offset(0f, thirdY), Offset(size.width, thirdY), stroke)
                drawLine(lineColor, Offset(0f, twoThirdY), Offset(size.width, twoThirdY), stroke)
            }

            CompositionGuides.CENTER -> {
                val tick = 22.dp.toPx()
                val cx = size.width / 2f
                val cy = size.height / 2f
                val color = Color(0x59FFFFFF)
                drawLine(color, Offset(cx - tick, cy), Offset(cx + tick, cy), stroke)
                drawLine(color, Offset(cx, cy - tick), Offset(cx, cy + tick), stroke)
            }

            CompositionGuides.DIAGONALS -> {
                drawLine(lineColor, Offset(0f, 0f), Offset(size.width, size.height), stroke)
                drawLine(lineColor, Offset(size.width, 0f), Offset(0f, size.height), stroke)
            }

            CompositionGuides.NONE -> Unit
        }
    }
}

/**
 * Horizontal level line: counter-rotates with the device so it reads as the
 * horizon; brightens when the camera is level. Draws nothing when there is no
 * gravity sensor or the attitude is not a plausible shooting one.
 */
@Composable
fun LevelLine(rollDegrees: Float?, modifier: Modifier = Modifier) {
    if (rollDegrees == null) return
    val level = LevelMath.isLevel(rollDegrees)
    Canvas(modifier = modifier) {
        val y = size.height * 0.45f
        val half = size.width * 0.18f
        val cx = size.width / 2f
        val color = if (level) Color.White else Color(0x55FFFFFF)
        rotate(degrees = -rollDegrees, pivot = Offset(cx, y)) {
            drawLine(
                color = color,
                start = Offset(cx - half, y),
                end = Offset(cx + half, y),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

/** Current screen roll from the gravity sensor, or null when unavailable. */
@Composable
fun rememberSensorRoll(): Float? {
    val context = LocalContext.current
    var rollDegrees by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (manager == null || sensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val g = event.values
                    rollDegrees = if (LevelMath.isPlausibleShootingAttitude(g[2])) {
                        LevelMath.rollDegrees(g[0], g[1])
                    } else {
                        null
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { manager.unregisterListener(listener) }
        }
    }
    return rollDegrees
}
