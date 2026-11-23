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

enum class Tool { BRUSH, ERASER, LASSO, FILL, TEXT, EYEDROP, LINE, RECT, OVAL }

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
    private var floatBmp: Bitmap? = null
    private val floatMatrix = Matrix()
    private var floatCut: Rect? = null
    val hasFloat get() = floatBmp != null

    // ---------- жесты ----------
    private enum class Mode { NONE, DRAW, FLOAT_DRAG, TWO_VIEW, TWO_FLOAT, DONE }
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
        for (l in p.layers) {
            if (!l.visible && l !== cur) continue
            val bmp = p.cell(l.id, frame.id, false)
            layerPaint.alpha = if (l.visible) l.opacity else 60
            if (bmp != null) c.drawBitmap(bmp, 0f, 0f, layerPaint)
            if (l === cur) drawLive(c, s)
        }

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
    }

    private fun drawLive(c: Canvas, s: Float) {
        if (stroking && tool == Tool.BRUSH) c.drawPath(livePath, brushPaint)
        if (shapeActive) c.drawPath(shapePath(), brushPaint)
        floatBmp?.let { fb ->
            c.drawBitmap(fb, floatMatrix, bmpPaint)
            val pts = floatArrayOf(0f, 0f, fb.width.toFloat(), 0f, fb.width.toFloat(), fb.height.toFloat(), 0f, fb.height.toFloat())
            floatMatrix.mapPoints(pts)
            val outline = Path().apply { moveTo(pts[0], pts[1]); lineTo(pts[2], pts[3]); lineTo(pts[4], pts[5]); lineTo(pts[6], pts[7]); close() }
            ui.strokeWidth = 2f / s; ui.color = C.PURPLE
            ui.pathEffect = DashPathEffect(floatArrayOf(10f / s, 6f / s), 0f)
            c.drawPath(outline, ui); ui.pathEffect = null
        }
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
        val l = host.currentLayer(); val f = host.currentFrame()
        val cell = p.cell(l.id, f.id, true)!!
        val key = "${l.id}_${f.id}"
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
        undo.push(UndoEntry(host.currentLayer().id, host.currentFrame().id, r, before, after))
        UndoManager.paste(sh, after, r)
        p.markDirty(host.currentLayer().id, host.currentFrame().id)
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

    fun startFloat(bmp: Bitmap) {
        commitFloat()
        currentCell()
        floatBmp = bmp
        floatCut = null
        val cr = canvasRect
        val sc = minOf(1f, cr.width() * 0.8f / bmp.width, cr.height() * 0.8f / bmp.height)
        floatMatrix.reset()
        floatMatrix.postScale(sc, sc)
        floatMatrix.postTranslate(cr.centerX() - bmp.width * sc / 2, cr.centerY() - bmp.height * sc / 2)
        host.onFloatChanged(true)
        invalidate()
    }

    private fun floatBounds(): Rect {
        val fb = floatBmp ?: return Rect()
        val r = RectF(0f, 0f, fb.width.toFloat(), fb.height.toFloat())
        floatMatrix.mapRect(r)
        return Rect(r.left.toInt() - 2, r.top.toInt() - 2, r.right.toInt() + 2, r.bottom.toInt() + 2)
    }

    /** Впечатать выделение в слой. stamp = оставить копию в руках. */
    fun commitFloat(stamp: Boolean = false) {
        val fb = floatBmp ?: return
        val cell = currentCell()
        Canvas(cell).drawBitmap(fb, floatMatrix, bmpPaint)
        val r = floatBounds()
        floatCut?.let { r.union(it) }
        commitEdit(r)
        if (stamp) {
            floatCut = null
        } else {
            floatBmp = null; floatCut = null
            host.onFloatChanged(false)
        }
        invalidate()
    }

    fun deleteFloat() {
        if (floatBmp == null) return
        val cut = floatCut
        floatBmp = null; floatCut = null
        if (cut != null) commitEdit(cut)
        host.onFloatChanged(false)
        invalidate()
    }

    fun flipFloat() {
        val fb = floatBmp ?: return
        floatMatrix.preScale(-1f, 1f, fb.width / 2f, fb.height / 2f)
        invalidate()
    }

    private fun pointInFloat(wx: Float, wy: Float): Boolean {
        val fb = floatBmp ?: return false
        val m = Matrix(); if (!floatMatrix.invert(m)) return false
        val a = floatArrayOf(wx, wy); m.mapPoints(a)
        val pad = 20f / viewScale()
        return a[0] >= -pad && a[1] >= -pad && a[0] <= fb.width + pad && a[1] <= fb.height + pad
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
        floatBmp = fb
        floatCut = r
        floatMatrix.setTranslate(r.left.toFloat(), r.top.toFloat())
        host.onFloatChanged(true)
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

    private fun sample(wx: Float, wy: Float): Int {
        val x = wx.toInt(); val y = wy.toInt()
        if (x < 0 || y < 0 || x >= p.worldW || y >= p.worldH) return p.bgColor
        val f = host.currentFrame()
        for (l in p.layers.asReversed()) {
            if (!l.visible) continue
            val bmp = p.cell(l.id, f.id, false) ?: continue
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
                val w = toWorld(e.x, e.y)
                mode = Mode.DRAW
                if (floatBmp != null) {
                    if (pointInFloat(w[0], w[1])) {
                        mode = Mode.FLOAT_DRAG; dragLastX = w[0]; dragLastY = w[1]; return true
                    }
                    commitFloat()
                }
                beginTool(w[0], w[1])
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    val wasFloat = mode == Mode.FLOAT_DRAG
                    cancelTool()
                    mode = if (wasFloat) Mode.TWO_FLOAT else Mode.TWO_VIEW
                    startTwo(e)
                } else mode = Mode.DONE
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAW -> {
                        for (h in 0 until e.historySize) {
                            val w = toWorld(e.getHistoricalX(0, h), e.getHistoricalY(0, h)); moveTool(w[0], w[1])
                        }
                        val w = toWorld(e.x, e.y); moveTool(w[0], w[1])
                    }
                    Mode.FLOAT_DRAG -> {
                        val w = toWorld(e.x, e.y)
                        floatMatrix.postTranslate(w[0] - dragLastX, w[1] - dragLastY)
                        dragLastX = w[0]; dragLastY = w[1]; invalidate()
                    }
                    Mode.TWO_VIEW, Mode.TWO_FLOAT -> if (e.pointerCount >= 2) moveTwo(e)
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TWO_VIEW) snapRotation()
                mode = Mode.DONE
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAW) {
                    val w = toWorld(e.x, e.y); endTool(w[0], w[1])
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
            val a = toWorld(ax, ay); val b = toWorld(bx, by)
            g0x = (a[0] + b[0]) / 2; g0y = (a[1] + b[1]) / 2
            gd0 = maxOf(1f, hypot(b[0] - a[0], b[1] - a[1])); ga0 = Math.toDegrees(atan2((b[1] - a[1]).toDouble(), (b[0] - a[0]).toDouble())).toFloat()
            startM.set(floatMatrix)
        } else {
            g0x = (ax + bx) / 2; g0y = (ay + by) / 2
            gd0 = maxOf(1f, hypot(bx - ax, by - ay)); ga0 = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
            startM.set(view)
        }
    }

    private fun moveTwo(e: MotionEvent) {
        var ax = e.getX(0); var ay = e.getY(0); var bx = e.getX(1); var by = e.getY(1)
        if (mode == Mode.TWO_FLOAT) {
            val a = toWorld(ax, ay); val b = toWorld(bx, by)
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
        if (mode == Mode.TWO_FLOAT) floatMatrix.set(m) else { view.set(m); view.invert(inv) }
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
