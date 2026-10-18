package app.cameraplus.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import app.cameraplus.media.MediaLibrary
import app.cameraplus.ui.theme.Black
import app.cameraplus.ui.theme.DimWhite
import app.cameraplus.ui.theme.FaintWhite
import app.cameraplus.ui.theme.White

/**
 * The gallery (v1.1): what Camera+ has actually saved, newest first, with a
 * full-screen viewer. Photos open in-app, and since 2026-10-18 videos play
 * in-app as well, through the platform's own `VideoView` controls — no extra
 * media stack, and no bounce out to another app.
 */
@Composable
fun GalleryScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MediaLibrary.Item>?>(null) }
    var opened by remember { mutableStateOf<MediaLibrary.Item?>(null) }

    LaunchedEffect(Unit) { items = MediaLibrary.recent(context) }

    Box(modifier = modifier.fillMaxSize().background(Black)) {
        val current = opened
        if (current != null) {
            Viewer(
                item = current,
                onBack = { opened = null },
                onFavorite = { favorite ->
                    scope.launch {
                        if (MediaLibrary.setFavorite(context, current, favorite)) {
                            opened = current.copy(isFavorite = favorite)
                            items = items?.map { if (it.uri == current.uri) it.copy(isFavorite = favorite) else it }
                        }
                    }
                },
                onDelete = {
                    scope.launch {
                        if (MediaLibrary.delete(context, current)) {
                            items = items?.filterNot { it.uri == current.uri }
                            opened = null
                        }
                    }
                },
            )
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
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
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
    onFavorite: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var confirmDelete by remember(item.uri) { mutableStateOf(false) }

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
            Icon(
                imageVector = if (item.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (item.isFavorite) "Remove favorite" else "Favorite",
                tint = White,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(25.dp)
                    .clickable { onFavorite(!item.isFavorite) },
            )
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = White,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(25.dp)
                    .clickable { confirmDelete = true },
            )
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            when {
                item.isVideo -> VideoPlayer(
                    uri = item.uri,
                    modifier = Modifier.fillMaxSize(),
                )

                image != null -> Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> Text(
                    text = "Could not load this item",
                    color = White,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.dateMillis))} · ${formatBytes(item.sizeBytes)}",
                color = White,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this item?") },
            text = { Text(item.displayName) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("DELETE") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("CANCEL") }
            },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
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

/**
 * In-app video playback (2026-10-18): a plain platform [VideoView] with its
 * own [MediaController], so a clip plays where it was opened. The clip loops
 * like a preview instead of freezing on its last frame, and the player is
 * released as soon as the viewer moves on.
 */
@Composable
private fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<VideoView?>(null) }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(uri)
                setOnPreparedListener { media ->
                    media.isLooping = true
                    start()
                }
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                player = this
            }
        },
        modifier = modifier,
    )
    DisposableEffect(uri) {
        onDispose {
            player?.stopPlayback()
            player = null
        }
    }
}
