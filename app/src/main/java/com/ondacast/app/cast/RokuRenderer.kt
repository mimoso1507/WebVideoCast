package com.ondacast.app.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Control de Roku por ECP (puerto 8060) usando el canal "Play on Roku". Experimental. */
class RokuRenderer(private val d: Device) : Renderer {
    private val base = "http://${d.host}:8060"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun post(path: String) = withContext(Dispatchers.IO) {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 6000
            c.doOutput = true
            c.outputStream.use { }
            val code = c.responseCode
            if (code >= 400) throw IllegalStateException("Roku respondió HTTP $code")
        } finally {
            c.disconnect()
        }
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val c = URL(base + path).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String, media: CastMedia) {
        val t = when (media.kind) {
            MediaKind.VIDEO -> "v"
            MediaKind.AUDIO -> "a"
            MediaKind.IMAGE -> "p"
        }
        val fmt = when {
            media.mime.contains("mpegURL", true) -> "hls"
            media.mime.contains("mp4") -> "mp4"
            media.mime.contains("matroska") -> "mkv"
            media.mime.contains("mpeg") && media.kind == MediaKind.AUDIO -> "mp3"
            else -> "mp4"
        }
        post(
            "/input/15985?t=$t&u=${enc(url)}&k=0&videoName=${enc(media.title)}" +
                "&songName=${enc(media.title)}&videoFormat=$fmt"
        )
    }

    override suspend fun play() = post("/keypress/Play")
    override suspend fun pause() = post("/keypress/Play")
    override suspend fun stop() = post("/keypress/Back")
    override suspend fun seek(ms: Long) { /* Roku no admite saltos absolutos por ECP */ }
    override suspend fun setVolume(v: Int) { /* Solo en Roku TV con teclas de volumen */ }

    override suspend fun poll(): PlayerState? {
        val xml = get("/query/media-player") ?: return null
        val state = Regex("<player[^>]*state=\"(\\w+)\"").find(xml)?.groupValues?.get(1)
        val pos = Regex("<position>\\s*(\\d+)").find(xml)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val dur = Regex("<duration>\\s*(\\d+)").find(xml)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val st = when (state) {
            "play" -> PlayStatus.PLAYING
            "pause" -> PlayStatus.PAUSED
            "buffer", "startup", "open" -> PlayStatus.LOADING
            else -> PlayStatus.IDLE
        }
        return PlayerState(status = st, positionMs = pos, durationMs = dur)
    }
}
