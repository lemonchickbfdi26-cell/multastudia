package ru.leon.multastudia

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Простой кодировщик GIF: общая палитра 6×7×6 + 4 серых, без дизеринга.
 * Для мультиков с линиями и заливками выглядит нормально.
 */
class GifEncoder(private val out: OutputStream, private val w: Int, private val h: Int) {
    private val palette = IntArray(256)

    init {
        var i = 0
        for (r in 0 until 6) for (g in 0 until 7) for (b in 0 until 6) {
            palette[i++] = ((r * 255 / 5) shl 16) or ((g * 255 / 6) shl 8) or (b * 255 / 5)
        }
        while (i < 256) { val v = (i - 252) * 64 + 32; palette[i++] = (v shl 16) or (v shl 8) or v }
        // заголовок
        out.write("GIF89a".toByteArray())
        short(w); short(h)
        out.write(0xF7) // глобальная палитра 256 цветов
        out.write(0); out.write(0)
        for (c in palette) { out.write((c shr 16) and 255); out.write((c shr 8) and 255); out.write(c and 255) }
        // бесконечный повтор
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun short(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }

    private fun index(c: Int): Int {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        if (r == g && g == b) {
            // чистый серый: выбираем ближайший из куба или серых
        }
        val ri = (r * 5 + 127) / 255; val gi = (g * 6 + 127) / 255; val bi = (b * 5 + 127) / 255
        return ri * 42 + gi * 6 + bi
    }

    fun addFrame(bmp: Bitmap, delayCs: Int) {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val idx = ByteArray(w * h)
        for (i in px.indices) idx[i] = index(px[i]).toByte()
        // управление графикой: задержка
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
        short(delayCs)
        out.write(0); out.write(0)
        // дескриптор
        out.write(0x2C); short(0); short(0); short(w); short(h); out.write(0)
        lzw(idx)
    }

    fun finish() { out.write(0x3B); out.flush() }

    private fun lzw(data: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 1 shl minCode
        val eoi = clear + 1
        val block = ByteArray(255)
        var blockLen = 0
        var bitBuf = 0
        var bitCnt = 0
        fun flushBlock() {
            if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0 }
        }
        fun emit(code: Int, size: Int) {
            bitBuf = bitBuf or (code shl bitCnt)
            bitCnt += size
            while (bitCnt >= 8) {
                block[blockLen++] = (bitBuf and 255).toByte()
                if (blockLen == 255) flushBlock()
                bitBuf = bitBuf ushr 8
                bitCnt -= 8
            }
        }
        val table = HashMap<Int, Int>(8192)
        var codeSize = minCode + 1
        var next = eoi + 1
        emit(clear, codeSize)
        var prefix = data[0].toInt() and 255
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 255
            val key = (prefix shl 8) or k
            val found = table[key]
            if (found != null) { prefix = found; continue }
            emit(prefix, codeSize)
            if (next < 4096) {
                table[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear, codeSize)
                table.clear(); codeSize = minCode + 1; next = eoi + 1
            }
            prefix = k
        }
        emit(prefix, codeSize)
        emit(eoi, codeSize)
        if (bitCnt > 0) { block[blockLen++] = (bitBuf and 255).toByte(); if (blockLen == 255) flushBlock() }
        flushBlock()
        out.write(0)
    }
}
