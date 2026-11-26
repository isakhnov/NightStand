package com.nightstand.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.nightstand.Prefs
import com.nightstand.photos.smart.Candidate
import com.nightstand.photos.smart.PhotoSource

/**
 * Photos stored on the phone (MediaStore) — all, favourites, or one album.
 * Google Photos cloud-only items aren't reachable (see docs/views/03-photos).
 */
object PhotoLibrary {
    private val permission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Full or Android 14 "selected photos" access both count. */
    fun isGranted(c: Context) =
        c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 &&
                c.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

    fun query(c: Context): List<Uri> = candidates(c).map { it.uri }.shuffled()

    private fun selection(c: Context): Pair<String, Array<String>?> = when (Prefs.photoSource(c)) {
        Prefs.PhotoSource.FAVORITES -> "${MediaStore.MediaColumns.IS_FAVORITE}=1" to null
        Prefs.PhotoSource.ALBUM -> {
            val id = Prefs.photoAlbum(c)?.first
            if (id == null) noScreenshots() else "${MediaStore.Images.Media.BUCKET_ID}=?" to arrayOf(id)
        }
        Prefs.PhotoSource.ALL -> noScreenshots()
    }

    /** Photos for the current source setting, with capture time (bursts) and album. */
    fun candidates(c: Context): List<Candidate> {
        if (!isGranted(c)) return emptyList()
        val coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val (sel, args) = selection(c)
        val out = mutableListOf<Candidate>()
        c.contentResolver.query(
            coll, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.BUCKET_ID),
            sel, args, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                out += Candidate(id, ContentUris.withAppendedId(coll, id), cur.getLong(1), cur.getString(2))
            }
        }
        return out
    }

    fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** Small thumbnail for the image encoder (short side ≥ 256 after the system's sizing). */
    fun thumbnail(c: Context, uri: Uri, side: Int = 320): Bitmap? =
        runCatching { c.contentResolver.loadThumbnail(uri, android.util.Size(side, side), null) }.getOrNull()

    private fun noScreenshots(): Pair<String, Array<String>?> =
        "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} NOT IN ('Screenshots','Screen recordings')" to null

    data class Album(val id: String, val name: String, val count: Int, val cover: Uri)

    fun albums(c: Context): List<Album> {
        if (!isGranted(c)) return emptyList()
        val counts = LinkedHashMap<String, Album>()
        c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media._ID),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cur ->
            val coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            while (cur.moveToNext()) {
                val id = cur.getString(0) ?: continue
                val name = cur.getString(1) ?: "(unnamed)"
                // Newest photo in the album is its cover.
                counts[id] = counts[id]?.let { it.copy(count = it.count + 1) }
                    ?: Album(id, name, 1, ContentUris.withAppendedId(coll, cur.getLong(2)))
            }
        }
        return counts.values.sortedByDescending { it.count }
    }

    /**
     * Decode for display, long side capped at [maxSide] px; EXIF rotation applied.
     * [software]: required when the bitmap will be drawn on a Bitmap-backed
     * Canvas (settings previews). ImageDecoder's default is a HARDWARE bitmap,
     * and drawing one in software throws — that crashed the 0.5 settings screen.
     */
    fun decode(c: Context, uri: Uri, maxSide: Int, software: Boolean = false): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
            if (software) d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val s = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            d.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
        }
    }.getOrNull()
}

/** [PhotoSource] backed by MediaStore (the real phone). */
class MediaStoreSource(private val c: Context) : PhotoSource {
    override fun candidates() = PhotoLibrary.candidates(c)
    override fun thumbnail(c: Candidate) = PhotoLibrary.thumbnail(this.c, c.uri)
    override fun decode(c: Candidate, maxSide: Int) = PhotoLibrary.decode(this.c, c.uri, maxSide)
    override fun isGranted() = PhotoLibrary.isGranted(c)
}
