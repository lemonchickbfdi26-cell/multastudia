package ru.leon.multastudia

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Экран со списком мультфильмов. */
class MainActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BAR)
            setPadding(dp(20f), dp(18f), dp(20f), dp(16f))
        }
        header.addView(label("Мультастудия", 26f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
        header.addView(label("студия для создания мультфильмов", 13f, C.DIM))
        root.addView(header, lp(MATCH, WRAP))

        val newBtn = label("+  Новый мультфильм", 17f).apply {
            gravity = Gravity.CENTER
            background = rounded(C.ACCENT, dp(26f).toFloat())
            setOnClickListener { newProjectDialog() }
        }
        root.addView(newBtn, lp(MATCH, dp(54f)).apply { setMargins(dp(16f), dp(16f), dp(16f), dp(8f)) })
        val openBtn = label("Открыть копию проекта (.zip)", 14f, C.ACCENT).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6f), 0, dp(6f))
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                try { startActivityForResult(i, REQ_IMPORT) } catch (e: Throwable) {}
            }
        }
        root.addView(openBtn, lp(MATCH, WRAP))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(8f), dp(16f), dp(16f)) }
        val scroll = ScrollView(this); scroll.addView(list)
        root.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val dirs = Project.root(filesDir).listFiles()?.filter { File(it, "project.json").exists() }
            ?.sortedByDescending { File(it, "project.json").lastModified() } ?: emptyList()
        if (dirs.isEmpty()) {
            list.addView(label("Пока нет мультфильмов.\nНажми «Новый мультфильм», чтобы начать!", 15f, C.DIM).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(40f), 0, 0)
            }, lp(MATCH, WRAP))
            return
        }
        // по два в ряд
        var row: LinearLayout? = null
        dirs.forEachIndexed { i, d ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                list.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(12f) })
            }
            row!!.addView(projectCard(d), lp(0, WRAP, 1f).apply { if (i % 2 == 0) rightMargin = dp(6f) else leftMargin = dp(6f) })
        }
        if (dirs.size % 2 == 1) row!!.addView(View(this), lp(0, 1, 1f).apply { leftMargin = dp(6f) })
    }

    private fun projectCard(dir: File): View {
        val info = Project.peekInfo(dir)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.CARD, dp(14f).toFloat())
            setPadding(dp(8f), dp(8f), dp(8f), dp(10f))
        }
        val thumb = FrameLayout(this).apply { background = rounded(Color.WHITE, dp(8f).toFloat()) }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        info?.second?.let { f -> BitmapFactory.decodeFile(f.absolutePath)?.let { img.setImageBitmap(it) } }
        thumb.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(thumb, lp(MATCH, dp(96f)))
        val name = label(info?.first ?: "?", 14f).apply { maxLines = 1; setPadding(dp(2f), dp(6f), 0, 0) }
        card.addView(name)
        card.setOnClickListener { open(dir) }
        card.setOnLongClickListener { cardMenu(dir, info?.first ?: ""); true }
        return card
    }

    private fun open(dir: File) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("dir", dir.absolutePath))
    }

    private fun cardMenu(dir: File, name: String) {
        AlertDialog.Builder(this, R.style.DialogTheme).setTitle(name)
            .setItems(arrayOf("Открыть", "Сохранить копию (.zip)", "Удалить")) { _, w ->
                if (w == 0) open(dir)
                else if (w == 1) {
                    pendingExport = dir
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").ifBlank { "multfilm" } + ".zip")
                    }
                    try { startActivityForResult(i, REQ_EXPORT) } catch (e: Throwable) {}
                }
                else AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Удалить «$name» навсегда?")
                    .setPositiveButton("Удалить") { _, _ -> dir.deleteRecursively(); refresh() }
                    .setNegativeButton("Отмена", null).show()
            }.show()
    }

    private var pendingExport: File? = null

    companion object {
        const val REQ_EXPORT = 11
        const val REQ_IMPORT = 12
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    val dir = pendingExport ?: return
                    contentResolver.openOutputStream(uri)!!.use { os ->
                        java.util.zip.ZipOutputStream(os).use { zip ->
                            for (f in dir.listFiles() ?: emptyArray()) {
                                if (!f.isFile || f.name.endsWith(".tmp")) continue
                                zip.putNextEntry(java.util.zip.ZipEntry(f.name))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                    android.widget.Toast.makeText(this, "Копия сохранена", android.widget.Toast.LENGTH_SHORT).show()
                }
                REQ_IMPORT -> {
                    val dir = File(Project.root(filesDir), "p" + System.currentTimeMillis())
                    dir.mkdirs()
                    contentResolver.openInputStream(uri)!!.use { ins ->
                        java.util.zip.ZipInputStream(ins).use { zip ->
                            while (true) {
                                val e = zip.nextEntry ?: break
                                if (e.isDirectory) continue
                                val name = e.name.substringAfterLast('/').substringAfterLast('\\')
                                if (name.isBlank() || name.startsWith(".")) continue
                                File(dir, name).outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                    if (!File(dir, "project.json").exists()) {
                        dir.deleteRecursively()
                        android.widget.Toast.makeText(this, "Это не копия проекта Мультастудии", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(this, "Проект открыт", android.widget.Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
        } catch (e: Throwable) {
            android.widget.Toast.makeText(this, "Ошибка: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun newProjectDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(8f), dp(20f), 0) }
        val nameEt = EditText(this).apply { hint = "Название"; setText("Мультфильм ${(Project.root(filesDir).list()?.size ?: 0) + 1}") }
        box.addView(nameEt)
        box.addView(label("Размер холста", 13f, C.DIM).apply { setPadding(0, dp(10f), 0, 0) })
        val sizes = listOf(
            Triple("16:9 обычный (960×540)", 960, 540),
            Triple("16:9 HD (1280×720)", 1280, 720),
            Triple("Квадрат (720×720)", 720, 720),
            Triple("Вертикальный 9:16 (540×960)", 540, 960),
            Triple("4:3 (800×600)", 800, 600)
        )
        val rg = RadioGroup(this)
        sizes.forEachIndexed { i, s ->
            rg.addView(RadioButton(this).apply { id = 100 + i; text = s.first; textSize = 14f })
        }
        rg.check(100)
        box.addView(rg)
        var fps = 12
        box.addView(sliderRow("Кадров/с", 1, 30, fps) { fps = it })

        AlertDialog.Builder(this, R.style.DialogTheme).setTitle("Новый мультфильм").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Создать") { _, _ ->
                val s = sizes[(rg.checkedRadioButtonId - 100).coerceIn(0, sizes.size - 1)]
                val p = Project.create(filesDir, nameEt.text.toString().ifBlank { "Мультфильм" }, s.second, s.third, fps)
                open(p.dir)
            }
            .setNegativeButton("Отмена", null).show()
    }
}
