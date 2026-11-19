package com.ondacast.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.ondacast.app.httpGet
import com.ondacast.app.xmlTag
import com.ondacast.app.xmlUnescape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** Descubre Chromecast usando MediaRouter del SDK de Google Cast. */
class CastDiscovery(ctx: Context, private val onChange: (List<Device>) -> Unit) {
    private val router = MediaRouter.getInstance(ctx)
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        ).build()

    private val cb = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
    }

    fun start() {
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        publish()
    }

    private fun routes(): List<MediaRouter.RouteInfo> =
        router.routes.filter { !it.isDefault && it.matchesSelector(selector) }

    fun publish() {
        onChange(routes().map { Device("cast:${it.id}", it.name, DeviceType.CHROMECAST, routeId = it.id) })
    }

    fun select(routeId: String): Boolean {
        val r = routes().firstOrNull { it.id == routeId } ?: return false
        router.selectRoute(r)
        return true
    }

    fun isSelected(routeId: String): Boolean = routes().firstOrNull { it.id == routeId }?.isSelected == true
}

/** Descubre TV DLNA/UPnP y Roku por SSDP. */
object Ssdp {
    private const val ADDR = "239.255.255.250"

    suspend fun discover(ctx: Context, timeoutMs: Int = 3500): List<Device> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, String>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("ondacast").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                socket.broadcast = true
                val group = InetAddress.getByName(ADDR)
                val targets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "roku:ecp",
                )
                repeat(2) {
                    for (st in targets) {
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: $ADDR:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\nST: $st\r\n\r\n"
                        val b = msg.toByteArray()
                        socket.send(DatagramPacket(b, b.size, group, 1900))
                    }
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        val text = String(p.data, 0, p.length)
                        var loc: String? = null
                        var st = ""
                        for (line in text.split("\r\n")) {
                            val i = line.indexOf(':')
                            if (i <= 0) continue
                            val k = line.substring(0, i).trim().lowercase()
                            val v = line.substring(i + 1).trim()
                            if (k == "location") loc = v
                            if (k == "st") st = v
                        }
                        if (loc != null && !found.containsKey(loc)) found[loc] = st
                    } catch (e: SocketTimeoutException) {
                        // seguir esperando respuestas
                    }
                }
            }
        } catch (e: Exception) {
            // sin red: devolvemos lo encontrado
        } finally {
            if (lock.isHeld) lock.release()
        }

        val devices = coroutineScope {
            found.map { (loc, st) -> async { describe(loc, st) } }.awaitAll().filterNotNull()
        }
        // Un mismo televisor puede aparecer como Roku y como DLNA: preferimos Roku.
        val rokuHosts = devices.filter { it.type == DeviceType.ROKU }.map { it.host }.toSet()
        devices.filter { it.type == DeviceType.ROKU || it.host !in rokuHosts }.distinctBy { it.id }
    }

    private fun describe(loc: String, st: String): Device? {
        val url = try { URL(loc) } catch (e: Exception) { return null }
        if (st.contains("roku", true) || url.port == 8060) {
            val info = httpGet("http://${url.host}:8060/query/device-info") ?: return null
            val name = xmlTag(info, "user-device-name")?.takeIf { it.isNotBlank() }
                ?: xmlTag(info, "friendly-device-name")
                ?: xmlTag(info, "model-name") ?: "Roku"
            return Device("roku:${url.host}", name.xmlUnescape(), DeviceType.ROKU, host = url.host)
        }
        val xml = httpGet(loc) ?: return null
        var av: Pair<String, String>? = null
        var rc: Pair<String, String>? = null
        Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val s = m.groupValues[1]
            val type = xmlTag(s, "serviceType")?.trim() ?: return@forEach
            val control = xmlTag(s, "controlURL")?.trim() ?: return@forEach
            val abs = try { URL(url, control).toString() } catch (e: Exception) { return@forEach }
            if (type.contains("AVTransport")) av = type to abs
            if (type.contains("RenderingControl")) rc = type to abs
        }
        val avt = av ?: return null
        val name = xmlTag(xml, "friendlyName")?.xmlUnescape() ?: url.host
        val udn = xmlTag(xml, "UDN")?.trim() ?: url.host
        return Device(
            id = "dlna:$udn", name = name, type = DeviceType.DLNA, host = url.host,
            avTransportUrl = avt.second, avType = avt.first,
            renderingUrl = rc?.second, rcType = rc?.first,
        )
    }
}
