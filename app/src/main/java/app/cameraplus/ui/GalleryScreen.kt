package app.cameraplus.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cameraplus.media.MediaLibrary
import app.cameraplus.ui.theme.Black
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.FaintWhite
import app.cameraplus.ui.theme.White

/**
 * The gallery (v1.1): what Camera+ has actually saved, newest first, with a
 * full-screen viewer. Photos open in-app; videos hand off to the system
 * player, which is the honest way to play video without dragging a whole
 * media stack into this app.
 */
@Composable
fun GalleryScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaLibrary.Item>?>(null) }
    var opened by remember { mutableStateOf<MediaLibrary.Item?>(null) }

    LaunchedEffect(Unit) { items = MediaLibrary.recent(context) }

    Box(modifier = modifier.fillMaxSize().background(Black)) {
        val current = opened
        if (current != null) {
            Viewer(item = current, onBack = { opened = null })
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                GalleryHeader(onDismiss = onDismiss)
                val loaded = items
                when {
                    loaded == null -> Box(Modifier.fillMaxSize())
                    loaded.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Nothing captured yet",
                            color = DimWhite,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(loaded, key = { it.uri.toString() }) { item ->
                            GalleryCell(item = item, onClick = { opened = item })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "CAMERA+",
            color = White,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 4.sp,
            style = MaterialTheme.typography.titleSmall,
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            text = "CLOSE",
            color = DimWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onDismiss() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun GalleryCell(
    item: MediaLibrary.Item,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = item.uri) {
        value = MediaLibrary.thumbnail(context, item.uri)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x14FFFFFF))
            .clickable { onClick() },
    ) {
        thumbnail?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.isVideo) {
            Icon(
                imageVector = Icons.Outlined.PlayCircle,
                contentDescription = "Video",
                tint = White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(26.dp),
            )
        }
    }
}

@Composable
private fun Viewer(
    item: MediaLibrary.Item,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) {
        val maxPx = with(density) { 1080.dp.toPx().toInt() }
        bitmap = if (item.isVideo) null else MediaLibrary.fullImage(context, item.uri, maxPx)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = White,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onBack() },
            )
            Text(
                text = item.displayName,
                color = DimWhite,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = "Share",
                tint = White,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { share(context, item) },
            )
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            when {
                image != null -> Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                item.isVideo -> Text(
                    text = "Tap to open in your video player",
                    color = FaintWhite,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { openExternally(context, item.uri, "video/*") },
                )

                else -> Text(
                    text = "Could not load this item",
                    color = FaintWhite,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun share(context: android.content.Context, item: MediaLibrary.Item) {
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = if (item.isVideo) "video/mp4" else "image/*"
                    putExtra(Intent.EXTRA_STREAM, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share",
            ),
        )
    }
}

private fun openExternally(context: android.content.Context, uri: Uri, mime: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }
}