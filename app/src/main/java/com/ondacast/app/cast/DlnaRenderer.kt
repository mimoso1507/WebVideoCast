package com.ondacast.app.cast

import com.ondacast.app.xmlEscape
import com.ondacast.app.xmlTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Control UPnP/DLNA (AVTransport + RenderingControl) por SOAP. */
class DlnaRenderer(private val d: Device) : Renderer {
    private val av = d.avTransportUrl ?: error("Sin AVTransport")
    private val avType = d.avType ?: "urn:schemas-upnp-org:service:AVTransport:1"
    private var pollCount = 0
    private var lastVolume = -1

    private suspend fun call(url: String, type: String, action: String, args: String = ""): String =
        withContext(Dispatchers.IO) {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:$action xmlns:u=\"$type\">$args</u:$action></s:Body></s:Envelope>"
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 5000
                c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                c.setRequestProperty("SOAPACTION", "\"$type#$action\"")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code >= 400) c.errorStream else c.inputStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code >= 400) {
                    val err = xmlTag(text, "errorDescription") ?: "HTTP $code"
                    throw IllegalStateException("La TV rechazó $action: $err")
                }
                text
            } finally {
                c.disconnect()
            }
        }

    private fun upnpClass(k: MediaKind) = when (k) {
        MediaKind.VIDEO -> "object.item.videoItem"
        MediaKind.AUDIO -> "object.item.audioItem.musicTrack"
        MediaKind.IMAGE -> "object.item.imageItem.photo"
    }

    override suspend fun load(url: String, media: CastMedia) {
        runCatching { call(av, avType, "Stop", "<InstanceID>0</InstanceID>") }
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${media.title.xmlEscape()}</dc:title>" +
            "<upnp:class>${upnpClass(media.kind)}</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${media.mime}:*\">${url.xmlEscape()}</res>" +
            "</item></DIDL-Lite>"
        call(
            av, avType, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${url.xmlEscape()}</CurrentURI>" +
                "<CurrentURIMetaData>${didl.xmlEscape()}</CurrentURIMetaData>"
        )
        play()
    }

    override suspend fun play() {
        call(av, avType, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    override suspend fun pause() {
        call(av, avType, "Pause", "<InstanceID>0</InstanceID>")
    }

    override suspend fun stop() {
        call(av, avType, "Stop", "<InstanceID>0</InstanceID>")
    }

    override suspend fun seek(ms: Long) {
        val s = ms / 1000
        val t = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        call(
            av, avType, "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$t</Target>"
        )
    }

    override suspend fun setVolume(v: Int) {
        val url = d.renderingUrl ?: return
        call(
            url, d.rcType ?: "urn:schemas-upnp-org:service:RenderingControl:1", "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${v.coerceIn(0, 100)}</DesiredVolume>"
        )
        lastVolume = v
    }

    private fun parseTime(s: String?): Long {
        if (s == null) return 0
        val parts = s.substringBefore('.').split(":")
        if (parts.size != 3) return 0
        val h = parts[0].toLongOrNull() ?: return 0
        val m = parts[1].toLongOrNull() ?: return 0
        val sec = parts[2].toLongOrNull() ?: return 0
        return (h * 3600 + m * 60 + sec) * 1000
    }

    override suspend fun poll(): PlayerState? {
        val ti = call(av, avType, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        val pos = runCatching { call(av, avType, "GetPositionInfo", "<InstanceID>0</InstanceID>") }.getOrDefault("")
        if (d.renderingUrl != null && pollCount++ % 5 == 0) {
            runCatching {
                val r = call(
                    d.renderingUrl, d.rcType ?: "urn:schemas-upnp-org:service:RenderingControl:1", "GetVolume",
                    "<InstanceID>0</InstanceID><Channel>Master</Channel>"
                )
                xmlTag(r, "CurrentVolume")?.trim()?.toIntOrNull()?.let { lastVolume = it }
            }
        }
        val status = when (xmlTag(ti, "CurrentTransportState")?.trim()) {
            "PLAYING" -> PlayStatus.PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlayStatus.PAUSED
            "TRANSITIONING" -> PlayStatus.LOADING
            else -> PlayStatus.IDLE
        }
        return PlayerState(
            status = status,
            positionMs = parseTime(xmlTag(pos, "RelTime")?.trim()),
            durationMs = parseTime(xmlTag(pos, "TrackDuration")?.trim()),
            volume = lastVolume,
        )
    }
}
