package app.cameraplus.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A temporary 3x centre crop of the live preview for precise manual focus.
 * PreviewView may not expose a bitmap on every surface implementation; in that
 * case the assist simply waits for the next frame rather than affecting the
 * camera stream or capture framing.
 */
@Composable
fun FocusAssistOverlay(previewView: PreviewView, modifier: Modifier = Modifier) {
    var sample by remember(previewView) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(previewView) {
        while (true) {
            val source = runCatching { previewView.bitmap }.getOrNull()
            if (source != null && source.width > 0 && source.height > 0) {
                val output = withContext(Dispatchers.Default) {
                    centerCropSample(source)
                }
                sample = output?.asImageBitmap()
            }
            delay(180)
        }
    }
    Column(
        modifier = modifier
            .background(Color(0xCC000000), CircleShape)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(Color(0xFF171717))
                .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            sample?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "3x manual-focus assist",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.matchParentSize().clip(CircleShape),
                )
            }
        }
        Text(
            text = "3× FOCUS",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
    }
}

private fun centerCropSample(source: Bitmap): Bitmap? {
    val cropSide = minOf(source.width, source.height) / 3
    if (cropSide <= 0) return null
    val left = (source.width - cropSide) / 2
    val top = (source.height - cropSide) / 2
    val target = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
    return try {
        Canvas(target).drawBitmap(
            source,
            Rect(left, top, left + cropSide, top + cropSide),
            Rect(0, 0, target.width, target.height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        target
    } catch (_: Throwable) {
        target.recycle()
        null
    } finally {
        source.recycle()
    }
}
