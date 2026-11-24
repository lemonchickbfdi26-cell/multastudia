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
    private lateinit var bottomNormal: LinearLayout
    private lateinit var bottomSel: LinearLayout
    private val selButtons = ArrayList<IconButton>()
    private lateinit var selPasteBtn: IconButton
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
    private lateinit var keyPanel: LinearLayout
    private lateinit var keyBtn: TextView
    private lateinit var easeBtn: LinearLayout
    private lateinit var easeCurve: CurveView
    private lateinit var easeText: TextView
    private lateinit var keyHint: TextView
    private lateinit var multiBtn: TextView
    private lateinit var posePanel: LinearLayout
    private var lastEase = Ease.IN_OUT
    private val thumbKeyBadges = ArrayList<View>()
    private var playCache: HashMap<Int, Bitmap>? = null
    private var clipboard: Map<Int, Bitmap>? = null
    private var playing = false
    private var playPos = 0

    companion object {
        const val REQ_IMAGE = 1
        const val REQ_GIF = 2
        const val REQ_PNG_DIR = 3
        const val REQ_PNG_ONE = 4
        const val REQ_MP4 = 5
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
        flushKeys()
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

        // ---- панель ключевых кадров ----
        keyPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.BAR, dp(18f).toFloat()); setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            visibility = View.GONE; isClickable = true
        }
        keyHint = label("", 12f, C.DIM).apply { gravity = Gravity.CENTER }
        keyPanel.addView(keyHint, lp(MATCH, WRAP))
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        keyBtn = label("◆ Ключ", 14f).apply {
            gravity = Gravity.CENTER; setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            setOnClickListener { toggleKey() }
        }
        keyRow.addView(keyBtn, lp(WRAP, WRAP))
        easeBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.CARD2, dp(12f).toFloat()); setPadding(dp(4f), 0, dp(10f), 0)
            setOnClickListener { pickEase() }
        }
        easeCurve = CurveView(this, lastEase)
        easeBtn.addView(easeCurve, lp(dp(44f), dp(38f)))
        easeText = label("", 13f).apply { maxLines = 1 }
        easeBtn.addView(easeText, lp(0, WRAP, 1f))
        keyRow.addView(easeBtn, lp(0, WRAP, 1f).apply { leftMargin = dp(6f); rightMargin = dp(6f) })
        multiBtn = label("Несколько", 12f).apply {
            gravity = Gravity.CENTER; setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener { cv.multi = !cv.multi; refreshKeyPanel(); updateKeyBadges() }
        }
        keyRow.addView(multiBtn, lp(WRAP, WRAP).apply { leftMargin = dp(4f) })
        keyRow.addView(label("Сброс", 13f, C.DIM).apply {
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f)); setOnClickListener { resetKeys() }
        })
        keyPanel.addView(keyRow, lp(MATCH, WRAP).apply { topMargin = dp(4f) })
        area.addView(keyPanel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(10f), 0, dp(10f), dp(10f)) })

        posePanel = card().apply { visibility = View.GONE; setPadding(dp(12f), dp(8f), dp(12f), dp(6f)) }
        toolsCol.addView(posePanel, lp(MATCH, WRAP).apply { topMargin = dp(8f) })

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
        bottomNormal = bottom
        undoBtn = IconButton(this, Ico.UNDO, "Отменить", 24f).apply { setOnClickListener { cv.doUndo(); refreshButtons() } }
        redoBtn = IconButton(this, Ico.REDO, "Вернуть", 24f).apply { setOnClickListener { cv.doRedo(); refreshButtons() } }
        val copyBtn = IconButton(this, Ico.COPY, "Копир.", 24f).apply { setOnClickListener { copyFrame() } }
        pasteBtn = IconButton(this, Ico.PASTE, "Вставить", 24f).apply { setOnClickListener { pasteFrame() } }
        onionBtn = IconButton(this, Ico.ONION, "Калька", 24f).apply { setOnClickListener { cv.onion = !cv.onion; cv.invalidate(); refreshButtons() } }
        val imgBtn = IconButton(this, Ico.IMAGE, "Картинка", 24f).apply { setOnClickListener { pickImage() } }
        for (b in listOf(undoBtn, redoBtn, copyBtn, pasteBtn, onionBtn, imgBtn)) bottom.addView(b, lp(0, WRAP, 1f))
        root.addView(bottom, lp(MATCH, WRAP))

        // ---- нижняя панель лассо (как во Флиппе) ----
        bottomSel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(C.BAR); setPadding(0, dp(2f), 0, dp(4f)); visibility = View.GONE
        }
        val sCopy = IconButton(this, Ico.COPY, "Копировать", 24f).apply {
            setOnClickListener { if (cv.copyFloat()) { toastShort("Изображение скопировано"); refreshSelBar() } }
        }
        selPasteBtn = IconButton(this, Ico.PASTE, "Вставить", 24f).apply {
            setOnClickListener { if (cv.pasteFloat()) toastShort("Изображение вставлено") }
        }
        val sFlipX = IconButton(this, Ico.FLIP, "Отразить X", 24f).apply { setOnClickListener { cv.flipFloatX() } }
        val sFlipY = IconButton(this, Ico.FLIP_Y, "Отразить Y", 24f).apply { setOnClickListener { cv.flipFloatY() } }
        val sDel = IconButton(this, Ico.TRASH, "Удалить", 24f).apply { setOnClickListener { cv.deleteFloat() } }
        val sDone = IconButton(this, Ico.CHECK, "Готово", 24f).apply { setOnClickListener { cv.commitFloat() } }
        selButtons.addAll(listOf(sCopy, sFlipX, sFlipY, sDel, sDone))
        for (b in listOf(sCopy, selPasteBtn, sFlipX, sFlipY, sDel, sDone)) bottomSel.addView(b, lp(0, WRAP, 1f))
        root.addView(bottomSel, lp(MATCH, WRAP))

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
        moreBtn.active = t in listOf(Tool.LINE, Tool.RECT, Tool.OVAL, Tool.EYEDROP, Tool.MOVE, Tool.CAMERA, Tool.POSE)
        hidePanels()
        refreshSelBar()
        refreshKeyPanel()
        updateKeyBadges()
        cv.invalidate()
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
        add(Ico.MOVE, "Движение") { selectTool(Tool.MOVE) }
        add(Ico.BONE, "Поза") { selectTool(Tool.POSE) }
        add(Ico.CAMERA, "Камера") { selectTool(Tool.CAMERA) }
        add(Ico.EYEDROP, "Пипетка") { selectTool(Tool.EYEDROP) }
        morePanel.addView(row)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun add2(ico: Ico, text: String, act: () -> Unit) {
            row2.addView(IconButton(this, ico, text, 24f).apply { setOnClickListener { act() } }, lp(0, WRAP, 1f))
        }
        add2(Ico.LINE, "Линия") { selectTool(Tool.LINE); showSettings() }
        add2(Ico.RECT, "Прямоуг.") { selectTool(Tool.RECT); showSettings() }
        add2(Ico.OVAL, "Овал") { selectTool(Tool.OVAL); showSettings() }
        add2(Ico.GRID, if (cv.grid) "Сетка ✓" else "Сетка") { cv.grid = !cv.grid; cv.invalidate(); hidePanels() }
        morePanel.addView(row2)
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
        if (currentLayer().isObject) refreshThumbsLazy()
        ui.removeCallbacks(thumbUpdate)
        ui.postDelayed(thumbUpdate, 350)
    }

    override fun onFloatChanged(has: Boolean) {
        refreshSelBar()
    }

    private fun refreshSelBar() {
        val show = cv.tool == Tool.LASSO || cv.hasFloat
        bottomSel.visibility = if (show) View.VISIBLE else View.GONE
        bottomNormal.visibility = if (show) View.GONE else View.VISIBLE
        for (b in selButtons) b.dimmed = !cv.hasFloat
        selPasteBtn.dimmed = !cv.hasClip
    }

    override fun onFloatMenu() {
        val items = arrayOf(
            (if (!cv.perspectiveMode) "● " else "○ ") + "Трансформация",
            (if (cv.perspectiveMode) "● " else "○ ") + "Перспектива",
            "◆ Превратить в объект (автоанимация)",
            "🦴 Отдать кусочек персонажу (риг)"
        )
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Выделение")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> cv.perspectiveMode = false
                    1 -> cv.perspectiveMode = true
                    2 -> makeObjectFromSelection()
                    3 -> giveSelectionToRig()
                }
            }.show()
    }

    private fun toastShort(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ===================================================================
    // Автоанимация: объекты, ключи, камера
    // ===================================================================

    override fun defaultEase(): Ease = lastEase

    private var keysPending = false

    override fun onKeysChanged(live: Boolean) {
        if (live) { keysPending = true; refreshKeyPanel(rebuildPose = false); updateKeyBadges(); return }
        keysPending = false
        p.invalidateAllPreviews()
        p.saveMeta()
        refreshKeyPanel()
        updateKeyBadges()
        refreshThumbsLazy()
    }

    /** Доступ к ключам того, что сейчас двигаем (объект, группа, камера или персонаж). */
    private inner class Keys(
        val count: () -> Int,
        val has: (Int) -> Boolean,
        val easeAt: (Int) -> Ease?,
        val setEase: (Int, Ease) -> Unit,
        val remove: (Int) -> Unit,
        val addHere: (Int) -> Unit,
        val clear: () -> Unit
    )

    private fun trackKeys(list: List<Pair<Track, () -> Xf>>) = Keys(
        count = { list.maxOfOrNull { it.first.keys.size } ?: 0 },
        has = { f -> list.any { it.first.keyAt(f) != null } },
        easeAt = { f -> list.firstNotNullOfOrNull { it.first.keyAt(f)?.ease } },
        setEase = { f, e -> list.forEach { it.first.keyAt(f)?.ease = e } },
        remove = { f -> list.forEach { it.first.remove(f) } },
        addHere = { f -> list.forEach { (t, xf) -> t.set(f, xf().copy(), lastEase) } },
        clear = { list.forEach { it.first.keys.clear() } }
    )

    private fun activeKeys(): Keys? {
        val cur = currentLayer()
        return when {
            cv.tool == Tool.CAMERA -> trackKeys(listOf(p.camera to { p.cameraXf(frameIdx) }))
            cv.tool == Tool.MOVE && cv.multi -> {
                val g = p.layers.filter { it.isObject && it.id in cv.group }
                if (g.isEmpty()) null else trackKeys(g.map { l -> l.track to { p.objectXf(l, frameIdx) } })
            }
            cv.tool == Tool.MOVE && cur.isObject -> trackKeys(listOf(cur.track to { p.objectXf(cur, frameIdx) }))
            cv.tool == Tool.POSE && cur.isRig -> {
                val t = cur.rig!!.track
                Keys(
                    count = { t.keys.size }, has = { t.keyAt(it) != null }, easeAt = { t.keyAt(it)?.ease },
                    setEase = { f, e -> t.keyAt(f)?.ease = e }, remove = { t.remove(it) },
                    addHere = { f -> t.set(f, p.rigPose(cur, frameIdx).copy(), lastEase) }, clear = { t.keys.clear() }
                )
            }
            else -> null
        }
    }

    private fun flushKeys() { if (keysPending) onKeysChanged(false) }

    private fun refreshKeyPanel(rebuildPose: Boolean = true) {
        val show = cv.tool == Tool.MOVE || cv.tool == Tool.CAMERA || cv.tool == Tool.POSE
        keyPanel.visibility = if (show) View.VISIBLE else View.GONE
        multiBtn.visibility = if (cv.tool == Tool.MOVE) View.VISIBLE else View.GONE
        multiBtn.text = if (cv.multi) "Группа ✓" else "Несколько"
        multiBtn.background = rounded(if (cv.multi) C.PURPLE else C.CARD2, dp(12f).toFloat())
        if (rebuildPose) refreshPosePanel()
        if (!show) return
        val ks = activeKeys()
        if (ks == null) {
            keyHint.text = when {
                cv.tool == Tool.POSE -> "Поза работает со слоем-персонажем. Создай его: «Слой» → «Новый персонаж (риг)»."
                cv.multi -> "Нажимай на объекты, чтобы собрать группу, потом двигай всех вместе."
                else -> "Движение работает со слоем-объектом. Выдели рисунок лассо → «⋯» → «Превратить в объект», или просто нажми на объект."
            }
            keyBtn.visibility = View.GONE; easeBtn.visibility = View.GONE
            return
        }
        keyBtn.visibility = View.VISIBLE; easeBtn.visibility = View.VISIBLE
        val fid = currentFrame().id
        val has = ks.has(fid)
        val n = ks.count()
        keyHint.text = when {
            cv.tool == Tool.MOVE && cv.multi -> "В группе: ${cv.group.size}. Нажми на объект, чтобы добавить/убрать. Ключей: $n"
            n == 0 -> when (cv.tool) {
                Tool.POSE -> "Тяни суставы — ключ поставится сам. Жёлтая точка двигает всего персонажа."
                Tool.CAMERA -> "Двигай, крути и масштабируй камеру — ключ поставится сам."
                else -> "Двигай, крути и масштабируй объект — ключ поставится сам. Нажатие на другой объект выбирает его."
            }
            else -> "Ключей: $n. Плавность действует от этого ключа до следующего."
        }
        keyBtn.text = if (has) "◆ Ключ" else "◇ Ключ"
        keyBtn.background = rounded(if (has) C.ACCENT else C.CARD2, dp(12f).toFloat())
        val e = ks.easeAt(fid) ?: lastEase
        easeCurve.ease = e; easeCurve.invalidate()
        easeText.text = e.title
    }

    private fun toggleKey() {
        val ks = activeKeys() ?: return
        val fid = currentFrame().id
        if (ks.has(fid)) ks.remove(fid) else ks.addHere(fid)
        cv.invalidate()
        onKeysChanged(false)
    }

    private fun pickEase() {
        val ks = activeKeys()
        val fid = currentFrame().id
        EasePicker.show(this, ks?.easeAt?.invoke(fid) ?: lastEase) { e ->
            lastEase = e
            if (ks != null && ks.has(fid)) { ks.setEase(fid, e); onKeysChanged(false) } else refreshKeyPanel()
            cv.invalidate()
        }
    }

    private fun resetKeys() {
        val ks = activeKeys() ?: return
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Удалить все ключи?")
            .setMessage(when (cv.tool) { Tool.CAMERA -> "Камера вернётся на весь холст."; Tool.POSE -> "Персонаж вернётся в спокойную позу."; else -> "Объекты вернутся на место." })
            .setPositiveButton("Удалить") { _, _ -> ks.clear(); cv.invalidate(); onKeysChanged(false) }
            .setNegativeButton("Отмена", null).show()
    }

    // ---------- выбор объектов и поза ----------

    override fun onObjectTapped(layerId: Int) {
        if (!cv.multi) {
            val i = p.layers.indexOfFirst { it.id == layerId }
            if (i >= 0 && i != layerIdx) { layerIdx = i; cv.dropShadow(); refreshLayerBtn() }
        }
        refreshKeyPanel(); updateKeyBadges(); cv.invalidate()
    }

    override fun onPoseBoneSelected(bone: Int) { refreshPosePanel() }

    override fun onHint(text: String) { toastShort(text) }

    private fun refreshPosePanel() {
        val show = cv.tool == Tool.POSE && currentLayer().isRig
        posePanel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        posePanel.removeAllViews()
        val pose = cv.currentPose() ?: return
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bs = currentLayer().rig!!.bones()
        if (cv.poseBone >= bs.size) cv.poseBone = 1
        r1.addView(label("🦴 " + bs[cv.poseBone].title, 14f).apply { maxLines = 1 }, lp(0, WRAP, 1f))
        r1.addView(label("Мастерская", 13f).apply {
            background = rounded(C.ACCENT, dp(12f).toFloat()); setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { openWorkshop(currentLayer()) }
        })
        posePanel.addView(r1)
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val modes = arrayOf("Крутить", "Тянуть", "Оторвать")
        for (i in 0..2) r2.addView(label(modes[i], 12f).apply {
            gravity = Gravity.CENTER
            background = rounded(if (cv.poseMode == i) C.PURPLE else C.CARD2, dp(10f).toFloat()); setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            setOnClickListener { cv.poseMode = i; refreshPosePanel() }
        }, lp(0, WRAP, 1f).apply { rightMargin = dp(4f) })
        posePanel.addView(r2, lp(MATCH, WRAP).apply { topMargin = dp(6f) })
        val r3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun btn(t: String, act: () -> Unit) = r3.addView(label(t, 12f).apply {
            gravity = Gravity.CENTER; background = rounded(C.CARD2, dp(10f).toFloat()); setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            setOnClickListener { act(); refreshPosePanel() }
        }, lp(0, WRAP, 1f).apply { rightMargin = dp(4f) })
        btn("К себе") { cv.tiltBone(20f) }
        btn("От себя") { cv.tiltBone(-20f) }
        btn("Скрутить") { cv.twistBone(30f) }
        btn("Сброс") { cv.resetBone() }
        posePanel.addView(r3, lp(MATCH, WRAP).apply { topMargin = dp(4f) })
        val r4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun btn4(t: String, act: () -> Unit) = r4.addView(label(t, 12f).apply {
            gravity = Gravity.CENTER; background = rounded(C.CARD2, dp(10f).toFloat()); setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            setOnClickListener { act(); refreshPosePanel() }
        }, lp(0, WRAP, 1f).apply { rightMargin = dp(4f) })
        btn4("⤓ Назад (за тело)") { cv.depthBone(-60f) }
        btn4("⤒ Вперёд") { cv.depthBone(60f) }
        posePanel.addView(r4, lp(MATCH, WRAP).apply { topMargin = dp(4f) })
        val yawNorm = (((pose.yaw + 180f) % 360f + 360f) % 360f - 180f).toInt()
        posePanel.addView(sliderRow("Поворот", -180, 180, yawNorm, { "$it°" }) { cv.setRigGlobal(yaw = it.toFloat()) })
        posePanel.addView(sliderRow("Наклон", -60, 60, pose.pitch.toInt(), { "$it°" }) { cv.setRigGlobal(pitch = it.toFloat()) })
        posePanel.addView(sliderRow("Размер", 10, 300, (pose.scale / p.rigDefault().scale * 100).toInt(), { "$it%" }) {
            cv.setRigGlobal(scale = p.rigDefault().scale * it / 100f)
        })
    }

    private fun openWorkshop(l: Layer) {
        if (!l.isRig) return
        RigWorkshop(this, p, l, { cb -> pickImageFor(cb) }) {
            p.invalidateAllPreviews(); p.saveMeta(); cv.invalidate(); refreshThumbsLazy()
        }.show()
    }

    private var imageCallback: ((Bitmap) -> Unit)? = null

    private fun pickImageFor(cb: (Bitmap) -> Unit) {
        imageCallback = cb
        pickImage()
    }

    private fun newRigLayer(): Layer {
        val l = Layer(p.newId(), "Персонаж ${p.layers.count { it.isRig } + 1}").apply { rig = Rig() }
        p.layers.add(layerIdx + 1, l)
        layerIdx += 1
        p.saveMeta()
        return l
    }

    private fun giveSelectionToRig() {
        val bmp = cv.floatToBitmap(remove = false) ?: return
        var rigs = p.layers.filter { it.isRig }
        if (rigs.isEmpty()) {
            val keep = layerIdx
            val l = newRigLayer()
            layerIdx = keep
            rigs = listOf(l)
            toastShort("Создан слой «${l.name}»")
        }
        RigWorkshop.askPart(this, rigs, bmp, p) { l, _ ->
            cv.deleteFloat()
            p.invalidateAllPreviews(); p.saveMeta(); refreshThumbsLazy()
            val i = p.layers.indexOf(l)
            if (i >= 0) { layerIdx = i; cv.dropShadow(); refreshLayerBtn(); selectTool(Tool.POSE) }
            toastShort("Кусочек отдан персонажу. Поправить его можно в «Мастерской»")
        }
    }

    private fun makeObjectFromSelection() {
        val world = cv.floatToObject() ?: return
        val l = Layer(p.newId(), "Объект ${p.layers.count { it.isObject } + 1}").apply { isObject = true }
        p.layers.add(layerIdx + 1, l)
        p.setCell(l.id, 0, world)
        layerIdx += 1
        p.saveMeta()
        cv.dropShadow()
        refreshLayerBtn()
        selectTool(Tool.MOVE)
        toastShort("Готово! Теперь двигай объект и ставь ключи на разных кадрах")
    }

    private fun updateKeyBadges() {
        val ks = activeKeys()
        p.frames.forEachIndexed { i, f ->
            thumbKeyBadges.getOrNull(i)?.visibility = if (ks != null && ks.has(f.id)) View.VISIBLE else View.GONE
        }
    }

    private val lazyThumbs = object : Runnable {
        override fun run() {
            if (playing) return
            val i = p.frames.indexOfFirst { it.previewDirty }
            if (i < 0) return
            updateThumb(i)
            ui.postDelayed(this, 20)
        }
    }

    private fun refreshThumbsLazy() {
        ui.removeCallbacks(lazyThumbs)
        ui.postDelayed(lazyThumbs, 60)
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
        flushKeys()
        val old = currentFrame()
        if (old.previewDirty) { p.preview(old); updateThumb(frameIdx) }
        frameIdx = ni
        val f = currentFrame()
        p.evictExcept(setOf(f.id, 0))
        for (l in p.layers) p.cell(l.id, f.id, false)
        cv.dropShadow()
        cv.invalidate()
        refreshStripSelection()
        refreshKeyPanel()
    }

    private fun rebuildStrip() {
        frameStrip.removeAllViews()
        thumbKeyBadges.clear()
        p.frames.forEachIndexed { i, f -> frameStrip.addView(makeThumb(i, f)) }
        refreshStripSelection()
        updateKeyBadges()
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
        val kb = label("◆", 13f, C.ACCENT).apply { visibility = View.GONE }
        box.addView(kb, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(5f), dp(2f)) })
        thumbKeyBadges.add(kb)
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
        if (!p.camera.isEmpty || p.layers.any { it.isObject }) p.invalidateAllPreviews()
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
        for (l in p.layers) l.track.remove(f.id)
        p.camera.remove(f.id)
        p.invalidateAllPreviews()
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
        if (!p.camera.isEmpty || p.layers.any { it.isObject }) p.invalidateAllPreviews()
        frameIdx = ni
        p.saveMeta()
        rebuildStrip()
    }

    private fun frameMenu() {
        val items = arrayOf("Дублировать", "Новый пустой после", "Длительность (×${currentFrame().hold})…", "Сдвинуть влево", "Сдвинуть вправо", "Сделать обложкой", "Удалить кадр")
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Кадр ${frameIdx + 1}")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> addFrame(copyCurrent = true)
                    1 -> addFrame(copyCurrent = false)
                    2 -> holdDialog()
                    3 -> moveFrame(-1)
                    4 -> moveFrame(1)
                    5 -> { p.coverId = currentFrame().id; p.preview(currentFrame()); p.saveMeta(); toastShort("Кадр стал обложкой") }
                    6 -> AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Удалить кадр?")
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
        val f = p.frames[fi]
        cv.playBitmap = playCache?.get(f.id) ?: p.preview(f)
        counter.text = "${fi + 1}/${p.frames.size}"
    }

    private fun startPlay() {
        cv.commitFloat()
        flushKeys()
        hidePanels()
        p.preview(currentFrame())
        updateThumb(frameIdx)
        val seq = ArrayList<Int>()
        p.frames.forEachIndexed { i, f -> repeat(f.hold) { seq.add(i) } }
        // заранее готовим превьюшки (с камерой, если она двигается)
        for (f in p.frames) p.preview(f)
        playCache = if (p.camera.isEmpty) null else HashMap<Int, Bitmap>().also { m ->
            for (f in p.frames) m[f.id] = p.compose(f, p.previewScale(), Bitmap.Config.RGB_565, withCamera = true)
        }
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
        playCache = null
        counter.text = "${frameIdx + 1}/${p.frames.size}"
    }

    // ===================================================================
    // Слои
    // ===================================================================

    private fun refreshLayerBtn() {
        val l = currentLayer()
        layerBtn.text = (if (l.isRig) "🦴 " else if (l.isObject) "◆ " else "") + l.name
        if (::keyPanel.isInitialized) { refreshKeyPanel(); updateKeyBadges() }
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
                val name = label((if (l.isRig) "🦴 " else if (l.isObject) "◆ " else "") + l.name, 15f).apply { maxLines = 1 }
                row.addView(name, lp(0, WRAP, 1f))
                val up = IconButton(this, Ico.UP, iconDp = 20f)
                up.setOnClickListener { if (idx < p.layers.size - 1) { swapLayers(idx, idx + 1); rebuild() } }
                val down = IconButton(this, Ico.DOWN, iconDp = 20f)
                down.setOnClickListener { if (idx > 0) { swapLayers(idx, idx - 1); rebuild() } }
                row.addView(up); row.addView(down)
                row.setOnClickListener { cv.commitFloat(); layerIdx = idx; cv.dropShadow(); refreshLayerBtn(); cv.invalidate(); rebuild() }
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
            val addObj = label("◆  Новый слой-объект (автоанимация)", 15f, C.ACCENT).apply { setPadding(dp(8f), dp(6f), dp(8f), dp(6f)) }
            addObj.setOnClickListener {
                if (p.layers.size >= 12) return@setOnClickListener
                p.layers.add(layerIdx + 1, Layer(p.newId(), "Объект ${p.layers.count { it.isObject } + 1}").apply { isObject = true })
                layerIdx += 1; p.saveMeta(); cv.dropShadow(); refreshLayerBtn(); cv.invalidate(); rebuild()
            }
            box.addView(addObj)
            val addRig = label("🦴  Новый персонаж (риг 2D в 3D)", 15f, C.ACCENT).apply { setPadding(dp(8f), dp(6f), dp(8f), dp(6f)) }
            addRig.setOnClickListener {
                if (p.layers.size >= 12) return@setOnClickListener
                newRigLayer(); cv.dropShadow(); refreshLayerBtn(); cv.invalidate(); rebuild()
            }
            box.addView(addRig)
            box.addView(label("Долгое нажатие на слой — переименовать / удалить", 12f, C.DIM).apply { setPadding(dp(8f), 0, dp(8f), 0) })
        }
        rebuild()
        dlg.setOnDismissListener {
            p.saveMeta(); refreshLayerBtn(); rebuildStrip()
            if (currentLayer().isRig && cv.tool != Tool.POSE) selectTool(Tool.POSE)
        }
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
            .setItems(if (l.isRig) arrayOf("Переименовать", "Удалить слой", "Мастерская персонажа") else arrayOf("Переименовать", "Удалить слой")) { _, w ->
                if (w == 2) { openWorkshop(l); return@setItems }
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
            .setItems(arrayOf("Видео MP4", "GIF-анимация", "Кадры PNG (в папку)", "Только этот кадр PNG")) { _, w ->
                when (w) {
                    0 -> videoQualityDialog()
                    1 -> createDoc("image/gif", safeName() + ".gif", REQ_GIF)
                    2 -> try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PNG_DIR)
                    } catch (e: Throwable) { toast("Не получилось открыть выбор папки") }
                    3 -> createDoc("image/png", safeName() + "_${frameIdx + 1}.png", REQ_PNG_ONE)
                }
            }.show()
    }

    private var videoSize = 1920 to 1080

    private fun videoQualityDialog() {
        val opts = listOf(1080, 720, minOf(p.canvasW, p.canvasH))
        val labels = opts.mapIndexed { i, t ->
            val (vw, vh) = VideoExport.pickSize(p, t)
            (when (i) { 0 -> "Full HD — лучшее"; 1 -> "HD"; else -> "Как холст" }) + "  ($vw×$vh)"
        }.toTypedArray()
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Качество видео")
            .setItems(labels) { _, i ->
                videoSize = VideoExport.pickSize(p, opts[i])
                createDoc("video/mp4", safeName() + ".mp4", REQ_MP4)
            }.show()
    }

    private fun exportMp4(uri: Uri) {
        val (vw, vh) = videoSize
        runExport("Создаю видео…") { progress ->
            val tmp = File(cacheDir, "export.mp4")
            tmp.delete()
            VideoExport(p, vw, vh).run(tmp, progress)
            progress("Сохраняю файл…")
            contentResolver.openOutputStream(uri)!!.use { os -> tmp.inputStream().use { it.copyTo(os) } }
            tmp.delete()
            "Видео сохранено ($vw×$vh, ${p.fps} кадров/с)"
        }
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
                val bmp = loadImage(uri) ?: run { toast("Не удалось открыть картинку"); imageCallback = null; return }
                imageCallback?.let { cb -> imageCallback = null; cb(bmp); return }
                if (cv.tool != Tool.LASSO) selectTool(Tool.LASSO)
                cv.startFloat(bmp)
            }
            REQ_GIF -> exportGif(uri)
            REQ_MP4 -> exportMp4(uri)
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
