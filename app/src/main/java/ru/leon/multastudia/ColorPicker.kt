package ru.leon.multastudia

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

object ColorPicker {
    private val presets = intArrayOf(
        0xFF000000.toInt(), 0xFF555555.toInt(), 0xFFAAAAAA.toInt(), 0xFFFFFFFF.toInt(), 0xFF8B4513.toInt(), 0xFFFFD1A4.toInt(),
        0xFFE53935.toInt(), 0xFFFF7043.toInt(), 0xFFFFB300.toInt(), 0xFFFFEB3B.toInt(), 0xFF8BC34A.toInt(), 0xFF2E7D32.toInt(),
        0xFF00BCD4.toInt(), 0xFF1E88E5.toInt(), 0xFF283593.toInt(), 0xFF8E24AA.toInt(), 0xFFFF4F7B.toInt(), 0xFFF8BBD0.toInt()
    )
    val recent = ArrayList<Int>()

    fun show(ctx: Context, start: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(start, hsv)
        var current = start

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(18f), ctx.dp(14f), ctx.dp(18f), ctx.dp(6f))
        }
        val preview = View(ctx).apply { background = rounded(start, ctx.dp(10f).toFloat()) }
        root.addView(preview, lp(MATCH, ctx.dp(44f)))

        lateinit var hS: LinearLayout
        lateinit var sS: LinearLayout
        lateinit var vS: LinearLayout
        fun update() {
            current = Color.HSVToColor(hsv)
            preview.background = rounded(current, ctx.dp(10f).toFloat())
        }

        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cols = 6
        var row: LinearLayout? = null
        val all = presets.toList() + recent.take(6)
        all.forEachIndexed { i, col ->
            if (i % cols == 0) { row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; grid.addView(row) }
            val d = ColorDot(ctx, col)
            d.setOnClickListener {
                Color.colorToHSV(col, hsv); update()
                rebuildSliders(hS, sS, vS)
            }
            row!!.addView(d, lp(0, ctx.dp(42f), 1f))
        }
        root.addView(grid)

        hS = ctx.sliderRow("Оттенок", 0, 360, hsv[0].toInt()) { hsv[0] = it.toFloat(); update() }
        sS = ctx.sliderRow("Насыщенность", 0, 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f; update() }
        vS = ctx.sliderRow("Яркость", 0, 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f; update() }
        hS.tag = { hsv[0].toInt() }; sS.tag = { (hsv[1] * 100).toInt() }; vS.tag = { (hsv[2] * 100).toInt() }
        root.addView(hS); root.addView(sS); root.addView(vS)

        AlertDialog.Builder(ctx, R.style.DialogTheme)
            .setTitle("Цвет")
            .setView(root)
            .setPositiveButton("Готово") { _, _ ->
                recent.remove(current); recent.add(0, current)
                onPick(current)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @Suppress("UNCHECKED_CAST")
    private fun rebuildSliders(vararg rows: LinearLayout) {
        for (r in rows) {
            val sb = r.getChildAt(1) as android.widget.SeekBar
            val getter = r.tag as () -> Int
            sb.progress = getter()
        }
    }
}
