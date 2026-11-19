package com.ondacast.app.mirror

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.ondacast.app.MainActivity
import com.ondacast.app.OndaApp
import com.ondacast.app.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread

object MirrorConfig {
    private fun r16(x: Int) = maxOf(16, (x + 8) / 16 * 16)

    fun outputSize(devW: Int, devH: Int): Pair<Int, Int> {
        val short = Store.mirrorRes
        val long = when (short) {
            480 -> 854
            720 -> 1280
            else -> 1920
        }
        return when (Store.mirrorFormat) {
            "169" -> r16(long) to r16(short)
            "43" -> r16(short * 4 / 3) to r16(short)
            else -> if (devH > devW) r16(long * devW / devH) to r16(long) else r16(long) to r16(long * devH / devW)
        }
    }
}

class MirrorService : Service() {
    @Volatile
    private var running = false
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var venc: MediaCodec? = null
    private var aenc: MediaCodec? = null
    private var record: AudioRecord? = null
    private var mux: TsMuxer? = null
    private val lock = Any()
    private var t0 = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMirror()
            stopSelf()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, OndaApp.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("OndaCast")
            .setContentText("Duplicando la pantalla en la TV")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2002, n)
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || running) {
            if (data == null) stopSelf()
            return START_NOT_STICKY
        }
        try {
            startMirror(code, data)
        } catch (e: Exception) {
            stopMirror()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startMirror(code: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopMirror()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val (w, h) = MirrorConfig.outputSize(dm.widthPixels, dm.heightPixels)

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, Store.mirrorKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, Store.mirrorFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        venc = enc
        display = proj.createVirtualDisplay(
            "ondacast", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            surface, null, null
        )

        val audioOk = setupAudio(proj)
        mux = TsMuxer(audioOk)
        running = true
        state.value = true
        t0 = System.nanoTime() / 1000

        thread(name = "onda-video") { videoLoop(enc) }
        if (audioOk) thread(name = "onda-audio") { audioLoop() }
    }

    private fun setupAudio(proj: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val af = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(af)
                .setBufferSizeInBytes(maxOf(min, 4096) * 4)
                .build()
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ae.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ae.start()
            rec.startRecording()
            record = rec
            aenc = ae
            true
        } catch (e: Exception) {
            record?.release(); record = null
            false
        }
    }

    private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

    private fun videoLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var config: ByteArray? = null
        try {
            while (running) {
                val i = enc.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val buf = enc.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val d = ByteArray(info.size)
                    buf.get(d)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        config = d
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val es = if (key && config != null) AUD + config + d else AUD + d
                        val ts = System.nanoTime() / 1000 - t0
                        val out = synchronized(lock) { mux?.video(es, ts, key) }
                        if (out != null) MirrorHub.publish(out, key)
                    }
                }
                enc.releaseOutputBuffer(i, false)
            }
        } catch (e: Exception) {
            // el codificador se detuvo
        }
    }

    private fun adts(len: Int): ByteArray {
        val fl = len + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (4 shl 2) or (2 shr 2)).toByte(),
            (((2 and 3) shl 6) or (fl shr 11)).toByte(),
            ((fl shr 3) and 0xFF).toByte(),
            (((fl and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
    }

    private fun audioLoop() {
        val ae = aenc ?: return
        val rec = record ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val ii = ae.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = ae.getInputBuffer(ii)!!
                    ib.clear()
                    val n = rec.read(ib, ib.capacity())
                    if (n > 0) ae.queueInputBuffer(ii, 0, n, System.nanoTime() / 1000 - t0, 0)
                    else ae.queueInputBuffer(ii, 0, 0, 0, 0)
                }
                while (true) {
                    val oi = ae.dequeueOutputBuffer(info, 0)
                    if (oi < 0) break
                    val ob = ae.getOutputBuffer(oi)
                    if (ob != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val raw = ByteArray(info.size)
                        ob.get(raw)
                        val ts = System.nanoTime() / 1000 - t0
                        val out = synchronized(lock) { mux?.audio(adts(raw.size) + raw, ts) }
                        if (out != null) MirrorHub.publish(out, false)
                    }
                    ae.releaseOutputBuffer(oi, false)
                }
            }
        } catch (e: Exception) {
            // fin del audio
        }
    }

    private fun stopMirror() {
        if (!running && projection == null) return
        running = false
        state.value = false
        MirrorHub.stopAll()
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { aenc?.stop(); aenc?.release() } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { venc?.stop(); venc?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        record = null; aenc = null; display = null; venc = null; projection = null
    }

    override fun onDestroy() {
        stopMirror()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.ondacast.app.STOP_MIRROR"
        private const val SAMPLE_RATE = 44100
        val state = MutableStateFlow(false)
    }
}
