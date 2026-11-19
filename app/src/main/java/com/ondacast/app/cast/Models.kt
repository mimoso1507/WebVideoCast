package com.ondacast.app.cast

enum class DeviceType { CHROMECAST, DLNA, ROKU }
enum class MediaKind { VIDEO, AUDIO, IMAGE }

data class Device(
    val id: String,
    val name: String,
    val type: DeviceType,
    val host: String = "",
    val avTransportUrl: String? = null,
    val avType: String? = null,
    val renderingUrl: String? = null,
    val rcType: String? = null,
    val routeId: String? = null,
)

data class CastMedia(
    val kind: MediaKind,
    val title: String,
    val uri: String,
    val mime: String,
    val isLocal: Boolean,
    val referer: String? = null,
    val userAgent: String? = null,
)

enum class PlayStatus { IDLE, LOADING, PLAYING, PAUSED, ERROR }

data class PlayerState(
    val status: PlayStatus = PlayStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = -1,
    val title: String = "",
    val kind: MediaKind? = null,
    val error: String? = null,
)

interface Renderer {
    suspend fun load(url: String, media: CastMedia)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seek(ms: Long)
    suspend fun setVolume(v: Int)
    suspend fun poll(): PlayerState?
    fun release() {}
}
