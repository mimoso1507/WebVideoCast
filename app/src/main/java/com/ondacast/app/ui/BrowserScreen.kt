package com.ondacast.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ondacast.app.cast.CastController
import com.ondacast.app.cast.CastMedia
import com.ondacast.app.cast.MediaKind
import com.ondacast.app.data.Store

class Detected(val url: String, val kind: MediaKind, val title: String)

private val VIDEO_EXT = setOf("mp4", "m3u8", "mpd", "webm", "mkv", "mov", "m4v", "flv")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "opus")

private fun extOf(url: String): String =
    url.substringBefore('#').substringBefore('?').substringAfterLast('.', "").lowercase()

fun guessMime(url: String, kind: MediaKind): String = when (extOf(url)) {
    "mp4", "m4v" -> "video/mp4"
    "m3u8" -> "application/x-mpegURL"
    "mpd" -> "application/dash+xml"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "flv" -> "video/x-flv"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "flac" -> "audio/flac"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> when (kind) {
        MediaKind.VIDEO -> "video/mp4"
        MediaKind.AUDIO -> "audio/mpeg"
        MediaKind.IMAGE -> "image/jpeg"
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

private val DETECT_JS = """
(function(){
  if (window.__onda) return; window.__onda = true;
  var seen = {};
  function abs(u){ try { return new URL(u, location.href).href } catch(e){ return null } }
  function report(u, k, t){
    if(!u || u.indexOf('blob:')==0 || u.indexOf('data:')==0) return;
    u = abs(u); if(!u || seen[u]) return; seen[u] = 1;
    OndaBridge.found(u, k, t || document.title || '');
  }
  function kindOf(u){
    var m = (u||'').split('#')[0].split('?')[0].match(/\.([a-z0-9]+)$/i);
    if(!m) return null; var e = m[1].toLowerCase();
    if(/^(mp3|m4a|aac|flac|ogg|wav|opus)$/.test(e)) return 'audio';
    if(/^(mp4|m3u8|mpd|webm|mkv|mov|m4v|flv)$/.test(e)) return 'video';
    return null;
  }
  function scan(){
    document.querySelectorAll('video').forEach(function(v){
      report(v.currentSrc || v.src, 'video');
      v.querySelectorAll('source').forEach(function(s){ report(s.src, 'video'); });
    });
    document.querySelectorAll('audio').forEach(function(a){
      report(a.currentSrc || a.src, 'audio');
      a.querySelectorAll('source').forEach(function(s){ report(s.src, 'audio'); });
    });
    document.querySelectorAll('img').forEach(function(i){
      if(i.naturalWidth >= 300 && i.naturalHeight >= 200) report(i.currentSrc || i.src, 'image');
    });
    var og = document.querySelector('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:audio"]');
    if(og) report(og.content, 'video');
    document.querySelectorAll('a[href]').forEach(function(a){
      var k = kindOf(a.href); if(k) report(a.href, k);
    });
  }
  function check(u){ var a = abs(u); if(!a) return; var k = kindOf(a); if(k) report(a, k); }
  var oo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(m,u){ try{ check(u) }catch(e){} return oo.apply(this, arguments) };
  var of = window.fetch;
  if(of) window.fetch = function(u){ try{ check(typeof u === 'string' ? u : u.url) }catch(e){} return of.apply(this, arguments) };
  scan(); setInterval(scan, 2000);
})();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
class BrowserState(ctx: Context) {
    val detected = mutableStateListOf<Detected>()
    var address by mutableStateOf("https://www.google.com")
    var pageUrl by mutableStateOf("")
    var pageTitle by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var desktop by mutableStateOf(Store.desktopMode)
    var bookmarked by mutableStateOf(false)

    private val seen = HashSet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private var started = false
    val web = WebView(ctx)
    private val defaultUa: String = web.settings.userAgentString

    val userAgent: String get() = web.settings.userAgentString

    init {
        web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = if (desktop) DESKTOP_UA else defaultUa
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun found(url: String, kind: String, title: String) {
                val k = when (kind) {
                    "audio" -> MediaKind.AUDIO
                    "image" -> MediaKind.IMAGE
                    else -> MediaKind.VIDEO
                }
                add(url, k, title)
            }
        }, "OndaBridge")
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                detected.clear(); seen.clear()
                pageUrl = url; address = url
                sync(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageTitle = view.title ?: ""
                sync(view)
                view.evaluateJavascript(DETECT_JS, null)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                pageUrl = url; address = url
                sync(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: return true
                return !(s == "http" || s == "https")
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                val e = extOf(u)
                if (e in VIDEO_EXT) add(u, MediaKind.VIDEO, "")
                else if (e in AUDIO_EXT) add(u, MediaKind.AUDIO, "")
                return null
            }
        }
    }

    private fun sync(v: WebView) {
        canBack = v.canGoBack()
        canForward = v.canGoForward()
        bookmarked = Store.isBookmarked(pageUrl)
    }

    private fun add(url: String, kind: MediaKind, title: String) {
        ui.post {
            if (seen.add(url)) {
                val t = title.ifBlank { pageTitle.ifBlank { url.substringBefore('?').substringAfterLast('/') } }
                detected.add(Detected(url, kind, t))
            }
        }
    }

    fun load(input: String) {
        val t = input.trim()
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
        started = true
        web.loadUrl(url)
    }

    fun ensureStarted() {
        if (!started) load(address)
    }

    fun applyDesktop(on: Boolean) {
        desktop = on
        Store.desktopMode = on
        web.settings.userAgentString = if (on) DESKTOP_UA else defaultUa
        web.reload()
    }

    fun toMedia(d: Detected) = CastMedia(
        kind = d.kind, title = d.title, uri = d.url, mime = guessMime(d.url, d.kind),
        isLocal = false, referer = pageUrl.ifEmpty { null }, userAgent = userAgent
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(b: BrowserState) {
    var sheet by remember { mutableStateOf(false) }
    val kb = LocalSoftwareKeyboardController.current

    BackHandler(enabled = b.canBack) { b.web.goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { b.web.goBack() }, enabled = b.canBack) { Icon(Icons.Default.ArrowBack, "Atrás") }
            IconButton(onClick = { b.web.goForward() }, enabled = b.canForward) { Icon(Icons.Default.ArrowForward, "Adelante") }
            OutlinedTextField(
                value = b.address, onValueChange = { b.address = it },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { b.load(b.address); kb?.hide() }),
            )
            IconButton(onClick = { b.web.reload() }) { Icon(Icons.Default.Refresh, "Recargar") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                Store.toggleBookmark(b.pageTitle.ifBlank { b.pageUrl }, b.pageUrl)
                b.bookmarked = Store.isBookmarked(b.pageUrl)
            }) {
                Icon(if (b.bookmarked) Icons.Default.Star else Icons.Default.StarBorder, "Favorito")
            }
            IconButton(onClick = { b.applyDesktop(!b.desktop) }) {
                Icon(
                    Icons.Default.DesktopWindows, "Sitio de escritorio",
                    tint = if (b.desktop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { sheet = true }) {
                BadgedBox(badge = { if (b.detected.isNotEmpty()) Badge { Text("${b.detected.size}") } }) {
                    Icon(Icons.Default.Cast, "Medios detectados", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (b.progress in 1..99) LinearProgressIndicator(progress = { b.progress / 100f }, modifier = Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = {
                (b.web.parent as? ViewGroup)?.removeView(b.web)
                b.ensureStarted()
                b.web
            }
        )
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Text(
                if (b.detected.isEmpty()) "Aún no se detecta video, audio ni imágenes en esta página. Reproduce el contenido y vuelve a abrir esta lista."
                else "Detectado en esta página (${b.detected.size})",
                Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall
            )
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(b.detected.toList()) { d ->
                    ListItem(
                        headlineContent = { Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(d.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Icon(
                                when (d.kind) {
                                    MediaKind.VIDEO -> Icons.Default.Videocam
                                    MediaKind.AUDIO -> Icons.Default.MusicNote
                                    MediaKind.IMAGE -> Icons.Default.Image
                                }, null
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { CastController.enqueue(b.toMedia(d)) }) {
                                Icon(Icons.Default.PlaylistAdd, "Añadir a la cola")
                            }
                        },
                        modifier = Modifier.clickable {
                            sheet = false
                            CastController.requestCast(b.toMedia(d))
                        }
                    )
                }
            }
        }
    }
}
