package ru.leon.multastudia

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class Layer(val id: Int, var name: String) {
    var visible = true
    var opacity = 255
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
        frames.firstOrNull { it.id == frameId }?.previewDirty = true
    }

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
    }

    // ---------- сборка кадра ----------

    /** Собирает кадр (только область холста) в картинку нужного масштаба. */
    fun compose(frame: Frame, scale: Float, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val w = maxOf(1, (canvasW * scale).toInt())
        val h = maxOf(1, (canvasH * scale).toInt())
        val out = Bitmap.createBitmap(w, h, config)
        val c = Canvas(out)
        c.drawColor(bgColor)
        c.scale(w.toFloat() / canvasW, h.toFloat() / canvasH)
        c.translate(-marginX.toFloat(), -marginY.toFloat())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in layers) {
            if (!l.visible) continue
            val bmp = peekCell(l.id, frame.id) ?: continue
            p.alpha = l.opacity
            c.drawBitmap(bmp, 0f, 0f, p)
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
        val b = compose(frame, previewScale(), Bitmap.Config.RGB_565)
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
        for (l in layers) la.put(JSONObject().put("id", l.id).put("name", l.name).put("visible", l.visible).put("opacity", l.opacity))
        o.put("layers", la)
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
                val la = o.getJSONArray("layers")
                for (i in 0 until la.length()) {
                    val j = la.getJSONObject(i)
                    p.layers.add(Layer(j.getInt("id"), j.optString("name", "Слой")).apply {
                        visible = j.optBoolean("visible", true); opacity = j.optInt("opacity", 255)
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
