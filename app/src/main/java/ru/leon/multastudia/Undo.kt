package ru.leon.multastudia

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

/** Одна правка: кусочек картинки «до» и «после» в прямоугольнике. */
class UndoEntry(val layerId: Int, val frameId: Int, val rect: Rect, val before: Bitmap, val after: Bitmap) {
    val bytes get() = before.byteCount + after.byteCount
}

class UndoManager(private val maxBytes: Int = 48 * 1024 * 1024) {
    private val undo = ArrayList<UndoEntry>()
    private val redo = ArrayList<UndoEntry>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(e: UndoEntry) {
        undo.add(e)
        redo.clear()
        var total = undo.sumOf { it.bytes }
        while (total > maxBytes && undo.size > 1) {
            total -= undo.removeAt(0).bytes
        }
    }

    fun popUndo(): UndoEntry? = if (undo.isEmpty()) null else undo.removeAt(undo.size - 1).also { redo.add(it) }
    fun popRedo(): UndoEntry? = if (redo.isEmpty()) null else redo.removeAt(redo.size - 1).also { undo.add(it) }

    /** Убрать правки удалённого кадра или слоя. */
    fun forget(layerId: Int? = null, frameId: Int? = null) {
        val pred: (UndoEntry) -> Boolean = { (layerId != null && it.layerId == layerId) || (frameId != null && it.frameId == frameId) }
        undo.removeAll(pred); redo.removeAll(pred)
    }

    fun clear() { undo.clear(); redo.clear() }

    companion object {
        private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

        fun cut(src: Bitmap, r: Rect): Bitmap = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())

        fun paste(dst: Bitmap, part: Bitmap, r: Rect) {
            Canvas(dst).drawBitmap(part, r.left.toFloat(), r.top.toFloat(), srcPaint)
        }
    }
}
