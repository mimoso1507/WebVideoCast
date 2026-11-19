package com.ondacast.app.mirror

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/** Reparte el flujo MPEG-TS a los clientes (TVs) conectados. Cada cliente empieza en un fotograma clave. */
object MirrorHub {
    class Client {
        val q = LinkedBlockingQueue<ByteArray>(600)

        @Volatile
        var synced = false

        @Volatile
        var closed = false
    }

    private val clients = CopyOnWriteArrayList<Client>()

    fun addClient(): Client = Client().also { clients.add(it) }

    fun remove(c: Client) {
        c.closed = true
        clients.remove(c)
    }

    fun publish(data: ByteArray, key: Boolean) {
        for (c in clients) {
            if (!c.synced) {
                if (!key) continue
                c.synced = true
            }
            if (!c.q.offer(data)) {
                // cliente lento: descartamos y esperamos al siguiente fotograma clave
                c.q.clear()
                c.synced = false
            }
        }
    }

    fun stopAll() {
        clients.forEach { it.closed = true }
        clients.clear()
    }
}
