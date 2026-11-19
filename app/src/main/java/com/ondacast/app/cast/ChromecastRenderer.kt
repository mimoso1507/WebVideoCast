package com.ondacast.app.cast

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChromecastRenderer(private val session: CastSession) : Renderer {

    private fun client() = session.remoteMediaClient ?: throw IllegalStateException("Chromecast sin conexión")

    override suspend fun load(url: String, media: CastMedia) = withContext(Dispatchers.Main) {
        val md = MediaMetadata(
            when (media.kind) {
                MediaKind.VIDEO -> MediaMetadata.MEDIA_TYPE_MOVIE
                MediaKind.AUDIO -> MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
                MediaKind.IMAGE -> MediaMetadata.MEDIA_TYPE_PHOTO
            }
        )
        md.putString(MediaMetadata.KEY_TITLE, media.title)
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.mime)
            .setMetadata(md)
            .build()
        client().load(
            MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()
        )
        Unit
    }

    override suspend fun play() = withContext(Dispatchers.Main) { client().play(); Unit }
    override suspend fun pause() = withContext(Dispatchers.Main) { client().pause(); Unit }
    override suspend fun stop() = withContext(Dispatchers.Main) { client().stop(); Unit }

    override suspend fun seek(ms: Long) = withContext(Dispatchers.Main) {
        client().seek(MediaSeekOptions.Builder().setPosition(ms).build())
        Unit
    }

    override suspend fun setVolume(v: Int) = withContext(Dispatchers.Main) {
        session.volume = v.coerceIn(0, 100) / 100.0
    }

    override suspend fun poll(): PlayerState? = withContext(Dispatchers.Main) {
        val c = session.remoteMediaClient ?: return@withContext null
        val st = when (c.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> PlayStatus.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> PlayStatus.PAUSED
            MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> PlayStatus.LOADING
            else -> PlayStatus.IDLE
        }
        PlayerState(
            status = st,
            positionMs = c.approximateStreamPosition,
            durationMs = maxOf(0L, c.streamDuration),
            volume = (session.volume * 100).toInt(),
        )
    }
}
