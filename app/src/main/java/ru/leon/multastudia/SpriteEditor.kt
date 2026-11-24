package ru.leon.multastudia

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Маленький редактор кусочка персонажа: кисть, ластик, заливка, пипетка, отмена.
 */
class SpriteEditor(private val ctx: Context, src: Bitmap, private val brush: BrushSettings, private val onSave: (Bitmap) -> Unit) {
    private var bmp: Bitmap = src.copy(Bitmap.Config.ARGB_8888, true)
    private val undo = ArrayList<Bitmap>()
    private var tool = Tool.BRUSH
    private var eraser = 16f
    private var tolerance = 60
    private val dlg = Dialog(ctx, android.R.style.Theme_Material_NoActionBar_Fullscreen)
    private lateinit var view: Pad
    private val toolBtns = HashMap<Tool, IconButton>()
    private lateinit var colorDot: ColorDot
    private lateinit var settings: LinearLayout

    fun show() {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR); setPadding(ctx.dp(4f), 0, ctx.dp(4f), 0)
        }
        top.addView(IconButton(ctx, Ico.CLOSE).apply { setOnClickListener { dlg.dismiss() } })
        top.addView(ctx.label("Кусочек персонажа", 16f), lp(0, WRAP, 1f))
        top.addView(IconButton(ctx, Ico.UNDO).apply { setOnClickListener { doUndo() } })
        top.addView(IconButton(ctx, Ico.CHECK).apply {
            iconView.color = C.ACCENT
            setOnClickListener { onSave(trim(bmp)); dlg.dismiss() }
        })
        root.addView(top, lp(MATCH, ctx.dp(52f)))

        val tools = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR); setPadding(ctx.dp(6f), ctx.dp(2f), ctx.dp(6f), ctx.dp(2f))
        }
        for ((t, i, name) in listOf(Triple(Tool.BRUSH, Ico.BRUSH, "Кисть"), Triple(Tool.ERASER, Ico.ERASER, "Ластик"),
                                    Triple(Tool.FILL, Ico.FILL, "Заливка"), Triple(Tool.EYEDROP, Ico.EYEDROP, "Пипетка"))) {
            val b = IconButton(ctx, i, name, 22f)
            b.setOnClickListener { select(t) }
            toolBtns[t] = b
            tools.addView(b, lp(0, WRAP, 1f))
        }
        tools.addView(IconButton(ctx, Ico.FIT, "Шире", 22f).apply { setOnClickListener { grow() } }, lp(0, WRAP, 1f))
        colorDot = ColorDot(ctx, brush.color)
        colorDot.setOnClickListener { ColorPicker.show(ctx, brush.color) { c -> brush.color = c; colorDot.color = c; colorDot.invalidate() } }
        tools.addView(colorDot, lp(ctx.dp(48f), ctx.dp(48f)))
        root.addView(tools, lp(MATCH, WRAP))

        settings = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BAR); setPadding(ctx.dp(14f), 0, ctx.dp(14f), ctx.dp(6f)) }
        root.addView(settings, lp(MATCH, WRAP))

        view = Pad(ctx)
        root.addView(view, lp(MATCH, 0, 1f))
        root.addView(ctx.label("Двумя пальцами — двигать и приближать. «Шире» добавляет место по краям.", 12f, C.DIM).apply {
            gravity = Gravity.CENTER; setPadding(ctx.dp(8f), ctx.dp(6f), ctx.dp(8f), ctx.dp(8f))
        }, lp(MATCH, WRAP))
        dlg.setContentView(root)
        select(Tool.BRUSH)
        dlg.show()
    }

    private fun select(t: Tool) {
        tool = t
        for ((k, b) in toolBtns) b.active = k == t
        settings.removeAllViews()
        when (t) {
            Tool.BRUSH -> {
                settings.addView(ctx.sliderRow("Размер", 1, 80, brush.size.toInt()) { brush.size = it.toFloat() })
                settings.addView(ctx.sliderRow("Прозрачность", 5, 100, brush.opacity * 100 / 255, { "$it%" }) { brush.opacity = it * 255 / 100 })
            }
            Tool.ERASER -> settings.addView(ctx.sliderRow("Размер", 2, 120, eraser.toInt()) { eraser = it.toFloat() })
            Tool.FILL -> settings.addView(ctx.sliderRow("Допуск", 0, 200, tolerance) { tolerance = it })
            else -> {}
        }
    }

    private fun pushUndo() {
        undo.add(bmp.copy(Bitmap.Config.ARGB_8888, true))
        if (undo.size > 25) undo.removeAt(0)
    }

    private fun doUndo() {
        if (undo.isEmpty()) return
        bmp = undo.removeAt(undo.size - 1)
        view.invalidate()
    }

    /** Добавить прозрачные поля, чтобы было где дорисовать. */
    private fun grow() {
        pushUndo()
        val px = maxOf(20, bmp.width / 5); val py = maxOf(20, bmp.height / 5)
        val n = Bitmap.createBitmap(bmp.width + px * 2, bmp.height + py * 2, Bitmap.Config.ARGB_8888)
        Canvas(n).drawBitmap(bmp, px.toFloat(), py.toFloat(), null)
        bmp = n
        view.fit()
    }

    /** Обрезать пустые края, но оставить чуть-чуть места. */
    private fun trim(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 4) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y
        }
        if (r < 0) return b
        l = maxOf(0, l - 2); t = maxOf(0, t - 2); r = minOf(w - 1, r + 2); bt = minOf(h - 1, bt + 2)
        return Bitmap.createBitmap(b, l, t, r - l + 1, bt - t + 1)
    }

    @SuppressLint("ViewConstructor")
    private inner class Pad(c: Context) : View(c) {
        private val m = Matrix()
        private val inv = Matrix()
        private val path = Path()
        private var drawing = false
        private var two = false
        private var lx = 0f; private var ly = 0f
        private val startM = Matrix()
        private var g0x = 0f; private var g0y = 0f; private var gd0 = 1f; private var ga0 = 0f
        private val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val checker: Paint

        init {
            val cb = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            cb.setPixel(0, 0, 0xFFCCCCCC.toInt()); cb.setPixel(1, 1, 0xFFCCCCCC.toInt())
            cb.setPixel(1, 0, Color.WHITE); cb.setPixel(0, 1, Color.WHITE)
            checker = Paint().apply { shader = BitmapShader(cb, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { it.setLocalMatrix(Matrix().apply { setScale(12f, 12f) }) } }
        }

        fun fit() {
            if (width == 0) return
            val s = minOf(width * 0.9f / bmp.width, height * 0.9f / bmp.height)
            m.reset(); m.postScale(s, s); m.postTranslate((width - bmp.width * s) / 2, (height - bmp.height * s) / 2)
            m.invert(inv); invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit() }

        override fun onDraw(c: Canvas) {
            c.drawColor(0xFF3A3A3A.toInt())
            c.save(); c.concat(m)
            c.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), checker)
            c.drawBitmap(bmp, 0f, 0f, null)
            if (drawing && tool == Tool.BRUSH) c.drawPath(path, bp)
            c.restore()
        }

        private fun pt(x: Float, y: Float): FloatArray { val a = floatArrayOf(x, y); inv.mapPoints(a); return a }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    two = false
                    val p = pt(e.x, e.y)
                    when (tool) {
                        Tool.BRUSH -> {
                            bp.color = brush.color; bp.alpha = Color.alpha(brush.color) * brush.opacity / 255; bp.strokeWidth = brush.size
                            pushUndo(); drawing = true; path.reset(); path.moveTo(p[0], p[1]); path.lineTo(p[0] + 0.01f, p[1])
                        }
                        Tool.ERASER -> {
                            ep.strokeWidth = eraser; pushUndo(); drawing = true
                            Canvas(bmp).drawPoint(p[0], p[1], ep)
                        }
                        else -> {}
                    }
                    lx = p[0]; ly = p[1]
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    if (drawing) { drawing = false; doUndo() }
                    two = true
                    val ax = e.getX(0); val ay = e.getY(0); val bx = e.getX(1); val by = e.getY(1)
                    g0x = (ax + bx) / 2; g0y = (ay + by) / 2
                    gd0 = maxOf(1f, hypot(bx - ax, by - ay)); ga0 = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
                    startM.set(m)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (two && e.pointerCount >= 2) {
                        val ax = e.getX(0); val ay = e.getY(0); val bx = e.getX(1); val by = e.getY(1)
                        val sc = maxOf(1f, hypot(bx - ax, by - ay)) / gd0
                        m.set(startM); m.postTranslate(-g0x, -g0y); m.postScale(sc, sc); m.postTranslate((ax + bx) / 2, (ay + by) / 2)
                        m.invert(inv); invalidate()
                    } else if (drawing) {
                        val p = pt(e.x, e.y)
                        if (tool == Tool.BRUSH) path.quadTo(lx, ly, (lx + p[0]) / 2, (ly + p[1]) / 2)
                        else Canvas(bmp).drawLine(lx, ly, p[0], p[1], ep)
                        lx = p[0]; ly = p[1]; invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val p = pt(e.x, e.y)
                    if (drawing && tool == Tool.BRUSH) { path.lineTo(p[0], p[1]); Canvas(bmp).drawPath(path, bp) }
                    if (!two) when (tool) {
                        Tool.FILL -> { pushUndo(); if (FloodFill.fill(bmp, p[0].toInt(), p[1].toInt(), brush.color, tolerance) == null) undo.removeAt(undo.size - 1) }
                        Tool.EYEDROP -> {
                            val x = p[0].toInt(); val y = p[1].toInt()
                            if (x in 0 until bmp.width && y in 0 until bmp.height) {
                                val c = bmp.getPixel(x, y)
                                if (Color.alpha(c) > 20) { brush.color = c or 0xFF000000.toInt(); colorDot.color = brush.color; colorDot.invalidate(); select(Tool.FILL) }
                            }
                        }
                        else -> {}
                    }
                    drawing = false; path.reset(); invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { drawing = false; path.reset(); invalidate() }
            }
            return true
        }
    }
}
