package ru.leon.multastudia

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Палитра: название + цвета. */
class Palette(var name: String, val colors: ArrayList<Int>)

/**
 * Выбор цвета как во Флиппе: квадрат насыщенность/яркость, прозрачность, полосы,
 * свои палитры (сохраняются), недавние цвета и ввод HEX.
 */
object ColorPicker {
    private val basic = intArrayOf(
        0xFF000000.toInt(), 0xFF555555.toInt(), 0xFFAAAAAA.toInt(), 0xFFFFFFFF.toInt(), 0xFF8B4513.toInt(), 0xFFFFD1A4.toInt(),
        0xFFE53935.toInt(), 0xFFFF7043.toInt(), 0xFFFFB300.toInt(), 0xFFFFEB3B.toInt(), 0xFF8BC34A.toInt(), 0xFF2E7D32.toInt(),
        0xFF00BCD4.toInt(), 0xFF1E88E5.toInt(), 0xFF283593.toInt(), 0xFF8E24AA.toInt(), 0xFFFF4F7B.toInt(), 0xFFF8BBD0.toInt()
    )
    val recent = ArrayList<Int>()
    private val palettes = ArrayList<Palette>()
    private var cur = 0          // 0 — «Базовая», дальше свои
    private var loaded = false

    // ---------- сохранение ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("colors", Context.MODE_PRIVATE)

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = prefs(ctx)
        try {
            val a = JSONArray(sp.getString("palettes", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val c = o.getJSONArray("c")
                palettes.add(Palette(o.optString("n", "Палитра"), ArrayList((0 until c.length()).map { c.getInt(it) })))
            }
            val r = JSONArray(sp.getString("recent", "[]"))
            if (recent.isEmpty()) for (i in 0 until r.length()) recent.add(r.getInt(i))
        } catch (_: Throwable) {}
        cur = sp.getInt("cur", 0).coerceIn(0, palettes.size)
    }

    private fun save(ctx: Context) {
        val a = JSONArray()
        for (p in palettes) a.put(JSONObject().put("n", p.name).put("c", JSONArray(p.colors)))
        prefs(ctx).edit().putString("palettes", a.toString()).putString("recent", JSONArray(recent.take(12)).toString()).putInt("cur", cur).apply()
    }

    private fun addRecent(c: Int) { recent.remove(c); recent.add(0, c); while (recent.size > 12) recent.removeAt(recent.size - 1) }

    // ---------- окно ----------

    fun show(ctx: Context, start: Int, onPick: (Int) -> Unit) {
        load(ctx)
        val hsv = FloatArray(3); Color.colorToHSV(start, hsv)
        var alpha = Color.alpha(start) / 255f
        val dlg = Dialog(ctx, android.R.style.Theme_Material_NoActionBar_Fullscreen)

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val views = ArrayList<View>()
        lateinit var newDot: ColorDot
        lateinit var refreshAll: () -> Unit
        lateinit var rebuildPal: () -> Unit

        fun color() = Color.HSVToColor((alpha * 255).toInt().coerceIn(0, 255), hsv)

        // верх: закрыть, HEX, +в палитру, старый и новый цвет
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(6f), ctx.dp(6f), ctx.dp(10f), ctx.dp(6f)) }
        top.addView(IconButton(ctx, Ico.CLOSE).apply { setOnClickListener { dlg.dismiss() } })
        top.addView(View(ctx), lp(0, 1, 1f))
        top.addView(ctx.label("#HEX", 14f).apply {
            background = rounded(C.CARD2, ctx.dp(12f).toFloat()); setPadding(ctx.dp(12f), ctx.dp(8f), ctx.dp(12f), ctx.dp(8f))
            setOnClickListener {
                val et = EditText(ctx).apply {
                    setText(String.format("%06X", color() and 0xFFFFFF)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                }
                AlertDialog.Builder(ctx, R.style.DialogTheme).setTitle("Цвет в HEX (например FF4F7B)").setView(et)
                    .setPositiveButton("ОК") { _, _ ->
                        try {
                            val c = Color.parseColor("#" + et.text.toString().trim().removePrefix("#"))
                            Color.colorToHSV(c, hsv)
                            if (et.text.length > 7) alpha = Color.alpha(c) / 255f
                            refreshAll()
                        } catch (_: Throwable) { Toast.makeText(ctx, "Не похоже на цвет", Toast.LENGTH_SHORT).show() }
                    }.setNegativeButton("Отмена", null).show()
            }
        })
        top.addView(View(ctx), lp(ctx.dp(8f), 1))
        top.addView(IconButton(ctx, Ico.PLUS).apply {
            background = rounded(C.CARD2, ctx.dp(14f).toFloat())
            setOnClickListener {
                if (cur == 0) {
                    if (palettes.isEmpty()) palettes.add(Palette("Палитра 1", ArrayList()))
                    cur = 1
                }
                palettes[cur - 1].colors.add(color()); save(ctx); rebuildPal()
                Toast.makeText(ctx, "Добавлено в «${palettes[cur - 1].name}»", Toast.LENGTH_SHORT).show()
            }
        })
        top.addView(View(ctx), lp(ctx.dp(10f), 1))
        top.addView(ColorDot(ctx, start).apply { setOnClickListener { Color.colorToHSV(start, hsv); alpha = Color.alpha(start) / 255f; refreshAll() } }, lp(ctx.dp(46f), ctx.dp(46f)))
        newDot = ColorDot(ctx, start).apply { selected2 = true }
        newDot.setOnClickListener { val c = color(); addRecent(c); save(ctx); onPick(c); dlg.dismiss() }
        top.addView(newDot, lp(ctx.dp(52f), ctx.dp(52f)))
        root.addView(top, lp(MATCH, WRAP))

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16f), ctx.dp(4f), ctx.dp(16f), ctx.dp(16f)) }

        // квадрат + прозрачность
        val sq = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val square = SVSquare(ctx, hsv) { refreshAll() }
        val alphaBar = Bar(ctx, vertical = true, colors = { intArrayOf(Color.HSVToColor(hsv), Color.HSVToColor(0, hsv)) }, checker = true,
            getV = { 1f - alpha }, setV = { alpha = 1f - it; refreshAll() }, knob = { color() })
        sq.addView(square, lp(0, ctx.dp(300f), 1f))
        sq.addView(alphaBar, lp(ctx.dp(44f), ctx.dp(300f)).apply { leftMargin = ctx.dp(14f) })
        body.addView(sq)

        // полосы: оттенок, насыщенность, яркость
        val hues = IntArray(13) { Color.HSVToColor(floatArrayOf(it * 30f % 360f, 1f, 1f)) }.also { it[12] = Color.RED }
        val hueBar = Bar(ctx, false, { hues }, false, { hsv[0] / 360f }, { hsv[0] = (it * 360f).coerceIn(0f, 359.9f); refreshAll() }, { Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)) })
        val satBar = Bar(ctx, false, { intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))) }, false,
            { hsv[1] }, { hsv[1] = it; refreshAll() }, { Color.HSVToColor(hsv) })
        val valBar = Bar(ctx, false, { intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))) }, false,
            { hsv[2] }, { hsv[2] = it; refreshAll() }, { Color.HSVToColor(hsv) })
        for (b in listOf(hueBar, satBar, valBar)) body.addView(b, lp(MATCH, ctx.dp(42f)).apply { topMargin = ctx.dp(14f) })
        views.addAll(listOf(square, alphaBar, hueBar, satBar, valBar))

        // палитры
        val palHead = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(18f), 0, ctx.dp(6f)) }
        val palName = ctx.label("", 17f)
        palHead.addView(palName, lp(0, WRAP, 1f))
        palHead.addView(ctx.label("Палитры ▾", 14f, C.ACCENT).apply {
            setPadding(ctx.dp(10f), ctx.dp(6f), 0, ctx.dp(6f))
            setOnClickListener { paletteMenu(ctx) { save(ctx); rebuildPal() } }
        })
        body.addView(palHead)
        val swatches = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(swatches)
        val recentHead = ctx.label("Недавние", 14f, C.DIM).apply { setPadding(0, ctx.dp(12f), 0, ctx.dp(4f)) }
        body.addView(recentHead)
        val recentRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(recentRow)
        body.addView(ctx.label("Нажми на большой кружок справа сверху — цвет выбран. Долгое нажатие на цвет в своей палитре — удалить.", 12f, C.DIM).apply { setPadding(0, ctx.dp(12f), 0, 0) })

        fun dot(c: Int, onLong: (() -> Unit)?): View = ColorDot(ctx, c).apply {
            setOnClickListener { Color.colorToHSV(c, hsv); alpha = Color.alpha(c) / 255f; refreshAll() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }

        fun rebuildSwatches() {
            swatches.removeAllViews()
            val list: List<Int> = if (cur == 0) basic.toList() else palettes[cur - 1].colors
            palName.text = if (cur == 0) "Базовая" else palettes[cur - 1].name
            val items = ArrayList<View>()
            list.forEachIndexed { i, c ->
                items.add(dot(c, if (cur == 0) null else ({
                    palettes[cur - 1].colors.removeAt(i); save(ctx); rebuildSwatches()
                })))
            }
            if (cur != 0) items.add(IconButton(ctx, Ico.PLUS).apply {
                background = rounded(C.CARD2, ctx.dp(30f).toFloat())
                setOnClickListener { palettes[cur - 1].colors.add(color()); save(ctx); rebuildSwatches() }
            })
            var row: LinearLayout? = null
            items.forEachIndexed { i, v ->
                if (i % 6 == 0) { row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }; swatches.addView(row) }
                row!!.addView(v, lp(0, ctx.dp(52f), 1f))
            }
            val rest = items.size % 6
            if (rest != 0) repeat(6 - rest) { row!!.addView(View(ctx), lp(0, 1, 1f)) }
            recentRow.removeAllViews()
            for (c in recent.take(6)) recentRow.addView(dot(c, null), lp(0, ctx.dp(46f), 1f))
            repeat(6 - minOf(6, recent.size)) { recentRow.addView(View(ctx), lp(0, 1, 1f)) }
            recentHead.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        }

        refreshAll = {
            newDot.color = color(); newDot.invalidate()
            for (v in views) v.invalidate()
        }
        rebuildPal = { rebuildSwatches() }

        val scroll = ScrollView(ctx); scroll.addView(body)
        root.addView(scroll, lp(MATCH, 0, 1f))
        dlg.setContentView(root)
        refreshAll(); rebuildPal()
        dlg.show()
    }

    private fun paletteMenu(ctx: Context, done: () -> Unit) {
        val names = ArrayList<String>()
        names.add((if (cur == 0) "● " else "") + "Базовая")
        palettes.forEachIndexed { i, p -> names.add((if (cur == i + 1) "● " else "") + p.name + "  (${p.colors.size})") }
        names.add("+ Новая палитра")
        if (cur != 0) { names.add("Переименовать «${palettes[cur - 1].name}»"); names.add("Удалить «${palettes[cur - 1].name}»") }
        AlertDialog.Builder(ctx, R.style.DialogTheme).setTitle("Палитры").setItems(names.toTypedArray()) { _, w ->
            val newIdx = palettes.size + 1
            when {
                w <= palettes.size -> { cur = w; done() }
                w == newIdx -> askName(ctx, "Палитра ${palettes.size + 1}") { n -> palettes.add(Palette(n, ArrayList())); cur = palettes.size; done() }
                w == newIdx + 1 -> askName(ctx, palettes[cur - 1].name) { n -> palettes[cur - 1].name = n; done() }
                w == newIdx + 2 -> AlertDialog.Builder(ctx, R.style.DialogTheme).setTitle("Удалить палитру «${palettes[cur - 1].name}»?")
                    .setPositiveButton("Удалить") { _, _ -> palettes.removeAt(cur - 1); cur = 0; done() }
                    .setNegativeButton("Отмена", null).show()
            }
        }.show()
    }

    private fun askName(ctx: Context, def: String, ok: (String) -> Unit) {
        val et = EditText(ctx).apply { setText(def) }
        AlertDialog.Builder(ctx, R.style.DialogTheme).setTitle("Название палитры").setView(et)
            .setPositiveButton("ОК") { _, _ -> ok(et.text.toString().ifBlank { def }) }
            .setNegativeButton("Отмена", null).show()
    }

    // ---------- элементы ----------

    /** Квадрат: по горизонтали насыщенность, по вертикали яркость. */
    @SuppressLint("ViewConstructor")
    private class SVSquare(ctx: Context, val hsv: FloatArray, val changed: () -> Unit) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val k = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val rad = dp(18f).toFloat()
            p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p)
            p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p)
            val x = hsv[1] * width; val y = (1f - hsv[2]) * height
            k.style = Paint.Style.FILL; k.color = Color.HSVToColor(hsv); c.drawCircle(x, y, dp(14f).toFloat(), k)
            k.style = Paint.Style.STROKE; k.strokeWidth = dp(3f).toFloat(); k.color = Color.WHITE; c.drawCircle(x, y, dp(14f).toFloat(), k)
            k.strokeWidth = dp(1f).toFloat(); k.color = 0x66000000; c.drawCircle(x, y, dp(16f).toFloat(), k)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            hsv[1] = (e.x / width).coerceIn(0f, 1f)
            hsv[2] = (1f - e.y / height).coerceIn(0f, 1f)
            changed()
            return true
        }
    }

    /** Полоса с градиентом и кружком. */
    @SuppressLint("ViewConstructor")
    private class Bar(
        ctx: Context, val vertical: Boolean, val colors: () -> IntArray, val checker: Boolean,
        val getV: () -> Float, val setV: (Float) -> Unit, val knob: () -> Int
    ) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val k = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chk: Paint = Paint().apply {
            val cb = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            cb.setPixel(0, 0, 0xFF555555.toInt()); cb.setPixel(1, 1, 0xFF555555.toInt()); cb.setPixel(1, 0, 0xFF333333.toInt()); cb.setPixel(0, 1, 0xFF333333.toInt())
            shader = BitmapShader(cb, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { it.setLocalMatrix(Matrix().apply { setScale(10f, 10f) }) }
        }

        override fun onDraw(c: Canvas) {
            val r = RectF(2f, 2f, width - 2f, height - 2f)
            val rad = minOf(width, height) / 2f
            if (checker) c.drawRoundRect(r, rad, rad, chk)
            val cols = colors()
            p.shader = if (vertical) LinearGradient(0f, 0f, 0f, height.toFloat(), cols, null, Shader.TileMode.CLAMP)
                       else LinearGradient(0f, 0f, width.toFloat(), 0f, cols, null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p)
            k.style = Paint.Style.STROKE; k.strokeWidth = dp(1f).toFloat(); k.color = 0xFF666666.toInt()
            c.drawRoundRect(r, rad, rad, k)
            val kr = minOf(width, height) / 2f - dp(3f)
            val v = getV().coerceIn(0f, 1f)
            val x = if (vertical) width / 2f else kr + dp(3f) + v * (width - 2 * (kr + dp(3f)))
            val y = if (vertical) kr + dp(3f) + v * (height - 2 * (kr + dp(3f))) else height / 2f
            k.style = Paint.Style.FILL; k.color = knob(); c.drawCircle(x, y, kr, k)
            k.style = Paint.Style.STROKE; k.strokeWidth = dp(3f).toFloat(); k.color = Color.WHITE; c.drawCircle(x, y, kr, k)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val kr = minOf(width, height) / 2f
            val v = if (vertical) (e.y - kr) / (height - 2 * kr) else (e.x - kr) / (width - 2 * kr)
            setV(v.coerceIn(0f, 1f))
            return true
        }
    }
}
