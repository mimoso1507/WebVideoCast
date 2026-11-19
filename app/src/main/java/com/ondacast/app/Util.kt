package com.ondacast.app

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

object Net {
    /** IPv4 local del teléfono dentro de la Wi‑Fi (o del punto de acceso). */
    fun localIpv4(): String? {
        val ifs = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        } catch (e: Exception) {
            return null
        }
        val cands = ifs.filter { it.isUp && !it.isLoopback }.flatMap { nif ->
            nif.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { nif.name to (it.hostAddress ?: "") }
        }.filter { it.second.isNotEmpty() }
        val wifi = cands.firstOrNull {
            it.first.startsWith("wlan") || it.first.startsWith("ap") || it.first.startsWith("swlan")
        }
        val priv = cands.firstOrNull {
            it.second.startsWith("192.168.") || it.second.startsWith("10.") || it.second.startsWith("172.")
        }
        return (wifi ?: priv ?: cands.firstOrNull())?.second
    }
}

fun String.xmlEscape(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

fun String.xmlUnescape(): String = replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&apos;", "'").replace("&amp;", "&")

fun Long.fmtTime(): String {
    val s = this / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun httpGet(u: String, timeout: Int = 3000): String? = try {
    val c = URL(u).openConnection() as HttpURLConnection
    c.connectTimeout = timeout
    c.readTimeout = timeout
    c.inputStream.bufferedReader().use { it.readText() }
} catch (e: Exception) {
    null
}

fun xmlTag(xml: String, name: String): String? =
    Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
