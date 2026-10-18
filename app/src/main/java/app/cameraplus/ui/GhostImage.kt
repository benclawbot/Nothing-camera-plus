package app.cameraplus.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/** The last shot sits this far below full opacity as an onion skin. */
const val GHOST_IMAGE_ALPHA = 0.38f

/**
 * Ghost image / onion skin (Open Camera): the previous shot held over the
 * viewfinder at low opacity, so a repeat composition lines up. Drawn under the
 * guides and readouts so it never hides the live frame's overlays.
 */
@Composable
fun GhostImageOverlay(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        alpha = GHOST_IMAGE_ALPHA,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * The largest power-of-two factor by which the longest side may be halved
 * without dropping below [maxDimension]. Pure so it can be unit tested; the
 * decode itself is guarded by a null-returning catch at the call site.
 */
fun ghostSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= maxDimension) {
        longest /= 2
        sample *= 2
    }
    return sample
}

/** Decodes [uri] small enough to be held on screen as an onion skin. */
fun decodeGhostBitmap(
    context: Context,
    uri: Uri,
    maxDimension: Int = 1_440,
): ImageBitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
    val options = BitmapFactory.Options().apply {
        inSampleSize = ghostSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
    }
    val bitmap = resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
    return bitmap?.asImageBitmap()
}
