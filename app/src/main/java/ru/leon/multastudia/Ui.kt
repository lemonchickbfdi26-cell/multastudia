package ru.leon.multastudia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

object C {
    const val BG = 0xFF2B2B2B.toInt()
    const val BAR = 0xFF1F1F1F.toInt()
    const val CARD = 0xFF333333.toInt()
    const val CARD2 = 0xFF3C3C3C.toInt()
    const val ACCENT = 0xFFFF4F7B.toInt()
    const val PURPLE = 0xFF7C4DFF.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val DIM = 0xFF9A9A9A.toInt()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Float): Int = context.dp(v)

fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radiusPx
    if (strokePx > 0) setStroke(strokePx, strokeColor)
}

fun Context.label(text: String, size: Float = 14f, color: Int = C.TEXT) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color)
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = -1
const val WRAP = -2

/** Строка «название + ползунок + значение». */
fun Context.sliderRow(title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit): LinearLayout {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val t = label(title, 13f, C.DIM)
    val v = label(fmt(value), 13f)
    v.gravity = Gravity.END
    val sb = SeekBar(this).apply {
        this.max = max - min
        progress = value - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                v.text = fmt(p + min); if (fromUser) onChange(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    row.addView(t, lp(dp(96f), WRAP))
    row.addView(sb, lp(0, WRAP, 1f))
    row.addView(v, lp(dp(40f), WRAP))
    return row
}

enum class Ico { CLOSE, BRUSH, ERASER, LASSO, FILL, TEXT, MORE, UNDO, REDO, COPY, PASTE, ONION, IMAGE, PLAY, PAUSE, PREV, NEXT, PLUS, SHARE, MENU, FIT, LAYERS, EYEDROP, LINE, RECT, OVAL, CHECK, FLIP, TRASH, EYE, EYE_OFF, UP, DOWN, GRID }

/** Иконки рисуются кодом — без картинок и без лишних библиотек. */
class IconView(ctx: Context, var icon: Ico, var color: Int = C.TEXT) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 24f
        c.save()
        c.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        c.scale(s, s)
        p.color = color; p.strokeWidth = 1.8f; p.style = Paint.Style.STROKE
        path.reset()
        when (icon) {
            Ico.CLOSE -> { c.drawLine(6f, 6f, 18f, 18f, p); c.drawLine(18f, 6f, 6f, 18f, p) }
            Ico.BRUSH -> {
                path.moveTo(19f, 4f); path.lineTo(10f, 13f); c.drawPath(path, p)
                path.reset(); path.moveTo(10f, 12f); path.cubicTo(6f, 12f, 6f, 16f, 5f, 19f); path.cubicTo(9f, 19f, 12f, 18f, 12f, 14f); path.close(); c.drawPath(path, p)
            }
            Ico.ERASER -> {
                path.moveTo(4f, 15f); path.lineTo(13f, 6f); path.lineTo(19f, 12f); path.lineTo(12f, 19f); path.lineTo(8f, 19f); path.close(); c.drawPath(path, p)
                c.drawLine(8.5f, 10.5f, 14.5f, 16.5f, p); c.drawLine(12f, 19f, 20f, 19f, p)
            }
            Ico.LASSO -> {
                c.drawOval(RectF(4f, 5f, 20f, 14f), p)
                path.moveTo(8f, 13f); path.cubicTo(5f, 16f, 9f, 19f, 11f, 16f); path.cubicTo(12f, 19f, 10f, 21f, 9f, 21f); c.drawPath(path, p)
            }
            Ico.FILL -> {
                path.moveTo(5f, 11f); path.lineTo(11f, 5f); path.lineTo(18f, 12f); path.lineTo(12f, 18f); path.close(); c.drawPath(path, p)
                c.drawLine(5f, 11f, 18f, 12f, p)
                p.style = Paint.Style.FILL; c.drawCircle(19.5f, 17.5f, 1.8f, p)
            }
            Ico.TEXT -> { c.drawLine(5f, 5f, 19f, 5f, p); c.drawLine(12f, 5f, 12f, 20f, p); c.drawLine(9f, 20f, 15f, 20f, p) }
            Ico.MORE -> { p.style = Paint.Style.FILL; for (x in listOf(6f, 12f, 18f)) c.drawCircle(x, 12f, 1.6f, p) }
            Ico.MENU -> { p.style = Paint.Style.FILL; for (y in listOf(6f, 12f, 18f)) c.drawCircle(12f, y, 1.6f, p) }
            Ico.UNDO, Ico.REDO -> {
                if (icon == Ico.REDO) { c.scale(-1f, 1f, 12f, 12f) }
                path.moveTo(9f, 6f); path.lineTo(5f, 10f); path.lineTo(9f, 14f); c.drawPath(path, p)
                path.reset(); path.moveTo(5f, 10f); path.lineTo(14f, 10f); path.cubicTo(21f, 10f, 21f, 19f, 14f, 19f); path.lineTo(9f, 19f); c.drawPath(path, p)
            }
            Ico.COPY -> { c.drawRoundRect(RectF(8f, 8f, 19f, 19f), 2f, 2f, p); path.moveTo(5f, 15f); path.lineTo(5f, 5f); path.lineTo(15f, 5f); c.drawPath(path, p) }
            Ico.PASTE -> { c.drawRoundRect(RectF(6f, 5f, 18f, 20f), 2f, 2f, p); c.drawRoundRect(RectF(9f, 3f, 15f, 7f), 1f, 1f, p) }
            Ico.ONION -> {
                c.drawCircle(12f, 13f, 7f, p); c.drawOval(RectF(9f, 6f, 15f, 20f), p)
                c.drawLine(12f, 6f, 12f, 3f, p)
            }
            Ico.IMAGE -> {
                c.drawRoundRect(RectF(4f, 5f, 20f, 19f), 2f, 2f, p)
                path.moveTo(4f, 16f); path.lineTo(9f, 11f); path.lineTo(14f, 16f); path.lineTo(16f, 14f); path.lineTo(20f, 18f); c.drawPath(path, p)
                c.drawCircle(15f, 9f, 1.5f, p)
            }
            Ico.PLAY -> { p.style = Paint.Style.FILL; path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close(); c.drawPath(path, p) }
            Ico.PAUSE -> { p.style = Paint.Style.FILL; c.drawRect(7f, 5f, 10.5f, 19f, p); c.drawRect(13.5f, 5f, 17f, 19f, p) }
            Ico.PREV, Ico.NEXT -> {
                if (icon == Ico.NEXT) c.scale(-1f, 1f, 12f, 12f)
                p.style = Paint.Style.FILL; c.drawRect(6f, 6f, 8.5f, 18f, p)
                path.moveTo(18f, 6f); path.lineTo(9f, 12f); path.lineTo(18f, 18f); path.close(); c.drawPath(path, p)
            }
            Ico.PLUS -> { c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p) }
            Ico.SHARE -> {
                c.drawCircle(17f, 6f, 2.5f, p); c.drawCircle(7f, 12f, 2.5f, p); c.drawCircle(17f, 18f, 2.5f, p)
                c.drawLine(9.2f, 10.8f, 14.8f, 7.2f, p); c.drawLine(9.2f, 13.2f, 14.8f, 16.8f, p)
            }
            Ico.FIT -> {
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(20f, 15f); path.lineTo(20f, 20f); path.lineTo(15f, 20f)
                path.moveTo(9f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                c.drawPath(path, p)
            }
            Ico.LAYERS -> {
                path.moveTo(12f, 4f); path.lineTo(20f, 8.5f); path.lineTo(12f, 13f); path.lineTo(4f, 8.5f); path.close()
                path.moveTo(4f, 12.5f); path.lineTo(12f, 17f); path.lineTo(20f, 12.5f)
                path.moveTo(4f, 16f); path.lineTo(12f, 20.5f); path.lineTo(20f, 16f)
                c.drawPath(path, p)
            }
            Ico.EYEDROP -> {
                c.drawLine(5f, 19f, 14f, 10f, p)
                path.moveTo(12f, 8f); path.lineTo(16f, 12f); c.drawPath(path, p)
                c.drawCircle(17f, 7f, 3f, p)
            }
            Ico.LINE -> c.drawLine(5f, 19f, 19f, 5f, p)
            Ico.RECT -> c.drawRect(5f, 7f, 19f, 17f, p)
            Ico.OVAL -> c.drawOval(RectF(4f, 6f, 20f, 18f), p)
            Ico.CHECK -> { path.moveTo(5f, 12f); path.lineTo(10f, 17f); path.lineTo(19f, 7f); c.drawPath(path, p) }
            Ico.FLIP -> {
                c.drawLine(12f, 3f, 12f, 21f, p)
                path.moveTo(9f, 6f); path.lineTo(3f, 17f); path.lineTo(9f, 17f); path.close()
                path.moveTo(15f, 6f); path.lineTo(21f, 17f); path.lineTo(15f, 17f); path.close(); c.drawPath(path, p)
            }
            Ico.TRASH -> {
                c.drawLine(4f, 7f, 20f, 7f, p); c.drawRect(9f, 4f, 15f, 7f, p)
                path.moveTo(6f, 7f); path.lineTo(7f, 20f); path.lineTo(17f, 20f); path.lineTo(18f, 7f); c.drawPath(path, p)
            }
            Ico.EYE, Ico.EYE_OFF -> {
                path.moveTo(3f, 12f); path.quadTo(12f, 3f, 21f, 12f); path.quadTo(12f, 21f, 3f, 12f); c.drawPath(path, p)
                c.drawCircle(12f, 12f, 3f, p)
                if (icon == Ico.EYE_OFF) c.drawLine(4f, 4f, 20f, 20f, p)
            }
            Ico.UP -> { path.moveTo(6f, 15f); path.lineTo(12f, 9f); path.lineTo(18f, 15f); c.drawPath(path, p) }
            Ico.DOWN -> { path.moveTo(6f, 9f); path.lineTo(12f, 15f); path.lineTo(18f, 9f); c.drawPath(path, p) }
            Ico.GRID -> {
                c.drawRect(4f, 4f, 20f, 20f, p)
                c.drawLine(9.3f, 4f, 9.3f, 20f, p); c.drawLine(14.6f, 4f, 14.6f, 20f, p)
                c.drawLine(4f, 9.3f, 20f, 9.3f, p); c.drawLine(4f, 14.6f, 20f, 14.6f, p)
            }
        }
        c.restore()
    }
}

/** Кнопка: иконка и (необязательно) подпись снизу. */
class IconButton(ctx: Context, icon: Ico, text: String? = null, iconDp: Float = 26f) : LinearLayout(ctx) {
    val iconView = IconView(ctx, icon)
    private val tv: TextView? = text?.let { ctx.label(it, 11f) }
    var active = false
        set(v) { field = v; background = if (v) rounded(C.ACCENT, dp(14f).toFloat()) else null }
    var dimmed = false
        set(v) { field = v; val col = if (v) 0xFF666666.toInt() else C.TEXT; iconView.color = col; iconView.invalidate(); tv?.setTextColor(col) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = dp(6f)
        setPadding(pad, pad, pad, pad)
        addView(iconView, LayoutParams(dp(iconDp), dp(iconDp)))
        tv?.let { it.gravity = Gravity.CENTER; it.maxLines = 1; addView(it, LayoutParams(WRAP, WRAP)) }
        isClickable = true
    }

    fun setIcon(i: Ico) { iconView.icon = i; iconView.invalidate() }
}

/** Кружок цвета. */
class ColorDot(ctx: Context, var color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var selected2 = false
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f - dp(3f)
        // шахматка под прозрачным
        p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(width / 2f, height / 2f, r, p)
        p.color = color; c.drawCircle(width / 2f, height / 2f, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(if (selected2) 3f else 1.5f).toFloat()
        p.color = if (selected2) C.ACCENT else 0xFF777777.toInt()
        c.drawCircle(width / 2f, height / 2f, r, p)
    }
}
