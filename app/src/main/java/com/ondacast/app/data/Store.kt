package com.ondacast.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ondacast.app.cast.CastMedia
import com.ondacast.app.cast.MediaKind
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("onda", Context.MODE_PRIVATE)
    }

    /** fit = ajustar, fill = llenar (recorta), stretch = estirar, orig = original sin tocar. */
    var imageMode: String
        get() = sp.getString("imageMode", "fit") ?: "fit"
        set(v) = sp.edit().putString("imageMode", v).apply()

    var slideshowSec: Int
        get() = sp.getInt("slideshowSec", 5)
        set(v) = sp.edit().putInt("slideshowSec", v).apply()

    var proxyWeb: Boolean
        get() = sp.getBoolean("proxyWeb", true)
        set(v) = sp.edit().putBoolean("proxyWeb", v).apply()

    var desktopMode: Boolean
        get() = sp.getBoolean("desktopMode", false)
        set(v) = sp.edit().putBoolean("desktopMode", v).apply()

    var mirrorRes: Int
        get() = sp.getInt("mirrorRes", 720)
        set(v) = sp.edit().putInt("mirrorRes", v).apply()

    var mirrorFps: Int
        get() = sp.getInt("mirrorFps", 30)
        set(v) = sp.edit().putInt("mirrorFps", v).apply()

    var mirrorKbps: Int
        get() = sp.getInt("mirrorKbps", 4000)
        set(v) = sp.edit().putInt("mirrorKbps", v).apply()

    /** orig = proporción del teléfono, 169 = estirar a 16:9, 43 = estirar a 4:3. */
    var mirrorFormat: String
        get() = sp.getString("mirrorFormat", "orig") ?: "orig"
        set(v) = sp.edit().putString("mirrorFormat", v).apply()

    /** Al empezar el espejo, poner el celular en pantalla negra (luz mínima). */
    var blackOnMirror: Boolean
        get() = sp.getBoolean("blackOnMirror", true)
        set(v) = sp.edit().putBoolean("blackOnMirror", v).apply()

    // ---- Historial ----
    fun history(): List<CastMedia> = readMedia("history")

    fun addHistory(m: CastMedia) {
        val list = (listOf(m) + history().filter { it.uri != m.uri }).take(30)
        writeMedia("history", list)
    }

    fun clearHistory() = sp.edit().remove("history").apply()

    private fun readMedia(key: String): List<CastMedia> = try {
        val arr = JSONArray(sp.getString(key, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CastMedia(
                kind = MediaKind.valueOf(o.getString("kind")),
                title = o.getString("title"),
                uri = o.getString("uri"),
                mime = o.getString("mime"),
                isLocal = o.getBoolean("local"),
                referer = o.optString("ref").ifEmpty { null },
                userAgent = o.optString("ua").ifEmpty { null },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeMedia(key: String, list: List<CastMedia>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("kind", it.kind.name).put("title", it.title).put("uri", it.uri)
                    .put("mime", it.mime).put("local", it.isLocal)
                    .put("ref", it.referer ?: "").put("ua", it.userAgent ?: "")
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    // ---- Favoritos del navegador (título, url) ----
    fun bookmarks(): List<Pair<String, String>> = try {
        val arr = JSONArray(sp.getString("bookmarks", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("t") to o.getString("u") } }
    } catch (e: Exception) {
        emptyList()
    }

    fun isBookmarked(url: String) = bookmarks().any { it.second == url }

    fun toggleBookmark(title: String, url: String) {
        val cur = bookmarks()
        val next = if (cur.any { it.second == url }) cur.filter { it.second != url } else cur + (title to url)
        val arr = JSONArray()
        next.forEach { arr.put(JSONObject().put("t", it.first).put("u", it.second)) }
        sp.edit().putString("bookmarks", arr.toString()).apply()
    }
}
