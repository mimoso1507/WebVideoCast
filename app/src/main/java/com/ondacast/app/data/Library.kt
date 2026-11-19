package com.ondacast.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.ondacast.app.cast.CastMedia
import com.ondacast.app.cast.MediaKind

data class LocalItem(
    val uri: Uri,
    val title: String,
    val kind: MediaKind,
    val mime: String,
    val durationMs: Long,
    val size: Long,
) {
    fun toMedia() = CastMedia(kind, title, uri.toString(), mime, isLocal = true)
}

object Library {
    fun query(ctx: Context, kind: MediaKind): List<LocalItem> {
        val base = when (kind) {
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val hasDuration = kind != MediaKind.IMAGE
        val proj = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
        )
        if (hasDuration) proj.add(MediaStore.MediaColumns.DURATION)
        val out = ArrayList<LocalItem>()
        try {
            ctx.contentResolver.query(
                base, proj.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 3000) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "archivo"
                    val mime = c.getString(2) ?: defaultMime(kind)
                    val size = c.getLong(3)
                    val dur = if (hasDuration) c.getLong(4) else 0L
                    out.add(
                        LocalItem(
                            ContentUris.withAppendedId(base, id), name.substringBeforeLast('.'),
                            kind, mime, dur, size
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // sin permiso o error de proveedor
        }
        return out
    }

    fun defaultMime(kind: MediaKind) = when (kind) {
        MediaKind.VIDEO -> "video/mp4"
        MediaKind.AUDIO -> "audio/mpeg"
        MediaKind.IMAGE -> "image/jpeg"
    }

    /** Convierte un archivo elegido con el selector del sistema en un elemento enviable. */
    fun fromUri(ctx: Context, uri: Uri): CastMedia? {
        val mime = ctx.contentResolver.getType(uri) ?: return null
        val kind = when {
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime.startsWith("image/") -> MediaKind.IMAGE
            else -> return null
        }
        var name = "archivo"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        return CastMedia(kind, name.substringBeforeLast('.'), uri.toString(), mime, isLocal = true)
    }
}
