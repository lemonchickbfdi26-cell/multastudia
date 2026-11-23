package ru.leon.multastudia

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Плавающее выделение (лассо / вставленная картинка).
 * Положение задаётся четырьмя углами в координатах мира: ЛВ, ПВ, ПН, ЛН.
 * Так одним способом получаются и обычная трансформация, и перспектива.
 */
class FloatSelection(val bmp: Bitmap, val quad: FloatArray, var cut: Rect?) {
    var perspective = false
    var pivotX = 0f
    var pivotY = 0f
    val matrix = Matrix()
    private val src = floatArrayOf(0f, 0f, bmp.width.toFloat(), 0f, bmp.width.toFloat(), bmp.height.toFloat(), 0f, bmp.height.toFloat())

    init { centerPivot(); update() }

    fun update() { matrix.setPolyToPoly(src, 0, quad, 0, 4) }

    fun centerPivot() {
        pivotX = (quad[0] + quad[2] + quad[4] + quad[6]) / 4
        pivotY = (quad[1] + quad[3] + quad[5] + quad[7]) / 4
    }

    fun bounds(): Rect {
        val r = RectF(quad[0], quad[1], quad[0], quad[1])
        for (i in 1..3) r.union(quad[i * 2], quad[i * 2 + 1])
        return Rect(r.left.toInt() - 3, r.top.toInt() - 3, r.right.toInt() + 4, r.bottom.toInt() + 4)
    }

    fun contains(wx: Float, wy: Float, pad: Float): Boolean {
        val inv = Matrix()
        if (!matrix.invert(inv)) return false
        val a = floatArrayOf(wx, wy); inv.mapPoints(a)
        val sx = hypot(quad[2] - quad[0], quad[3] - quad[1]) / bmp.width
        val p = pad / maxOf(0.01f, sx)
        return a[0] >= -p && a[1] >= -p && a[0] <= bmp.width + p && a[1] <= bmp.height + p
    }

    /** Отражение по X (слева направо) — меняем местами углы. */
    fun flipX() { swap(0, 1); swap(3, 2); update() }
    fun flipY() { swap(0, 3); swap(1, 2); update() }

    private fun swap(a: Int, b: Int) {
        val x = quad[a * 2]; val y = quad[a * 2 + 1]
        quad[a * 2] = quad[b * 2]; quad[a * 2 + 1] = quad[b * 2 + 1]
        quad[b * 2] = x; quad[b * 2 + 1] = y
    }

    fun copyQuad() = quad.copyOf()

    // -------- операции от исходного состояния (start*) --------

    fun moveFrom(start: FloatArray, spx: Float, spy: Float, dx: Float, dy: Float) {
        for (i in 0..3) { quad[i * 2] = start[i * 2] + dx; quad[i * 2 + 1] = start[i * 2 + 1] + dy }
        pivotX = spx + dx; pivotY = spy + dy
        update()
    }

    fun rotateFrom(start: FloatArray, sTx: Float, sTy: Float, tx: Float, ty: Float) {
        val a0 = atan2(sTy - pivotY, sTx - pivotX)
        val a1 = atan2(ty - pivotY, tx - pivotX)
        val d = a1 - a0
        val c = cos(d); val s = sin(d)
        for (i in 0..3) {
            val x = start[i * 2] - pivotX; val y = start[i * 2 + 1] - pivotY
            quad[i * 2] = pivotX + x * c - y * s
            quad[i * 2 + 1] = pivotY + x * s + y * c
        }
        update()
    }

    fun matrixFrom(start: FloatArray, spx: Float, spy: Float, m: Matrix) {
        val pts = start.copyOf(); m.mapPoints(pts)
        System.arraycopy(pts, 0, quad, 0, 8)
        val pv = floatArrayOf(spx, spy); m.mapPoints(pv)
        pivotX = pv[0]; pivotY = pv[1]
        update()
    }

    /** Тянем угол i. В перспективе угол двигается свободно. */
    fun dragCorner(i: Int, start: FloatArray, spx: Float, spy: Float, dx: Float, dy: Float) {
        if (perspective) {
            System.arraycopy(start, 0, quad, 0, 8)
            quad[i * 2] += dx; quad[i * 2 + 1] += dy
            update(); return
        }
        val o = (i + 2) % 4
        val ox = start[o * 2]; val oy = start[o * 2 + 1]
        val ax = start[i * 2]; val ay = start[i * 2 + 1]
        scaleAxes(start, spx, spy, ox, oy, ax, ay, ax + dx, ay + dy, useU = true, useV = true)
    }

    /** Тянем сторону j: 0 верх, 1 право, 2 низ, 3 лево. */
    fun dragSide(j: Int, start: FloatArray, spx: Float, spy: Float, dx: Float, dy: Float) {
        val a = j; val b = (j + 1) % 4
        if (perspective) {
            System.arraycopy(start, 0, quad, 0, 8)
            quad[a * 2] += dx; quad[a * 2 + 1] += dy
            quad[b * 2] += dx; quad[b * 2 + 1] += dy
            update(); return
        }
        val oa = (j + 2) % 4; val ob = (j + 3) % 4
        val ox = (start[oa * 2] + start[ob * 2]) / 2; val oy = (start[oa * 2 + 1] + start[ob * 2 + 1]) / 2
        val mx = (start[a * 2] + start[b * 2]) / 2; val my = (start[a * 2 + 1] + start[b * 2 + 1]) / 2
        val vertical = j == 0 || j == 2
        scaleAxes(start, spx, spy, ox, oy, mx, my, mx + dx, my + dy, useU = !vertical, useV = vertical)
    }

    private fun scaleAxes(
        start: FloatArray, spx: Float, spy: Float, ox: Float, oy: Float,
        fromX: Float, fromY: Float, toX: Float, toY: Float, useU: Boolean, useV: Boolean
    ) {
        var ux = start[2] - start[0]; var uy = start[3] - start[1]
        val ul = maxOf(1e-3f, hypot(ux, uy)); ux /= ul; uy /= ul
        var vx = start[6] - start[0]; var vy = start[7] - start[1]
        val vl = maxOf(1e-3f, hypot(vx, vy)); vx /= vl; vy /= vl
        fun ratio(ax: Float, ay: Float): Float {
            val d0 = (fromX - ox) * ax + (fromY - oy) * ay
            val d1 = (toX - ox) * ax + (toY - oy) * ay
            if (Math.abs(d0) < 1e-3f) return 1f
            val r = d1 / d0
            if (Math.abs(r) < 0.02f) return if (r < 0f) -0.02f else 0.02f
            return r
        }
        val sx = if (useU) ratio(ux, uy) else 1f
        val sy = if (useV) ratio(vx, vy) else 1f
        fun map(px: Float, py: Float): Pair<Float, Float> {
            val dx = px - ox; val dy = py - oy
            val a = dx * ux + dy * uy
            val b = dx * vx + dy * vy
            // остаток (если оси не перпендикулярны) не трогаем
            val rx = dx - a * ux - b * vx; val ry = dy - a * uy - b * vy
            return Pair(ox + ux * a * sx + vx * b * sy + rx, oy + uy * a * sx + vy * b * sy + ry)
        }
        for (i in 0..3) {
            val (nx, ny) = map(start[i * 2], start[i * 2 + 1])
            quad[i * 2] = nx; quad[i * 2 + 1] = ny
        }
        val (px, py) = map(spx, spy)
        pivotX = px; pivotY = py
        update()
    }

    companion object {
        fun atRect(bmp: Bitmap, left: Float, top: Float, scale: Float, cut: Rect?): FloatSelection {
            val w = bmp.width * scale; val h = bmp.height * scale
            return FloatSelection(bmp, floatArrayOf(left, top, left + w, top, left + w, top + h, left, top + h), cut)
        }
    }
}
