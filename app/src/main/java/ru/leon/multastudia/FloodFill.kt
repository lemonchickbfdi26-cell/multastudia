package ru.leon.multastudia

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

object FloodFill {
    /**
     * Заливка на одном слое. Возвращает изменённую область или null.
     * tolerance 0..255. После заливки расширяем её на 1 пиксель под сглаженные края линий.
     */
    fun fill(bmp: Bitmap, sx: Int, sy: Int, color: Int, tolerance: Int): Rect? {
        val w = bmp.width; val h = bmp.height
        if (sx !in 0 until w || sy !in 0 until h) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val target = px[sy * w + sx]
        if (target == color) return null

        val ta = Color.alpha(target); val tr = Color.red(target); val tg = Color.green(target); val tb = Color.blue(target)
        fun match(c: Int): Boolean {
            val a = Color.alpha(c)
            if (ta == 0 && a == 0) return true
            val d = maxOf(
                Math.abs(a - ta),
                Math.abs(Color.red(c) - tr) * a / 255,
                Math.abs(Color.green(c) - tg) * a / 255,
                Math.abs(Color.blue(c) - tb) * a / 255
            )
            return d <= tolerance
        }

        val mask = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        stack[sp++] = sy * w + sx
        var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
        while (sp > 0) {
            val idx = stack[--sp]
            val y = idx / w
            var x = idx % w
            // идём влево
            while (x > 0 && !mask[y * w + x - 1] && match(px[y * w + x - 1])) x--
            var upOpen = false; var downOpen = false
            while (x < w) {
                val i = y * w + x
                if (mask[i] || !match(px[i])) break
                mask[i] = true
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (y > 0) {
                    val u = i - w
                    val ok = !mask[u] && match(px[u])
                    if (ok && !upOpen) { if (sp < stack.size) stack[sp++] = u; upOpen = true } else if (!ok) upOpen = false
                }
                if (y < h - 1) {
                    val d = i + w
                    val ok = !mask[d] && match(px[d])
                    if (ok && !downOpen) { if (sp < stack.size) stack[sp++] = d; downOpen = true } else if (!ok) downOpen = false
                }
                x++
            }
        }

        // закрашиваем + расширение на 1 пиксель (только под полупрозрачными/светлыми краями)
        val x0 = maxOf(0, minX - 1); val x1 = minOf(w - 1, maxX + 1)
        val y0 = maxOf(0, minY - 1); val y1 = minOf(h - 1, maxY + 1)
        val out = px.copyOf()
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * w + x
            if (mask[i]) { out[i] = color; continue }
            val near = (x > 0 && mask[i - 1]) || (x < w - 1 && mask[i + 1]) || (y > 0 && mask[i - w]) || (y < h - 1 && mask[i + w])
            if (near) {
                val c = px[i]
                // рисуем заливку ПОД краем: смешиваем край поверх цвета заливки
                out[i] = blendOver(c, color)
            }
        }
        val r = Rect(x0, y0, x1 + 1, y1 + 1)
        bmp.setPixels(out, y0 * w + x0, w, x0, y0, r.width(), r.height())
        return r
    }

    /** top поверх bottom (обычное смешивание). */
    private fun blendOver(top: Int, bottom: Int): Int {
        val ta = Color.alpha(top) / 255f
        val ba = Color.alpha(bottom) / 255f
        val oa = ta + ba * (1 - ta)
        if (oa <= 0f) return 0
        fun ch(t: Int, b: Int) = ((t * ta + b * ba * (1 - ta)) / oa).toInt().coerceIn(0, 255)
        return Color.argb((oa * 255).toInt(), ch(Color.red(top), Color.red(bottom)), ch(Color.green(top), Color.green(bottom)), ch(Color.blue(top), Color.blue(bottom)))
    }
}
