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
// Скелет. y — вниз, z — к зрителю.
// Шаблон человечка + свои кости (хвост, уши, что угодно).
// =====================================================================

object Skel {
    class B(
        val title: String, val parent: Int, val head: V3, val tail: V3,
        val rigid: Boolean = false, val chainChild: Int = -1, val extra: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().put("t", title).put("p", parent).put("h", head.toJson()).put("e", tail.toJson())
        companion object {
            fun fromJson(o: JSONObject) = B(o.optString("t", "Кость"), o.getInt("p"), V3.fromJson(o.getJSONArray("h")), V3.fromJson(o.getJSONArray("e")), extra = true)
        }
    }

    /** Наибольшее число костей в одном персонаже. */
    const val MAX = 64

    val base = listOf(
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

    fun restLen2D(bs: List<B>, b: Int): Float { val v = bs[b].tail - bs[b].head; return hypot(v.x, v.y) }
    fun restAng(bs: List<B>, b: Int): Float { val v = bs[b].tail - bs[b].head; return Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat() }

    /** Прикреплён ли ребёнок к концу родителя (тогда растяжение родителя двигает ребёнка). */
    fun atParentTail(bs: List<B>, b: Int): Boolean {
        val p = bs[b].parent; if (p < 0) return false
        return (bs[b].head - bs[p].tail).len() < 1f
    }

    /** Цепочка костей, по которой гнётся одна картинка (рука, нога, хвост). */
    fun chain(bs: List<B>, b: Int): List<Int> {
        val cc = bs[b].chainChild
        if (cc >= 0) return listOf(b, cc)
        if (!bs[b].extra) return listOf(b)
        val out = arrayListOf(b)
        var cur = b
        while (out.size < 16) {
            val kids = bs.indices.filter { bs[it].parent == cur && atParentTail(bs, it) }
            if (kids.size != 1) break
            cur = kids[0]; out.add(cur)
        }
        return out
    }

    fun canBend(bs: List<B>, b: Int) = chain(bs, b).size >= 2

    fun chainLen(bs: List<B>, b: Int) = chain(bs, b).sumOf { restLen2D(bs, it).toDouble() }.toFloat()
}

// =====================================================================
// Поза
// =====================================================================

class Pose(var x: Float, var y: Float) {
    val rot = Array(Skel.MAX) { Q() }
    val stretch = FloatArray(Skel.MAX) { 1f }
    val off = Array(Skel.MAX) { V3() }
    var yaw = 0f
    var pitch = 0f
    var scale = 1f

    fun copy(): Pose {
        val p = Pose(x, y)
        for (i in 0 until Skel.MAX) { p.rot[i] = rot[i].copy(); p.stretch[i] = stretch[i]; p.off[i] = off[i].copy() }
        p.yaw = yaw; p.pitch = pitch; p.scale = scale
        return p
    }

    fun global(): Q = Q.axis(1f, 0f, 0f, pitch) * Q.axis(0f, 1f, 0f, yaw)

    /** Переставить кости после удаления (map[старый] = новый или -1). */
    fun remap(map: IntArray, n: Int): Pose {
        val p = copy()
        for (i in 0 until Skel.MAX) { p.rot[i] = Q(); p.stretch[i] = 1f; p.off[i] = V3() }
        for (old in 0 until minOf(n, Skel.MAX)) {
            val nw = map[old]; if (nw < 0) continue
            p.rot[nw] = rot[old].copy(); p.stretch[nw] = stretch[old]; p.off[nw] = off[old].copy()
        }
        return p
    }

    fun toJson(n: Int): JSONObject {
        val o = JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("yaw", yaw.toDouble()).put("pitch", pitch.toDouble()).put("s", scale.toDouble())
        val r = JSONArray(); val st = JSONArray(); val of = JSONArray()
        for (i in 0 until minOf(n, Skel.MAX)) { r.put(rot[i].toJson()); st.put(stretch[i].toDouble()); of.put(off[i].toJson()) }
        o.put("rot", r).put("st", st).put("off", of)
        return o
    }

    companion object {
        fun lerp(a: Pose, b: Pose, t: Float): Pose {
            fun l(u: Float, v: Float) = u + (v - u) * t
            val p = Pose(l(a.x, b.x), l(a.y, b.y))
            for (i in 0 until Skel.MAX) {
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
            for (i in 0 until Skel.MAX) {
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

    fun toJson(n: Int): JSONArray { val a = JSONArray(); for (k in keys) a.put(JSONObject().put("f", k.frameId).put("e", k.ease.name).put("p", k.pose.toJson(n))); return a }

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
    val extra = ArrayList<Skel.B>()
    private var cache: List<Skel.B>? = null

    fun bones(): List<Skel.B> { cache?.let { return it }; val l = Skel.base + extra; cache = l; return l }
    private fun changed() { cache = null }

    /** Добавить свою кость. angle — направление на виде спереди (90° — вниз), depth — к зрителю(+)/от зрителя(−). */
    fun addBone(title: String, parent: Int, fromEnd: Boolean, angle: Float, len: Float, depth: Float): Int {
        if (bones().size >= Skel.MAX) return -1
        val pb = bones()[parent]
        val head = (if (fromEnd) pb.tail else pb.head).copy()
        extra.add(Skel.B(title, parent, head, head + dirVec(angle, len, depth), extra = true))
        changed()
        return bones().size - 1
    }

    fun dirVec(angle: Float, len: Float, depth: Float): V3 {
        val a = Math.toRadians(angle.toDouble())
        return V3((cos(a) * len).toFloat(), (sin(a) * len).toFloat(), depth / 100f * len)
    }

    /** Поменять направление/длину своей кости; всё, что на ней висит, переедет следом. */
    fun setBone(b: Int, title: String, angle: Float, len: Float, depth: Float) {
        val bs = bones()
        val ei = b - Skel.base.size
        if (ei < 0) return
        val old = bs[b]
        val newTail = old.head + dirVec(angle, len, depth)
        val delta = newTail - old.tail
        extra[ei] = Skel.B(title, old.parent, old.head, newTail, extra = true)
        // сдвинуть потомков
        fun shift(parent: Int) {
            for (i in bs.indices) if (bs[i].parent == parent && i >= Skel.base.size) {
                val e = extra[i - Skel.base.size]
                extra[i - Skel.base.size] = Skel.B(e.title, e.parent, e.head + delta, e.tail + delta, extra = true)
                shift(i)
            }
        }
        shift(b)
        changed()
    }

    /** Удалить свою кость вместе с тем, что на ней висит. */
    fun removeBone(b: Int) {
        val bs = bones()
        if (b < Skel.base.size) return
        val dead = HashSet<Int>(); dead.add(b)
        var grew = true
        while (grew) { grew = false; for (i in bs.indices) if (i !in dead && bs[i].parent in dead) { dead.add(i); grew = true } }
        val map = IntArray(bs.size) { -1 }
        var n = 0
        for (i in bs.indices) if (i !in dead) map[i] = n++
        val newExtra = ArrayList<Skel.B>()
        for (i in Skel.base.size until bs.size) if (i !in dead) {
            val e = bs[i]; newExtra.add(Skel.B(e.title, map[e.parent], e.head, e.tail, extra = true))
        }
        extra.clear(); extra.addAll(newExtra)
        parts.removeAll { it.bone in dead }
        for (pt in parts) pt.bone = map[pt.bone]
        for (k in track.keys) k.pose = k.pose.remap(map, bs.size)
        changed()
    }

    fun boneAngle(b: Int) = Skel.restAng(bones(), b)
    fun boneLen(b: Int) = (bones()[b].tail - bones()[b].head).let { hypot(it.x, it.y) }
    fun boneDepth(b: Int): Float { val v = bones()[b].tail - bones()[b].head; val l = hypot(v.x, v.y); return if (l < 1e-3f) 0f else v.z / l * 100f }

    fun toJson(): JSONObject {
        val pa = JSONArray(); for (p in parts) pa.put(p.toJson())
        val ex = JSONArray(); for (b in extra) ex.put(b.toJson())
        return JSONObject().put("parts", pa).put("track", track.toJson(bones().size)).put("next", nextPartId).put("extra", ex)
    }

    companion object {
        fun fromJson(o: JSONObject?): Rig {
            val r = Rig(); if (o == null) return r
            val pa = o.optJSONArray("parts")
            if (pa != null) for (i in 0 until pa.length()) r.parts.add(Part.fromJson(pa.getJSONObject(i)))
            val ex = o.optJSONArray("extra")
            if (ex != null) for (i in 0 until ex.length()) r.extra.add(Skel.B.fromJson(ex.getJSONObject(i)))
            r.track = PoseTrack.fromJson(o.optJSONArray("track"))
            r.nextPartId = o.optInt("next", r.parts.maxOfOrNull { it.id + 1 } ?: 1)
            return r
        }
    }
}

// =====================================================================
// Расчёт скелета и рисование
// =====================================================================

/** Скелет в позе: точки (x,y) + глубина z. */
class Solved(val head: Array<V3>, val tail: Array<V3>, val world: Array<Q>) {
    val n get() = head.size
}

object RigMath {
    /** Позиции в «пространстве персонажа» (до масштаба и сдвига). */
    fun solveLocal(p: Pose, bs: List<Skel.B>): Solved {
        val n = bs.size
        val head = Array(n) { V3() }; val tail = Array(n) { V3() }; val world = Array(n) { Q() }
        val g = p.global()
        for (b in 0 until n) {
            val sb = bs[b]
            if (sb.parent < 0) {
                world[b] = (g * p.rot[b]).norm()
                head[b] = g.rot(sb.head) + p.off[b]
            } else {
                val pa = sb.parent
                world[b] = (world[pa] * p.rot[b]).norm()
                var rel = sb.head - bs[pa].head
                if (Skel.atParentTail(bs, b)) rel = rel * p.stretch[pa]
                head[b] = head[pa] + world[pa].rot(rel) + p.off[b]
            }
            tail[b] = head[b] + world[b].rot((sb.tail - sb.head) * p.stretch[b])
        }
        return Solved(head, tail, world)
    }

    /** То же, но в координатах мира (холста). */
    fun solve(p: Pose, bs: List<Skel.B>): Solved {
        val s = solveLocal(p, bs)
        fun scr(v: V3) = V3(p.x + v.x * p.scale, p.y + v.y * p.scale, v.z)
        return Solved(Array(s.n) { scr(s.head[it]) }, Array(s.n) { scr(s.tail[it]) }, s.world)
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

    fun draw(c: Canvas, rig: Rig, pose: Pose, paint: Paint, s: Solved = RigMath.solve(pose, rig.bones())) {
        val bs = rig.bones()
        class Item(val part: Part, val depth: Float, val mirror: Boolean, val chain: List<Int>)
        val items = ArrayList<Item>()
        for (b in 0 until minOf(bs.size, s.n)) {
            val (view, mirror) = RigMath.facing(s.world[b])
            for (pt in partsFor(rig, b, view)) {
                val ch = if (pt.bendy) Skel.chain(bs, b) else listOf(b)
                var d = 0f
                for (k in ch) d += (s.head[k].z + s.tail[k].z) / 2
                d /= ch.size
                items.add(Item(pt, d + pt.z * 25f, mirror && pt.view == VIEW_SIDE, ch))
            }
        }
        items.sortBy { it.depth }
        val m = Matrix()
        for (item in items) {
            val pt = item.part; val bmp = pt.bmp ?: continue
            if (item.chain.size >= 2) drawBendy(c, pt, bmp, pose, s, bs, item.chain, item.mirror, paint)
            else drawRigid(c, pt, bmp, pose, s, bs, item.mirror, paint, m)
        }
    }

    private fun drawRigid(c: Canvas, pt: Part, bmp: Bitmap, pose: Pose, s: Solved, bs: List<Skel.B>, mirror: Boolean, paint: Paint, mtx: Matrix) {
        val b = pt.bone
        val h = s.head[b]; val t = s.tail[b]
        val dx = t.x - h.x; val dy = t.y - h.y
        val len = hypot(dx, dy)
        val rest = Skel.restLen2D(bs, b)
        val ang = if (len > 0.5f) Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() else Skel.restAng(bs, b)
        var k = 1f
        if (!bs[b].rigid && rest > 1f) k = (len / (rest * pose.scale)).coerceIn(0.3f, 2.5f)
        val fs = pt.scale * pose.scale
        mtx.setTranslate(-bmp.width / 2f, -bmp.height / 2f)
        mtx.postScale(fs * (if (mirror) -1f else 1f), fs)
        mtx.postRotate(pt.rot)
        mtx.postTranslate(pt.across * pose.scale, pt.along * pose.scale)
        // растяжение вдоль кости (в «спокойной» системе кость смотрит под углом restAng)
        val ra = Skel.restAng(bs, b)
        mtx.postRotate(-ra); mtx.postScale(k, 1f); mtx.postRotate(ra)
        mtx.postRotate(ang - ra)
        mtx.postTranslate((h.x + t.x) / 2, (h.y + t.y) / 2)
        c.drawBitmap(bmp, mtx, paint)
    }

    private const val ROWS_PER_BONE = 10

    /** Картинка изгибается по плавной кривой через все суставы цепочки. */
    private fun drawBendy(c: Canvas, pt: Part, bmp: Bitmap, pose: Pose, s: Solved, bs: List<Skel.B>, chain: List<Int>, mirror: Boolean, paint: Paint) {
        val pts = ArrayList<FloatArray>()
        pts.add(floatArrayOf(s.head[chain[0]].x, s.head[chain[0]].y))
        for (b in chain) pts.add(floatArrayOf(s.tail[b].x, s.tail[b].y))
        val restSeg = chain.map { Skel.restLen2D(bs, it).coerceAtLeast(1f) }
        val cum = FloatArray(restSeg.size + 1)
        for (i in restSeg.indices) cum[i + 1] = cum[i] + restSeg[i]
        val restL = cum.last()
        val nP = pts.size

        fun pAt(i: Int): FloatArray = when {
            i < 0 -> floatArrayOf(2 * pts[0][0] - pts[1][0], 2 * pts[0][1] - pts[1][1])
            i >= nP -> floatArrayOf(2 * pts[nP - 1][0] - pts[nP - 2][0], 2 * pts[nP - 1][1] - pts[nP - 2][1])
            else -> pts[i]
        }
        fun curve(d: Float): FloatArray {
            val dd = d.coerceIn(0f, restL)
            var seg = 0
            while (seg < restSeg.size - 1 && dd > cum[seg + 1]) seg++
            val t = ((dd - cum[seg]) / restSeg[seg]).coerceIn(0f, 1f)
            val p0 = pAt(seg - 1); val p1 = pAt(seg); val p2 = pAt(seg + 1); val p3 = pAt(seg + 2)
            val t2 = t * t; val t3 = t2 * t
            fun cr(a: Float, b: Float, cc: Float, e: Float) = 0.5f * (2 * b + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - e) * t2 + (-a + 3 * b - 3 * cc + e) * t3)
            return floatArrayOf(cr(p0[0], p1[0], p2[0], p3[0]), cr(p0[1], p1[1], p2[1], p3[1]))
        }

        val rows = ROWS_PER_BONE * chain.size
        val fs = pt.scale * pose.scale
        val w = bmp.width.toFloat(); val hgt = bmp.height.toFloat()
        val verts = FloatArray((rows + 1) * 2 * 2)
        val eps = restL * 0.01f
        for (r in 0..rows) {
            val yImg = hgt * r / rows
            val d = (yImg - hgt / 2) * pt.scale + restL / 2 + pt.along
            val dc = d.coerceIn(0f, restL)
            val a = curve(dc - eps); val b = curve(dc + eps)
            var tx = b[0] - a[0]; var ty = b[1] - a[1]
            val tl = hypot(tx, ty).coerceAtLeast(1e-4f); tx /= tl; ty /= tl
            val pc = curve(dc)
            var px = pc[0]; var py = pc[1]
            if (d != dc) { val extra = (d - dc) * pose.scale; px += tx * extra; py += ty * extra }
            val nx = -ty; val ny = tx
            for (col in 0..1) {
                val off = ((if (col == 0) 0f else w) - w / 2) * fs * (if (mirror) -1f else 1f) + pt.across * pose.scale
                val i = (r * 2 + col) * 2
                verts[i] = px - nx * off
                verts[i + 1] = py - ny * off
            }
        }
        c.drawBitmapMesh(bmp, 1, rows, verts, 0, null, 0, paint)
    }

    /** Скелет поверх (для режима позы и мастерской). */
    fun drawBones(c: Canvas, s: Solved, sel: Int, lineW: Float, r: Float, p: Paint, extraFrom: Int = Skel.base.size) {
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        for (b in 0 until s.n) {
            p.strokeWidth = lineW * (if (b == sel) 2.2f else 1.4f)
            p.color = when { b == sel -> C.ACCENT; b >= extraFrom -> 0xCC00BCD4.toInt(); else -> 0xCC7C4DFF.toInt() }
            c.drawLine(s.head[b].x, s.head[b].y, s.tail[b].x, s.tail[b].y, p)
        }
        p.style = Paint.Style.FILL
        for (b in 0 until s.n) {
            p.color = if (b == sel) C.ACCENT else 0xFFFFFFFF.toInt()
            c.drawCircle(s.tail[b].x, s.tail[b].y, r, p)
        }
        p.color = 0xFFFFC107.toInt()
        c.drawCircle(s.head[0].x, s.head[0].y, r * 1.3f, p)
    }

    /** Подогнать новый кусочек под кость. */
    fun autoFit(pt: Part, bmp: Bitmap, bs: List<Skel.B>) {
        val b = pt.bone
        val len = if (pt.bendy && Skel.canBend(bs, b)) Skel.chainLen(bs, b) else maxOf(Skel.restLen2D(bs, b), 30f)
        pt.scale = (len * 1.15f / bmp.height).coerceIn(0.05f, 20f)
        if (b == 3) pt.scale = (95f / maxOf(bmp.width, bmp.height)).coerceIn(0.05f, 20f) // голова
    }
}

/** Повороты кости в позе. */
object PoseEdit {
    /** Повернуть кость b вокруг оси (в мире персонажа). */
    fun rotateWorld(p: Pose, b: Int, axis: Q, startPose: Pose, bs: List<Skel.B>) {
        val s0 = RigMath.solveLocal(startPose, bs)
        val wNew = axis * s0.world[b]
        val parentW = if (bs[b].parent >= 0) s0.world[bs[b].parent] else startPose.global()
        p.rot[b] = (parentW.conj() * wNew).norm()
    }

    fun screenAngle(ax: Float, ay: Float, bx: Float, by: Float) = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat()
}
