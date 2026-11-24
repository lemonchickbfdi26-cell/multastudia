package ru.leon.multastudia

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class Layer(val id: Int, var name: String) {
    var visible = true
    var opacity = 255
    /** Слой-объект: один рисунок на все кадры + ключевые кадры движения. */
    var isObject = false
    var track = Track()
    /** Где на рисунке объекта что-то нарисовано (кэш). */
    var bounds: Rect? = null
    var boundsDirty = true
    /** Слой-персонаж (риг «2D в 3D»). */
    var rig: Rig? = null
    val isRig get() = rig != null
}

class Frame(val id: Int) {
    /** Сколько кадров показывать этот рисунок (растягивание). */
    var hold = 1
    var preview: Bitmap? = null
    var previewDirty = true
}

/**
 * Проект мультфильма. Каждый «кусочек» (слой × кадр) — это отдельная картинка размером
 * с мир (холст + поля вокруг, чтобы можно было рисовать за холстом).
 * В памяти держим только нужные кусочки, остальные лежат на диске в PNG.
 */
class Project(val dir: File) {
    var name = "Без названия"
    var canvasW = 960
    var canvasH = 540
    var fps = 12
    var bgColor = Color.WHITE
    /** Кадр-обложка для списка мультфильмов (0 — первый кадр). */
    var coverId = 0
    val layers = mutableListOf<Layer>()
    val frames = mutableListOf<Frame>()
    private var nextId = 1

    val marginX get() = canvasW / 4
    val marginY get() = canvasH / 4
    val worldW get() = canvasW + marginX * 2
    val worldH get() = canvasH + marginY * 2

    /** Камера: какой кусок мира попадает в фильм. */
    var camera = Track()

    private val cells = HashMap<String, Bitmap>()
    private val dirty = HashSet<String>()

    fun newId() = nextId++

    private fun key(layerId: Int, frameId: Int) = "${layerId}_$frameId"
    private fun cellFile(layerId: Int, frameId: Int) = File(dir, "c_${layerId}_$frameId.png")
    private fun previewFile(frameId: Int) = File(dir, "p_$frameId.jpg")

    fun totalLength(): Int = frames.sumOf { it.hold }

    // ---------- кусочки ----------

    fun loadedCell(layerId: Int, frameId: Int): Bitmap? = cells[key(layerId, frameId)]

    fun cell(layerId: Int, frameId: Int, create: Boolean): Bitmap? {
        val k = key(layerId, frameId)
        cells[k]?.let { return it }
        val f = cellFile(layerId, frameId)
        if (f.exists()) {
            val bmp = decodeCell(f)
            if (bmp != null) {
                cells[k] = bmp
                return bmp
            }
        }
        if (!create) return null
        val bmp = Bitmap.createBitmap(worldW, worldH, Bitmap.Config.ARGB_8888)
        cells[k] = bmp
        return bmp
    }

    private fun decodeCell(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val b = try { BitmapFactory.decodeFile(f.absolutePath, o) } catch (e: Throwable) { null } ?: return null
        if (b.width == worldW && b.height == worldH && b.isMutable) return b
        val fixed = Bitmap.createBitmap(worldW, worldH, Bitmap.Config.ARGB_8888)
        Canvas(fixed).drawBitmap(b, 0f, 0f, null)
        return fixed
    }

    /** Картинка кусочка только для чтения (не кладём в кэш, если не загружена). */
    private fun peekCell(layerId: Int, frameId: Int): Bitmap? {
        cells[key(layerId, frameId)]?.let { return it }
        val f = cellFile(layerId, frameId)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Throwable) { null }
    }

    fun setCell(layerId: Int, frameId: Int, bmp: Bitmap) {
        cells[key(layerId, frameId)] = bmp
        markDirty(layerId, frameId)
    }

    fun markDirty(layerId: Int, frameId: Int) {
        dirty.add(key(layerId, frameId))
        if (frameId == 0) {
            invalidateAllPreviews()
            layers.firstOrNull { it.id == layerId }?.boundsDirty = true
        } else frames.firstOrNull { it.id == frameId }?.previewDirty = true
    }

    fun invalidateAllPreviews() { for (f in frames) f.previewDirty = true }

    // ---------- объекты и камера ----------

    fun objectBounds(l: Layer): Rect {
        val cached = l.bounds
        if (cached != null && !l.boundsDirty) return cached
        val bmp = cells[key(l.id, 0)] ?: peekCell(l.id, 0)
        var r: Rect? = null
        if (bmp != null) {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) if ((px[row + x] ushr 24) > 8) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
            if (x1 >= 0) r = Rect(x0, y0, x1 + 1, y1 + 1)
        }
        val res = r ?: Rect(marginX + canvasW / 2 - 40, marginY + canvasH / 2 - 40, marginX + canvasW / 2 + 40, marginY + canvasH / 2 + 40)
        l.bounds = res; l.boundsDirty = false
        return res
    }

    fun objectDefault(l: Layer): Xf {
        val b = objectBounds(l)
        return Xf(b.exactCenterX(), b.exactCenterY())
    }

    fun cameraDefault() = Xf((marginX + canvasW / 2f), (marginY + canvasH / 2f))

    fun objectXf(l: Layer, idx: Int): Xf = l.track.eval(frames, idx, objectDefault(l))

    fun rigDefault(): Pose {
        val sc = canvasH * 0.78f / 415f
        return Pose(marginX + canvasW / 2f, marginY + canvasH / 2f + 30f * sc).apply { scale = sc }
    }

    fun rigHome(r: Rig): Pose {
        val h = r.home ?: return rigDefault()
        return Pose(h[0], h[1]).apply { scale = h[2] }
    }

    fun rigPose(l: Layer, idx: Int): Pose = l.rig!!.track.eval(frames, idx, rigHome(l.rig!!))

    private fun partFile(layerId: Int, partId: Int) = File(dir, "rp_${layerId}_$partId.png")

    fun savePart(layerId: Int, pt: Part) {
        val b = pt.bmp ?: return
        val copy = b.copy(Bitmap.Config.ARGB_8888, false)
        val f = partFile(layerId, pt.id)
        io.submit { dir.mkdirs(); FileOutputStream(f).use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    fun deletePart(layerId: Int, pt: Part) { val f = partFile(layerId, pt.id); io.submit { f.delete() } }

    private fun loadParts(l: Layer) {
        val r = l.rig ?: return
        for (pt in r.parts) {
            val f = partFile(l.id, pt.id)
            if (f.exists()) pt.bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Throwable) { null }
        }
    }
    fun cameraXf(idx: Int): Xf = camera.eval(frames, idx, cameraDefault())

    fun hasCellData(layerId: Int, frameId: Int): Boolean =
        cells.containsKey(key(layerId, frameId)) || cellFile(layerId, frameId).exists()

    // ---------- сохранение ----------

    private val io = Executors.newSingleThreadExecutor()

    fun saveDirty(wait: Boolean = false) {
        val jobs = ArrayList<Pair<File, Bitmap>>()
        for (k in dirty) {
            val bmp = cells[k] ?: continue
            val (l, f) = k.split("_").map { it.toInt() }
            jobs.add(cellFile(l, f) to bmp.copy(Bitmap.Config.ARGB_8888, false))
        }
        dirty.clear()
        val meta = metaJson()
        val fut = io.submit {
            dir.mkdirs()
            for ((file, bmp) in jobs) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(file)
            }
            File(dir, "project.json").writeText(meta)
        }
        if (wait) try { fut.get() } catch (_: Throwable) {}
    }

    /** Выгрузить из памяти все кусочки, кроме кадров из списка. */
    fun evictExcept(keepFrames: Set<Int>) {
        saveDirty()
        val it = cells.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val frameId = e.key.substringAfter("_").toInt()
            if (frameId !in keepFrames) it.remove()
        }
    }

    fun deleteCellFiles(frameId: Int) {
        for (l in layers) {
            cells.remove(key(l.id, frameId))
            dirty.remove(key(l.id, frameId))
            io.submit { cellFile(l.id, frameId).delete() }
        }
        io.submit { previewFile(frameId).delete() }
    }

    fun deleteLayerFiles(layerId: Int) {
        for (fr in frames) {
            cells.remove(key(layerId, fr.id))
            dirty.remove(key(layerId, fr.id))
            io.submit { cellFile(layerId, fr.id).delete() }
            fr.previewDirty = true
        }
        cells.remove(key(layerId, 0)); dirty.remove(key(layerId, 0))
        io.submit { cellFile(layerId, 0).delete() }
    }

    // ---------- сборка кадра ----------

    /** Собирает кадр (только область холста) в картинку нужного масштаба. */
    fun compose(frame: Frame, scale: Float, config: Bitmap.Config = Bitmap.Config.ARGB_8888, withCamera: Boolean = true): Bitmap {
        val w = maxOf(1, (canvasW * scale).toInt())
        val h = maxOf(1, (canvasH * scale).toInt())
        val out = Bitmap.createBitmap(w, h, config)
        val c = Canvas(out)
        c.drawColor(bgColor)
        c.scale(w.toFloat() / canvasW, h.toFloat() / canvasH)
        c.translate(-marginX.toFloat(), -marginY.toFloat())
        val idx = frames.indexOf(frame)
        if (withCamera && !camera.isEmpty) {
            val inv = Matrix()
            if (cameraXf(idx).matrix().invert(inv)) c.concat(inv)
        }
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val m = Matrix()
        for (l in layers) {
            if (!l.visible) continue
            if (l.isRig) {
                p.alpha = l.opacity
                RigRender.draw(c, l.rig!!, rigPose(l, idx), p)
            } else if (l.isObject) {
                val bmp = peekCell(l.id, 0) ?: continue
                val xf = objectXf(l, idx)
                p.alpha = (l.opacity * xf.alpha).toInt().coerceIn(0, 255)
                c.drawBitmap(bmp, xf.matrix(m), p)
            } else {
                val bmp = peekCell(l.id, frame.id) ?: continue
                p.alpha = l.opacity
                c.drawBitmap(bmp, 0f, 0f, p)
            }
        }
        return out
    }

    fun previewScale() = minOf(1f, 640f / canvasW)

    fun preview(frame: Frame): Bitmap {
        val cur = frame.preview
        if (cur != null && !frame.previewDirty) return cur
        if (cur == null && !frame.previewDirty) {
            val f = previewFile(frame.id)
            if (f.exists()) {
                val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val b = try { BitmapFactory.decodeFile(f.absolutePath, o) } catch (e: Throwable) { null }
                if (b != null) { frame.preview = b; return b }
            }
        }
        val b = compose(frame, previewScale(), Bitmap.Config.RGB_565, withCamera = false)
        frame.preview = b
        frame.previewDirty = false
        val copy = b.copy(Bitmap.Config.RGB_565, false)
        val file = previewFile(frame.id)
        io.submit {
            try { FileOutputStream(file).use { copy.compress(Bitmap.CompressFormat.JPEG, 85, it) } } catch (_: Throwable) {}
        }
        return b
    }

    // ---------- описание проекта ----------

    private fun metaJson(): String {
        val o = JSONObject()
        o.put("name", name); o.put("w", canvasW); o.put("h", canvasH); o.put("fps", fps)
        o.put("bg", bgColor); o.put("nextId", nextId); o.put("cover", coverId)
        val la = JSONArray()
        for (l in layers) la.put(JSONObject().put("id", l.id).put("name", l.name).put("visible", l.visible).put("opacity", l.opacity)
            .put("object", l.isObject).put("track", l.track.toJson()).put("rig", l.rig?.toJson()))
        o.put("layers", la)
        o.put("camera", camera.toJson())
        val fa = JSONArray()
        for (f in frames) fa.put(JSONObject().put("id", f.id).put("hold", f.hold))
        o.put("frames", fa)
        return o.toString()
    }

    fun saveMeta() {
        val meta = metaJson()
        io.submit { dir.mkdirs(); File(dir, "project.json").writeText(meta) }
    }

    fun flush() { try { io.submit {}.get() } catch (_: Throwable) {} }

    companion object {
        fun root(base: File) = File(base, "projects").also { it.mkdirs() }

        fun create(base: File, name: String, w: Int, h: Int, fps: Int): Project {
            val dir = File(root(base), "p" + System.currentTimeMillis())
            dir.mkdirs()
            val p = Project(dir)
            p.name = name; p.canvasW = w; p.canvasH = h; p.fps = fps
            p.layers.add(Layer(p.newId(), "Слой 1"))
            p.frames.add(Frame(p.newId()))
            File(dir, "project.json").writeText(p.metaJson())
            return p
        }

        fun load(dir: File): Project? {
            return try {
                val o = JSONObject(File(dir, "project.json").readText())
                val p = Project(dir)
                p.name = o.optString("name", "Без названия")
                p.canvasW = o.getInt("w"); p.canvasH = o.getInt("h")
                p.fps = o.optInt("fps", 12); p.bgColor = o.optInt("bg", Color.WHITE)
                p.nextId = o.optInt("nextId", 1000)
                p.coverId = o.optInt("cover", 0)
                p.camera = Track.fromJson(o.optJSONArray("camera"))
                for (l in p.layers) p.loadParts(l)
                val la = o.getJSONArray("layers")
                for (i in 0 until la.length()) {
                    val j = la.getJSONObject(i)
                    p.layers.add(Layer(j.getInt("id"), j.optString("name", "Слой")).apply {
                        visible = j.optBoolean("visible", true); opacity = j.optInt("opacity", 255)
                        isObject = j.optBoolean("object", false); track = Track.fromJson(j.optJSONArray("track"))
                        if (j.has("rig") && !j.isNull("rig")) rig = Rig.fromJson(j.optJSONObject("rig"))
                    })
                }
                val fa = o.getJSONArray("frames")
                for (i in 0 until fa.length()) {
                    val j = fa.getJSONObject(i)
                    p.frames.add(Frame(j.getInt("id")).apply { hold = j.optInt("hold", 1); previewDirty = false })
                }
                if (p.layers.isEmpty()) p.layers.add(Layer(p.newId(), "Слой 1"))
                if (p.frames.isEmpty()) p.frames.add(Frame(p.newId()))
                p
            } catch (e: Throwable) { null }
        }

        /** Быстро прочитать только имя и первую превьюшку для списка проектов. */
        fun peekInfo(dir: File): Pair<String, File?>? {
            return try {
                val o = JSONObject(File(dir, "project.json").readText())
                val fa = o.getJSONArray("frames")
                val cover = o.optInt("cover", 0)
                val coverFile = File(dir, "p_$cover.jpg")
                val first = if (cover != 0 && coverFile.exists()) coverFile
                    else if (fa.length() > 0) File(dir, "p_${fa.getJSONObject(0).getInt("id")}.jpg") else null
                o.optString("name", "Без названия") to first?.takeIf { it.exists() }
            } catch (e: Throwable) { null }
        }
    }
}
