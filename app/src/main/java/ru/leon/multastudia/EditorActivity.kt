package ru.leon.multastudia

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class EditorActivity : Activity(), CanvasHost {

    private lateinit var p: Project
    private lateinit var cv: CanvasView
    private var frameIdx = 0
    private var layerIdx = 0
    private val ui = Handler(Looper.getMainLooper())

    // элементы интерфейса
    private lateinit var titleView: TextView
    private val toolButtons = HashMap<Tool, IconButton>()
    private lateinit var moreBtn: IconButton
    private lateinit var settingsPanel: LinearLayout
    private lateinit var morePanel: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var frameStrip: LinearLayout
    private lateinit var stripScroll: HorizontalScrollView
    private lateinit var layerBtn: TextView
    private lateinit var counter: TextView
    private lateinit var playBtn: IconButton
    private lateinit var undoBtn: IconButton
    private lateinit var redoBtn: IconButton
    private lateinit var onionBtn: IconButton
    private lateinit var pasteBtn: IconButton

    private var lastDrawTool = Tool.BRUSH
    private var clipboard: Map<Int, Bitmap>? = null
    private var playing = false
    private var playPos = 0

    companion object {
        const val REQ_IMAGE = 1
        const val REQ_GIF = 2
        const val REQ_PNG_DIR = 3
        const val REQ_PNG_ONE = 4
    }

    // ===================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(intent.getStringExtra("dir") ?: run { finish(); return })
        p = Project.load(dir) ?: run { Toast.makeText(this, "Не удалось открыть проект", Toast.LENGTH_LONG).show(); finish(); return }
        cv = CanvasView(this, p, this)
        setContentView(buildUi())
        selectTool(Tool.BRUSH)
        rebuildStrip()
        refreshLayerBtn()
        refreshButtons()
    }

    override fun onPause() {
        super.onPause()
        stopPlay()
        cv.commitFloat()
        p.preview(currentFrame())
        p.saveDirty()
        p.saveMeta()
    }

    override fun onBackPressed() {
        if (settingsPanel.visibility == View.VISIBLE || morePanel.visibility == View.VISIBLE) { hidePanels(); return }
        super.onBackPressed()
    }

    // ===================================================================
    // Интерфейс
    // ===================================================================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        // ---- верхняя панель ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR); setPadding(dp(4f), 0, dp(4f), 0)
        }
        top.addView(IconButton(this, Ico.CLOSE).apply { setOnClickListener { finish() } })
        titleView = label(p.name, 15f).apply {
            gravity = Gravity.CENTER; maxLines = 1
            setOnClickListener { renameDialog() }
        }
        top.addView(titleView, lp(0, WRAP, 1f))
        top.addView(IconButton(this, Ico.FIT).apply { setOnClickListener { cv.fitView() } })
        top.addView(IconButton(this, Ico.SHARE).apply { iconView.color = C.ACCENT; setOnClickListener { exportMenu() } })
        top.addView(IconButton(this, Ico.MENU).apply { setOnClickListener { settingsMenu() } })
        root.addView(top, lp(MATCH, dp(52f)))

        // ---- холст + плавающие панели ----
        val area = FrameLayout(this)
        area.addView(cv, FrameLayout.LayoutParams(MATCH, MATCH))

        val toolsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(C.BAR, dp(18f).toFloat()); setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        }
        for ((t, ico) in listOf(Tool.BRUSH to Ico.BRUSH, Tool.ERASER to Ico.ERASER, Tool.LASSO to Ico.LASSO, Tool.FILL to Ico.FILL, Tool.TEXT to Ico.TEXT)) {
            val b = IconButton(this, ico)
            b.setOnClickListener { onToolClick(t) }
            toolButtons[t] = b
            tools.addView(b, lp(0, dp(48f), 1f))
        }
        moreBtn = IconButton(this, Ico.MORE).apply { setOnClickListener { toggleMore() } }
        tools.addView(moreBtn, lp(0, dp(48f), 1f))
        toolsCol.addView(tools, lp(MATCH, WRAP))

        settingsPanel = card()
        morePanel = card()
        toolsCol.addView(settingsPanel, lp(MATCH, WRAP).apply { topMargin = dp(8f) })
        toolsCol.addView(morePanel, lp(MATCH, WRAP).apply { topMargin = dp(8f) })
        settingsPanel.visibility = View.GONE; morePanel.visibility = View.GONE
        area.addView(toolsCol, FrameLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10f), dp(10f), dp(10f), 0) })

        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(C.PURPLE, dp(18f).toFloat()); setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            visibility = View.GONE
        }
        selectionBar.addView(IconButton(this, Ico.CHECK, "Готово", 22f).apply { setOnClickListener { cv.commitFloat() } })
        selectionBar.addView(IconButton(this, Ico.COPY, "Штамп", 22f).apply { setOnClickListener { cv.commitFloat(stamp = true) } })
        selectionBar.addView(IconButton(this, Ico.FLIP, "Отразить", 22f).apply { setOnClickListener { cv.flipFloat() } })
        selectionBar.addView(IconButton(this, Ico.TRASH, "Удалить", 22f).apply { setOnClickListener { cv.deleteFloat() } })
        area.addView(selectionBar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12f) })

        root.addView(area, lp(MATCH, 0, 1f))

        // ---- плеер ----
        val player = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = rounded(C.BAR, dp(16f).toFloat())
        }
        player.addView(IconButton(this, Ico.PREV, iconDp = 22f).apply { setOnClickListener { goToFrame(frameIdx - 1) } })
        playBtn = IconButton(this, Ico.PLAY, iconDp = 24f).apply { setOnClickListener { if (playing) stopPlay() else startPlay() } }
        player.addView(playBtn)
        player.addView(IconButton(this, Ico.NEXT, iconDp = 22f).apply { setOnClickListener { goToFrame(frameIdx + 1) } })
        counter = label("1/1", 15f).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            background = rounded(C.CARD2, dp(10f).toFloat()); setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
        }
        player.addView(counter, lp(WRAP, WRAP).apply { leftMargin = dp(8f); rightMargin = dp(6f) })
        val playerWrap = FrameLayout(this)
        playerWrap.addView(player, FrameLayout.LayoutParams(WRAP, dp(46f), Gravity.CENTER))
        root.addView(playerWrap, lp(MATCH, dp(54f)))

        // ---- лента кадров ----
        val timeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.BAR); setPadding(dp(8f), dp(6f), dp(4f), dp(6f))
        }
        layerBtn = label("Слой", 15f, Color.BLACK).apply {
            gravity = Gravity.CENTER; maxLines = 2
            background = rounded(Color.WHITE, dp(14f).toFloat())
            setOnClickListener { layersDialog() }
        }
        timeline.addView(layerBtn, lp(dp(76f), dp(58f)))
        stripScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        frameStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6f), 0, dp(6f), 0) }
        stripScroll.addView(frameStrip)
        timeline.addView(stripScroll, lp(0, dp(64f), 1f))
        timeline.addView(IconButton(this, Ico.PLUS).apply {
            background = rounded(C.CARD2, dp(12f).toFloat())
            setOnClickListener { addFrame(copyCurrent = false) }
        }, lp(dp(46f), dp(58f)))
        root.addView(timeline, lp(MATCH, WRAP))

        // ---- нижняя панель ----
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(C.BAR); setPadding(0, dp(2f), 0, dp(4f))
        }
        undoBtn = IconButton(this, Ico.UNDO, "Отменить", 24f).apply { setOnClickListener { cv.doUndo(); refreshButtons() } }
        redoBtn = IconButton(this, Ico.REDO, "Вернуть", 24f).apply { setOnClickListener { cv.doRedo(); refreshButtons() } }
        val copyBtn = IconButton(this, Ico.COPY, "Копир.", 24f).apply { setOnClickListener { copyFrame() } }
        pasteBtn = IconButton(this, Ico.PASTE, "Вставить", 24f).apply { setOnClickListener { pasteFrame() } }
        onionBtn = IconButton(this, Ico.ONION, "Калька", 24f).apply { setOnClickListener { cv.onion = !cv.onion; cv.invalidate(); refreshButtons() } }
        val imgBtn = IconButton(this, Ico.IMAGE, "Картинка", 24f).apply { setOnClickListener { pickImage() } }
        for (b in listOf(undoBtn, redoBtn, copyBtn, pasteBtn, onionBtn, imgBtn)) bottom.addView(b, lp(0, WRAP, 1f))
        root.addView(bottom, lp(MATCH, WRAP))

        return root
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.BAR, dp(18f).toFloat())
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        isClickable = true
    }

    // ===================================================================
    // Инструменты
    // ===================================================================

    private fun onToolClick(t: Tool) {
        if (cv.tool == t && t != Tool.LASSO) {
            if (settingsPanel.visibility == View.VISIBLE) hidePanels() else showSettings()
        } else selectTool(t)
    }

    private fun selectTool(t: Tool) {
        if (t != Tool.LASSO) cv.commitFloat()
        cv.tool = t
        if (t != Tool.EYEDROP) lastDrawTool = t
        for ((k, b) in toolButtons) b.active = k == t
        moreBtn.active = t in listOf(Tool.LINE, Tool.RECT, Tool.OVAL, Tool.EYEDROP)
        hidePanels()
    }

    private fun hidePanels() { settingsPanel.visibility = View.GONE; morePanel.visibility = View.GONE }

    private fun toggleMore() {
        if (morePanel.visibility == View.VISIBLE) { hidePanels(); return }
        hidePanels()
        morePanel.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun add(ico: Ico, text: String, act: () -> Unit) {
            row.addView(IconButton(this, ico, text, 24f).apply { setOnClickListener { act() } }, lp(0, WRAP, 1f))
        }
        add(Ico.EYEDROP, "Пипетка") { selectTool(Tool.EYEDROP) }
        add(Ico.LINE, "Линия") { selectTool(Tool.LINE); showSettings() }
        add(Ico.RECT, "Прямоуг.") { selectTool(Tool.RECT); showSettings() }
        add(Ico.OVAL, "Овал") { selectTool(Tool.OVAL); showSettings() }
        add(Ico.GRID, if (cv.grid) "Сетка ✓" else "Сетка") { cv.grid = !cv.grid; cv.invalidate(); hidePanels() }
        morePanel.addView(row)
        morePanel.visibility = View.VISIBLE
    }

    private fun showSettings() {
        hidePanels()
        val sp = settingsPanel
        sp.removeAllViews()
        val b = cv.brush
        fun colorRow() {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label("Цвет", 13f, C.DIM), lp(dp(96f), WRAP))
            val dot = ColorDot(this, b.color)
            dot.setOnClickListener { ColorPicker.show(this, b.color) { c -> b.color = c; dot.color = c; dot.invalidate() } }
            row.addView(dot, lp(dp(44f), dp(44f)))
            for (c in ColorPicker.recent.take(5)) {
                val d = ColorDot(this, c)
                d.setOnClickListener { b.color = c; dot.color = c; dot.invalidate() }
                row.addView(d, lp(dp(36f), dp(36f)))
            }
            sp.addView(row)
        }
        when (cv.tool) {
            Tool.BRUSH, Tool.LINE, Tool.RECT, Tool.OVAL -> {
                sp.addView(sliderRow("Размер", 1, 150, b.size.toInt()) { b.size = it.toFloat() })
                sp.addView(sliderRow("Прозрачность", 5, 100, b.opacity * 100 / 255, { "$it%" }) { b.opacity = it * 255 / 100 })
                if (cv.tool == Tool.BRUSH) sp.addView(sliderRow("Сглаживание", 0, 10, b.smoothing) { b.smoothing = it })
                colorRow()
            }
            Tool.ERASER -> sp.addView(sliderRow("Размер", 2, 250, cv.eraserSize.toInt()) { cv.eraserSize = it.toFloat() })
            Tool.FILL -> {
                sp.addView(sliderRow("Допуск", 0, 200, cv.fillTolerance) { cv.fillTolerance = it })
                colorRow()
            }
            Tool.TEXT -> {
                sp.addView(sliderRow("Размер", 10, 300, cv.textSize.toInt()) { cv.textSize = it.toFloat() })
                colorRow()
            }
            else -> return
        }
        sp.visibility = View.VISIBLE
    }

    // ===================================================================
    // CanvasHost
    // ===================================================================

    override fun currentLayer(): Layer = p.layers[layerIdx.coerceIn(0, p.layers.size - 1)]
    override fun currentFrame(): Frame = p.frames[frameIdx.coerceIn(0, p.frames.size - 1)]
    override fun frameIndex(): Int = frameIdx

    private val thumbUpdate = Runnable { updateThumb(frameIdx) }

    override fun onEdited() {
        refreshButtons()
        ui.removeCallbacks(thumbUpdate)
        ui.postDelayed(thumbUpdate, 350)
    }

    override fun onFloatChanged(has: Boolean) {
        selectionBar.visibility = if (has) View.VISIBLE else View.GONE
    }

    override fun onColorPicked(color: Int, done: Boolean) {
        cv.brush.color = color
        if (done) {
            ColorPicker.recent.remove(color); ColorPicker.recent.add(0, color)
            selectTool(if (lastDrawTool == Tool.EYEDROP) Tool.BRUSH else lastDrawTool)
        }
    }

    override fun requestText(x: Float, y: Float) {
        val et = EditText(this).apply { hint = "Текст"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Текст").setView(et)
            .setPositiveButton("ОК") { _, _ -> cv.drawText(et.text.toString(), x, y) }
            .setNegativeButton("Отмена", null).show()
    }

    override fun onUndoJump(frameId: Int, layerId: Int) {
        val fi = p.frames.indexOfFirst { it.id == frameId }
        if (fi >= 0 && fi != frameIdx) goToFrame(fi)
        val li = p.layers.indexOfFirst { it.id == layerId }
        if (li >= 0 && li != layerIdx) { layerIdx = li; refreshLayerBtn() }
    }

    private fun refreshButtons() {
        undoBtn.dimmed = !cv.undo.canUndo
        redoBtn.dimmed = !cv.undo.canRedo
        pasteBtn.dimmed = clipboard == null
        onionBtn.iconView.color = if (cv.onion) C.ACCENT else C.TEXT
        onionBtn.iconView.invalidate()
    }

    // ===================================================================
    // Кадры
    // ===================================================================

    private fun goToFrame(i: Int) {
        if (p.frames.isEmpty()) return
        val ni = i.coerceIn(0, p.frames.size - 1)
        if (playing) stopPlay()
        cv.commitFloat()
        val old = currentFrame()
        if (old.previewDirty) { p.preview(old); updateThumb(frameIdx) }
        frameIdx = ni
        val f = currentFrame()
        p.evictExcept(setOf(f.id))
        for (l in p.layers) p.cell(l.id, f.id, false)
        cv.dropShadow()
        cv.invalidate()
        refreshStripSelection()
    }

    private fun rebuildStrip() {
        frameStrip.removeAllViews()
        p.frames.forEachIndexed { i, f -> frameStrip.addView(makeThumb(i, f)) }
        refreshStripSelection()
    }

    private fun makeThumb(i: Int, f: Frame): View {
        val box = FrameLayout(this)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            setImageBitmap(p.preview(f))
        }
        box.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        val num = label("${i + 1}", 11f, Color.WHITE).apply {
            background = rounded(0xAA000000.toInt(), dp(6f).toFloat()); setPadding(dp(5f), 0, dp(5f), 0)
        }
        box.addView(num, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4f), dp(4f), 0) })
        if (f.hold > 1) {
            val h = label("×${f.hold}", 11f, Color.WHITE).apply {
                background = rounded(C.PURPLE, dp(6f).toFloat()); setPadding(dp(5f), 0, dp(5f), 0)
            }
            box.addView(h, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(4f), 0, 0, dp(4f)) })
        }
        val ratio = p.canvasW.toFloat() / p.canvasH
        val h = dp(58f)
        val w = (h * ratio).toInt().coerceIn(dp(44f), dp(110f))
        box.layoutParams = LinearLayout.LayoutParams(w, h).apply { rightMargin = dp(6f) }
        box.setOnClickListener { if (i == frameIdx) frameMenu() else goToFrame(i) }
        box.setOnLongClickListener { goToFrame(i); frameMenu(); true }
        return box
    }

    private fun refreshStripSelection() {
        for (i in 0 until frameStrip.childCount) {
            val v = frameStrip.getChildAt(i)
            v.background = if (i == frameIdx) rounded(Color.WHITE, dp(10f).toFloat(), C.ACCENT, dp(3f))
            else rounded(Color.WHITE, dp(10f).toFloat())
        }
        val total = p.frames.size
        counter.text = "${frameIdx + 1}/$total"
        frameStrip.getChildAt(frameIdx)?.let { v ->
            stripScroll.post {
                val l = v.left; val r = v.right
                if (l < stripScroll.scrollX || r > stripScroll.scrollX + stripScroll.width)
                    stripScroll.smoothScrollTo(l - stripScroll.width / 3, 0)
            }
        }
    }

    private fun updateThumb(i: Int) {
        val f = p.frames.getOrNull(i) ?: return
        val box = frameStrip.getChildAt(i) as? ViewGroup ?: return
        (box.getChildAt(0) as? ImageView)?.setImageBitmap(p.preview(f))
    }

    private fun addFrame(copyCurrent: Boolean) {
        cv.commitFloat()
        val cur = currentFrame()
        val nf = Frame(p.newId())
        if (copyCurrent) {
            nf.hold = cur.hold
            for (l in p.layers) {
                val src = p.cell(l.id, cur.id, false) ?: continue
                p.setCell(l.id, nf.id, src.copy(Bitmap.Config.ARGB_8888, true))
            }
        }
        p.frames.add(frameIdx + 1, nf)
        p.saveMeta()
        rebuildStrip()
        goToFrame(frameIdx + 1)
    }

    private fun copyFrame() {
        cv.commitFloat()
        val f = currentFrame()
        val m = HashMap<Int, Bitmap>()
        for (l in p.layers) p.cell(l.id, f.id, false)?.let { m[l.id] = it.copy(Bitmap.Config.ARGB_8888, false) }
        clipboard = m
        refreshButtons()
        Toast.makeText(this, "Кадр скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun pasteFrame() {
        val cb = clipboard ?: return
        cv.commitFloat()
        val nf = Frame(p.newId())
        for ((lid, bmp) in cb) if (p.layers.any { it.id == lid }) p.setCell(lid, nf.id, bmp.copy(Bitmap.Config.ARGB_8888, true))
        p.frames.add(frameIdx + 1, nf)
        p.saveMeta()
        rebuildStrip()
        goToFrame(frameIdx + 1)
    }

    private fun deleteFrame() {
        val f = currentFrame()
        if (p.frames.size == 1) {
            for (l in p.layers) p.cell(l.id, f.id, false)?.eraseColor(0)
            for (l in p.layers) p.markDirty(l.id, f.id)
            cv.undo.forget(frameId = f.id); cv.dropShadow(); cv.invalidate(); updateThumb(0)
            return
        }
        p.deleteCellFiles(f.id)
        cv.undo.forget(frameId = f.id)
        p.frames.removeAt(frameIdx)
        if (frameIdx >= p.frames.size) frameIdx = p.frames.size - 1
        p.saveMeta()
        rebuildStrip()
        val target = frameIdx
        frameIdx = -1
        goToFrame(target)
        refreshButtons()
    }

    private fun moveFrame(dir: Int) {
        val ni = frameIdx + dir
        if (ni !in p.frames.indices) return
        val f = p.frames.removeAt(frameIdx)
        p.frames.add(ni, f)
        frameIdx = ni
        p.saveMeta()
        rebuildStrip()
    }

    private fun frameMenu() {
        val items = arrayOf("Дублировать", "Новый пустой после", "Длительность (×${currentFrame().hold})…", "Сдвинуть влево", "Сдвинуть вправо", "Удалить кадр")
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Кадр ${frameIdx + 1}")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> addFrame(copyCurrent = true)
                    1 -> addFrame(copyCurrent = false)
                    2 -> holdDialog()
                    3 -> moveFrame(-1)
                    4 -> moveFrame(1)
                    5 -> AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Удалить кадр?")
                        .setPositiveButton("Удалить") { _, _ -> deleteFrame() }.setNegativeButton("Отмена", null).show()
                }
            }.show()
    }

    private fun holdDialog() {
        val np = NumberPicker(this).apply { minValue = 1; maxValue = 48; value = currentFrame().hold; wrapSelectorWheel = false }
        val wrap = FrameLayout(this); wrap.addView(np, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Сколько кадров держать рисунок")
            .setView(wrap)
            .setPositiveButton("ОК") { _, _ -> currentFrame().hold = np.value; p.saveMeta(); rebuildStrip() }
            .setNegativeButton("Отмена", null).show()
    }

    // ===================================================================
    // Проигрывание
    // ===================================================================

    private val playTick = object : Runnable {
        override fun run() {
            if (!playing) return
            val seq = playSequence
            if (seq.isEmpty()) { stopPlay(); return }
            playPos = (playPos + 1) % seq.size
            showPlayFrame(seq[playPos])
            ui.postDelayed(this, (1000L / p.fps.coerceAtLeast(1)))
        }
    }
    private var playSequence = IntArray(0)

    private fun showPlayFrame(fi: Int) {
        cv.playBitmap = p.preview(p.frames[fi])
        counter.text = "${fi + 1}/${p.frames.size}"
    }

    private fun startPlay() {
        cv.commitFloat()
        hidePanels()
        p.preview(currentFrame())
        updateThumb(frameIdx)
        val seq = ArrayList<Int>()
        p.frames.forEachIndexed { i, f -> repeat(f.hold) { seq.add(i) } }
        // заранее готовим превьюшки
        for (f in p.frames) p.preview(f)
        playSequence = seq.toIntArray()
        playing = true
        playBtn.setIcon(Ico.PAUSE)
        playPos = seq.indexOf(frameIdx).coerceAtLeast(0)
        showPlayFrame(seq[playPos])
        ui.postDelayed(playTick, 1000L / p.fps.coerceAtLeast(1))
    }

    private fun stopPlay() {
        if (!playing) return
        playing = false
        ui.removeCallbacks(playTick)
        playBtn.setIcon(Ico.PLAY)
        cv.playBitmap = null
        counter.text = "${frameIdx + 1}/${p.frames.size}"
    }

    // ===================================================================
    // Слои
    // ===================================================================

    private fun refreshLayerBtn() {
        layerBtn.text = currentLayer().name
    }

    private fun layersDialog() {
        cv.commitFloat()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), dp(8f), dp(10f), dp(8f)) }
        val scroll = ScrollView(this); scroll.addView(box)
        val dlg = AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Слои").setView(scroll)
            .setPositiveButton("Закрыть", null).create()

        fun rebuild() {
            box.removeAllViews()
            for (idx in p.layers.indices.reversed()) {
                val l = p.layers[idx]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = rounded(if (idx == layerIdx) C.ACCENT else C.CARD2, dp(12f).toFloat())
                    setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
                }
                val eye = IconButton(this, if (l.visible) Ico.EYE else Ico.EYE_OFF, iconDp = 22f)
                eye.setOnClickListener { l.visible = !l.visible; p.frames.forEach { it.previewDirty = true }; cv.invalidate(); rebuild() }
                row.addView(eye)
                val name = label(l.name, 15f).apply { maxLines = 1 }
                row.addView(name, lp(0, WRAP, 1f))
                val up = IconButton(this, Ico.UP, iconDp = 20f)
                up.setOnClickListener { if (idx < p.layers.size - 1) { swapLayers(idx, idx + 1); rebuild() } }
                val down = IconButton(this, Ico.DOWN, iconDp = 20f)
                down.setOnClickListener { if (idx > 0) { swapLayers(idx, idx - 1); rebuild() } }
                row.addView(up); row.addView(down)
                row.setOnClickListener { layerIdx = idx; cv.dropShadow(); refreshLayerBtn(); cv.invalidate(); rebuild() }
                row.setOnLongClickListener { layerMenu(idx) { rebuild() }; true }
                box.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(6f) })
            }
            val cur = currentLayer()
            box.addView(sliderRow("Непрозрачность", 0, 100, cur.opacity * 100 / 255, { "$it%" }) {
                cur.opacity = it * 255 / 100; p.frames.forEach { f -> f.previewDirty = true }; cv.invalidate()
            })
            val add = label("+  Новый слой", 15f, C.ACCENT).apply { setPadding(dp(8f), dp(12f), dp(8f), dp(6f)) }
            add.setOnClickListener {
                if (p.layers.size >= 12) { Toast.makeText(this, "Максимум 12 слоёв", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                p.layers.add(layerIdx + 1, Layer(p.newId(), "Слой ${p.layers.size + 1}"))
                layerIdx += 1; p.saveMeta(); cv.dropShadow(); refreshLayerBtn(); cv.invalidate(); rebuild()
            }
            box.addView(add)
            box.addView(label("Долгое нажатие на слой — переименовать / удалить", 12f, C.DIM).apply { setPadding(dp(8f), 0, dp(8f), 0) })
        }
        rebuild()
        dlg.setOnDismissListener { p.saveMeta(); refreshLayerBtn(); rebuildStrip() }
        dlg.show()
    }

    private fun swapLayers(a: Int, b: Int) {
        val t = p.layers[a]; p.layers[a] = p.layers[b]; p.layers[b] = t
        if (layerIdx == a) layerIdx = b else if (layerIdx == b) layerIdx = a
        p.frames.forEach { it.previewDirty = true }
        cv.invalidate()
    }

    private fun layerMenu(idx: Int, done: () -> Unit) {
        val l = p.layers[idx]
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle(l.name)
            .setItems(arrayOf("Переименовать", "Удалить слой")) { _, w ->
                if (w == 0) {
                    val et = EditText(this).apply { setText(l.name) }
                    AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Имя слоя").setView(et)
                        .setPositiveButton("ОК") { _, _ -> l.name = et.text.toString().ifBlank { l.name }; refreshLayerBtn(); done() }
                        .setNegativeButton("Отмена", null).show()
                } else {
                    if (p.layers.size == 1) { Toast.makeText(this, "Нельзя удалить единственный слой", Toast.LENGTH_SHORT).show(); return@setItems }
                    AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Удалить «${l.name}» во всех кадрах?")
                        .setPositiveButton("Удалить") { _, _ ->
                            p.deleteLayerFiles(l.id); cv.undo.forget(layerId = l.id)
                            p.layers.removeAt(idx)
                            layerIdx = layerIdx.coerceAtMost(p.layers.size - 1)
                            cv.dropShadow(); refreshLayerBtn(); cv.invalidate(); refreshButtons(); done()
                        }.setNegativeButton("Отмена", null).show()
                }
            }.show()
    }

    // ===================================================================
    // Меню проекта
    // ===================================================================

    private fun renameDialog() {
        val et = EditText(this).apply { setText(p.name) }
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Название мультфильма").setView(et)
            .setPositiveButton("ОК") { _, _ -> p.name = et.text.toString().ifBlank { p.name }; titleView.text = p.name; p.saveMeta() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun settingsMenu() {
        val items = arrayOf(
            "Скорость: ${p.fps} кадров/с",
            "Калька: ${cv.onionBefore} до, ${cv.onionAfter} после",
            "Сетка: " + if (cv.grid) "вкл" else "выкл",
            "Цвет фона",
            "Переименовать",
            "Сбросить вид"
        )
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Настройки проекта").setItems(items) { _, w ->
            when (w) {
                0 -> {
                    val np = NumberPicker(this).apply { minValue = 1; maxValue = 60; value = p.fps }
                    val wrap = FrameLayout(this); wrap.addView(np, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                    AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Кадров в секунду").setView(wrap)
                        .setPositiveButton("ОК") { _, _ -> p.fps = np.value; p.saveMeta() }.setNegativeButton("Отмена", null).show()
                }
                1 -> {
                    val opts = arrayOf("1 до / 0 после", "1 до / 1 после", "2 до / 1 после", "2 до / 2 после", "3 до / 0 после")
                    val vals = arrayOf(1 to 0, 1 to 1, 2 to 1, 2 to 2, 3 to 0)
                    AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Калька").setItems(opts) { _, i ->
                        cv.onionBefore = vals[i].first; cv.onionAfter = vals[i].second; cv.onion = true; cv.invalidate(); refreshButtons()
                    }.show()
                }
                2 -> { cv.grid = !cv.grid; cv.invalidate() }
                3 -> ColorPicker.show(this, p.bgColor) { c ->
                    p.bgColor = c or 0xFF000000.toInt(); p.frames.forEach { it.previewDirty = true }; p.saveMeta(); cv.invalidate(); rebuildStrip()
                }
                4 -> renameDialog()
                5 -> cv.fitView()
            }
        }.show()
    }

    // ===================================================================
    // Картинка
    // ===================================================================

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        try { startActivityForResult(Intent.createChooser(i, "Картинка"), REQ_IMAGE) } catch (e: Throwable) { toast("Нет приложения для выбора картинок") }
    }

    private fun loadImage(uri: Uri): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / sample > p.worldW * 1.5 || o.outHeight / sample > p.worldH * 1.5) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Throwable) { null }
    }

    // ===================================================================
    // Экспорт
    // ===================================================================

    private fun safeName() = p.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").ifBlank { "multfilm" }

    private fun exportMenu() {
        stopPlay(); cv.commitFloat()
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Создать фильм")
            .setItems(arrayOf("GIF-анимация", "Кадры PNG (в папку)", "Только этот кадр PNG")) { _, w ->
                when (w) {
                    0 -> createDoc("image/gif", safeName() + ".gif", REQ_GIF)
                    1 -> try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PNG_DIR)
                    } catch (e: Throwable) { toast("Не получилось открыть выбор папки") }
                    2 -> createDoc("image/png", safeName() + "_${frameIdx + 1}.png", REQ_PNG_ONE)
                }
            }.show()
    }

    private fun createDoc(mime: String, name: String, req: Int) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE, name) }
        try { startActivityForResult(i, req) } catch (e: Throwable) { toast("Не получилось открыть сохранение") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_IMAGE -> {
                val bmp = loadImage(uri) ?: run { toast("Не удалось открыть картинку"); return }
                if (cv.tool != Tool.LASSO) selectTool(Tool.LASSO)
                cv.startFloat(bmp)
            }
            REQ_GIF -> exportGif(uri)
            REQ_PNG_DIR -> exportPngs(uri)
            REQ_PNG_ONE -> exportOne(uri)
        }
    }

    private fun exportScale(): Float = minOf(1f, 960f / p.canvasW)

    private fun progressDialog(title: String): Pair<AlertDialog, TextView> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20f), dp(20f), dp(20f), dp(10f)) }
        box.addView(ProgressBar(this))
        val t = label("", 14f); t.gravity = Gravity.CENTER
        box.addView(t)
        val d = AlertDialog.Builder(this, R.style.DialogTheme).setTitle(title).setView(box).setCancelable(false).create()
        d.show()
        return d to t
    }

    private fun runExport(title: String, work: ((String) -> Unit) -> String) {
        p.saveDirty(wait = true)
        val (dlg, txt) = progressDialog(title)
        Thread {
            val msg = try { work { s -> ui.post { txt.text = s } } } catch (e: Throwable) { "Ошибка: ${e.message}" }
            ui.post { dlg.dismiss(); toast(msg) }
        }.start()
    }

    private fun exportGif(uri: Uri) {
        val frames = p.frames.toList()
        val sc = exportScale()
        val fps = p.fps
        runExport("Создаю GIF…") { progress ->
            contentResolver.openOutputStream(uri)!!.buffered().use { os ->
                val first = p.compose(frames[0], sc)
                val enc = GifEncoder(os, first.width, first.height)
                var t = 0.0
                var written = 0
                frames.forEachIndexed { i, f ->
                    progress("Кадр ${i + 1} из ${frames.size}")
                    val bmp = if (i == 0) first else p.compose(f, sc)
                    t += f.hold * 100.0 / fps
                    val delay = (Math.round(t) - written).toInt().coerceAtLeast(2)
                    written += delay
                    enc.addFrame(bmp, delay)
                }
                enc.finish()
            }
            "GIF сохранён"
        }
    }

    private fun exportPngs(tree: Uri) {
        val frames = p.frames.toList()
        val sc = minOf(1f, 1920f / p.canvasW)
        runExport("Сохраняю кадры…") { progress ->
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            var n = 1
            frames.forEachIndexed { i, f ->
                progress("Кадр ${i + 1} из ${frames.size}")
                val bmp = p.compose(f, sc)
                repeat(f.hold) {
                    val name = safeName() + "_" + n.toString().padStart(4, '0')
                    val doc = DocumentsContract.createDocument(contentResolver, parent, "image/png", name)
                    if (doc != null) contentResolver.openOutputStream(doc)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    n++
                }
            }
            "Сохранено кадров: ${n - 1}"
        }
    }

    private fun exportOne(uri: Uri) {
        val f = currentFrame()
        runExport("Сохраняю кадр…") {
            val bmp = p.compose(f, 1f)
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "Кадр сохранён"
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
