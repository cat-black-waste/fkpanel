package com.fk.panel

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * 从 /stream.h264 拉 annexb H.264，用 MediaCodec 硬解到 Surface。
 * 不依赖任何第三方库。
 */
class VideoDecoder(
    private val surface: Surface,
    private val onSize: (Int, Int) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var codec: MediaCodec? = null
    private var conn: HttpURLConnection? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var width = 540
    private var height = 1200
    private var configured = false
    private var ptsUs = 0L

    fun start(url: String, auth: String) {
        stop()
        running = true
        thread = Thread({ loop(url, auth) }, "fk-video").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { conn?.disconnect() }
        thread?.interrupt()
        thread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        configured = false
    }

    private fun loop(url: String, auth: String) {
        while (running) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 8000
                c.readTimeout = 20000
                c.setRequestProperty("Authorization", auth)
                c.inputStream.use { readLoop(it) }
            } catch (_: Exception) {
            } finally {
                runCatching { conn?.disconnect() }
                sps = null; pps = null; configured = false
            }
            if (!running) break
            Thread.sleep(400)
        }
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(64 * 1024)
        var acc = ByteArray(512 * 1024)
        var accLen = 0
        while (running) {
            val n = input.read(buf)
            if (n <= 0) break
            if (accLen + n > acc.size) {
                val bigger = ByteArray(acc.size * 2)
                System.arraycopy(acc, 0, bigger, 0, accLen)
                acc = bigger
            }
            System.arraycopy(buf, 0, acc, accLen, n)
            accLen += n

            var start = findStart(acc, 0, accLen)
            while (start >= 0 && running) {
                val next = findStart(acc, start + 3, accLen)
                if (next < 0) break
                var end = next
                while (end > start && acc[end - 1] == 0.toByte()) end--
                handleNal(acc, start, end)
                start = next
            }
            if (start > 0) {
                System.arraycopy(acc, start, acc, 0, accLen - start)
                accLen -= start
            }
        }
    }

    private fun findStart(b: ByteArray, from: Int, len: Int): Int {
        var i = from
        while (i + 2 < len) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) return i
            i++
        }
        return -1
    }

    private fun handleNal(b: ByteArray, start: Int, end: Int) {
        if (end - start < 4) return
        when (b[start + 3].toInt() and 0x1F) {
            7 -> { sps = b.copyOfRange(start, end); parseSps(sps!!); tryConfigure() }
            8 -> { pps = b.copyOfRange(start, end); tryConfigure() }
            1, 5 -> { tryConfigure(); feed(b, start, end, (b[start + 3].toInt() and 0x1F) == 5) }
        }
    }

    private fun tryConfigure() {
        if (configured || sps == null || pps == null) return
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            val c = MediaCodec.createDecoderByType("video/avc")
            c.configure(fmt, surface, null, 0)
            c.start()
            codec = c
            configured = true
            onSize(width, height)
        } catch (_: Exception) {
            configured = false
        }
    }

    private val info = MediaCodec.BufferInfo()

    private fun feed(b: ByteArray, start: Int, end: Int, key: Boolean) {
        val c = codec ?: return
        if (!configured) return
        try {
            val idx = c.dequeueInputBuffer(8000)
            if (idx < 0) return
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            var len = end - start
            if (len > ib.capacity()) len = ib.capacity()
            ib.put(b, start, len)
            c.queueInputBuffer(idx, 0, len, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            ptsUs += 33333
            while (true) {
                val o = c.dequeueOutputBuffer(info, 0)
                if (o >= 0) c.releaseOutputBuffer(o, info.size != 0)
                else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    width = f.getInteger("width"); height = f.getInteger("height")
                    onSize(width, height)
                } else break
            }
        } catch (_: Exception) {}
    }

    /* 极简 SPS 解析，只为拿分辨率 */
    private fun parseSps(sps: ByteArray) {
        try {
            val br = BitReader(sps, 4)
            val profile = br.u(8); br.u(8); br.u(8); br.ue()
            if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)) {
                val chroma = br.ue()
                if (chroma == 3) br.u(1)
                br.ue(); br.ue(); br.u(1)
                if (br.u(1) == 1) {
                    val cnt = if (chroma != 3) 8 else 12
                    for (k in 0 until cnt) if (br.u(1) == 1) skipScaling(br, if (k < 6) 16 else 64)
                }
            }
            br.ue()
            val poc = br.ue()
            if (poc == 0) br.ue() else if (poc == 1) {
                br.u(1); br.se(); br.se()
                val k = br.ue(); for (j in 0 until k) br.se()
            }
            br.ue(); br.u(1)
            val wMbs = br.ue() + 1
            val hMap = br.ue() + 1
            val frameMbsOnly = br.u(1)
            if (frameMbsOnly == 0) br.u(1)
            br.u(1)
            var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
            if (br.u(1) == 1) { cropL = br.ue(); cropR = br.ue(); cropT = br.ue(); cropB = br.ue() }
            val w = wMbs * 16 - (cropL + cropR) * 2
            val h = (2 - frameMbsOnly) * hMap * 16 - (cropT + cropB) * 2 * (2 - frameMbsOnly)
            if (w > 0 && h > 0) { width = w; height = h }
        } catch (_: Exception) {}
    }

    private fun skipScaling(br: BitReader, size: Int) {
        var last = 8; var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + br.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    private class BitReader(private val d: ByteArray, byteOffset: Int) {
        private var pos = byteOffset * 8
        fun u(bits: Int): Int {
            var v = 0
            for (i in 0 until bits) {
                val bi = pos shr 3
                val bit = if (bi < d.size) (d[bi].toInt() shr (7 - (pos and 7))) and 1 else 0
                v = (v shl 1) or bit
                pos++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (pos < d.size * 8 && u(1) == 0) { zeros++; if (zeros > 31) return 0 }
            return if (zeros == 0) 0 else (1 shl zeros) - 1 + u(zeros)
        }
        fun se(): Int { val k = ue(); val sign = if (k and 1 == 0) -1 else 1; return sign * ((k + 1) / 2) }
    }
}
