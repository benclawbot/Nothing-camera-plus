package app.cameraplus.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads back what the camera saved, so the app has a real gallery instead of
 * sending people out to a system app (v1.1).
 *
 * Everything here reads the app's own MediaStore folders —
 * `Pictures/Camera+` and `Movies/Camera+` — so it only ever shows Camera+'s
 * shots, and thumbnails come from the platform's own `loadThumbnail`, which
 * is cheap enough to do while scrolling.
 */
object MediaLibrary {

    /** One row in the gallery: either a photo or a video. */
    data class Item(
        val uri: Uri,
        val isVideo: Boolean,
        val displayName: String,
        val dateMillis: Long,
        val sizeBytes: Long,
        val isFavorite: Boolean,
    )

    private val IMAGE_PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.SIZE,
        MediaStore.MediaColumns.IS_FAVORITE,
    )

    private val VIDEO_PROJECTION = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.SIZE,
        MediaStore.MediaColumns.IS_FAVORITE,
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
                        relativePath = "Pictures/Camera+",
                        limit = limit,
                    ),
                )
                addAll(
                    query(
                        context = context,
                        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection = VIDEO_PROJECTION,
                        relativePath = "Movies/Camera+",
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
        relativePath: String,
        limit: Int,
    ): List<Item> {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$relativePath%")
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
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val favoriteColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
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
                                displayName = cursor.getString(nameColumn) ?: "Camera+",
                                dateMillis = cursor.getLong(dateColumn) * 1000L,
                                sizeBytes = bytes,
                                isFavorite = favoriteColumn >= 0 && cursor.getInt(favoriteColumn) != 0,
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

    /** Full-size decode bounded by [maxSize], for the viewer. */
    suspend fun fullImage(context: Context, uri: Uri, maxSize: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                } ?: return@runCatching null
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longest / sample > maxSize) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
        }

    /** Sets MediaStore's favorite bit for an item owned by the app. */
    suspend fun setFavorite(context: Context, item: Item, favorite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0)
                }
                context.contentResolver.update(item.uri, values, null, null) > 0
            }.getOrDefault(false)
        }

    /** Deletes one Camera+ item from MediaStore. */
    suspend fun delete(context: Context, item: Item): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.delete(item.uri, null, null) > 0 }
                .getOrDefault(false)
        }
}
