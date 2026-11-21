package com.ondacast.app.mirror

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Último fotograma JPEG de la pantalla, para verlo desde el navegador de otro dispositivo (MJPEG). */
object WebHub {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var seq = 0L
    private var frame: ByteArray? = null

    @Volatile
    var active = false

    fun publish(jpeg: ByteArray) = lock.withLock {
        frame = jpeg
        seq++
        cond.signalAll()
    }

    /** Espera un fotograma más nuevo que [after]. Devuelve null si no llega a tiempo. */
    fun waitFrame(after: Long, timeoutMs: Long): Pair<Long, ByteArray>? = lock.withLock {
        if (frame == null || seq == after) cond.await(timeoutMs, TimeUnit.MILLISECONDS)
        val f = frame
        if (f != null && seq != after) seq to f else null
    }

    fun reset(on: Boolean) = lock.withLock {
        active = on
        if (!on) frame = null
        seq = 0
        cond.signalAll()
    }
}
