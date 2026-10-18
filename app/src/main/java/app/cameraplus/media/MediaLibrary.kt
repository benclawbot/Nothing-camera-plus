package app.cameraplus.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds Camera+'s latest capture and loads its small preview for the camera
 * shutter's review button. Full review is delegated to the installed system
 * gallery through [SystemGallery], never rendered as an in-app gallery.
 */
object MediaLibrary {

    /** One row in the gallery: either a photo or a video. */
    data class Item(
        val uri: Uri,
        val isVideo: Boolean,
        val dateMillis: Long,
    )

    private val IMAGE_PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.SIZE,
    )

    private val VIDEO_PROJECTION = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.SIZE,
    )

    /** Newest first, photos and videos interleaved. */
    suspend fun recent(context: Context, limit: Int = 200): List<Item> =
        withContext(Dispatchers.IO) {
            val items = buildList {
                addAll(
                    query(
                        context = context,
                        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection = IMAGE_PROJECTION,
                        limit = limit,
                    ),
                )
                addAll(
                    query(
                        context = context,
                        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection = VIDEO_PROJECTION,
                        limit = limit,
                    ),
                )
            }
            items.sortedByDescending { it.dateMillis }.take(limit)
        }

    private fun query(
        context: Context,
        collection: Uri,
        projection: Array<String>,
        limit: Int,
    ): List<Item> {
        // The camera folder is shared with other camera apps, so scope to the
        // rows this app inserted rather than to the folder.
        val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val selectionArgs = arrayOf(context.packageName)
        val isVideo = collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val id = cursor.getLong(idColumn)
                        val bytes = cursor.getLong(sizeColumn)
                        // Failed saves leave 0-byte rows behind; do not show
                        // them as broken tiles in the grid.
                        if (bytes <= 0) continue
                        add(
                            Item(
                                uri = ContentUris.withAppendedId(collection, id),
                                isVideo = isVideo,
                                dateMillis = cursor.getLong(dateColumn) * 1000L,
                            ),
                        )
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Platform thumbnail decode; null when the item is gone or unreadable. */
    suspend fun thumbnail(context: Context, uri: Uri, size: Size = Size(360, 360)): Bitmap? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
            runCatching { context.contentResolver.loadThumbnail(uri, size, null) }.getOrNull()
        }

}
