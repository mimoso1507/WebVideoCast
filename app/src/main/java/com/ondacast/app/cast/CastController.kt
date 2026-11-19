package com.ondacast.app.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.ondacast.app.KeepAliveService
import com.ondacast.app.Net
import com.ondacast.app.data.Store
import com.ondacast.app.server.MediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object CastController {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private val castDevices = MutableStateFlow<List<Device>>(emptyList())
    private val upnpDevices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> =
        combine(castDevices, upnpDevices) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val selected = MutableStateFlow<Device?>(null)
    val player = MutableStateFlow(PlayerState())
    val queue = MutableStateFlow<List<CastMedia>>(emptyList())
    val queueIndex = MutableStateFlow(-1)
    val scanning = MutableStateFlow(false)
    val showPicker = MutableStateFlow(false)
    val pending = MutableStateFlow<CastMedia?>(null)
    val sleepRemaining = MutableStateFlow(0)
    val slideshowActive = MutableStateFlow(false)
    val blackMode = MutableStateFlow(false)
    val browserUrl = MutableStateFlow<String?>(null)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var renderer: Renderer? = null
    private var castDiscovery: CastDiscovery? = null
    private var server: MediaServer? = null
    private var current: CastMedia? = null
    private var sawPlaying = false
    private var loadedAt = 0L
    private var sleepJob: Job? = null
    private var slideshowJob: Job? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        main.post {
            try {
                CastContext.getSharedInstance(app).sessionManager.addSessionManagerListener(
                    endListener, CastSession::class.java
                )
                castDiscovery = CastDiscovery(app) { castDevices.value = it }.also { it.start() }
            } catch (e: Throwable) {
                // Sin Google Play Services: solo DLNA y Roku
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                val r = renderer ?: continue
                val s = try { r.poll() } catch (e: Exception) { null } ?: continue
                val cur = current
                if (s.status == PlayStatus.PLAYING) sawPlaying = true
                val shown = if (cur == null) s.copy(status = PlayStatus.IDLE)
                else s.copy(title = cur.title, kind = cur.kind)
                player.value = shown
                if (s.status == PlayStatus.IDLE && sawPlaying && cur != null && cur.kind != MediaKind.IMAGE &&
                    System.currentTimeMillis() - loadedAt > 3000
                ) {
                    sawPlaying = false
                    next(auto = true)
                }
            }
        }
    }

    private val endListener = object : SessionManagerListener<CastSession> {
        override fun onSessionEnded(s: CastSession, error: Int) {
            if (selected.value?.type == DeviceType.CHROMECAST) resetConnection()
        }
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStarted(s: CastSession, id: String) {}
        override fun onSessionStartFailed(s: CastSession, error: Int) {}
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    private fun resetConnection() {
        renderer?.release()
        renderer = null
        selected.value = null
        current = null
        player.value = PlayerState()
        stopKeepAlive()
    }

    // ---------- Búsqueda ----------
    fun scan() {
        if (scanning.value) return
        scope.launch {
            scanning.value = true
            main.post { castDiscovery?.publish() }
            try {
                upnpDevices.value = Ssdp.discover(app)
            } finally {
                scanning.value = false
            }
        }
    }

    // ---------- Conexión ----------
    fun select(d: Device) {
        scope.launch {
            try {
                renderer?.release()
                renderer = null
                val r: Renderer = when (d.type) {
                    DeviceType.DLNA -> DlnaRenderer(d)
                    DeviceType.ROKU -> RokuRenderer(d)
                    DeviceType.CHROMECAST -> {
                        val s = connectCast(d)
                        if (s == null) {
                            events.emit("No se pudo conectar con ${d.name}")
                            return@launch
                        }
                        ChromecastRenderer(s)
                    }
                }
                renderer = r
                selected.value = d
                showPicker.value = false
                events.emit("Conectado a ${d.name}")
                pending.value?.let {
                    pending.value = null
                    if (it.uri.endsWith("/mirror.ts")) castMirror() else cast(it)
                }
            } catch (e: Exception) {
                events.emit("Error al conectar: ${e.message}")
            }
        }
    }

    private suspend fun connectCast(d: Device): CastSession? = withContext(Dispatchers.Main) {
        val disc = castDiscovery ?: return@withContext null
        val sm = CastContext.getSharedInstance(app).sessionManager
        val existing = sm.currentCastSession
        if (existing != null && existing.isConnected && disc.isSelected(d.routeId ?: "")) return@withContext existing
        if (existing != null) sm.endCurrentSession(true)
        withTimeoutOrNull(15000) {
            suspendCancellableCoroutine<CastSession?> { cont ->
                val l = object : SessionManagerListener<CastSession> {
                    override fun onSessionStarted(s: CastSession, id: String) {
                        sm.removeSessionManagerListener(this, CastSession::class.java)
                        if (cont.isActive) cont.resume(s)
                    }
                    override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
                        sm.removeSessionManagerListener(this, CastSession::class.java)
                        if (cont.isActive) cont.resume(s)
                    }
                    override fun onSessionStartFailed(s: CastSession, error: Int) {
                        sm.removeSessionManagerListener(this, CastSession::class.java)
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onSessionStarting(s: CastSession) {}
                    override fun onSessionEnding(s: CastSession) {}
                    override fun onSessionEnded(s: CastSession, error: Int) {}
                    override fun onSessionResuming(s: CastSession, id: String) {}
                    override fun onSessionResumeFailed(s: CastSession, error: Int) {}
                    override fun onSessionSuspended(s: CastSession, reason: Int) {}
                }
                sm.addSessionManagerListener(l, CastSession::class.java)
                cont.invokeOnCancellation { sm.removeSessionManagerListener(l, CastSession::class.java) }
                if (!disc.select(d.routeId ?: "")) cont.resume(null)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            val wasCast = selected.value?.type == DeviceType.CHROMECAST
            runCatching { renderer?.stop() }
            slideshowJob?.cancel()
            slideshowActive.value = false
            if (wasCast) withContext(Dispatchers.Main) {
                runCatching { CastContext.getSharedInstance(app).sessionManager.endCurrentSession(true) }
            }
            resetConnection()
        }
    }

    // ---------- Reproducción ----------
    private fun ensureServer(): MediaServer {
        server?.let { return it }
        val s = try {
            MediaServer(app, 8765).also { it.start() }
        } catch (e: Exception) {
            MediaServer(app, 0).also { it.start() }
        }
        server = s
        return s
    }

    private fun guessIp() = Net.localIpv4() ?: throw IllegalStateException("No hay conexión Wi‑Fi")

    private fun isPlaylist(m: CastMedia) =
        m.mime.contains("mpegURL", true) || m.mime.contains("dash", true)

    private fun resolve(m: CastMedia): String {
        val s = ensureServer()
        val ip = guessIp()
        return when {
            m.isLocal -> {
                val uri = Uri.parse(m.uri)
                val id = s.register(uri, m.mime)
                if (m.kind == MediaKind.IMAGE) {
                    "${s.baseUrl(ip)}/img/$id.jpg?m=${Store.imageMode}&v=${System.currentTimeMillis()}"
                } else {
                    "${s.baseUrl(ip)}/m/$id/archivo"
                }
            }
            Store.proxyWeb && !isPlaylist(m) && m.uri.startsWith("http") ->
                s.proxyUrl(ip, m.uri, m.referer, m.userAgent)
            else -> m.uri
        }
    }

    /** Envía a la TV; si no hay TV conectada, abre el selector y lo envía al conectar. */
    fun requestCast(m: CastMedia) {
        if (renderer == null) {
            pending.value = m
            showPicker.value = true
            scan()
        } else {
            cast(m)
        }
    }

    fun cast(m: CastMedia, addToQueue: Boolean = true) {
        scope.launch {
            val r = renderer ?: run {
                events.emit("Primero elige una TV")
                return@launch
            }
            try {
                player.value = PlayerState(PlayStatus.LOADING, title = m.title, kind = m.kind)
                val url = resolve(m)
                current = m
                sawPlaying = false
                loadedAt = System.currentTimeMillis()
                r.load(url, m)
                Store.addHistory(m)
                startKeepAlive()
                if (addToQueue && queue.value.none { it.uri == m.uri }) {
                    queue.value = queue.value + m
                }
                queueIndex.value = queue.value.indexOfFirst { it.uri == m.uri }
            } catch (e: Exception) {
                player.value = PlayerState(PlayStatus.ERROR, error = e.message)
                events.emit("No se pudo reproducir: ${e.message}")
            }
        }
    }

    fun castMirror() {
        val m = CastMedia(
            MediaKind.VIDEO, "Pantalla del teléfono",
            "http://${Net.localIpv4()}:8765/mirror.ts", "video/mpeg", isLocal = false
        )
        if (renderer == null) {
            pending.value = m
            showPicker.value = true
            scan()
            return
        }
        scope.launch {
            val r = renderer ?: return@launch
            try {
                val s = ensureServer()
                val url = "${s.baseUrl(guessIp())}/mirror.ts"
                current = m
                sawPlaying = false
                loadedAt = System.currentTimeMillis()
                player.value = PlayerState(PlayStatus.LOADING, title = m.title, kind = m.kind)
                r.load(url, m.copy(uri = url))
                if (Store.blackOnMirror) blackMode.value = true
            } catch (e: Exception) {
                events.emit("No se pudo duplicar: ${e.message}")
            }
        }
    }

    fun enqueue(m: CastMedia) {
        queue.value = queue.value + m
        scope.launch { events.emit("Añadido a la cola") }
    }

    fun removeFromQueue(i: Int) {
        queue.value = queue.value.filterIndexed { idx, _ -> idx != i }
    }

    fun playQueueAt(i: Int) {
        queue.value.getOrNull(i)?.let { cast(it, addToQueue = false) }
    }

    fun next(auto: Boolean = false) {
        val q = queue.value
        val i = queueIndex.value + 1
        if (i in q.indices) playQueueAt(i)
        else if (!auto) scope.launch { events.emit("No hay más elementos en la cola") }
    }

    fun prev() {
        val i = queueIndex.value - 1
        if (i >= 0) playQueueAt(i)
    }

    fun togglePlay() {
        scope.launch {
            val r = renderer ?: return@launch
            try {
                if (player.value.status == PlayStatus.PLAYING) r.pause() else r.play()
            } catch (e: Exception) {
                events.emit("Error: ${e.message}")
            }
        }
    }

    fun seek(ms: Long) {
        scope.launch { runCatching { renderer?.seek(ms) } }
    }

    fun setVolume(v: Int) {
        scope.launch {
            runCatching { renderer?.setVolume(v) }
            player.value = player.value.copy(volume = v)
        }
    }

    fun stop() {
        scope.launch {
            sawPlaying = false
            slideshowJob?.cancel()
            slideshowActive.value = false
            runCatching { renderer?.stop() }
            current = null
            player.value = player.value.copy(status = PlayStatus.IDLE, positionMs = 0)
            stopKeepAlive()
        }
    }

    // ---------- Herramientas ----------
    fun setImageMode(mode: String) {
        Store.imageMode = mode
        val cur = current
        if (cur != null && cur.kind == MediaKind.IMAGE && cur.isLocal) cast(cur, addToQueue = false)
    }

    fun startSlideshow(items: List<CastMedia>, seconds: Int) {
        if (items.isEmpty()) return
        if (renderer == null) {
            scope.launch { events.emit("Primero elige una TV") }
            showPicker.value = true
            scan()
            return
        }
        slideshowJob?.cancel()
        slideshowActive.value = true
        slideshowJob = scope.launch {
            var i = 0
            while (isActive) {
                cast(items[i % items.size], addToQueue = false)
                i++
                delay(seconds * 1000L)
            }
        }
    }

    fun stopSlideshow() {
        slideshowJob?.cancel()
        slideshowActive.value = false
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            sleepRemaining.value = 0
            return
        }
        sleepJob = scope.launch {
            var left = minutes * 60
            while (left > 0 && isActive) {
                sleepRemaining.value = left
                delay(1000)
                left--
            }
            sleepRemaining.value = 0
            stop()
            events.emit("Temporizador: transmisión detenida")
        }
    }

    private fun startKeepAlive() {
        ContextCompat.startForegroundService(app, Intent(app, KeepAliveService::class.java))
    }

    private fun stopKeepAlive() {
        app.stopService(Intent(app, KeepAliveService::class.java))
    }
}
