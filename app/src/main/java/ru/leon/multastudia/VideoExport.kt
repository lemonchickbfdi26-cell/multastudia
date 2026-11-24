package ru.leon.multastudia

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Экспорт в MP4 (H.264) без звука.
 * Кадры кодируются через MediaCodec, картинка переводится в YUV вручную —
 * так работает на всех телефонах начиная с Android 5.
 */
class VideoExport(private val p: Project, private val w: Int, private val h: Int) {

    companion object {
        private const val MIME = "video/avc"

        /** Подобрать размер видео: короткая сторона = target, но не больше, чем умеет телефон. */
        fun pickSize(p: Project, target: Int): Pair<Int, Int> {
            val caps = try {
                MediaCodec.createEncoderByType(MIME).let { c ->
                    val vc = c.codecInfo.getCapabilitiesForType(MIME).videoCapabilities; c.release(); vc
                }
            } catch (e: Throwable) { null }
            var t = target
            while (true) {
                val sc = t.toFloat() / minOf(p.canvasW, p.canvasH)
                val vw = ((p.canvasW * sc).toInt() / 2) * 2
                val vh = ((p.canvasH * sc).toInt() / 2) * 2
                if (caps == null || caps.isSizeSupported(vw, vh) || t <= 240) return vw to vh
                t = when { t > 1080 -> 1080; t > 720 -> 720; t > 480 -> 480; else -> 240 }
            }
        }
    }

    fun run(out: File, progress: (String) -> Unit) {
        val fps = p.fps.coerceIn(1, 60)
        val codec = MediaCodec.createEncoderByType(MIME)
        val fmt = MediaFormat.createVideoFormat(MIME, w, h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        var bitrate = (w.toLong() * h * fps * 0.35).toInt().coerceIn(4_000_000, 40_000_000)
        try {
            val range = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities.bitrateRange
            bitrate = bitrate.coerceIn(range.lower, range.upper)
        } catch (_: Throwable) {}
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0 && muxing && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        }

        val yuv = Yuv(w, h)
        val scale = w.toFloat() / p.canvasW
        var n = 0L
        try {
            p.frames.forEachIndexed { i, f ->
                progress("Кадр ${i + 1} из ${p.frames.size}")
                val bmp = p.compose(f, scale)
                val frame = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
                yuv.load(frame)
                repeat(f.hold) {
                    var inIdx: Int
                    while (true) {
                        inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) break
                        drain(false)
                    }
                    val img = codec.getInputImage(inIdx)
                    val size = if (img != null) yuv.writeImage(img) else yuv.writeBuffer(codec.getInputBuffer(inIdx)!!)
                    codec.queueInputBuffer(inIdx, 0, size, n * 1_000_000L / fps, 0)
                    n++
                    drain(false)
                }
            }
            // конец
            var inIdx: Int
            while (true) { inIdx = codec.dequeueInputBuffer(10_000); if (inIdx >= 0) break; drain(false) }
            codec.queueInputBuffer(inIdx, 0, 0, n * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            try { if (muxing) muxer.stop() } catch (_: Throwable) {}
            muxer.release()
        }
    }

    /** Перевод ARGB → YUV 4:2:0 (BT.601). */
    private class Yuv(val w: Int, val h: Int) {
        val px = IntArray(w * h)
        val y = ByteArray(w * h)
        val u = ByteArray((w / 2) * (h / 2))
        val v = ByteArray((w / 2) * (h / 2))

        fun load(b: Bitmap) {
            b.getPixels(px, 0, w, 0, 0, w, h)
            for (i in 0 until w * h) {
                val c = px[i]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
                y[i] = (((66 * r + 129 * g + 25 * bl + 128) shr 8) + 16).toByte()
            }
            val cw = w / 2
            for (cy in 0 until h / 2) for (cx in 0 until cw) {
                val i0 = (cy * 2) * w + cx * 2
                var r = 0; var g = 0; var bl = 0
                for (k in intArrayOf(i0, i0 + 1, i0 + w, i0 + w + 1)) {
                    val c = px[k]; r += (c shr 16) and 255; g += (c shr 8) and 255; bl += c and 255
                }
                r /= 4; g /= 4; bl /= 4
                u[cy * cw + cx] = (((-38 * r - 74 * g + 112 * bl + 128) shr 8) + 128).toByte()
                v[cy * cw + cx] = (((112 * r - 94 * g - 18 * bl + 128) shr 8) + 128).toByte()
            }
        }

        fun writeImage(img: android.media.Image): Int {
            val pl = img.planes
            // Y
            val yb = pl[0].buffer; val yrs = pl[0].rowStride; val yps = pl[0].pixelStride
            for (row in 0 until h) {
                if (yps == 1) { yb.position(row * yrs); yb.put(y, row * w, w) }
                else for (col in 0 until w) yb.put(row * yrs + col * yps, y[row * w + col])
            }
            val cw = w / 2; val ch = h / 2
            for ((plane, src) in listOf(pl[1] to u, pl[2] to v)) {
                val b = plane.buffer; val rs = plane.rowStride; val ps = plane.pixelStride
                for (row in 0 until ch) {
                    if (ps == 1) { b.position(row * rs); b.put(src, row * cw, cw) }
                    else for (col in 0 until cw) b.put(row * rs + col * ps, src[row * cw + col])
                }
            }
            return w * h * 3 / 2
        }

        /** Запасной путь: буфер в формате I420. */
        fun writeBuffer(buf: java.nio.ByteBuffer): Int {
            buf.clear()
            buf.put(y); buf.put(u); buf.put(v)
            return w * h * 3 / 2
        }
    }
}
