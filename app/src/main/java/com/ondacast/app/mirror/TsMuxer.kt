package com.ondacast.app.mirror

import java.io.ByteArrayOutputStream

/** Empaquetador MPEG-TS mínimo: H.264 (PID 0x100) y AAC-ADTS opcional (PID 0x101). */
class TsMuxer(private val hasAudio: Boolean) {
    private val cc = IntArray(8192)

    private fun crc32(d: ByteArray): Int {
        var crc = -1
        for (b in d) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private fun psi(pid: Int, body: ByteArray): ByteArray {
        val sectionLen = body.size + 4
        val sec = ByteArrayOutputStream()
        sec.write(body[0].toInt())
        sec.write(0xB0 or ((sectionLen shr 8) and 0x0F))
        sec.write(sectionLen and 0xFF)
        sec.write(body, 1, body.size - 1)
        val withoutCrc = sec.toByteArray()
        val crc = crc32(withoutCrc)
        sec.write(crc ushr 24); sec.write(crc ushr 16); sec.write(crc ushr 8); sec.write(crc)
        val section = sec.toByteArray()
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or (pid shr 8)).toByte()
        pkt[2] = pid.toByte()
        pkt[3] = (0x10 or (cc[pid] and 0x0F)).toByte()
        cc[pid] = (cc[pid] + 1) and 0x0F
        pkt[4] = 0
        System.arraycopy(section, 0, pkt, 5, section.size)
        return pkt
    }

    private fun pat(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(0x00)                    // table_id
        b.write(0x00); b.write(0x01)     // transport_stream_id
        b.write(0xC1); b.write(0); b.write(0)
        b.write(0x00); b.write(0x01)     // program_number
        b.write(0xE0 or (PMT_PID shr 8)); b.write(PMT_PID and 0xFF)
        return psi(0, b.toByteArray())
    }

    private fun pmt(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(0x02)
        b.write(0x00); b.write(0x01)
        b.write(0xC1); b.write(0); b.write(0)
        b.write(0xE0 or (VIDEO_PID shr 8)); b.write(VIDEO_PID and 0xFF) // PCR PID
        b.write(0xF0); b.write(0x00)
        b.write(0x1B); b.write(0xE0 or (VIDEO_PID shr 8)); b.write(VIDEO_PID and 0xFF); b.write(0xF0); b.write(0x00)
        if (hasAudio) {
            b.write(0x0F); b.write(0xE0 or (AUDIO_PID shr 8)); b.write(AUDIO_PID and 0xFF); b.write(0xF0); b.write(0x00)
        }
        return psi(PMT_PID, b.toByteArray())
    }

    private fun pes(streamId: Int, payload: ByteArray, pts: Long, video: Boolean): ByteArray {
        val o = ByteArrayOutputStream(payload.size + 20)
        o.write(0); o.write(0); o.write(1); o.write(streamId)
        val len = if (video) 0 else payload.size + 8
        o.write(len shr 8); o.write(len and 0xFF)
        o.write(0x80); o.write(0x80); o.write(5)
        o.write((0x21 or (((pts shr 30) and 0x07).toInt() shl 1)))
        o.write(((pts shr 22) and 0xFF).toInt())
        o.write((((pts shr 15) and 0x7F).toInt() shl 1) or 1)
        o.write(((pts shr 7) and 0xFF).toInt())
        o.write((((pts) and 0x7F).toInt() shl 1) or 1)
        o.write(payload)
        return o.toByteArray()
    }

    private fun packetize(pid: Int, data: ByteArray, pcr: Long?, randomAccess: Boolean): ByteArray {
        val out = ByteArrayOutputStream(data.size + data.size / 10 + 400)
        var off = 0
        var first = true
        while (off < data.size) {
            val pkt = ByteArray(188)
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            pkt[2] = pid.toByte()
            val remaining = data.size - off
            val hasPcr = first && pcr != null
            val cap = 184 - (if (hasPcr) 8 else 0)
            val n = minOf(remaining, cap)
            val afTotal = 184 - n
            pkt[3] = (((if (afTotal > 0) 0x30 else 0x10)) or (cc[pid] and 0x0F)).toByte()
            cc[pid] = (cc[pid] + 1) and 0x0F
            var p = 4
            if (afTotal > 0) {
                pkt[p++] = (afTotal - 1).toByte()
                if (afTotal >= 2) {
                    var flags = 0
                    if (first && randomAccess) flags = flags or 0x40
                    if (hasPcr) flags = flags or 0x10
                    pkt[p++] = flags.toByte()
                    if (hasPcr) {
                        val base = pcr!!
                        pkt[p++] = (base shr 25).toByte()
                        pkt[p++] = (base shr 17).toByte()
                        pkt[p++] = (base shr 9).toByte()
                        pkt[p++] = (base shr 1).toByte()
                        pkt[p++] = (((base and 1L).toInt() shl 7) or 0x7E).toByte()
                        pkt[p++] = 0
                    }
                    while (p < 4 + afTotal) pkt[p++] = 0xFF.toByte()
                }
            }
            System.arraycopy(data, off, pkt, p, n)
            off += n
            first = false
            out.write(pkt)
        }
        return out.toByteArray()
    }

    private fun toPts(us: Long) = us * 9 / 100 + 90000L

    /** Unidad de acceso H.264 en Annex‑B. Si es clave se antepone PAT/PMT. */
    fun video(es: ByteArray, tsUs: Long, key: Boolean): ByteArray {
        val pts = toPts(tsUs)
        val o = ByteArrayOutputStream(es.size + 1000)
        if (key) { o.write(pat()); o.write(pmt()) }
        o.write(packetize(VIDEO_PID, pes(0xE0, es, pts, true), maxOf(0L, pts - 18000), key))
        return o.toByteArray()
    }

    fun audio(adts: ByteArray, tsUs: Long): ByteArray =
        packetize(AUDIO_PID, pes(0xC0, adts, toPts(tsUs), false), null, false)

    companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
    }
}
