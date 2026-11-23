package ru.leon.multastudia

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

enum class Tool { BRUSH, ERASER, LASSO, FILL, TEXT, EYEDROP, LINE, RECT, OVAL, MOVE, CAMERA }

class BrushSettings {
    var size = 6f
    var opacity = 255
    var smoothing = 3
    var color = Color.BLACK
}

interface CanvasHost {
    fun currentLayer(): Layer
    fun currentFrame(): Frame
    fun frameIndex(): Int
    fun onEdited()
    fun onFloatChanged(has: Boolean)
    fun onColorPicked(color: Int, done: Boolean)
    fun requestText(x: Float, y: Float)
    fun onUndoJump(frameId: Int, layerId: Int)
    fun onFloatMenu()
    /** Ключ поставлен/изменён (движение объекта или камеры). */
    fun onKeysChanged(live: Boolean)
    fun defaultEase(): Ease
}

@SuppressLint("ViewConstructor")
class CanvasView(ctx: Context, private val p: Project, private val host: CanvasHost) : View(ctx) {

    // ---------- настройки ----------
    var tool = Tool.BRUSH
    val brush = BrushSettings()
    var eraserSize = 24f
    var fillTolerance = 40
    var textSize = 48f
    var onion = true
    var onionBefore = 1
    var onionAfter = 1
    var grid = false
    val undo = UndoManager()

    // ---------- вид (камера экрана) ----------
    private val view = Matrix()
    private val inv = Matrix()
    private var fitted = false

    // ---------- проигрывание ----------
    var playBitmap: Bitmap? = null
        set(v) { field = v; invalidate() }

    // ---------- состояние рисования ----------
    private var shadow: Bitmap? = null
    private var shadowKey = ""

    private val livePath = Path()
    private var stroking = false
    private var strokePoints = 0
    private var strokeStartTime = 0L
    private var sx = 0f; private var sy = 0f   // сглаженная точка
    private var lx = 0f; private var ly = 0f   // предыдущая точка пути
    private val strokeDirty = RectF()

    private var shapeActive = false
    private var shX0 = 0f; private var shY0 = 0f; private var shX1 = 0f; private var shY1 = 0f

    private val lassoPath = Path()
    private var lassoing = false

    // ---------- плавающее выделение ----------
    private var sel: FloatSelection? = null
    val hasFloat get() = sel != null
    var perspectiveMode = false
        set(v) { field = v; sel?.perspective = v; invalidate() }
    private var clipBmp: Bitmap? = null
    private var clipQuad: FloatArray? = null
    val hasClip get() = clipBmp != null

    // ручки выделения
    private val H_NONE = -1; private val H_MOVE = 0; private val H_ROTATE = 1; private val H_MENU = 2; private val H_PIVOT = 3
    private val H_CORNER = 10; private val H_SIDE = 20
    private var handle = H_NONE
    private var selStart = FloatArray(8)
    private var selPivX = 0f; private var selPivY = 0f
    private var selTx = 0f; private var selTy = 0f

    // ---------- жесты ----------
    private enum class Mode { NONE, DRAW, FLOAT_DRAG, MENU_TAP, XF_DRAG, TWO_VIEW, TWO_FLOAT, DONE }
    private var mode = Mode.NONE
    private val startM = Matrix()
    private var g0x = 0f; private var g0y = 0f; private var gd0 = 1f; private var ga0 = 0f
    private var dragLastX = 0f; private var dragLastY = 0f
    private var downX = 0f; private var downY = 0f

    // ---------- кисти ----------
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ui = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val onionPrev = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = LightingColorFilter(0xFFFFFF, 0xFF3A3A); xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val onionNext = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = LightingColorFilter(0xFFFFFF, 0x3A8CFF); xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val canvasRect get() = RectF(p.marginX.toFloat(), p.marginY.toFloat(), (p.marginX + p.canvasW).toFloat(), (p.marginY + p.canvasH).toFloat())

    // ================= вид =================

    fun fitView() {
        if (width == 0 || height == 0) return
        val s = minOf(width * 0.94f / p.canvasW, height * 0.86f / p.canvasH)
        view.reset()
        view.postTranslate(-(p.marginX + p.canvasW / 2f), -(p.marginY + p.canvasH / 2f))
        view.postScale(s, s)
        view.postTranslate(width / 2f, height / 2f)
        view.invert(inv)
        fitted = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!fitted || ow == 0) fitView()
    }

    private fun viewScale(): Float {
        val v = FloatArray(9); view.getValues(v)
        return sqrt(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_X] + v[Matrix.MSKEW_Y] * v[Matrix.MSKEW_Y])
    }

    private fun toWorld(x: Float, y: Float): FloatArray { val a = floatArrayOf(x, y); inv.mapPoints(a); return a }

    /** Матрица текущего слоя (для слоя-объекта — его положение на этом кадре). */
    private fun curLayerMatrix(): Matrix? {
        val l = host.currentLayer()
        if (!l.isObject) return null
        return p.objectXf(l, host.frameIndex()).matrix()
    }

    /** Экран → координаты рисунка текущего слоя. */
    private fun toLayer(x: Float, y: Float): FloatArray {
        val a = toWorld(x, y)
        val m = curLayerMatrix() ?: return a
        val im = Matrix(); if (m.invert(im)) im.mapPoints(a)
        return a
    }

    private fun cellFrameId(): Int = if (host.currentLayer().isObject) 0 else host.currentFrame().id

    // ================= рисование на экране =================

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF3A3A3A.toInt())
        val s = viewScale()
        c.save()
        c.concat(view)
        val cr = canvasRect

        // граница «мира» (где ещё можно рисовать за холстом)
        ui.pathEffect = DashPathEffect(floatArrayOf(8f / s, 8f / s), 0f)
        ui.strokeWidth = 1f / s; ui.color = 0x33FFFFFF
        c.drawRect(0f, 0f, p.worldW.toFloat(), p.worldH.toFloat(), ui)
        ui.pathEffect = null

        // тень и бумага
        fill.color = 0x55000000; c.drawRect(cr.left + 4f / s, cr.top + 6f / s, cr.right + 4f / s, cr.bottom + 6f / s, fill)
        fill.color = p.bgColor; c.drawRect(cr, fill)

        val pb = playBitmap
        if (pb != null) {
            c.drawBitmap(pb, null, cr, bmpPaint)
            c.restore()
            return
        }

        // калька
        if (onion) {
            val idx = host.frameIndex()
            for (i in onionBefore downTo 1) {
                val f = p.frames.getOrNull(idx - i) ?: continue
                onionPrev.alpha = (110 / i)
                c.drawBitmap(p.preview(f), null, cr, onionPrev)
            }
            for (i in onionAfter downTo 1) {
                val f = p.frames.getOrNull(idx + i) ?: continue
                onionNext.alpha = (110 / i)
                c.drawBitmap(p.preview(f), null, cr, onionNext)
            }
        }

        // слои
        val frame = host.currentFrame()
        val cur = host.currentLayer()
        val fi = host.frameIndex()
        for (l in p.layers) {
            if (!l.visible && l !== cur) continue
            val base = if (l.visible) l.opacity else 60
            if (l.isObject) {
                val xf = p.objectXf(l, fi)
                val m = xf.matrix()
                c.save(); c.concat(m)
                layerPaint.alpha = (base * xf.alpha).toInt().coerceIn(0, 255)
                p.cell(l.id, 0, false)?.let { c.drawBitmap(it, 0f, 0f, layerPaint) }
                if (l === cur) drawLive(c, s * maxOf(0.01f, Math.abs(xf.sx)))
                c.restore()
            } else {
                val bmp = p.cell(l.id, frame.id, false)
                layerPaint.alpha = base
                if (bmp != null) c.drawBitmap(bmp, 0f, 0f, layerPaint)
                if (l === cur) drawLive(c, s)
            }
        }
        drawCameraFrame(c, s)

        // сетка
        if (grid) {
            ui.strokeWidth = 1f / s; ui.color = 0x55FF4F7B
            for (i in 1..2) {
                val x = cr.left + cr.width() * i / 3; c.drawLine(x, cr.top, x, cr.bottom, ui)
                val y = cr.top + cr.height() * i / 3; c.drawLine(cr.left, y, cr.right, y, ui)
            }
        }
        // рамка холста
        ui.strokeWidth = 1.5f / s; ui.color = 0xFF777777.toInt()
        c.drawRect(cr, ui)
        c.restore()
        drawSelectionOverlay(c)
    }

    // ---------- рамка выделения (в координатах экрана) ----------
    private val hp = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun layerToScreen(): Matrix {
        val m = Matrix(curLayerMatrix() ?: Matrix())
        m.postConcat(view)
        return m
    }

    private fun screenQuad(sl: FloatSelection): FloatArray { val q = sl.quad.copyOf(); layerToScreen().mapPoints(q); return q }

    /** Точки ручек на экране: 0..3 углы, 4..7 середины сторон, 8 вращение, 9 меню, 10 центр. */
    private fun handlePoints(sl: FloatSelection): FloatArray {
        val pv = floatArrayOf(sl.pivotX, sl.pivotY); layerToScreen().mapPoints(pv)
        return handlePointsFor(screenQuad(sl), pv[0], pv[1])
    }

    private fun handlePointsFor(q: FloatArray, pvx: Float, pvy: Float): FloatArray {
        val out = FloatArray(22)
        System.arraycopy(q, 0, out, 0, 8)
        for (j in 0..3) { val b = (j + 1) % 4; out[8 + j * 2] = (q[j * 2] + q[b * 2]) / 2; out[9 + j * 2] = (q[j * 2 + 1] + q[b * 2 + 1]) / 2 }
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
        val off = dp(40f).toFloat()
        fun outward(mx: Float, my: Float, idx: Int) {
            var dx = mx - cx; var dy = my - cy; val l = maxOf(1f, hypot(dx, dy)); dx /= l; dy /= l
            out[idx] = mx + dx * off; out[idx + 1] = my + dy * off
        }
        outward(out[12], out[13], 16)   // под нижней стороной — вращение
        outward(out[8], out[9], 18)     // над верхней — меню
        out[20] = pvx; out[21] = pvy
        return out
    }

    private fun drawSelectionOverlay(c: Canvas) {
        val sl = sel
        if (sl != null) { drawHandles(c, handlePoints(sl), C.ACCENT, sides = true, menu = true); return }
        if (playBitmap != null) return
        if (tool == Tool.MOVE || tool == Tool.CAMERA) xfHandles()?.let { drawHandles(c, it, if (tool == Tool.CAMERA) CAM_COLOR else C.ACCENT, sides = tool == Tool.MOVE, menu = false) }
    }

    private fun drawHandles(c: Canvas, h: FloatArray, color: Int, sides: Boolean, menu: Boolean) {
        val r = dp(8f).toFloat()
        hp.style = Paint.Style.STROKE; hp.strokeWidth = dp(1.5f).toFloat(); hp.color = color
        val path = Path().apply { moveTo(h[0], h[1]); lineTo(h[2], h[3]); lineTo(h[4], h[5]); lineTo(h[6], h[7]); close() }
        c.drawPath(path, hp)
        fun knob(x: Float, y: Float, rad: Float) {
            hp.style = Paint.Style.FILL; hp.color = Color.WHITE; c.drawCircle(x, y, rad, hp)
            hp.style = Paint.Style.STROKE; hp.color = 0xFF999999.toInt(); hp.strokeWidth = dp(1f).toFloat(); c.drawCircle(x, y, rad, hp)
        }
        // стороны — «таблетки» вдоль стороны
        if (sides) for (j in 0..3) {
            val b = (j + 1) % 4
            val ang = Math.toDegrees(atan2((h[b * 2 + 1] - h[j * 2 + 1]).toDouble(), (h[b * 2] - h[j * 2]).toDouble())).toFloat()
            val mx = h[8 + j * 2]; val my = h[9 + j * 2]
            c.save(); c.rotate(ang, mx, my)
            val rr = RectF(mx - dp(13f), my - dp(5f), mx + dp(13f), my + dp(5f))
            hp.style = Paint.Style.FILL; hp.color = Color.WHITE; c.drawRoundRect(rr, dp(5f).toFloat(), dp(5f).toFloat(), hp)
            hp.style = Paint.Style.STROKE; hp.color = 0xFF999999.toInt(); c.drawRoundRect(rr, dp(5f).toFloat(), dp(5f).toFloat(), hp)
            c.restore()
        }
        for (i in 0..3) knob(h[i * 2], h[i * 2 + 1], r)
        // центр вращения
        hp.style = Paint.Style.STROKE; hp.color = color; hp.strokeWidth = dp(2f).toFloat()
        c.drawCircle(h[20], h[21], r, hp)
        // кнопка вращения
        val big = dp(17f).toFloat()
        knob(h[16], h[17], big)
        hp.style = Paint.Style.STROKE; hp.color = 0xFF444444.toInt(); hp.strokeWidth = dp(1.8f).toFloat()
        val ar = RectF(h[16] - big / 2, h[17] - big / 2, h[16] + big / 2, h[17] + big / 2)
        c.drawArc(ar, 200f, 120f, false, hp); c.drawArc(ar, 20f, 120f, false, hp)
        // кнопка «…»
        if (!menu) return
        knob(h[18], h[19], big)
        hp.style = Paint.Style.FILL; hp.color = 0xFF444444.toInt()
        for (k in -1..1) c.drawCircle(h[18] + k * dp(6f), h[19], dp(1.8f).toFloat(), hp)
    }

    private fun hitHandle(sx: Float, sy: Float): Int {
        val sl = sel ?: return H_NONE
        val h = handlePoints(sl)
        val rad = dp(24f).toFloat()
        fun near(i: Int) = hypot(sx - h[i], sy - h[i + 1]) < rad
        if (near(18)) return H_MENU
        if (near(16)) return H_ROTATE
        for (i in 0..3) if (near(i * 2)) return H_CORNER + i
        for (j in 0..3) if (near(8 + j * 2)) return H_SIDE + j
        if (hypot(sx - h[20], sy - h[21]) < dp(14f)) return H_PIVOT
        val w = toLayer(sx, sy)
        if (sl.contains(w[0], w[1], dp(6f) / viewScale())) return H_MOVE
        return H_NONE
    }

    private fun drawLive(c: Canvas, s: Float) {
        if (stroking && tool == Tool.BRUSH) c.drawPath(livePath, brushPaint)
        if (shapeActive) c.drawPath(shapePath(), brushPaint)
        sel?.let { c.drawBitmap(it.bmp, it.matrix, bmpPaint) }
        if (lassoing) {
            ui.strokeWidth = 2f / s; ui.color = C.PURPLE
            ui.pathEffect = DashPathEffect(floatArrayOf(10f / s, 6f / s), 0f)
            c.drawPath(lassoPath, ui); ui.pathEffect = null
        }
    }

    private fun shapePath(): Path {
        val path = Path()
        when (tool) {
            Tool.LINE -> { path.moveTo(shX0, shY0); path.lineTo(shX1, shY1) }
            Tool.RECT -> path.addRect(minOf(shX0, shX1), minOf(shY0, shY1), maxOf(shX0, shX1), maxOf(shY0, shY1), Path.Direction.CW)
            Tool.OVAL -> path.addOval(RectF(minOf(shX0, shX1), minOf(shY0, shY1), maxOf(shX0, shX1), maxOf(shY0, shY1)), Path.Direction.CW)
            else -> {}
        }
        return path
    }

    // ================= правки и отмена =================

    private fun currentCell(): Bitmap {
        val l = host.currentLayer(); val fid = cellFrameId()
        val cell = p.cell(l.id, fid, true)!!
        val key = "${l.id}_$fid"
        if (shadow == null || shadowKey != key) {
            shadow = cell.copy(Bitmap.Config.ARGB_8888, true)
            shadowKey = key
        }
        return cell
    }

    fun dropShadow() { shadow = null; shadowKey = "" }

    private fun commitEdit(r0: Rect) {
        val cell = currentCell()
        val sh = shadow ?: return
        val r = Rect(r0)
        if (!r.intersect(0, 0, cell.width, cell.height) || r.isEmpty) return
        val before = UndoManager.cut(sh, r)
        val after = UndoManager.cut(cell, r)
        undo.push(UndoEntry(host.currentLayer().id, cellFrameId(), r, before, after))
        UndoManager.paste(sh, after, r)
        p.markDirty(host.currentLayer().id, cellFrameId())
        host.onEdited()
        invalidate()
    }

    fun doUndo() { commitFloat(); apply(undo.popUndo() ?: return, true) }
    fun doRedo() { commitFloat(); apply(undo.popRedo() ?: return, false) }

    private fun apply(e: UndoEntry, back: Boolean) {
        val cell = p.cell(e.layerId, e.frameId, true)!!
        UndoManager.paste(cell, if (back) e.before else e.after, e.rect)
        p.markDirty(e.layerId, e.frameId)
        dropShadow()
        host.onUndoJump(e.frameId, e.layerId)
        host.onEdited()
        invalidate()
    }

    // ================= выделение =================

    private fun setSel(n: FloatSelection?) {
        sel = n
        n?.perspective = perspectiveMode
        host.onFloatChanged(n != null)
        invalidate()
    }

    fun startFloat(bmp: Bitmap) {
        commitFloat()
        currentCell()
        val cr = canvasRect
        val sc = minOf(1f, cr.width() * 0.8f / bmp.width, cr.height() * 0.8f / bmp.height)
        setSel(FloatSelection.atRect(bmp, cr.centerX() - bmp.width * sc / 2, cr.centerY() - bmp.height * sc / 2, sc, null))
    }

    /** Впечатать выделение в слой. */
    fun commitFloat() {
        val sl = sel ?: return
        val cell = currentCell()
        Canvas(cell).drawBitmap(sl.bmp, sl.matrix, bmpPaint)
        val r = sl.bounds()
        sl.cut?.let { r.union(it) }
        sel = null
        commitEdit(r)
        host.onFloatChanged(false)
        invalidate()
    }

    fun deleteFloat() {
        val sl = sel ?: return
        sel = null
        sl.cut?.let { commitEdit(it) }
        host.onFloatChanged(false)
        invalidate()
    }

    /** Превратить выделение в рисунок для нового слоя-объекта (размером с мир). */
    fun floatToObject(): Bitmap? {
        val sl = sel ?: return null
        val world = Bitmap.createBitmap(p.worldW, p.worldH, Bitmap.Config.ARGB_8888)
        val m = Matrix(sl.matrix)
        curLayerMatrix()?.let { m.postConcat(it) }
        Canvas(world).drawBitmap(sl.bmp, m, bmpPaint)
        sel = null
        sl.cut?.let { commitEdit(it) }
        host.onFloatChanged(false)
        invalidate()
        return world
    }

    fun flipFloatX() { sel?.flipX(); invalidate() }
    fun flipFloatY() { sel?.flipY(); invalidate() }

    /** Копировать выделение (картинку и где она стоит). */
    fun copyFloat(): Boolean {
        val sl = sel ?: return false
        clipBmp = sl.bmp.copy(Bitmap.Config.ARGB_8888, false)
        clipQuad = sl.copyQuad()
        return true
    }

    /** Вставить скопированное на текущий кадр, туда же, где оно было. */
    fun pasteFloat(): Boolean {
        val b = clipBmp ?: return false
        val q = clipQuad ?: return false
        commitFloat()
        currentCell()
        setSel(FloatSelection(b.copy(Bitmap.Config.ARGB_8888, false), q.copyOf(), null))
        return true
    }

    private fun finishLasso() {
        lassoing = false
        lassoPath.close()
        val b = RectF(); lassoPath.computeBounds(b, true)
        val r = Rect(b.left.toInt() - 1, b.top.toInt() - 1, b.right.toInt() + 2, b.bottom.toInt() + 2)
        if (!r.intersect(0, 0, p.worldW, p.worldH) || r.width() < 4 || r.height() < 4) { invalidate(); return }
        val cell = currentCell()
        val fb = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        val fc = Canvas(fb)
        fc.translate(-r.left.toFloat(), -r.top.toFloat())
        fc.clipPath(lassoPath)
        fc.drawBitmap(cell, 0f, 0f, null)
        val cc = Canvas(cell)
        cc.save(); cc.clipPath(lassoPath); cc.drawColor(0, PorterDuff.Mode.CLEAR); cc.restore()
        // подрезаем пустые края, чтобы рамка была по рисунку
        val tight = opaqueBounds(fb)
        if (tight == null) { UndoManager.paste(cell, UndoManager.cut(shadow!!, r), r); invalidate(); return }
        val part = Bitmap.createBitmap(fb, tight.left, tight.top, tight.width(), tight.height())
        setSel(FloatSelection.atRect(part, (r.left + tight.left).toFloat(), (r.top + tight.top).toFloat(), 1f, r))
    }

    private fun opaqueBounds(b: Bitmap): Rect? {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var rr = -1; var bb = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 8) {
            if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > bb) bb = y
        }
        if (rr < 0) return null
        return Rect(l, t, rr + 1, bb + 1)
    }

    // ================= движение объекта и камеры =================

    private val CAM_COLOR = 0xFFFFC107.toInt()
    private var xfStart: Xf? = null
    private var xfHandle = H_NONE
    private var xfTx = 0f; private var xfTy = 0f

    /** Что сейчас двигаем: дорожка, рамка рисунка и положение «по умолчанию». */
    private fun xfTarget(): Triple<Track, RectF, Xf>? {
        val fi = host.frameIndex()
        if (tool == Tool.CAMERA) return Triple(p.camera, canvasRect, p.cameraDefault())
        val l = host.currentLayer()
        if (!l.isObject) return null
        return Triple(l.track, RectF(p.objectBounds(l)), p.objectDefault(l))
    }

    private fun currentXf(): Xf? {
        val (track, _, def) = xfTarget() ?: return null
        return track.eval(p.frames, host.frameIndex(), def)
    }

    private fun xfHandles(): FloatArray? {
        val (_, rect, _) = xfTarget() ?: return null
        val xf = currentXf() ?: return null
        val m = xf.matrix(); m.postConcat(view)
        val q = floatArrayOf(rect.left, rect.top, rect.right, rect.top, rect.right, rect.bottom, rect.left, rect.bottom)
        m.mapPoints(q)
        val pv = floatArrayOf(xf.x, xf.y); view.mapPoints(pv)
        return handlePointsFor(q, pv[0], pv[1])
    }

    private fun drawCameraFrame(c: Canvas, s: Float) {
        if (tool == Tool.CAMERA || p.camera.isEmpty) return
        val xf = p.cameraXf(host.frameIndex())
        val cr = canvasRect
        val q = floatArrayOf(cr.left, cr.top, cr.right, cr.top, cr.right, cr.bottom, cr.left, cr.bottom)
        xf.matrix().mapPoints(q)
        val path = Path().apply { moveTo(q[0], q[1]); lineTo(q[2], q[3]); lineTo(q[4], q[5]); lineTo(q[6], q[7]); close() }
        ui.strokeWidth = 2f / s; ui.color = CAM_COLOR
        ui.pathEffect = DashPathEffect(floatArrayOf(12f / s, 8f / s), 0f)
        c.drawPath(path, ui); ui.pathEffect = null
    }

    private fun beginXf(sx: Float, sy: Float): Boolean {
        val h = xfHandles() ?: return false
        val xf = currentXf() ?: return false
        val rad = dp(24f).toFloat()
        fun near(i: Int) = hypot(sx - h[i], sy - h[i + 1]) < rad
        var hnd = H_NONE
        if (near(16)) hnd = H_ROTATE
        if (hnd == H_NONE) for (i in 0..3) if (near(i * 2)) { hnd = H_CORNER + i; break }
        if (hnd == H_NONE && tool == Tool.MOVE) for (j in 0..3) if (near(8 + j * 2)) { hnd = H_SIDE + j; break }
        if (hnd == H_NONE && hypot(sx - h[20], sy - h[21]) < dp(16f)) hnd = H_PIVOT
        if (hnd == H_NONE) {
            // внутри рамки — двигаем; снаружи для объекта тоже двигаем (удобно хватать мелкие объекты)
            hnd = H_MOVE
        }
        xfHandle = hnd
        xfStart = xf.copy()
        val w = toWorld(sx, sy); xfTx = w[0]; xfTy = w[1]
        return true
    }

    private fun moveXf(wx: Float, wy: Float) {
        val st = xfStart ?: return
        val (track, _, _) = xfTarget() ?: return
        val n = st.copy()
        val dx = wx - xfTx; val dy = wy - xfTy
        when {
            xfHandle == H_MOVE -> { n.x = st.x + dx; n.y = st.y + dy }
            xfHandle == H_ROTATE -> {
                val a0 = Math.toDegrees(atan2((xfTy - st.y).toDouble(), (xfTx - st.x).toDouble()))
                val a1 = Math.toDegrees(atan2((wy - st.y).toDouble(), (wx - st.x).toDouble()))
                n.rot = st.rot + (a1 - a0).toFloat()
            }
            xfHandle in H_CORNER until H_CORNER + 4 -> {
                val d0 = maxOf(1f, hypot(xfTx - st.x, xfTy - st.y))
                val k = (hypot(wx - st.x, wy - st.y) / d0).coerceAtLeast(0.02f)
                n.sx = st.sx * k; n.sy = st.sy * k
            }
            xfHandle in H_SIDE until H_SIDE + 4 -> {
                val j = xfHandle - H_SIDE
                val rad = Math.toRadians(st.rot.toDouble())
                val ax: Float; val ay: Float
                if (j == 1 || j == 3) { ax = Math.cos(rad).toFloat(); ay = Math.sin(rad).toFloat() }
                else { ax = -Math.sin(rad).toFloat(); ay = Math.cos(rad).toFloat() }
                val d0 = (xfTx - st.x) * ax + (xfTy - st.y) * ay
                val d1 = (wx - st.x) * ax + (wy - st.y) * ay
                if (Math.abs(d0) > 1f) {
                    var k = d1 / d0
                    if (Math.abs(k) < 0.02f) k = if (k < 0) -0.02f else 0.02f
                    if (j == 1 || j == 3) n.sx = st.sx * k else n.sy = st.sy * k
                }
            }
            xfHandle == H_PIVOT -> {
                // переносим точку опоры, а рисунок остаётся на месте
                val nx = st.x + dx; val ny = st.y + dy
                val im = Matrix(); if (st.matrix().invert(im)) {
                    val b = floatArrayOf(nx, ny); im.mapPoints(b)
                    n.px = b[0]; n.py = b[1]; n.x = nx; n.y = ny
                }
            }
        }
        val fid = host.currentFrame().id
        val ease = track.keyAt(fid)?.ease ?: host.defaultEase()
        track.set(fid, n, ease)
        host.onKeysChanged(true)
        invalidate()
    }

    // ================= текст, пипетка =================

    fun drawText(text: String, x: Float, y: Float) {
        if (text.isBlank()) return
        val cell = currentCell()
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brush.color; textSize = this@CanvasView.textSize }
        val cc = Canvas(cell)
        var yy = y
        val bounds = Rect()
        val dirty = Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())
        for (line in text.split("\n")) {
            cc.drawText(line, x, yy, tp)
            tp.getTextBounds(line, 0, line.length, bounds)
            bounds.offset(x.toInt(), yy.toInt())
            dirty.union(bounds)
            yy += textSize * 1.2f
        }
        dirty.inset(-4, -4)
        commitEdit(dirty)
    }

    private fun sample(lx: Float, ly: Float): Int {
        val wp = floatArrayOf(lx, ly)
        curLayerMatrix()?.mapPoints(wp)
        val f = host.currentFrame()
        val fi = host.frameIndex()
        for (l in p.layers.asReversed()) {
            if (!l.visible) continue
            val pt = wp.copyOf()
            val bmp: Bitmap
            if (l.isObject) {
                bmp = p.cell(l.id, 0, false) ?: continue
                val im = Matrix(); if (!p.objectXf(l, fi).matrix().invert(im)) continue
                im.mapPoints(pt)
            } else bmp = p.cell(l.id, f.id, false) ?: continue
            val x = pt[0].toInt(); val y = pt[1].toInt()
            if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) > 40) return c or 0xFF000000.toInt()
        }
        return p.bgColor
    }

    // ================= касания =================

    private fun updateBrushPaint() {
        brushPaint.color = brush.color
        brushPaint.alpha = Color.alpha(brush.color) * brush.opacity / 255
        brushPaint.strokeWidth = brush.size
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (playBitmap != null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                val w = toLayer(e.x, e.y)
                mode = Mode.DRAW
                if (sel == null && (tool == Tool.MOVE || tool == Tool.CAMERA)) {
                    if (!beginXf(e.x, e.y)) mode = Mode.DONE else mode = Mode.XF_DRAG
                    return true
                }
                val sl = sel
                if (sl != null) {
                    val hnd = hitHandle(e.x, e.y)
                    if (hnd == H_MENU) { mode = Mode.MENU_TAP; return true }
                    if (hnd != H_NONE) {
                        handle = hnd; mode = Mode.FLOAT_DRAG
                        selStart = sl.copyQuad(); selPivX = sl.pivotX; selPivY = sl.pivotY
                        selTx = w[0]; selTy = w[1]
                        return true
                    }
                    commitFloat()
                }
                beginTool(w[0], w[1])
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    val wasFloat = mode == Mode.FLOAT_DRAG && sel != null
                    if (mode == Mode.XF_DRAG) host.onKeysChanged(false)
                    cancelTool()
                    mode = if (wasFloat) Mode.TWO_FLOAT else Mode.TWO_VIEW
                    startTwo(e)
                } else mode = Mode.DONE
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAW -> {
                        for (h in 0 until e.historySize) {
                            val w = toLayer(e.getHistoricalX(0, h), e.getHistoricalY(0, h)); moveTool(w[0], w[1])
                        }
                        val w = toLayer(e.x, e.y); moveTool(w[0], w[1])
                    }
                    Mode.FLOAT_DRAG -> {
                        val sl = sel
                        if (sl != null) {
                            val w = toLayer(e.x, e.y)
                            val dx = w[0] - selTx; val dy = w[1] - selTy
                            when {
                                handle == H_MOVE -> sl.moveFrom(selStart, selPivX, selPivY, dx, dy)
                                handle == H_ROTATE -> sl.rotateFrom(selStart, selTx, selTy, w[0], w[1])
                                handle == H_PIVOT -> { sl.pivotX = selPivX + dx; sl.pivotY = selPivY + dy }
                                handle in H_CORNER until H_CORNER + 4 -> sl.dragCorner(handle - H_CORNER, selStart, selPivX, selPivY, dx, dy)
                                handle in H_SIDE until H_SIDE + 4 -> sl.dragSide(handle - H_SIDE, selStart, selPivX, selPivY, dx, dy)
                            }
                            invalidate()
                        }
                    }
                    Mode.TWO_VIEW, Mode.TWO_FLOAT -> if (e.pointerCount >= 2) moveTwo(e)
                    Mode.XF_DRAG -> { val w = toWorld(e.x, e.y); moveXf(w[0], w[1]) }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TWO_VIEW) snapRotation()
                if (mode == Mode.XF_DRAG) host.onKeysChanged(false)
                mode = Mode.DONE
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.MENU_TAP) host.onFloatMenu()
                if (mode == Mode.XF_DRAG) host.onKeysChanged(false)
                if (mode == Mode.DRAW) {
                    val w = toLayer(e.x, e.y); endTool(w[0], w[1])
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> { cancelTool(); mode = Mode.NONE }
        }
        return true
    }

    private fun startTwo(e: MotionEvent) {
        val ax = e.getX(0); val ay = e.getY(0); val bx = e.getX(1); val by = e.getY(1)
        if (mode == Mode.TWO_FLOAT) {
            val a = toLayer(ax, ay); val b = toLayer(bx, by)
            g0x = (a[0] + b[0]) / 2; g0y = (a[1] + b[1]) / 2
            gd0 = maxOf(1f, hypot(b[0] - a[0], b[1] - a[1])); ga0 = Math.toDegrees(atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble())).toFloat()
            startM.reset()
            sel?.let { selStart = it.copyQuad(); selPivX = it.pivotX; selPivY = it.pivotY }
        } else {
            g0x = (ax + bx) / 2; g0y = (ay + by) / 2
            gd0 = maxOf(1f, hypot(bx - ax, by - ay)); ga0 = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
            startM.set(view)
        }
    }

    private fun moveTwo(e: MotionEvent) {
        var ax = e.getX(0); var ay = e.getY(0); var bx = e.getX(1); var by = e.getY(1)
        if (mode == Mode.TWO_FLOAT) {
            val a = toLayer(ax, ay); val b = toLayer(bx, by)
            ax = a[0]; ay = a[1]; bx = b[0]; by = b[1]
        }
        val cx = (ax + bx) / 2; val cy = (ay + by) / 2
        val d = maxOf(1f, hypot(bx - ax, by - ay))
        val ang = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
        val m = Matrix(startM)
        m.postTranslate(-g0x, -g0y)
        var sc = d / gd0
        if (mode == Mode.TWO_VIEW) {
            val v = FloatArray(9); startM.getValues(v)
            val s0 = sqrt(v[0] * v[0] + v[3] * v[3])
            sc = (s0 * sc).coerceIn(0.05f, 30f) / s0
        }
        m.postScale(sc, sc)
        m.postRotate(ang - ga0)
        m.postTranslate(cx, cy)
        if (mode == Mode.TWO_FLOAT) sel?.matrixFrom(selStart, selPivX, selPivY, m) else { view.set(m); view.invert(inv) }
        invalidate()
    }

    private fun snapRotation() {
        val v = FloatArray(9); view.getValues(v)
        val ang = Math.toDegrees(atan2(v[Matrix.MSKEW_Y].toDouble(), v[Matrix.MSCALE_X].toDouble())).toFloat()
        if (Math.abs(ang) in 0.01f..5f) {
            view.postRotate(-ang, width / 2f, height / 2f); view.invert(inv); invalidate()
        }
    }

    private fun beginTool(x: Float, y: Float) {
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                currentCell()
                updateBrushPaint()
                erasePaint.strokeWidth = eraserSize
                stroking = true; strokePoints = 0; strokeStartTime = System.currentTimeMillis()
                sx = x; sy = y; lx = x; ly = y
                livePath.reset(); livePath.moveTo(x, y); livePath.lineTo(x + 0.01f, y)
                strokeDirty.set(x, y, x, y)
                if (tool == Tool.ERASER) {
                    Canvas(currentCell()).drawPoint(x, y, erasePaint.apply { style = Paint.Style.STROKE })
                }
            }
            Tool.LINE, Tool.RECT, Tool.OVAL -> {
                currentCell(); updateBrushPaint()
                shapeActive = true; shX0 = x; shY0 = y; shX1 = x; shY1 = y
            }
            Tool.LASSO -> { lassoing = true; lassoPath.reset(); lassoPath.moveTo(x, y) }
            Tool.EYEDROP -> host.onColorPicked(sample(x, y), false)
            else -> {}
        }
        invalidate()
    }

    private fun moveTool(x: Float, y: Float) {
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                if (!stroking) return
                val f = (1f - brush.smoothing * 0.085f).coerceAtLeast(0.12f)
                val k = if (tool == Tool.ERASER) 1f else f
                sx += (x - sx) * k; sy += (y - sy) * k
                if (hypot(sx - lx, sy - ly) < 0.6f) return
                strokePoints++
                if (tool == Tool.BRUSH) {
                    livePath.quadTo(lx, ly, (lx + sx) / 2, (ly + sy) / 2)
                } else {
                    Canvas(currentCell()).drawLine(lx, ly, sx, sy, erasePaint)
                }
                lx = sx; ly = sy
                strokeDirty.union(sx, sy)
                invalidate()
            }
            Tool.LINE, Tool.RECT, Tool.OVAL -> { shX1 = x; shY1 = y; invalidate() }
            Tool.LASSO -> if (lassoing) { lassoPath.lineTo(x, y); invalidate() }
            Tool.EYEDROP -> host.onColorPicked(sample(x, y), false)
            else -> {}
        }
    }

    private fun endTool(x: Float, y: Float) {
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                if (!stroking) return
                stroking = false
                val cell = currentCell()
                val pad: Float
                if (tool == Tool.BRUSH) {
                    livePath.lineTo(sx, sy)
                    Canvas(cell).drawPath(livePath, brushPaint)
                    pad = brush.size
                } else {
                    Canvas(cell).drawLine(lx, ly, x, y, erasePaint)
                    strokeDirty.union(x, y)
                    pad = eraserSize
                }
                livePath.reset()
                commitEdit(Rect((strokeDirty.left - pad - 2).toInt(), (strokeDirty.top - pad - 2).toInt(), (strokeDirty.right + pad + 3).toInt(), (strokeDirty.bottom + pad + 3).toInt()))
            }
            Tool.LINE, Tool.RECT, Tool.OVAL -> {
                if (!shapeActive) return
                shapeActive = false
                val path = shapePath()
                Canvas(currentCell()).drawPath(path, brushPaint)
                val b = RectF(); path.computeBounds(b, true)
                val pad = brush.size + 3
                commitEdit(Rect((b.left - pad).toInt(), (b.top - pad).toInt(), (b.right + pad).toInt(), (b.bottom + pad).toInt()))
            }
            Tool.LASSO -> if (lassoing) finishLasso()
            Tool.FILL -> {
                val cell = currentCell()
                val r = FloodFill.fill(cell, x.toInt(), y.toInt(), brush.color, fillTolerance)
                if (r != null) commitEdit(r)
            }
            Tool.TEXT -> host.requestText(x, y)
            Tool.EYEDROP -> host.onColorPicked(sample(x, y), true)
            Tool.MOVE, Tool.CAMERA -> {}
        }
        invalidate()
    }

    private fun cancelTool() {
        if (stroking) {
            stroking = false
            if (tool == Tool.ERASER) {
                val sh = shadow
                if (sh != null) {
                    val pad = eraserSize + 3
                    val r = Rect((strokeDirty.left - pad).toInt(), (strokeDirty.top - pad).toInt(), (strokeDirty.right + pad).toInt(), (strokeDirty.bottom + pad).toInt())
                    if (r.intersect(0, 0, sh.width, sh.height)) UndoManager.paste(currentCell(), UndoManager.cut(sh, r), r)
                }
            }
            livePath.reset()
        }
        shapeActive = false
        lassoing = false
        invalidate()
    }
}
