package ru.leon.multastudia

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot

/**
 * «Мастерская персонажа»: 3D-скелет, который можно крутить, и кусочки, привязанные к костям.
 */
class RigWorkshop(
    private val ctx: Context,
    private val p: Project,
    private val layer: Layer,
    private val pickImage: ((Bitmap) -> Unit) -> Unit,
    private val brush: BrushSettings,
    private val onChanged: () -> Unit
) {
    private val rig = layer.rig!!
    private var bone = 1
    private var part: Part? = null
    private lateinit var preview: Preview
    private lateinit var chips: LinearLayout
    private lateinit var partsBox: LinearLayout
    private val dlg = Dialog(ctx, android.R.style.Theme_Material_NoActionBar_Fullscreen)

    fun show() {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR); setPadding(ctx.dp(4f), 0, ctx.dp(8f), 0)
        }
        top.addView(IconButton(ctx, Ico.CLOSE).apply { setOnClickListener { dlg.dismiss() } })
        top.addView(ctx.label("Мастерская: ${layer.name}", 16f), lp(0, WRAP, 1f))
        root.addView(top, lp(MATCH, ctx.dp(52f)))

        preview = Preview(ctx)
        root.addView(preview, lp(MATCH, 0, 1f))

        val tg = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(ctx.dp(8f), ctx.dp(4f), ctx.dp(8f), ctx.dp(4f)) }
        fun chip(t: String, act: (TextView) -> Unit) = ctx.label(t, 13f).apply {
            background = rounded(C.CARD2, ctx.dp(12f).toFloat()); setPadding(ctx.dp(12f), ctx.dp(7f), ctx.dp(12f), ctx.dp(7f))
            setOnClickListener { act(this) }
        }
        tg.addView(chip("Скелет: 3D") { tv -> preview.show3D = !preview.show3D; tv.text = if (preview.show3D) "Скелет: 3D" else "Как в мультике (2D)"; preview.invalidate() })
        tg.addView(View(ctx), lp(ctx.dp(8f), 1))
        tg.addView(chip("Спереди") { preview.yaw = 0f; preview.pitch = 0f; preview.invalidate() })
        tg.addView(View(ctx), lp(ctx.dp(8f), 1))
        tg.addView(chip("Сбоку") { preview.yaw = 90f; preview.pitch = 0f; preview.invalidate() })
        tg.addView(View(ctx), lp(ctx.dp(8f), 1))
        tg.addView(chip("Сзади") { preview.yaw = 180f; preview.pitch = 0f; preview.invalidate() })
        root.addView(tg, lp(MATCH, WRAP))

        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ctx.dp(8f), ctx.dp(4f), ctx.dp(8f), ctx.dp(4f)) }
        hs.addView(chips)
        root.addView(hs, lp(MATCH, WRAP))

        val sc = ScrollView(ctx)
        partsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12f), ctx.dp(6f), ctx.dp(12f), ctx.dp(12f)); setBackgroundColor(C.BAR) }
        sc.addView(partsBox)
        root.addView(sc, lp(MATCH, ctx.dp(250f)))

        dlg.setContentView(root)
        dlg.setOnDismissListener { onChanged() }
        rebuildChips(); rebuildParts()
        dlg.show()
    }

    private fun rebuildChips() {
        chips.removeAllViews()
        val bs = rig.bones()
        for (b in bs.indices) {
            val n = rig.parts.count { it.bone == b }
            val t = ctx.label((if (bs[b].extra) "✚ " else "") + bs[b].title + if (n > 0) " ($n)" else "", 13f, if (b == bone) Color.WHITE else C.TEXT).apply {
                background = rounded(if (b == bone) C.ACCENT else C.CARD2, ctx.dp(12f).toFloat())
                setPadding(ctx.dp(12f), ctx.dp(8f), ctx.dp(12f), ctx.dp(8f))
                setOnClickListener { selectBone(b) }
            }
            chips.addView(t, lp(WRAP, WRAP).apply { rightMargin = ctx.dp(6f) })
        }
        chips.addView(ctx.label("+ Кость", 13f, Color.WHITE).apply {
            background = rounded(0xFF00838F.toInt(), ctx.dp(12f).toFloat())
            setPadding(ctx.dp(12f), ctx.dp(8f), ctx.dp(12f), ctx.dp(8f))
            setOnClickListener { boneDialog(null) }
        })
    }

    /** Добавить свою кость к выбранной (b == null) или настроить свою кость b. */
    private fun boneDialog(edit: Int?) {
        val bs = rig.bones()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(18f), ctx.dp(8f), ctx.dp(18f), 0) }
        val nameEt = android.widget.EditText(ctx).apply {
            hint = "Название"; setTextColor(C.TEXT)
            setText(if (edit != null) bs[edit].title else "Хвост ${rig.extra.size + 1}")
        }
        box.addView(nameEt)
        var fromEnd = true
        var angle: Int; var len: Int; var depth: Int
        if (edit != null) {
            angle = rig.boneAngle(edit).toInt(); len = rig.boneLen(edit).toInt(); depth = rig.boneDepth(edit).toInt()
        } else {
            box.addView(ctx.label("Растёт из кости «${bs[bone].title}»", 13f, C.DIM).apply { setPadding(0, ctx.dp(6f), 0, 0) })
            val rg = android.widget.RadioGroup(ctx).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
            rg.addView(android.widget.RadioButton(ctx).apply { id = 700; text = "из конца"; setTextColor(C.TEXT) })
            rg.addView(android.widget.RadioButton(ctx).apply { id = 701; text = "из начала"; setTextColor(C.TEXT) })
            // для таза хвост логичнее растить из начала (низ спины)
            fromEnd = bone != 0
            rg.check(if (fromEnd) 700 else 701)
            rg.setOnCheckedChangeListener { _, id -> fromEnd = id == 700 }
            box.addView(rg)
            val parentIsExtra = bs[bone].extra
            angle = if (parentIsExtra) rig.boneAngle(bone).toInt() else 110
            len = if (parentIsExtra) rig.boneLen(bone).toInt() else 50
            depth = if (parentIsExtra) rig.boneDepth(bone).toInt() else -60
        }
        box.addView(ctx.sliderRow("Направление", -180, 180, angle, { "$it°" }) { angle = it })
        box.addView(ctx.sliderRow("Длина", 5, 300, len) { len = it })
        box.addView(ctx.sliderRow("Глубина", -100, 100, depth, { if (it < 0) "назад" else if (it > 0) "вперёд" else "0" }) { depth = it })
        box.addView(ctx.label("90° — вниз, 0° — вправо, −90° — вверх. «Глубина назад» — кость уходит за тело (для хвоста).", 12f, C.DIM))
        val b = AlertDialog.Builder(ctx, R.style.DialogTheme)
            .setTitle(if (edit != null) "Своя кость" else "Новая кость")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(if (edit != null) "Сохранить" else "Добавить") { _, _ ->
                val title = nameEt.text.toString().ifBlank { "Кость" }
                if (edit != null) rig.setBone(edit, title, angle.toFloat(), len.toFloat(), depth.toFloat())
                else {
                    val nb = rig.addBone(title, bone, fromEnd, angle.toFloat(), len.toFloat(), depth.toFloat())
                    if (nb < 0) { android.widget.Toast.makeText(ctx, "Слишком много костей", android.widget.Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                    bone = nb; part = null
                }
                changed(); rebuildChips(); rebuildParts()
            }
            .setNegativeButton("Отмена", null)
        if (edit != null && bs[edit].extra) b.setNeutralButton("Удалить кость") { _, _ ->
            AlertDialog.Builder(ctx, R.style.DialogTheme).setTitle("Удалить кость «${bs[edit].title}»?")
                .setMessage("Удалятся и кости, которые растут из неё, и их кусочки.")
                .setPositiveButton("Удалить") { _, _ ->
                    val removedParts = rig.parts.filter { it.bone == edit }
                    rig.removeBone(edit)
                    for (pt in removedParts) p.deletePart(layer.id, pt)
                    bone = bs[edit].parent.coerceIn(0, rig.bones().size - 1)
                    part = null; changed(); rebuildChips(); rebuildParts()
                }.setNegativeButton("Отмена", null).show()
        }
        b.show()
    }

    private fun selectBone(b: Int) {
        bone = b; part = null
        rebuildChips(); rebuildParts(); preview.invalidate()
    }

    private fun changed() { p.invalidateAllPreviews(); p.saveMeta(); preview.invalidate() }

    private fun rebuildParts() {
        partsBox.removeAllViews()
        val bs = rig.bones()
        if (bone >= bs.size) bone = 1
        val b = bs[bone]
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.label(b.title, 15f).apply { setPadding(0, ctx.dp(4f), 0, ctx.dp(6f)) }, lp(0, WRAP, 1f))
        head.addView(ctx.label("Настроить кость", 13f, C.ACCENT).apply {
            setPadding(ctx.dp(8f), ctx.dp(6f), 0, ctx.dp(6f)); setOnClickListener { boneDialog(bone) }
        })
        partsBox.addView(head)
        val list = rig.parts.filter { it.bone == bone }
        if (list.isEmpty()) {
            partsBox.addView(ctx.label("Нет кусочков. Нарисуй часть на холсте, обведи лассо → «⋯» → «Отдать персонажу». Или вставь картинку кнопкой ниже.", 13f, C.DIM))
        }
        for (pt in list) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (pt === part) C.ACCENT else C.CARD2, ctx.dp(12f).toFloat())
                setPadding(ctx.dp(6f), ctx.dp(4f), ctx.dp(10f), ctx.dp(4f))
                setOnClickListener { part = if (part === pt) null else pt; rebuildParts(); preview.invalidate() }
            }
            val img = ImageView(ctx).apply { setImageBitmap(pt.bmp); background = rounded(Color.WHITE, ctx.dp(6f).toFloat()); scaleType = ImageView.ScaleType.FIT_CENTER }
            row.addView(img, lp(ctx.dp(44f), ctx.dp(44f)))
            row.addView(ctx.label("  " + VIEW_NAMES[pt.view] + (if (pt.bendy) " · гнётся" else ""), 14f), lp(0, WRAP, 1f))
            partsBox.addView(row, lp(MATCH, WRAP).apply { bottomMargin = ctx.dp(6f) })
            if (pt === part) addEditor(pt)
        }
        val add = ctx.label("+  Вставить картинку для этой кости", 14f, C.ACCENT).apply { setPadding(ctx.dp(4f), ctx.dp(10f), 0, ctx.dp(4f)) }
        add.setOnClickListener {
            pickImage { bmp ->
                val pt = Part(rig.nextPartId++, bone, viewForPreview())
                pt.bendy = Skel.canBend(rig.bones(), bone)
                pt.bmp = bmp
                RigRender.autoFit(pt, bmp, rig.bones())
                rig.parts.add(pt); p.savePart(layer.id, pt)
                part = pt; changed(); rebuildChips(); rebuildParts()
            }
        }
        partsBox.addView(add)
    }

    private fun viewForPreview(): Int {
        val y = ((preview.yaw % 360f) + 360f) % 360f
        return when { y < 45f || y > 315f -> VIEW_FRONT; y in 135f..225f -> VIEW_BACK; else -> VIEW_SIDE }
    }

    private fun addEditor(pt: Part) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(6f), 0, ctx.dp(6f), ctx.dp(10f)) }
        box.addView(ctx.label("🎨  Рисовать / залить", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = rounded(C.ACCENT, ctx.dp(12f).toFloat())
            setPadding(ctx.dp(10f), ctx.dp(10f), ctx.dp(10f), ctx.dp(10f))
            setOnClickListener {
                val src = pt.bmp ?: return@setOnClickListener
                SpriteEditor(ctx, src, brush) { nb ->
                    pt.bmp = nb
                    p.savePart(layer.id, pt)
                    changed(); rebuildParts()
                }.show()
            }
        }, lp(MATCH, WRAP).apply { bottomMargin = ctx.dp(8f) })
        val bmp = pt.bmp
        val probe = Part(0, pt.bone, pt.view).apply { bendy = pt.bendy }
        if (bmp != null) RigRender.autoFit(probe, bmp, rig.bones())
        val base = probe.scale
        box.addView(ctx.sliderRow("Размер", 5, 400, (pt.scale / base * 100).toInt(), { "$it%" }) { pt.scale = base * it / 100f; changed() })
        box.addView(ctx.sliderRow("Вдоль кости", -200, 200, pt.along.toInt()) { pt.along = it.toFloat(); changed() })
        box.addView(ctx.sliderRow("Поперёк", -150, 150, pt.across.toInt()) { pt.across = it.toFloat(); changed() })
        if (!pt.bendy) box.addView(ctx.sliderRow("Поворот", -180, 180, pt.rot.toInt(), { "$it°" }) { pt.rot = it.toFloat(); changed() })
        box.addView(ctx.sliderRow("Выше / ниже", -5, 5, pt.z) { pt.z = it; changed() })

        val views = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        views.addView(ctx.label("Вид", 13f, C.DIM), lp(ctx.dp(96f), WRAP))
        for (v in 0..2) {
            views.addView(ctx.label(VIEW_NAMES[v], 13f).apply {
                background = rounded(if (pt.view == v) C.ACCENT else C.CARD, ctx.dp(10f).toFloat())
                setPadding(ctx.dp(10f), ctx.dp(6f), ctx.dp(10f), ctx.dp(6f)); gravity = Gravity.CENTER
                setOnClickListener { pt.view = v; changed(); rebuildParts() }
            }, lp(0, WRAP, 1f).apply { rightMargin = ctx.dp(4f) })
        }
        box.addView(views)

        if (Skel.canBend(rig.bones(), pt.bone)) {
            box.addView(CheckBox(ctx).apply {
                text = "Гнётся по суставам (одна картинка на всю руку/ногу/хвост)"; setTextColor(C.TEXT); isChecked = pt.bendy
                setOnCheckedChangeListener { _, c -> pt.bendy = c; pt.bmp?.let { RigRender.autoFit(pt, it, rig.bones()) }; changed(); rebuildParts() }
            })
        }
        box.addView(ctx.label("Удалить кусочек", 14f, 0xFFFF6B6B.toInt()).apply {
            setPadding(0, ctx.dp(8f), 0, 0)
            setOnClickListener {
                AlertDialog.Builder(ctx, R.style.DialogTheme).setTitle("Удалить кусочек?")
                    .setPositiveButton("Удалить") { _, _ -> rig.parts.remove(pt); p.deletePart(layer.id, pt); part = null; changed(); rebuildChips(); rebuildParts() }
                    .setNegativeButton("Отмена", null).show()
            }
        })
        partsBox.addView(box)
    }

    /** Просмотр: персонаж в спокойной позе, крутится пальцем. */
    @SuppressLint("ViewConstructor")
    inner class Preview(c: Context) : View(c) {
        var yaw = 25f
        var pitch = -8f
        var show3D = true
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val bp = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lx = 0f; private var ly = 0f; private var moved = false

        private fun pose(): Pose {
            val sc = height * 0.8f / 415f
            return Pose(width / 2f, height / 2f + 30f * sc).apply { scale = sc; yaw = this@Preview.yaw; pitch = this@Preview.pitch }
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(0xFF353535.toInt())
            // пол
            bp.style = Paint.Style.FILL; bp.color = 0xFF2E2E2E.toInt()
            c.drawRect(0f, height * 0.83f, width.toFloat(), height.toFloat(), bp)
            val ps = pose()
            val s = RigMath.solve(ps, rig.bones())
            if (show3D) paint.alpha = 200 else paint.alpha = 255
            RigRender.draw(c, rig, ps, paint, s)
            if (show3D) RigRender.drawBones(c, s, bone, dp(2f).toFloat(), dp(4.5f).toFloat(), bp)
            bp.style = Paint.Style.FILL; bp.color = C.DIM; bp.textSize = dp(12f).toFloat()
            c.drawText("Крути пальцем · нажми на кость, чтобы выбрать", dp(10f).toFloat(), dp(18f).toFloat(), bp)
            c.drawText("Поворот ${yaw.toInt()}°  ·  показан вид «${VIEW_NAMES[viewForPreview()]}»", dp(10f).toFloat(), dp(36f).toFloat(), bp)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lx; val dy = e.y - ly
                    if (!moved && hypot(dx, dy) > dp(6f)) moved = true
                    if (moved) {
                        yaw += dx * 0.5f; pitch = (pitch - dy * 0.3f).coerceIn(-60f, 60f)
                        lx = e.x; ly = e.y; invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    val s = RigMath.solve(pose(), rig.bones())
                    var best = -1; var bd = dp(26f).toFloat()
                    for (b in 0 until s.n) {
                        val mx = (s.head[b].x + s.tail[b].x) / 2; val my = (s.head[b].y + s.tail[b].y) / 2
                        val d = hypot(e.x - mx, e.y - my)
                        if (d < bd) { bd = d; best = b }
                    }
                    if (best >= 0) selectBone(best)
                }
            }
            return true
        }
    }

    companion object {
        /** Диалог «Отдать кусочек персонажу». */
        fun askPart(ctx: Context, rigs: List<Layer>, bmp: Bitmap, p: Project, done: (Layer, Part) -> Unit) {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(18f), ctx.dp(8f), ctx.dp(18f), 0) }
            var rigIdx = 0
            var bone = 1
            var view = VIEW_FRONT
            if (rigs.size > 1) {
                box.addView(ctx.label("Персонаж", 13f, C.DIM))
                val rg = android.widget.RadioGroup(ctx)
                rigs.forEachIndexed { i, l -> rg.addView(android.widget.RadioButton(ctx).apply { id = 300 + i; text = l.name; setTextColor(C.TEXT) }) }
                rg.check(300); rg.setOnCheckedChangeListener { _, id -> rigIdx = id - 300 }
                box.addView(rg)
            }
            box.addView(ctx.label("Кость", 13f, C.DIM).apply { setPadding(0, ctx.dp(8f), 0, 0) })
            val bonesRg = android.widget.RadioGroup(ctx)
            val allBones = rigs[0].rig!!.bones()
            allBones.forEachIndexed { i, b -> bonesRg.addView(android.widget.RadioButton(ctx).apply { id = 400 + i; text = b.title; setTextColor(C.TEXT) }) }
            bonesRg.check(401); bonesRg.setOnCheckedChangeListener { _, id -> bone = id - 400 }
            box.addView(ctx.label("Вид", 13f, C.DIM).apply { setPadding(0, ctx.dp(8f), 0, 0) })
            val viewRg = android.widget.RadioGroup(ctx).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
            VIEW_NAMES.forEachIndexed { i, n -> viewRg.addView(android.widget.RadioButton(ctx).apply { id = 500 + i; text = n; setTextColor(C.TEXT) }) }
            viewRg.check(500); viewRg.setOnCheckedChangeListener { _, id -> view = id - 500 }
            box.addView(viewRg)
            val bendy = CheckBox(ctx).apply { text = "Гнётся по суставу (рука/нога целиком)"; setTextColor(C.TEXT); isChecked = true }
            box.addView(bendy)
            box.addView(bonesRg)
            AlertDialog.Builder(ctx, R.style.DialogTheme).setTitle("Отдать кусочек персонажу")
                .setView(ScrollView(ctx).apply { addView(box) })
                .setPositiveButton("Готово") { _, _ ->
                    val l = rigs[rigIdx]; val r = l.rig!!
                    val bb = bone.coerceIn(0, r.bones().size - 1)
                    val pt = Part(r.nextPartId++, bb, view)
                    pt.bendy = bendy.isChecked && Skel.canBend(r.bones(), bb)
                    pt.bmp = bmp
                    RigRender.autoFit(pt, bmp, r.bones())
                    r.parts.add(pt); p.savePart(l.id, pt)
                    done(l, pt)
                }
                .setNegativeButton("Отмена", null).show()
        }
    }
}
