package com.ondacast.app.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import com.ondacast.app.mirror.MirrorHub
import com.ondacast.app.mirror.WebHub
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Servidor HTTP local. La TV descarga desde aquí:
 *  /m/{id}/{nombre}   archivos del teléfono (con Range)
 *  /img/{id}.jpg?m=   fotos con formato de pantalla (ajustar/llenar/estirar)
 *  /p?u=&r=&ua=       proxy para contenido web (Referer, cookies, User-Agent)
 *  /mirror.ts         duplicación de pantalla en MPEG-TS
 *  /view, /mirror.mjpg  ver la pantalla desde cualquier navegador (MJPEG)
 */
class MediaServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    private class Entry(val uri: Uri, val mime: String)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(uri: Uri, mime: String): String {
        val id = Integer.toHexString(uri.toString().hashCode())
        entries[id] = Entry(uri, mime)
        return id
    }

    fun baseUrl(ip: String) = "http://$ip:$listeningPort"

    fun proxyUrl(ip: String, url: String, referer: String?, ua: String?): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return "${baseUrl(ip)}/p?u=${e(url)}" +
            (if (referer != null) "&r=${e(referer)}" else "") +
            (if (ua != null) "&ua=${e(ua)}" else "")
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val resp = try {
            when {
                path.startsWith("/m/") -> serveLocal(session, path.removePrefix("/m/").substringBefore('/'))
                path.startsWith("/img/") -> serveImage(session)
                path == "/p" -> serveProxy(session)
                path == "/mirror.ts" -> serveMirror()
                path == "/" || path == "/view" ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", VIEW_HTML)
                path == "/mirror.mjpg" -> serveMjpeg()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No encontrado")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
        resp.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length")
        resp.addHeader("transferMode.dlna.org", "Streaming")
        return resp
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No encontrado")

    // ---------- Archivos locales ----------
    private fun serveLocal(session: IHTTPSession, id: String): Response {
        val e = entries[id] ?: return notFound()
        val afd = ctx.contentResolver.openAssetFileDescriptor(e.uri, "r") ?: return notFound()
        var total = afd.length
        if (total < 0) total = afd.parcelFileDescriptor.statSize
        if (total < 0) {
            afd.close()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Tamaño desconocido")
        }
        var start = 0L
        var end = total - 1
        var partial = false
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val r = range.removePrefix("bytes=").split("-")
            if (r[0].isEmpty()) {
                val n = r.getOrNull(1)?.toLongOrNull() ?: 0L
                start = maxOf(0L, total - n)
            } else {
                start = r[0].toLongOrNull() ?: 0L
                r.getOrNull(1)?.toLongOrNull()?.let { end = it }
            }
            partial = true
        }
        if (start >= total) {
            afd.close()
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").also {
                it.addHeader("Content-Range", "bytes */$total")
            }
        }
        end = minOf(end, total - 1)
        val len = end - start + 1
        val ins = afd.createInputStream()
        var skipped = 0L
        while (skipped < start) {
            val s = ins.skip(start - skipped)
            if (s <= 0) break
            skipped += s
        }
        val resp = newFixedLengthResponse(
            if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK, e.mime, ins, len
        )
        resp.addHeader("Accept-Ranges", "bytes")
        if (partial) resp.addHeader("Content-Range", "bytes $start-$end/$total")
        resp.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        return resp
    }

    // ---------- Fotos con formato ----------
    private fun serveImage(session: IHTTPSession): Response {
        val id = session.uri.removePrefix("/img/").substringBefore('.')
        val mode = session.parameters["m"]?.firstOrNull() ?: "fit"
        val e = entries[id] ?: return notFound()
        if (mode == "orig") return serveLocal(session, id)

        val bmp = decode(e.uri) ?: return notFound()
        val w = 1920
        val h = 1080
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val sw = bmp.width.toFloat()
        val sh = bmp.height.toFloat()
        val dst = when (mode) {
            "stretch" -> RectF(0f, 0f, w.toFloat(), h.toFloat())
            "fill" -> {
                val s = maxOf(w / sw, h / sh)
                val dw = sw * s
                val dh = sh * s
                RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
            }
            else -> {
                val s = minOf(w / sw, h / sh)
                val dw = sw * s
                val dh = sh * s
                RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
            }
        }
        c.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        bmp.recycle()
        out.recycle()
        val bytes = bos.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun decode(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = maxOf(info.size.width, info.size.height)
                if (m > 2560) {
                    val f = 2560f / m
                    decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                }
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > 2560) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) {
        null
    }

    // ---------- Proxy web ----------
    private fun serveProxy(session: IHTTPSession): Response {
        val u = session.parameters["u"]?.firstOrNull() ?: return notFound()
        val ref = session.parameters["r"]?.firstOrNull()
        val ua = session.parameters["ua"]?.firstOrNull()
        var url = URL(u)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            if (ref != null) conn.setRequestProperty("Referer", ref)
            if (ua != null) conn.setRequestProperty("User-Agent", ua)
            session.headers["range"]?.let { conn.setRequestProperty("Range", it) }
            try {
                CookieManager.getInstance().getCookie(url.toString())?.let { conn.setRequestProperty("Cookie", it) }
            } catch (_: Exception) {
            }
            val code = conn.responseCode
            if (code in 300..399 && hops < 6) {
                val loc = conn.getHeaderField("Location")
                if (loc != null) {
                    url = URL(url, loc)
                    conn.disconnect()
                    hops++
                    continue
                }
            }
            break
        }
        val code = conn.responseCode
        val stream: InputStream = (if (code >= 400) conn.errorStream else conn.inputStream)
            ?: ByteArrayInputStream(ByteArray(0))
        val mime = conn.contentType ?: "application/octet-stream"
        val len = conn.contentLengthLong
        val status = when {
            code == 206 -> Response.Status.PARTIAL_CONTENT
            code == 404 -> Response.Status.NOT_FOUND
            code in 200..299 -> Response.Status.OK
            else -> Response.Status.INTERNAL_ERROR
        }
        val resp = if (len >= 0) newFixedLengthResponse(status, mime, stream, len)
        else newChunkedResponse(status, mime, stream)
        resp.addHeader("Accept-Ranges", "bytes")
        conn.getHeaderField("Content-Range")?.let { resp.addHeader("Content-Range", it) }
        return resp
    }

    // ---------- Duplicar pantalla ----------
    private fun serveMirror(): Response {
        val client = MirrorHub.addClient()
        val stream = object : InputStream() {
            private var cur: ByteArray? = null
            private var off = 0

            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, o: Int, l: Int): Int {
                while (true) {
                    val c = cur
                    if (c != null && off < c.size) {
                        val n = minOf(l, c.size - off)
                        System.arraycopy(c, off, b, o, n)
                        off += n
                        return n
                    }
                    cur = null
                    if (client.closed && client.q.isEmpty()) return -1
                    val next = client.q.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (next != null) {
                        cur = next
                        off = 0
                    }
                }
            }

            override fun close() {
                MirrorHub.remove(client)
            }
        }
        val r = newChunkedResponse(Response.Status.OK, "video/mpeg", stream)
        r.addHeader("Cache-Control", "no-cache")
        return r
    }

    // ---------- Ver en el navegador (MJPEG) ----------
    private fun serveMjpeg(): Response {
        val stream = object : InputStream() {
            private var cur: ByteArray? = null
            private var off = 0
            private var lastSeq = -1L

            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, o: Int, l: Int): Int {
                while (true) {
                    val c = cur
                    if (c != null && off < c.size) {
                        val n = minOf(l, c.size - off)
                        System.arraycopy(c, off, b, o, n)
                        off += n
                        return n
                    }
                    cur = null
                    if (!WebHub.active) return -1
                    val f = WebHub.waitFrame(lastSeq, 1000) ?: continue
                    lastSeq = f.first
                    val head = "--onda\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.second.size}\r\n\r\n".toByteArray()
                    cur = head + f.second + "\r\n".toByteArray()
                    off = 0
                }
            }
        }
        val r = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=onda", stream)
        r.addHeader("Cache-Control", "no-cache, no-store")
        return r
    }

    companion object {
        private const val VIEW_HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>OndaCast</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
img{width:100vw;height:100vh;object-fit:contain;display:block}
#h{position:fixed;left:0;right:0;bottom:8px;text-align:center;color:#555;font:14px sans-serif}</style></head>
<body><img id="v" alt=""><div id="h">Toca la pantalla para pantalla completa</div>
<script>
var v=document.getElementById('v');
function go(){v.src='/mirror.mjpg?'+Date.now()}
v.onerror=function(){setTimeout(go,1000)};
go();
setTimeout(function(){document.getElementById('h').style.display='none'},6000);
document.body.onclick=function(){var d=document.documentElement;
if(!document.fullscreenElement&&d.requestFullscreen){d.requestFullscreen()}
else if(document.exitFullscreen){document.exitFullscreen()}};
</script></body></html>"""
    }
}
