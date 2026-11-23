package ru.leon.multastudia

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Плавности, как в Adobe. */
enum class Ease(val title: String) {
    LINEAR("Линейно"),
    IN("Разгон (ease in)"),
    OUT("Торможение (ease out)"),
    IN_OUT("Плавно (ease in-out)"),
    BACK_IN("Замах (back in)"),
    BACK_OUT("Перелёт (back out)"),
    BACK_IN_OUT("Замах и перелёт (back in-out)"),
    BOUNCE_OUT("Отскок (bounce out)"),
    BOUNCE_IN("Отскок в начале (bounce in)"),
    ELASTIC_OUT("Пружина (elastic out)"),
    ELASTIC_IN("Пружина в начале (elastic in)"),
    STEP("Резко (без перехода)");

    fun apply(t0: Float): Float {
        val t = t0.coerceIn(0f, 1f)
        val c1 = 1.70158f; val c2 = c1 * 1.525f; val c3 = c1 + 1f
        return when (this) {
            LINEAR -> t
            IN -> t * t * t
            OUT -> 1f - (1f - t).pow(3)
            IN_OUT -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
            BACK_IN -> c3 * t * t * t - c1 * t * t
            BACK_OUT -> 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
            BACK_IN_OUT -> if (t < 0.5f) ((2f * t).pow(2) * ((c2 + 1f) * 2f * t - c2)) / 2f
                           else ((2f * t - 2f).pow(2) * ((c2 + 1f) * (t * 2f - 2f) + c2) + 2f) / 2f
            BOUNCE_OUT -> bounce(t)
            BOUNCE_IN -> 1f - bounce(1f - t)
            ELASTIC_OUT -> if (t == 0f || t == 1f) t else 2f.pow(-10f * t) * sin((t * 10f - 0.75f) * (2f * PI.toFloat() / 3f)) + 1f
            ELASTIC_IN -> if (t == 0f || t == 1f) t else -(2f.pow(10f * t - 10f)) * sin((t * 10f - 10.75f) * (2f * PI.toFloat() / 3f))
            STEP -> if (t >= 1f) 1f else 0f
        }
    }

    private fun bounce(x: Float): Float {
        val n1 = 7.5625f; val d1 = 2.75f
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> { val y = x - 1.5f / d1; n1 * y * y + 0.75f }
            x < 2.5f / d1 -> { val y = x - 2.25f / d1; n1 * y * y + 0.9375f }
            else -> { val y = x - 2.625f / d1; n1 * y * y + 0.984375f }
        }
    }
}

/** Положение объекта: точка опоры (px,py в рисунке) стоит в (x,y) мира, плюс масштаб, поворот, прозрачность. */
class Xf(
    var x: Float, var y: Float,
    var sx: Float = 1f, var sy: Float = 1f,
    var rot: Float = 0f, var alpha: Float = 1f,
    var px: Float = x, var py: Float = y
) {
    fun copy() = Xf(x, y, sx, sy, rot, alpha, px, py)

    fun matrix(m: Matrix = Matrix()): Matrix {
        m.setTranslate(-px, -py)
        m.postScale(sx, sy)
        m.postRotate(rot)
        m.postTranslate(x, y)
        return m
    }

    fun toJson(): JSONObject = JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("sx", sx.toDouble()).put("sy", sy.toDouble())
        .put("r", rot.toDouble()).put("a", alpha.toDouble()).put("px", px.toDouble()).put("py", py.toDouble())

    companion object {
        fun lerp(a: Xf, b: Xf, t: Float): Xf {
            fun l(u: Float, v: Float) = u + (v - u) * t
            return Xf(l(a.x, b.x), l(a.y, b.y), l(a.sx, b.sx), l(a.sy, b.sy), l(a.rot, b.rot), l(a.alpha, b.alpha), l(a.px, b.px), l(a.py, b.py))
        }

        fun fromJson(o: JSONObject) = Xf(
            o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.optDouble("sx", 1.0).toFloat(), o.optDouble("sy", 1.0).toFloat(),
            o.optDouble("r", 0.0).toFloat(), o.optDouble("a", 1.0).toFloat(), o.getDouble("px").toFloat(), o.getDouble("py").toFloat()
        )
    }
}

class Key(val frameId: Int, var xf: Xf, var ease: Ease)

/** Дорожка ключевых кадров (у слоя-объекта или у камеры). */
class Track {
    val keys = ArrayList<Key>()

    fun keyAt(frameId: Int) = keys.firstOrNull { it.frameId == frameId }
    val isEmpty get() = keys.isEmpty()

    fun set(frameId: Int, xf: Xf, ease: Ease) {
        val k = keyAt(frameId)
        if (k != null) k.xf = xf else keys.add(Key(frameId, xf, ease))
    }

    fun remove(frameId: Int) { keys.removeAll { it.frameId == frameId } }

    /** Положение на кадре с номером idx. */
    fun eval(frames: List<Frame>, idx: Int, def: Xf): Xf {
        if (keys.isEmpty()) return def
        val pos = HashMap<Int, Int>()
        frames.forEachIndexed { i, f -> pos[f.id] = i }
        val list = keys.mapNotNull { k -> pos[k.frameId]?.let { it to k } }.sortedBy { it.first }
        if (list.isEmpty()) return def
        if (idx <= list.first().first) return list.first().second.xf
        if (idx >= list.last().first) return list.last().second.xf
        for (n in 0 until list.size - 1) {
            val (i0, k0) = list[n]; val (i1, k1) = list[n + 1]
            if (idx in i0..i1) {
                val t = (idx - i0).toFloat() / (i1 - i0)
                return Xf.lerp(k0.xf, k1.xf, k0.ease.apply(t))
            }
        }
        return list.last().second.xf
    }

    fun toJson(): JSONArray {
        val a = JSONArray()
        for (k in keys) a.put(JSONObject().put("f", k.frameId).put("e", k.ease.name).put("xf", k.xf.toJson()))
        return a
    }

    companion object {
        fun fromJson(a: JSONArray?): Track {
            val t = Track()
            if (a == null) return t
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val e = try { Ease.valueOf(o.optString("e", "LINEAR")) } catch (_: Throwable) { Ease.LINEAR }
                t.keys.add(Key(o.getInt("f"), Xf.fromJson(o.getJSONObject("xf")), e))
            }
            return t
        }
    }
}

/** Маленький график плавности. */
class CurveView(ctx: Context, var ease: Ease, private val color: Int = C.ACCENT) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val pad = height * 0.22f
        val w = width - pad * 2; val h = height - pad * 2
        p.color = 0xFF555555.toInt(); p.strokeWidth = dp(1f).toFloat()
        c.drawRect(pad, pad, pad + w, pad + h, p)
        val path = Path()
        for (i in 0..48) {
            val t = i / 48f
            val x = pad + w * t
            val y = pad + h - h * ease.apply(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        p.color = color; p.strokeWidth = dp(2f).toFloat()
        c.drawPath(path, p)
    }
}

object EasePicker {
    fun show(ctx: Context, current: Ease, onPick: (Ease) -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(10f), ctx.dp(6f), ctx.dp(10f), ctx.dp(6f)) }
        val scroll = ScrollView(ctx); scroll.addView(box)
        val dlg = AlertDialog.Builder(ctx, R.style.DialogTheme).setTitle("Плавность").setView(scroll).setNegativeButton("Отмена", null).create()
        for (e in Ease.values()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (e == current) C.ACCENT else C.CARD2, ctx.dp(12f).toFloat())
                setPadding(ctx.dp(6f), ctx.dp(2f), ctx.dp(10f), ctx.dp(2f))
            }
            row.addView(CurveView(ctx, e, if (e == current) 0xFFFFFFFF.toInt() else C.ACCENT), lp(ctx.dp(64f), ctx.dp(52f)))
            row.addView(ctx.label(e.title, 15f), lp(0, WRAP, 1f))
            row.setOnClickListener { onPick(e); dlg.dismiss() }
            box.addView(row, lp(MATCH, WRAP).apply { bottomMargin = ctx.dp(6f) })
        }
        dlg.show()
    }
}
