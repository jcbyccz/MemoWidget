package com.cczsx.notepad.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.cczsx.notepad.R
import com.cczsx.notepad.data.Note
import com.cczsx.notepad.data.NoteStore
import com.cczsx.notepad.widget.MemoWidgetProvider
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * 编辑界面：编辑便签内容、背景颜色/透明度、文字美化（颜色/大小/加粗/斜体/下划线/投影/对齐/滚动）、置顶。
 */
class EditNoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "note_id"
        /** 从桌面小部件点击进入编辑页的标记 */
        const val EXTRA_FROM_WIDGET = "from_widget"
        // 颜色行：[无背景/无边框/无颜色?] + 预设 + 最近使用 + [＋自定义]；bgColor/borderColor/fontColor = 0 表示“无”
        private val BG_COLORS = intArrayOf(          // 背景默认：无背景
            0xFFE3F3E7.toInt(),   // 薄荷
            0xFFDFEDF9.toInt(),   // 天青
            0xFFECEFF1.toInt()    // 中性灰
        )
        private val BORDER_COLORS = intArrayOf(      // 边框默认：森绿
            0xFF4C8C6A.toInt(),   // 森绿 ★默认
            0xFF4A90B8.toInt(),   // 湖蓝
            0xFF9AA0A6.toInt()    // 暖灰（透明底上最百搭的弱边框）
        )
        private val FONT_COLORS = intArrayOf(        // 字体默认：墨黑
            0xFF1F1F1F.toInt(),   // 墨黑 ★默认
            0xFF5A6673.toInt(),   // 冷灰
            0xFF808080.toInt(),   // 中性灰
            0xFF2E6DA3.toInt()    // 天青蓝
        )
    }

    private lateinit var note: Note
    private lateinit var edTitle: EditText
    private lateinit var edContent: EditText
    private lateinit var preview: TextView
    private lateinit var tvTime: TextView
    private lateinit var cvBg: LinearLayout
    private lateinit var cvBorder: LinearLayout
    private lateinit var cvFont: LinearLayout
    private lateinit var seekAlpha: SeekBar
    private lateinit var tvAlpha: TextView
    private lateinit var tvSize: TextView
    private lateinit var toggleBold: MaterialButtonToggleGroup
    private lateinit var groupH: MaterialButtonToggleGroup
    private lateinit var groupV: MaterialButtonToggleGroup
    private lateinit var chkScroll: CheckBox
    private lateinit var chkPin: CheckBox
    /** 是否从桌面小部件点击进入：保存/确认后直接退出，不回到列表页 */
    private var fromWidget = false
    /** 本次是否为未落库的新建草稿：全空不保存，避免误留“空便签” */
    private var isNewDraft = false

    // ---------- 撤销/重做：仅针对标题与正文的文本编辑，样式开关不参与 ----------
    /** 文本快照 */
    private data class TextSnap(val title: String, val content: String)

    /** 撤销栈：栈顶始终等于当前文本状态 */
    private val undoStack = ArrayDeque<TextSnap>()
    private val redoStack = ArrayDeque<TextSnap>()
    /** 程序化回放文本时挂起记录，避免撤销/重做自身入栈 */
    private var applyingHistory = false
    /** 上次入栈时间：400ms 内连续输入合并为同一步，暂停后另起一步 */
    private var lastPushAt = 0L
    private lateinit var btnUndo: android.widget.ImageView
    private lateinit var btnRedo: android.widget.ImageView

    private val textWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            recordTextChange()
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        NoteStore.init(this)
        ThemePrefs.apply(this)

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        fromWidget = intent.getBooleanExtra(EXTRA_FROM_WIDGET, false)
        isNewDraft = id < 0
        // 草稿模式：新建时不立即落库，首次保存才入库，返回退出不留空便签
        note = if (id < 0) NoteStore.draft() else (NoteStore.getById(id) ?: NoteStore.draft())

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = if (id < 0) "新建便签" else "编辑便签"
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        bindViews()
        loadState()
        buildSwatch()
        refreshPreview()
        setupHistory()

        // 右上角确认按钮：保存并退出
        findViewById<android.widget.ImageView>(R.id.btn_confirm).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_delete).setOnClickListener { onDelete() }
        findViewById<Button>(R.id.btn_set_default).setOnClickListener {
            NoteStore.saveDefaultStyle(note.copy(id = -1L))
            Toast.makeText(this, "已设为默认样式", Toast.LENGTH_SHORT).show()
        }
        // 设置小部件设计：点击展开/收起底部设计面板（面板为固定底部的可滚动区，
        // 展开时与正文平分屏幕并内部滚动，「将当前样式设置为默认」在面板最底部）
        findViewById<Button>(R.id.btn_widget_design).setOnClickListener {
            val panel = findViewById<android.widget.ScrollView>(R.id.style_panel)
            if (panel.visibility == View.VISIBLE) {
                panel.visibility = View.GONE
            } else {
                // 每次展开都从顶部显示，避免残留上次的滚动位置导致面板看起来显示不全
                panel.scrollTo(0, 0)
                panel.visibility = View.VISIBLE
            }
        }
        refreshTime()
    }

    private fun bindViews() {
        edTitle = findViewById(R.id.ed_title)
        edContent = findViewById(R.id.ed_content)
        preview = findViewById(R.id.preview_text)
        tvTime = findViewById(R.id.tv_update_time)
        cvBg = findViewById(R.id.bg_swatches)
        cvBorder = findViewById(R.id.border_swatches)
        cvFont = findViewById(R.id.font_swatches)
        seekAlpha = findViewById(R.id.seek_alpha)
        tvAlpha = findViewById(R.id.tv_alpha)
        tvSize = findViewById(R.id.tv_size)
        toggleBold = findViewById(R.id.toggle_bold)
        groupH = findViewById(R.id.group_align_h)
        groupV = findViewById(R.id.group_align_v)
        chkScroll = findViewById(R.id.chk_scroll)
        chkPin = findViewById(R.id.chk_pin)
    }

    private fun loadState() {
        edTitle.setText(note.title)
        edContent.setText(note.content)
        tvSize.text = "${note.fontSize}sp"

        seekAlpha.progress = (note.alpha * 100).toInt()
        tvAlpha.text = if (seekAlpha.progress == 0) "全透明" else "${seekAlpha.progress}%"
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvAlpha.text = if (p == 0) "全透明" else "$p%"
                note.alpha = p / 100f
                refreshPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_size_minus).setOnClickListener {
            note.fontSize = (note.fontSize - 1).coerceAtLeast(8)
            tvSize.text = "${note.fontSize}sp"
            refreshPreview()
        }
        findViewById<Button>(R.id.btn_size_plus).setOnClickListener {
            note.fontSize = (note.fontSize + 1).coerceAtMost(48)
            tvSize.text = "${note.fontSize}sp"
            refreshPreview()
        }

        // B / I / U / A 多选：加粗、斜体、下划线、投影各自开关，互不清除
        // 字形以图标渲染（ic_eff_*），字形本身自带隐喻：B 粗笔、I 斜笔、U 带下划线、A 带错位投影
        toggleBold.addOnButtonCheckedListener { _, id, _ ->
            val btn = toggleBold.findViewById<com.google.android.material.button.MaterialButton>(id)
            when (id) {
                R.id.btn_bold -> note.bold = btn?.isChecked == true
                R.id.btn_italic -> note.italic = btn?.isChecked == true
                R.id.btn_underline -> note.underline = btn?.isChecked == true
                R.id.btn_shadow -> note.shadow = btn?.isChecked == true
            }
            refreshPreview()
        }

        groupH.addOnButtonCheckedListener { _, id, checked ->
            if (checked) note.alignH = when (id) {
                R.id.btn_align_hc -> 1
                R.id.btn_align_hr -> 2
                else -> 0
            }
            refreshPreview()
        }
        groupV.addOnButtonCheckedListener { _, id, checked ->
            if (checked) note.alignV = when (id) {
                R.id.btn_align_vt -> 0
                R.id.btn_align_vb -> 2
                else -> 1
            }
            refreshPreview()
        }
        chkScroll.setOnCheckedChangeListener { _, c -> note.scrollEnabled = c }
        chkPin.setOnCheckedChangeListener { _, c -> note.pinned = c }

        // 初始化选中态（B/I 可同时选中）
        if (note.bold) toggleBold.check(R.id.btn_bold) else toggleBold.uncheck(R.id.btn_bold)
        if (note.italic) toggleBold.check(R.id.btn_italic) else toggleBold.uncheck(R.id.btn_italic)
        if (note.underline) toggleBold.check(R.id.btn_underline) else toggleBold.uncheck(R.id.btn_underline)
        if (note.shadow) toggleBold.check(R.id.btn_shadow) else toggleBold.uncheck(R.id.btn_shadow)
        groupH.check(when (note.alignH) { 1 -> R.id.btn_align_hc; 2 -> R.id.btn_align_hr; else -> R.id.btn_align_hl })
        groupV.check(when (note.alignV) { 0 -> R.id.btn_align_vt; 2 -> R.id.btn_align_vb; else -> R.id.btn_align_vc })
        chkScroll.isChecked = note.scrollEnabled
        chkPin.isChecked = note.pinned

        refreshPreview()
    }

    // ---------- 撤销/重做 ----------

    /** 进入页面后初始化：初始快照入栈 + 标题/正文挂监听（样式开关不记录） */
    private fun setupHistory() {
        undoStack.clear()
        redoStack.clear()
        undoStack.addLast(TextSnap(note.title, note.content))
        lastPushAt = 0L   // 首次输入即成为可撤销的新步
        edTitle.addTextChangedListener(textWatcher)
        edContent.addTextChangedListener(textWatcher)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        btnUndo.setOnClickListener { undo() }
        btnRedo.setOnClickListener { redo() }
        updateHistoryButtons()
    }

    /** 文本变化入栈：400ms 内连续输入并入同一步，停顿后另起一步；有新编辑则清空重做栈 */
    private fun recordTextChange() {
        if (applyingHistory) return
        val snap = TextSnap(edTitle.text.toString(), edContent.text.toString())
        val now = android.os.SystemClock.elapsedRealtime()
        if (undoStack.isEmpty() || now - lastPushAt > 400L) {
            undoStack.addLast(snap)
        } else {
            undoStack.removeLast()
            undoStack.addLast(snap)
        }
        redoStack.clear()
        lastPushAt = now
        updateHistoryButtons()
    }

    private fun undo() {
        if (undoStack.size < 2) return
        redoStack.addLast(undoStack.removeLast())
        applySnap(undoStack.last())
        lastPushAt = 0L
    }

    private fun redo() {
        val snap = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snap)
        applySnap(snap)
        lastPushAt = 0L
    }

    /** 程序化回放一段文本状态（挂起监听记录），光标落到本次改动的位置（差异锚点推算） */
    private fun applySnap(snap: TextSnap) {
        applyingHistory = true
        val oldTitle = edTitle.text.toString()
        val oldContent = edContent.text.toString()
        if (oldTitle != snap.title) {
            edTitle.setText(snap.title)
            edTitle.setSelection(editAnchor(oldTitle, snap.title))
        }
        if (oldContent != snap.content) {
            edContent.setText(snap.content)
            edContent.setSelection(editAnchor(oldContent, snap.content))
        }
        applyingHistory = false
        updateHistoryButtons()
    }

    /**
     * 计算回放后的光标锚点：取新旧文本的公共前缀与公共后缀，围出的差异区即本次改动的位置，
     * 光标落在差异区末端（重做/插入后跟住新文本，撤销/删除后停在删除点）。
     */
    private fun editAnchor(old: String, new: String): Int {
        val maxP = minOf(old.length, new.length)
        var p = 0
        while (p < maxP && old[p] == new[p]) p++
        var s = 0
        while (s < maxP - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        return maxOf(p, new.length - s).coerceIn(0, new.length)
    }

    private fun updateHistoryButtons() {
        val canUndo = undoStack.size >= 2
        val canRedo = redoStack.isNotEmpty()
        btnUndo.isEnabled = canUndo
        btnUndo.alpha = if (canUndo) 1f else 0.35f
        btnRedo.isEnabled = canRedo
        btnRedo.alpha = if (canRedo) 1f else 0.35f
    }

    private fun buildSwatch() {
        // 每行：[无背景/无边框?] + 预设 + 最近使用 + [＋自定义]；各自独立记录取色器历史
        buildColorRow(
            container = cvBg,
            presets = BG_COLORS,
            current = note.bgColor,
            recentKind = NoteStore.RECENT_BG,
            applyColor = { note.bgColor = it },
            allowNone = true
        )
        buildColorRow(
            container = cvBorder,
            presets = BORDER_COLORS,
            current = note.borderColor,
            recentKind = NoteStore.RECENT_BORDER,
            applyColor = { note.borderColor = it },
            allowNone = true
        )
        buildColorRow(
            container = cvFont,
            presets = FONT_COLORS,
            current = note.fontColor,
            recentKind = NoteStore.RECENT_FONT,
            applyColor = { note.fontColor = it },
            allowNone = true
        )
    }

    /** 行内颜色变化后的统一刷新 */
    private fun afterColorChanged() {
        refreshPreview()
        refreshSwatch()
    }

    /**
     * 统一构建一行颜色选择块：[无背景/无边框?] + 预设×N + 最近使用 + [＋自定义]。
     * applyColor 是该行唯一的写入入口，从结构上避免行与行之间串色。
     */
    private fun buildColorRow(
        container: LinearLayout,
        presets: IntArray,
        current: Int,
        recentKind: String,
        applyColor: (Int) -> Unit,
        allowNone: Boolean = false
    ) {
        // 1. “无背景/无边框/无颜色”（虚线灰环，置首格；颜色值 0 表示“无”）
        if (allowNone) {
            container.addView(createTile(Color.TRANSPARENT, selected = current == 0, dashed = true) {
                applyColor(0)
                afterColorChanged()
            })
        }
        // 2. 预设色
        presets.forEach { color ->
            container.addView(createTile(color, selected = current == color, dashed = false) {
                applyColor(color)
                afterColorChanged()
            })
        }
        // 3. 最近使用（取色器选过的颜色，最新在前，最多 3 个）
        NoteStore.recentColors(recentKind).forEach { color ->
            container.addView(createTile(color, selected = current == color, dashed = false) {
                applyColor(color)
                afterColorChanged()
            })
        }
        // 4. ＋自定义取色（白底实环加号，比虚线圈更醒目；选中的颜色自动记入“最近使用”）
        val initial = if (current != 0) current
        else NoteStore.recentColors(recentKind).firstOrNull() ?: Color.GRAY
        container.addView(createPickerTile(initial) { picked ->
            NoteStore.pushRecentColor(recentKind, picked)
            applyColor(picked)
            afterColorChanged()
        })
    }

    /** “＋自定义取色”色块：白底 + 深灰实线环 + 居中粗加号，一眼可辨，不会被误认成颜色 */
    private fun createPickerTile(initialColor: Int, onPick: (Int) -> Unit): android.view.View {
        val size = (24 * resources.displayMetrics.density).toInt()
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(Color.WHITE)
        d.setStroke(
            (2 * resources.displayMetrics.density).toInt(),
            android.content.res.ColorStateList.valueOf(0xFF424242.toInt())
        )
        val inner = android.view.View(this)
        inner.background = d
        val lp = LinearLayout.LayoutParams(size, size)
        lp.setMargins((4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt(), 0)
        val wrap = FrameLayout(this)
        wrap.addView(inner, FrameLayout.LayoutParams(size, size))
        wrap.layoutParams = lp
        val plus = TextView(this).apply {
            text = "＋"
            textSize = 15f
            setTextColor(0xFF424242.toInt())
            gravity = android.view.Gravity.CENTER
        }
        wrap.addView(plus, FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER))
        wrap.setOnClickListener {
            ColorPickerDialog.show(this, initialColor, onPick)
        }
        return wrap
    }

    /** 单个色块：预设色实线细环；自定义/无边框虚线环；选中时右上角叠加 ✓ 角标（轻盈现代） */
    private fun createTile(
        color: Int,
        selected: Boolean,
        dashed: Boolean,
        onClick: () -> Unit
    ): android.view.View {
        // 色圈统一 24dp：保证整页一屏放下仍有余量
        val size = (24 * resources.displayMetrics.density).toInt()
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        val dash = 4 * resources.displayMetrics.density
        when {
            dashed -> d.setStroke(
                (2 * resources.displayMetrics.density).toInt(),
                android.content.res.ColorStateList.valueOf(if (color == Color.TRANSPARENT) Color.GRAY else Color.DKGRAY),
                dash, dash
            )
            else -> d.setStroke(1.dp(), android.content.res.ColorStateList.valueOf(Color.DKGRAY))
        }
        val inner = android.view.View(this)
        inner.background = d
        val lp = LinearLayout.LayoutParams(size, size)
        lp.setMargins((4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt(), 0)
        val wrap = FrameLayout(this)
        wrap.addView(inner, FrameLayout.LayoutParams(size, size))
        wrap.layoutParams = lp
        wrap.setOnClickListener { onClick() }
        if (selected) {
            // 选中角标：深色小圆 + 白色 ✓
            val badgeSize = (13 * resources.displayMetrics.density).toInt()
            val badge = TextView(this).apply {
                text = "✓"
                textSize = 9f
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xEE212121.toInt())
                }
            }
            wrap.addView(badge, FrameLayout.LayoutParams(badgeSize, badgeSize, android.view.Gravity.TOP or android.view.Gravity.END).apply {
                setMargins(0, 0, 0, 0)
            })
        }
        return wrap
    }

    private fun refreshSwatch() {
        cvBg.removeAllViews()
        cvBorder.removeAllViews()
        cvFont.removeAllViews()
        buildSwatch()
    }

    private fun refreshPreview() {
        // 预览区只做样式演示：固定示例文字，不随便签内容变化
        preview.text = "小部件预览示例"
        // 预览区同步应用水平/垂直对齐（此前缺失，导致编辑页看不出对齐效果）
        val h = when (note.alignH) {
            1 -> android.view.Gravity.CENTER_HORIZONTAL
            2 -> android.view.Gravity.RIGHT
            else -> android.view.Gravity.LEFT
        }
        val v = when (note.alignV) {
            0 -> android.view.Gravity.TOP
            2 -> android.view.Gravity.BOTTOM
            else -> android.view.Gravity.CENTER_VERTICAL
        }
        preview.gravity = h or v
        TextStyler.applyText(preview, note)
        // 棋盘格底纹 + 半透明色，透明效果一目了然（避免误以为“背景被边框色改变”）
        TextStyler.applyBackground(preview, note, checkerPreviewTile)
    }

    /** 预览区透明底纹（情性创建） */
    private val checkerPreviewTile by lazy {
        TextStyler.makeCheckerTile(this, 12)
    }

    /** 修改时间展示（与待办编辑页一致；无修改保存不刷新时间） */
    private fun refreshTime() {
        val df = java.text.SimpleDateFormat("yyyy/MM/dd(E) HH:mm", java.util.Locale.CHINA)
        tvTime.text = df.format(java.util.Date(note.updatedAt))
    }

    private fun save() {
        note.title = edTitle.text.toString()
        note.content = edContent.text.toString()
        if (isNewDraft && note.title.isBlank() && note.content.isBlank()) {
            // 全空的新便签不落库，直接退出
            exitAfterSave()
            return
        }
        NoteStore.update(note)
        MemoWidgetProvider.refreshAll(this)
        exitAfterSave()
    }

    private fun onDelete() {
        NoteStore.softDelete(note.id)
        MemoWidgetProvider.refreshAll(this)
        exitAfterSave()
    }

    /**
     * 从桌面小部件进入时，保存后直接退出应用回到桌面（不进入列表页）；
     * 从应用内进入时，正常返回上一页（列表）。
     */
    private fun exitAfterSave() {
        if (fromWidget) finishAffinity() else finish()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}