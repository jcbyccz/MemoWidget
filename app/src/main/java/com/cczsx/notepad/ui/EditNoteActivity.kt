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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.cczsx.notepad.R
import com.cczsx.notepad.data.Note
import com.cczsx.notepad.data.NoteStore
import com.cczsx.notepad.data.PhotoStore
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
        /** 单条便签最多可添加的照片张数 */
        const val MAX_PHOTOS = 10
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

    // ---------- 照片：底部时间行最左侧“添加照片”按钮 + 编辑区照片竖排；小部件背景照片 ----------
    private lateinit var btnPhoto: android.widget.ImageView
    private lateinit var photoStrip: LinearLayout

    /** 选一张便签照片（系统相册选择器，无需存储权限），选中后进入裁剪页 */
    private val pickNotePhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) cropPhoto.launch(
            android.content.Intent(this, CropEditActivity::class.java)
                .putExtra(CropEditActivity.EXTRA_URI, uri.toString())
                .putExtra(CropEditActivity.EXTRA_PREFIX, "n${note.id}_")
        )
    }

    /** 裁剪页返回：把裁剪后的照片插入便签 */
    private val cropPhoto = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val name = res.data?.getStringExtra(CropEditActivity.EXTRA_NAME)
            if (!name.isNullOrBlank()) {
                note.photos.add(name)
                rebuildPhotoStrip()
            }
        }
    }

    /** 大图浏览返回：同步被删除的照片（删文件 + 更新便签 + 刷新缩略图） */
    private val viewPhotos = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val remain = res.data?.getStringArrayListExtra(PhotoViewerActivity.EXTRA_RESULT)
            if (remain != null) {
                note.photos.filter { it !in remain }.forEach { PhotoStore.delete(this, it) }
                note.photos.clear()
                note.photos.addAll(remain)
                rebuildPhotoStrip()
            }
        }
    }

    /** 选一张小部件背景照片 */
    private val pickBgPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) setBgPhoto(uri) }

    private val textWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            recordTextChange()
        }
        override fun afterTextChanged(s: android.text.Editable?) {}
    }

    /** 行首空白统一 0.5 字宽/字符（2026-11-19）：与小部件缩进同口径，
     *  编辑页所见即部件所得；只改显示度量不动内容 */
    private val leadingSpaceWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val ed = if (s === edTitle.text) edTitle else edContent
            TextStyler.applyLeadingSpaceSpans(ed)
        }
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
        // 设置小部件：点击展开/收起底部设计面板（展开时设计内容全部展示：
        // 面板高度自适应、不再与正文分屏，正文区收缩让位）
        findViewById<Button>(R.id.btn_widget_design).setOnClickListener {
            val panel = findViewById<View>(R.id.style_panel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        refreshTime()
        setupPhotos()
    }

    /** 照片区初始化：底部按钮、编辑区照片块、小部件背景照片行 */
    private fun setupPhotos() {
        btnPhoto = findViewById(R.id.btn_photo)
        photoStrip = findViewById(R.id.photo_strip)
        btnPhoto.setOnClickListener {
            if (note.photos.size >= MAX_PHOTOS) {
                Toast.makeText(this, "最多添加 $MAX_PHOTOS 张照片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickNotePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        rebuildPhotoStrip()

        findViewById<Button>(R.id.btn_bg_photo).setOnClickListener {
            pickBgPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.btn_bg_photo_clear).setOnClickListener {
            if (note.bgPhoto.isNotBlank()) {
                PhotoStore.delete(this, note.bgPhoto)
                note.bgPhoto = ""
                refreshPreview()
                refreshBgPhotoState()
            }
        }
        refreshBgPhotoState()
    }

    /** 背景照片“清除”按钮的显隐 */
    private fun refreshBgPhotoState() {
        findViewById<Button>(R.id.btn_bg_photo_clear).visibility =
            if (note.bgPhoto.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun setBgPhoto(uri: android.net.Uri) {
        val name = PhotoStore.importFrom(this, uri, "bg${note.id}_")
        if (name == null) {
            Toast.makeText(this, "照片读取失败：${PhotoStore.lastError}".trimEnd('：'), Toast.LENGTH_LONG).show()
            return
        }
        if (note.bgPhoto.isNotBlank()) PhotoStore.delete(this, note.bgPhoto)
        note.bgPhoto = name
        refreshPreview()
        refreshBgPhotoState()
    }

    /** 重建编辑区照片块：文字下方竖向排列、靠左，每张独占一行，
     *  显示尺寸贴合照片实际比例（宽度=内容区宽，高度按比例；高超 500dp 时整体
     *  等比缩小，竖图不再向两侧铺白底），无白色圆角框与描边，
     *  点击进大图浏览，照片右上角 ✕ 快捷删除；无照片时整块隐藏 */
    private fun rebuildPhotoStrip() {
        photoStrip.removeAllViews()
        if (note.photos.isEmpty()) {
            photoStrip.visibility = View.GONE
            return
        }
        photoStrip.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        // 内容区宽度：屏幕宽减去两侧 16dp 内边距；高度上限 500dp
        val boxW = (resources.displayMetrics.widthPixels - 32 * density).toInt()
        val maxH = (500 * density).toInt()
        val badgePx = (22 * density).toInt()
        note.photos.forEachIndexed { index, name ->
            val b = PhotoStore.loadBitmap(this, name, boxW, maxH, centerCrop = false)
            // 显示尺寸贴合照片比例：宽 = 内容区宽、高按比例；高超上限时整体等比缩小
            var drawW = boxW
            var drawH: Int
            if (b != null && b.width > 0 && b.height > 0) {
                drawH = (boxW.toLong() * b.height / b.width).toInt()
                if (drawH > maxH) {
                    drawH = maxH
                    drawW = (maxH.toLong() * b.width / b.height).toInt()
                }
            } else {
                drawH = maxH / 3   // 加载失败占位：全宽短块
            }
            val wrap = FrameLayout(this)
            wrap.layoutParams = LinearLayout.LayoutParams(drawW, drawH).apply {
                bottomMargin = (10 * density).toInt()
            }
            val iv = android.widget.ImageView(this)
            iv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            iv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            if (b != null) iv.setImageBitmap(b)
            // 点击照片 → 大图浏览（定位到这张，可滑动、可删除）
            iv.setOnClickListener {
                viewPhotos.launch(
                    android.content.Intent(this, PhotoViewerActivity::class.java)
                        .putExtra(PhotoViewerActivity.EXTRA_PHOTOS, java.util.ArrayList(note.photos))
                        .putExtra(PhotoViewerActivity.EXTRA_INDEX, index)
                )
            }
            val x = android.widget.ImageView(this)
            x.setImageResource(R.drawable.ic_close)
            x.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            x.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE212121.toInt())
            }
            x.setPadding(3.dp(), 3.dp(), 3.dp(), 3.dp())
            x.layoutParams = FrameLayout.LayoutParams(
                badgePx, badgePx, android.view.Gravity.TOP or android.view.Gravity.END
            )
            x.setOnClickListener {
                PhotoStore.delete(this, name)
                note.photos.remove(name)
                rebuildPhotoStrip()
            }
            wrap.addView(iv)
            wrap.addView(x)
            photoStrip.addView(wrap)
        }
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
        edTitle.addTextChangedListener(leadingSpaceWatcher)
        edContent.addTextChangedListener(leadingSpaceWatcher)
        // loadState 的 setText 发生在 watcher 挂载前：初始文本需手动应用一次
        // 行首空白宽度（否则重进编辑页空格按系统字体自然渲染，宽度比 0.5em 规则小一半）
        TextStyler.applyLeadingSpaceSpans(edTitle)
        TextStyler.applyLeadingSpaceSpans(edContent)
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