package ru.leon.multastudia

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// =====================================================================
// Немного 3D-математики
// =====================================================================

class V3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun len() = sqrt(x * x + y * y + z * z)
    fun copy() = V3(x, y, z)
    fun toJson(): JSONArray = JSONArray().put(x.toDouble()).put(y.toDouble()).put(z.toDouble())

    companion object {
        fun lerp(a: V3, b: V3, t: Float) = V3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
        fun fromJson(a: JSONArray?) = if (a == null) V3() else V3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
    }
}

/** Кватернион — поворот в 3D. */
class Q(var w: Float = 1f, var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    operator fun times(o: Q) = Q(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w
    )

    fun conj() = Q(w, -x, -y, -z)
    fun copy() = Q(w, x, y, z)

    fun norm(): Q {
        val l = sqrt(w * w + x * x + y * y + z * z)
        return if (l < 1e-6f) Q() else Q(w / l, x / l, y / l, z / l)
    }

    fun rot(v: V3): V3 {
        val r = this * Q(0f, v.x, v.y, v.z) * conj()
        return V3(r.x, r.y, r.z)
    }

    fun toJson(): JSONArray = JSONArray().put(w.toDouble()).put(x.toDouble()).put(y.toDouble()).put(z.toDouble())

    companion object {
        fun axis(ax: Float, ay: Float, az: Float, deg: Float): Q {
            val l = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f)
            val h = Math.toRadians(deg.toDouble()).toFloat() / 2f
            val s = sin(h) / l
            return Q(cos(h), ax * s, ay * s, az * s)
        }

        fun slerp(a: Q, b0: Q, t: Float): Q {
            var b = b0
            var d = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            if (d < 0f) { b = Q(-b.w, -b.x, -b.y, -b.z); d = -d }
            if (d > 0.9995f) return Q(a.w + (b.w - a.w) * t, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t).norm()
            val th = acos(d.coerceIn(-1f, 1f))
            val s = sin(th)
            val ka = sin((1 - t) * th) / s; val kb = sin(t * th) / s
            return Q(a.w * ka + b.w * kb, a.x * ka + b.x * kb, a.y * ka + b.y * kb, a.z * ka + b.z * kb)
        }

        fun fromJson(a: JSONArray?) = if (a == null) Q() else Q(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
    }
}

// =====================================================================
// Скелет (шаблон человечка). y — вниз, z — к зрителю.
// =====================================================================

object Skel {
    class B(val title: String, val parent: Int, val head: V3, val tail: V3, val rigid: Boolean = false, val chainChild: Int = -1)

    val bones = listOf(
        B("Таз / низ тела", -1, V3(0f, 0f, 0f), V3(0f, -60f, 0f)),              // 0
        B("Грудь / торс", 0, V3(0f, -60f, 0f), V3(0f, -130f, 0f)),              // 1
        B("Шея", 1, V3(0f, -130f, 0f), V3(0f, -150f, 0f)),                      // 2
        B("Голова", 2, V3(0f, -150f, 0f), V3(0f, -235f, 0f), rigid = true),     // 3
        B("Плечо (лев.)", 1, V3(-46f, -124f, 0f), V3(-46f, -58f, 0f), chainChild = 5),   // 4
        B("Предплечье (лев.)", 4, V3(-46f, -58f, 0f), V3(-46f, 6f, 0f)),        // 5
        B("Кисть (лев.)", 5, V3(-46f, 6f, 0f), V3(-46f, 32f, 0f), rigid = true),// 6
        B("Плечо (прав.)", 1, V3(46f, -124f, 0f), V3(46f, -58f, 0f), chainChild = 8),    // 7
        B("Предплечье (прав.)", 7, V3(46f, -58f, 0f), V3(46f, 6f, 0f)),         // 8
        B("Кисть (прав.)", 8, V3(46f, 6f, 0f), V3(46f, 32f, 0f), rigid = true), // 9
        B("Бедро (лев.)", 0, V3(-22f, 0f, 0f), V3(-22f, 86f, 0f), chainChild = 11),      // 10
        B("Голень (лев.)", 10, V3(-22f, 86f, 0f), V3(-22f, 168f, 0f)),          // 11
        B("Стопа (лев.)", 11, V3(-22f, 168f, 0f), V3(-22f, 178f, 26f), rigid = true),    // 12
        B("Бедро (прав.)", 0, V3(22f, 0f, 0f), V3(22f, 86f, 0f), chainChild = 14),       // 13
        B("Голень (прав.)", 13, V3(22f, 86f, 0f), V3(22f, 168f, 0f)),           // 14
        B("Стопа (прав.)", 14, V3(22f, 168f, 0f), V3(22f, 178f, 26f), rigid = true)      // 15
    )
    val N get() = bones.size

    fun restLen2D(b: Int): Float { val v = bones[b].tail - bones[b].head; return hypot(v.x, v.y) }
    fun restAng(b: Int): Float { val v = bones[b].tail - bones[b].head; return Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat() }

    /** Прикреплён ли ребёнок к концу родителя (тогда растяжение родителя двигает ребёнка). */
    fun atParentTail(b: Int): Boolean {
        val p = bones[b].parent; if (p < 0) return false
        return (bones[b].head - bones[p].tail).len() < 1f
    }
}

// =====================================================================
// Поза
// =====================================================================

class Pose(var x: Float, var y: Float) {
    val rot = Array(Skel.N) { Q() }
    val stretch = FloatArray(Skel.N) { 1f }
    val off = Array(Skel.N) { V3() }
    var yaw = 0f
    var pitch = 0f
    var scale = 1f

    fun copy(): Pose {
        val p = Pose(x, y)
        for (i in 0 until Skel.N) { p.rot[i] = rot[i].copy(); p.stretch[i] = stretch[i]; p.off[i] = off[i].copy() }
        p.yaw = yaw; p.pitch = pitch; p.scale = scale
        return p
    }

    fun global(): Q = Q.axis(1f, 0f, 0f, pitch) * Q.axis(0f, 1f, 0f, yaw)

    fun toJson(): JSONObject {
        val o = JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("yaw", yaw.toDouble()).put("pitch", pitch.toDouble()).put("s", scale.toDouble())
        val r = JSONArray(); val st = JSONArray(); val of = JSONArray()
        for (i in 0 until Skel.N) { r.put(rot[i].toJson()); st.put(stretch[i].toDouble()); of.put(off[i].toJson()) }
        o.put("rot", r).put("st", st).put("off", of)
        return o
    }

    companion object {
        fun lerp(a: Pose, b: Pose, t: Float): Pose {
            fun l(u: Float, v: Float) = u + (v - u) * t
            val p = Pose(l(a.x, b.x), l(a.y, b.y))
            for (i in 0 until Skel.N) {
                p.rot[i] = Q.slerp(a.rot[i], b.rot[i], t)
                p.stretch[i] = l(a.stretch[i], b.stretch[i])
                p.off[i] = V3.lerp(a.off[i], b.off[i], t)
            }
            p.yaw = l(a.yaw, b.yaw); p.pitch = l(a.pitch, b.pitch); p.scale = l(a.scale, b.scale)
            return p
        }

        fun fromJson(o: JSONObject): Pose {
            val p = Pose(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
            p.yaw = o.optDouble("yaw", 0.0).toFloat(); p.pitch = o.optDouble("pitch", 0.0).toFloat(); p.scale = o.optDouble("s", 1.0).toFloat()
            val r = o.optJSONArray("rot"); val st = o.optJSONArray("st"); val of = o.optJSONArray("off")
            for (i in 0 until Skel.N) {
                if (r != null && i < r.length()) p.rot[i] = Q.fromJson(r.getJSONArray(i))
                if (st != null && i < st.length()) p.stretch[i] = st.getDouble(i).toFloat()
                if (of != null && i < of.length()) p.off[i] = V3.fromJson(of.getJSONArray(i))
            }
            return p
        }
    }
}

class PoseKey(val frameId: Int, var pose: Pose, var ease: Ease)

class PoseTrack {
    val keys = ArrayList<PoseKey>()
    fun keyAt(fid: Int) = keys.firstOrNull { it.frameId == fid }
    fun set(fid: Int, p: Pose, e: Ease) { val k = keyAt(fid); if (k != null) k.pose = p else keys.add(PoseKey(fid, p, e)) }
    fun remove(fid: Int) { keys.removeAll { it.frameId == fid } }

    fun eval(frames: List<Frame>, idx: Int, def: Pose): Pose {
        if (keys.isEmpty()) return def
        val pos = HashMap<Int, Int>(); frames.forEachIndexed { i, f -> pos[f.id] = i }
        val list = keys.mapNotNull { k -> pos[k.frameId]?.let { it to k } }.sortedBy { it.first }
        if (list.isEmpty()) return def
        if (idx <= list.first().first) return list.first().second.pose
        if (idx >= list.last().first) return list.last().second.pose
        for (n in 0 until list.size - 1) {
            val (i0, k0) = list[n]; val (i1, k1) = list[n + 1]
            if (idx in i0..i1) return Pose.lerp(k0.pose, k1.pose, k0.ease.apply((idx - i0).toFloat() / (i1 - i0)))
        }
        return list.last().second.pose
    }

    fun toJson(): JSONArray { val a = JSONArray(); for (k in keys) a.put(JSONObject().put("f", k.frameId).put("e", k.ease.name).put("p", k.pose.toJson())); return a }

    companion object {
        fun fromJson(a: JSONArray?): PoseTrack {
            val t = PoseTrack(); if (a == null) return t
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val e = try { Ease.valueOf(o.optString("e", "IN_OUT")) } catch (_: Throwable) { Ease.IN_OUT }
                t.keys.add(PoseKey(o.getInt("f"), Pose.fromJson(o.getJSONObject("p")), e))
            }
            return t
        }
    }
}

// =====================================================================
// Кусочки персонажа
// =====================================================================

const val VIEW_FRONT = 0
const val VIEW_SIDE = 1
const val VIEW_BACK = 2
val VIEW_NAMES = arrayOf("Спереди", "Сбоку", "Сзади")

class Part(val id: Int, var bone: Int, var view: Int) {
    var bendy = false
    var scale = 1f
    var along = 0f
    var across = 0f
    var rot = 0f
    var z = 0
    var bmp: Bitmap? = null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("bone", bone).put("view", view).put("bendy", bendy)
        .put("scale", scale.toDouble()).put("along", along.toDouble()).put("across", across.toDouble()).put("rot", rot.toDouble()).put("z", z)

    companion object {
        fun fromJson(o: JSONObject) = Part(o.getInt("id"), o.getInt("bone"), o.optInt("view", 0)).apply {
            bendy = o.optBoolean("bendy", false); scale = o.optDouble("scale", 1.0).toFloat()
            along = o.optDouble("along", 0.0).toFloat(); across = o.optDouble("across", 0.0).toFloat()
            rot = o.optDouble("rot", 0.0).toFloat(); z = o.optInt("z", 0)
        }
    }
}

class Rig {
    val parts = ArrayList<Part>()
    var track = PoseTrack()
    var nextPartId = 1

    fun toJson(): JSONObject {
        val pa = JSONArray(); for (p in parts) pa.put(p.toJson())
        return JSONObject().put("parts", pa).put("track", track.toJson()).put("next", nextPartId)
    }

    companion object {
        fun fromJson(o: JSONObject?): Rig {
            val r = Rig(); if (o == null) return r
            val pa = o.optJSONArray("parts")
            if (pa != null) for (i in 0 until pa.length()) r.parts.add(Part.fromJson(pa.getJSONObject(i)))
            r.track = PoseTrack.fromJson(o.optJSONArray("track"))
            r.nextPartId = o.optInt("next", r.parts.maxOfOrNull { it.id + 1 } ?: 1)
            return r
        }
    }
}

// =====================================================================
// Расчёт скелета и рисование
// =====================================================================

/** Скелет в позе: точки уже на экране мира (x,y) + глубина z. */
class Solved(val head: Array<V3>, val tail: Array<V3>, val world: Array<Q>)

object RigMath {
    /** Позиции в «пространстве персонажа» (до масштаба и сдвига). */
    fun solveLocal(p: Pose): Solved {
        val n = Skel.N
        val head = Array(n) { V3() }; val tail = Array(n) { V3() }; val world = Array(n) { Q() }
        val g = p.global()
        for (b in 0 until n) {
            val sb = Skel.bones[b]
            if (sb.parent < 0) {
                world[b] = (g * p.rot[b]).norm()
                head[b] = g.rot(sb.head) + p.off[b]
            } else {
                val pa = sb.parent
                world[b] = (world[pa] * p.rot[b]).norm()
                var rel = sb.head - Skel.bones[pa].head
                if (Skel.atParentTail(b)) rel = rel * p.stretch[pa]
                head[b] = head[pa] + world[pa].rot(rel) + p.off[b]
            }
            tail[b] = head[b] + world[b].rot((sb.tail - sb.head) * p.stretch[b])
        }
        return Solved(head, tail, world)
    }

    /** То же, но в координатах мира (холста). */
    fun solve(p: Pose): Solved {
        val s = solveLocal(p)
        fun scr(v: V3) = V3(p.x + v.x * p.scale, p.y + v.y * p.scale, v.z)
        return Solved(Array(Skel.N) { scr(s.head[it]) }, Array(Skel.N) { scr(s.tail[it]) }, s.world)
    }

    /** Какой вид кусочка показать и надо ли отразить. */
    fun facing(w: Q): Pair<Int, Boolean> {
        val f = w.rot(V3(0f, 0f, 1f))
        return when {
            f.z >= 0.70f -> VIEW_FRONT to false
            f.z <= -0.70f -> VIEW_BACK to false
            else -> VIEW_SIDE to (f.x < 0f)
        }
    }
}

object RigRender {
    private val mtx = Matrix()

    /** Выбрать для кости кусочки нужного вида (если нужного нет — ближайший). */
    private fun partsFor(rig: Rig, bone: Int, view: Int): List<Part> {
        val all = rig.parts.filter { it.bone == bone && it.bmp != null }
        if (all.isEmpty()) return all
        val exact = all.filter { it.view == view }
        if (exact.isNotEmpty()) return exact
        val order = when (view) { VIEW_SIDE -> listOf(VIEW_FRONT, VIEW_BACK); VIEW_BACK -> listOf(VIEW_FRONT, VIEW_SIDE); else -> listOf(VIEW_SIDE, VIEW_BACK) }
        for (v in order) { val l = all.filter { it.view == v }; if (l.isNotEmpty()) return l }
        return all
    }

    fun draw(c: Canvas, rig: Rig, pose: Pose, paint: Paint, s: Solved = RigMath.solve(pose)) {
        class Item(val part: Part, val depth: Float, val view: Int, val mirror: Boolean)
        val items = ArrayList<Item>()
        // кости, которые уже нарисованы гнущимся кусочком родителя
        for (b in 0 until Skel.N) {
            val (view, mirror) = RigMath.facing(s.world[b])
            for (pt in partsFor(rig, b, view)) {
                val ch = Skel.bones[b].chainChild
                val d = if (pt.bendy && ch >= 0) (s.head[b].z + s.tail[b].z + s.tail[ch].z) / 3 else (s.head[b].z + s.tail[b].z) / 2
                items.add(Item(pt, d + pt.z * 25f, view, mirror && pt.view == VIEW_SIDE))
            }
        }
        items.sortBy { it.depth }
        for (item in items) {
            val pt = item.part; val bmp = pt.bmp ?: continue
            val ch = Skel.bones[pt.bone].chainChild
            if (pt.bendy && ch >= 0) drawBendy(c, pt, bmp, pose, s, ch, item.mirror, paint)
            else drawRigid(c, pt, bmp, pose, s, item.mirror, paint)
        }
    }

    private fun drawRigid(c: Canvas, pt: Part, bmp: Bitmap, pose: Pose, s: Solved, mirror: Boolean, paint: Paint) {
        val b = pt.bone
        val h = s.head[b]; val t = s.tail[b]
        val dx = t.x - h.x; val dy = t.y - h.y
        val len = hypot(dx, dy)
        val rest = Skel.restLen2D(b)
        val ang = if (len > 0.5f) Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() else Skel.restAng(b)
        var k = 1f
        if (!Skel.bones[b].rigid && rest > 1f) k = (len / (rest * pose.scale)).coerceIn(0.3f, 2.5f)
        val fs = pt.scale * pose.scale
        mtx.setTranslate(-bmp.width / 2f, -bmp.height / 2f)
        mtx.postScale(fs * (if (mirror) -1f else 1f), fs)
        mtx.postRotate(pt.rot)
        mtx.postTranslate(pt.across * pose.scale, pt.along * pose.scale)
        mtx.postScale(1f, k)
        mtx.postRotate(ang - Skel.restAng(b))
        mtx.postTranslate((h.x + t.x) / 2, (h.y + t.y) / 2)
        c.drawBitmap(bmp, mtx, paint)
    }

    private const val ROWS = 18

    private fun drawBendy(c: Canvas, pt: Part, bmp: Bitmap, pose: Pose, s: Solved, ch: Int, mirror: Boolean, paint: Paint) {
        val b = pt.bone
        val p0x = s.head[b].x; val p0y = s.head[b].y
        val p1x = s.tail[b].x; val p1y = s.tail[b].y
        val p2x = s.tail[ch].x; val p2y = s.tail[ch].y
        // контрольная точка, чтобы кривая проходила через локоть/колено — «резиновая» рука
        val cx = 2 * p1x - (p0x + p2x) / 2; val cy = 2 * p1y - (p0y + p2y) / 2
        val restL = Skel.restLen2D(b) + Skel.restLen2D(ch)
        val fs = pt.scale * pose.scale
        val w = bmp.width.toFloat(); val hgt = bmp.height.toFloat()
        val verts = FloatArray((ROWS + 1) * 2 * 2)
        for (r in 0..ROWS) {
            val yImg = hgt * r / ROWS
            val dRest = (yImg - hgt / 2) * pt.scale + restL / 2 + pt.along
            val u = dRest / restL
            val uc = u.coerceIn(0f, 1f)
            var px = (1 - uc) * (1 - uc) * p0x + 2 * uc * (1 - uc) * cx + uc * uc * p2x
            var py = (1 - uc) * (1 - uc) * p0y + 2 * uc * (1 - uc) * cy + uc * uc * p2y
            var tx = 2 * (1 - uc) * (cx - p0x) + 2 * uc * (p2x - cx)
            var ty = 2 * (1 - uc) * (cy - p0y) + 2 * uc * (p2y - cy)
            val tl = hypot(tx, ty).coerceAtLeast(1e-3f); tx /= tl; ty /= tl
            if (u < 0f || u > 1f) {
                // за концами — продолжаем по прямой
                val extra = (u - uc) * restL * pose.scale
                px += tx * extra; py += ty * extra
            }
            val nx = -ty; val ny = tx
            for (col in 0..1) {
                val off = ((if (col == 0) 0f else w) - w / 2) * fs * (if (mirror) -1f else 1f) + pt.across * pose.scale
                val i = (r * 2 + col) * 2
                verts[i] = px - nx * off
                verts[i + 1] = py - ny * off
            }
        }
        c.drawBitmapMesh(bmp, 1, ROWS, verts, 0, null, 0, paint)
    }

    /** Скелет поверх (для режима позы и мастерской). */
    fun drawBones(c: Canvas, s: Solved, sel: Int, lineW: Float, r: Float, p: Paint) {
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        for (b in 0 until Skel.N) {
            p.strokeWidth = lineW * (if (b == sel) 2.2f else 1.4f)
            p.color = if (b == sel) C.ACCENT else 0xCC7C4DFF.toInt()
            c.drawLine(s.head[b].x, s.head[b].y, s.tail[b].x, s.tail[b].y, p)
        }
        p.style = Paint.Style.FILL
        for (b in 0 until Skel.N) {
            p.color = if (b == sel) C.ACCENT else 0xFFFFFFFF.toInt()
            c.drawCircle(s.tail[b].x, s.tail[b].y, r, p)
        }
        p.color = 0xFFFFC107.toInt()
        c.drawCircle(s.head[0].x, s.head[0].y, r * 1.3f, p)
    }

    /** Подогнать новый кусочек под кость. */
    fun autoFit(pt: Part, bmp: Bitmap) {
        val b = pt.bone
        val ch = Skel.bones[b].chainChild
        val len = if (pt.bendy && ch >= 0) Skel.restLen2D(b) + Skel.restLen2D(ch) else maxOf(Skel.restLen2D(b), 30f)
        pt.scale = (len * 1.15f / bmp.height).coerceIn(0.05f, 20f)
        if (Skel.bones[b].rigid && b == 3) pt.scale = (95f / maxOf(bmp.width, bmp.height)).coerceIn(0.05f, 20f) // голова
    }
}

/** Повороты кости в позе. */
object PoseEdit {
    /** Повернуть кость b вокруг оси экрана (в мире персонажа). */
    fun rotateWorld(p: Pose, b: Int, axis: Q, startPose: Pose) {
        val s0 = RigMath.solveLocal(startPose)
        val wNew = axis * s0.world[b]
        val parentW = if (Skel.bones[b].parent >= 0) s0.world[Skel.bones[b].parent] else startPose.global()
        p.rot[b] = (parentW.conj() * wNew).norm()
    }

    fun screenAngle(ax: Float, ay: Float, bx: Float, by: Float) = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()

    fun isSmall(v: Float) = abs(v) < 1e-4f
}
