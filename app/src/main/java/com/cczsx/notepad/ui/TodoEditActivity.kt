package com.cczsx.notepad.ui

import android.content.ClipData
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.cczsx.notepad.R
import com.cczsx.notepad.data.NoteStore
import com.cczsx.notepad.data.PhotoStore
import com.cczsx.notepad.data.Todo
import com.cczsx.notepad.data.TodoItem
import com.cczsx.notepad.data.TodoStore
import com.cczsx.notepad.widget.MemoWidgetProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 待办编辑页：
 * - 工具栏 [撤销][重做][✓]（与便签页一致）
 * - 题目 + 条目行（拖拽把手 / 勾选框 / 内容输入 / 聚焦行右侧删除）
 * - 勾选 → 移入“已完该做的事(N)”分组；已完成区标题可折叠、右侧齿轮弹窗
 * - 底部修改时间 + “设置小部件设计”按钮展开便签同款样式面板
 */
class TodoEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "todo_id"
        const val EXTRA_FROM_WIDGET = "from_widget"

        /** 待办照片上限（与便签一致） */
        const val MAX_PHOTOS = 10

        private val BG_COLORS = intArrayOf(
            0xFFE3F3E7.toInt(),   // 薄荷
            0xFFDFEDF9.toInt(),   // 天青
            0xFFECEFF1.toInt()    // 中性灰
        )
        private val BORDER_COLORS = intArrayOf(
            0xFF4C8C6A.toInt(),   // 森绿 ★默认
            0xFF4A90B8.toInt(),   // 湖蓝
            0xFF9AA0A6.toInt()    // 暖灰
        )
        private val FONT_COLORS = intArrayOf(
            0xFF1F1F1F.toInt(),   // 墨黑 ★默认
            0xFF5A6673.toInt(),   // 冷灰
            0xFF808080.toInt(),   // 中性灰
            0xFF2E6DA3.toInt()    // 天青蓝
        )
    }

    // ---------- 状态 ----------
    private lateinit var todo: Todo
    private var isNewDraft = false
    private var fromWidget = false
    /** 已完成区折叠（运行时状态，不持久化） */
    private var doneCollapsed = false
    /** 行重建挂起：避免 rebuild 时的程序化 setText 误入撤销栈 */
    private var rebuilding = false
    /** 拖拽状态：被拖条目（身份引用）；dragTargetSlot = 组内插入槽（-1 = 未确定） */
    private var draggingItem: TodoItem? = null
    private var dragTargetSlot = -1
    /** 拖拽腾位的位移动画目标（view → dy），跳过无变化的重复动画 */
    private val rowShifts = HashMap<View, Float>()
    /** 行视图缓存（按条目身份）：rebuildRows 复用行视图，不再整页销毁重建 */
    private val rowCache = java.util.IdentityHashMap<TodoItem, View>()
    /** 拖拽腾位动画减速曲线 */
    private val dragDecelerate = android.view.animation.DecelerateInterpolator(1.6f)

    // ---------- 视图 ----------
    private lateinit var edTitle: EditText
    private lateinit var rowsUndo: LinearLayout
    private lateinit var rowsDone: LinearLayout
    private lateinit var divider: View
    private lateinit var doneHeader: LinearLayout
    private lateinit var btnDoneCollapse: ImageView
    private lateinit var tvDoneTitle: TextView
    private lateinit var tvTime: TextView
    private lateinit var preview: TextView
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

    // ---------- 撤销/重做：快照含题目与全部条目（文本+勾选+顺序） ----------
    private data class TodoSnap(val title: String, val items: List<Pair<String, Boolean>>)

    private val undoStack = ArrayDeque<TodoSnap>()
    private val redoStack = ArrayDeque<TodoSnap>()
    private var applyingHistory = false
    private var lastPushAt = 0L
    private lateinit var btnUndo: ImageView
    private lateinit var btnRedo: ImageView

    // ---------- 待办照片（与便签编辑页同款：插入图片 / 裁剪 / 大图浏览 / 快捷删除） ----------
    private lateinit var btnPhoto: ImageView
    private lateinit var photoStrip: LinearLayout

    /** 选一张待办照片（系统相册选择器，无需存储权限），选中后进入裁剪页 */
    private val pickTodoPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) cropTodoPhoto.launch(
            android.content.Intent(this, CropEditActivity::class.java)
                .putExtra(CropEditActivity.EXTRA_URI, uri.toString())
                .putExtra(CropEditActivity.EXTRA_PREFIX, "t${todo.id}_")
        )
    }

    /** 裁剪页返回：把裁剪后的照片加入待办（保存时随待办一起落库） */
    private val cropTodoPhoto = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val name = res.data?.getStringExtra(CropEditActivity.EXTRA_NAME)
            if (!name.isNullOrBlank()) {
                todo.photos.add(name)
                rebuildPhotoStrip()
            }
        }
    }

    /** 大图浏览返回：同步被删除的照片（删文件 + 更新待办 + 刷新缩略图） */
    private val viewTodoPhotos = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val remain = res.data?.getStringArrayListExtra(PhotoViewerActivity.EXTRA_RESULT)
            if (remain != null) {
                todo.photos.filter { it !in remain }.forEach { PhotoStore.delete(this, it) }
                todo.photos.clear()
                todo.photos.addAll(remain)
                rebuildPhotoStrip()
            }
        }
    }

    // ---------- 小部件背景照片（与便签页同款） ----------
    private val pickBgPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) setBgPhoto(uri) }

    private val titleWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (applyingHistory || rebuilding) return
            todo.title = s?.toString().orEmpty()
            recordChange()
        }
        override fun afterTextChanged(s: Editable?) {
            // 行首空白统一 0.5 字宽/字符（2026-11-19）：与小部件缩进同口径，
            // 编辑页所见即部件所得；只改显示度量不动内容（含撤销/重做 setText 触发）
            TextStyler.applyLeadingSpaceSpans(edTitle)
        }
    }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_todo_edit)
        NoteStore.init(this)
        TodoStore.init(this)
        ThemePrefs.apply(this)

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        fromWidget = intent.getBooleanExtra(EXTRA_FROM_WIDGET, false)
        isNewDraft = id < 0
        todo = if (id < 0) TodoStore.draft() else (TodoStore.getById(id) ?: TodoStore.draft())

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = if (id < 0) "新建待办" else "编辑待办"
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        bindViews()
        initStylePanel()
        rebuildRows()
        setupHistory()
        refreshTime()
        refreshDoneArea()

        findViewById<ImageView>(R.id.btn_confirm).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_delete).setOnClickListener {
            if (!isNewDraft) {
                TodoStore.softDelete(todo.id)
                MemoWidgetProvider.refreshAll(this)
            }
            exitAfterSave()
        }
        findViewById<Button>(R.id.btn_set_default).setOnClickListener {
            NoteStore.saveDefaultStyle(todo.styleNote())
            Toast.makeText(this, "已设为默认样式", Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.btn_add_item).setOnClickListener { addItem() }
        // 设置小部件：点击展开/收起底部设计面板（与便签编辑页同款；
        // 展开时设计内容全部展示：面板高度自适应、不再与正文分屏，正文区收缩让位）
        findViewById<Button>(R.id.btn_widget_design).setOnClickListener {
            val panel = findViewById<View>(R.id.style_panel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        // 已完成区：标题行点击折叠/展开，齿轮弹菜单
        val collapseListener = View.OnClickListener { toggleDoneCollapsed() }
        btnDoneCollapse.setOnClickListener(collapseListener)
        tvDoneTitle.setOnClickListener(collapseListener)
        findViewById<ImageView>(R.id.btn_done_gear).setOnClickListener { showDoneSettings() }
        setupBgPhotoButtons()
        setupTodoPhotos()
    }

    /** 待办照片区初始化：底部“添加照片”按钮 + 编辑区缩略图区 */
    private fun setupTodoPhotos() {
        btnPhoto = findViewById(R.id.btn_photo)
        photoStrip = findViewById(R.id.photo_strip)
        btnPhoto.setOnClickListener {
            if (todo.photos.size >= MAX_PHOTOS) {
                Toast.makeText(this, "最多添加 $MAX_PHOTOS 张照片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickTodoPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        rebuildPhotoStrip()
    }

    /** 重建待办照片块：文字/条目下方竖向排列、靠左，每张独占一行，
     *  显示尺寸贴合照片实际比例（宽度=内容区宽，高度按比例；高超 500dp 时整体
     *  等比缩小），无白色圆角框与描边；点击进大图浏览，照片右上角 ✕ 快捷删除；
     *  无照片时整块隐藏（与便签编辑页完全同款） */
    private fun rebuildPhotoStrip() {
        photoStrip.removeAllViews()
        if (todo.photos.isEmpty()) {
            photoStrip.visibility = View.GONE
            return
        }
        photoStrip.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val boxW = (resources.displayMetrics.widthPixels - 32 * density).toInt()
        val maxH = (500 * density).toInt()
        val badgePx = (22 * density).toInt()
        todo.photos.forEachIndexed { index, name ->
            val b = PhotoStore.loadBitmap(this, name, boxW, maxH, centerCrop = false)
            var drawW = boxW
            var drawH: Int
            if (b != null && b.width > 0 && b.height > 0) {
                drawH = (boxW.toLong() * b.height / b.width).toInt()
                if (drawH > maxH) {
                    drawH = maxH
                    drawW = (maxH.toLong() * b.width / b.height).toInt()
                }
            } else {
                drawH = maxH / 3
            }
            val wrap = FrameLayout(this)
            wrap.layoutParams = LinearLayout.LayoutParams(drawW, drawH).apply {
                bottomMargin = (10 * density).toInt()
            }
            val iv = ImageView(this)
            iv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            if (b != null) iv.setImageBitmap(b)
            iv.setOnClickListener {
                viewTodoPhotos.launch(
                    android.content.Intent(this, PhotoViewerActivity::class.java)
                        .putExtra(PhotoViewerActivity.EXTRA_PHOTOS, java.util.ArrayList(todo.photos))
                        .putExtra(PhotoViewerActivity.EXTRA_INDEX, index)
                )
            }
            val x = ImageView(this)
            x.setImageResource(R.drawable.ic_close)
            x.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            x.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE212121.toInt())
            }
            x.setPadding(3.dp(), 3.dp(), 3.dp(), 3.dp())
            x.layoutParams = FrameLayout.LayoutParams(
                badgePx, badgePx, Gravity.TOP or Gravity.END
            )
            x.setOnClickListener {
                PhotoStore.delete(this, name)
                todo.photos.remove(name)
                rebuildPhotoStrip()
            }
            wrap.addView(iv)
            wrap.addView(x)
            photoStrip.addView(wrap)
        }
    }

    /** 小部件设计面板：背景照片选择/清除 */
    private fun setupBgPhotoButtons() {
        findViewById<TextView>(R.id.btn_bg_photo).setOnClickListener {
            pickBgPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<TextView>(R.id.btn_bg_photo_clear).setOnClickListener {
            if (todo.bgPhoto.isNotBlank()) {
                PhotoStore.delete(this, todo.bgPhoto)
                todo.bgPhoto = ""
                refreshPreview()
                refreshBgPhotoState()
            }
        }
        refreshBgPhotoState()
    }

    private fun setBgPhoto(uri: android.net.Uri) {
        val name = PhotoStore.importFrom(this, uri, "bg${todo.id}_")
        if (name == null) {
            Toast.makeText(this, "照片读取失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        if (todo.bgPhoto.isNotBlank()) PhotoStore.delete(this, todo.bgPhoto)
        todo.bgPhoto = name
        refreshPreview()
        refreshBgPhotoState()
    }

    /** 背景照片“清除”按钮的显隐 */
    private fun refreshBgPhotoState() {
        findViewById<TextView>(R.id.btn_bg_photo_clear).visibility =
            if (todo.bgPhoto.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun bindViews() {
        edTitle = findViewById(R.id.ed_title)
        rowsUndo = findViewById(R.id.rows_undo)
        rowsDone = findViewById(R.id.rows_done)
        divider = findViewById(R.id.divider)
        doneHeader = findViewById(R.id.done_header)
        btnDoneCollapse = findViewById(R.id.btn_done_collapse)
        tvDoneTitle = findViewById(R.id.tv_done_title)
        tvTime = findViewById(R.id.tv_update_time)
        preview = findViewById(R.id.preview_text)
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
        // 拖拽监听挂在整个内容根容器：行/输入框等深层视图都没有 drag 监听，事件会冒泡到
        // 根容器不断流；手指拖出行区域（“＋添加目录”、时间等空白）也能继续算目标，才能拖到最底
        attachDragHandler(rowsUndo.parent as? LinearLayout ?: rowsUndo)
    }

    // ---------- 条目行渲染 ----------

    /** 重建全部条目行：detach 后按缓存复用再挂载（勾选迁移 / 增删行 / 拖拽落定后调用） */
    private fun rebuildRows() {
        rebuilding = true
        rowsUndo.removeAllViews()
        rowsDone.removeAllViews()
        todo.items.forEach { item ->
            val row = rowCache.getOrPut(item) { buildItemRow(item) }
            refreshRow(row, item)
            if (item.done) rowsDone.addView(row) else rowsUndo.addView(row)
        }
        // 丢弃已删除条目的缓存行（身份比较；TodoItem 是 data class，equals 对同文案条目不可靠）
        rowCache.keys.removeAll { k -> todo.items.none { it === k } }
        rebuilding = false
        refreshDoneArea()
    }

    /** 构建单行：[拖拽把手][勾选框][内容输入][删除（聚焦时显示，仅未完成行）]。
     *  行视图进缓存整个编辑期复用；监听器闭包持有条目对象，索引在触发时按身份动态解析 */
    private fun buildItemRow(item: TodoItem): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = (46 * density()).toInt()
        row.tag = item
        // 条目之间行距（上下各 3dp）：列表更透气，与用户「行距增加一点」的观感要求一致
        row.setPadding(0, (3 * density()).toInt(), 0, (3 * density()).toInt())

        // 1. 拖拽把手：长按发起拖动
        val handle = ImageView(this)
        handle.setImageResource(R.drawable.ic_drag)
        val hLp = LinearLayout.LayoutParams((28 * density()).toInt(), (28 * density()).toInt())
        hLp.marginEnd = (4 * density()).toInt()
        handle.layoutParams = hLp
        handle.setPadding((5 * density()).toInt(), (5 * density()).toInt(), (5 * density()).toInt(), (5 * density()).toInt())
        handle.setOnLongClickListener { v ->
            // 长按发起拖拽。注意：不能在此 rebuildRows()——重建会把把手从窗口移除，
            // 用已 detach 的 view 发起 drag 会静默失败（拖拽“没反应”的根因）。
            if (indexOfItem(item) < 0) return@setOnLongClickListener false
            // 不可见拖影（墨水屏不显示拖影）：被拖行本体保持可见并随目标槽位移动。
            // 注意不能用空构造 DragShadowBuilder()：0x0 影子会抛 dimensions must be positive
            v.startDragAndDrop(
                ClipData.newPlainText("todo_item", "todo_item"),
                BlankDragShadow(), item, 0
            )
            true
        }
        row.addView(handle)

        // 2. 勾选框：点击勾选/取消，勾选即迁移到已完成区
        val check = ImageView(this)
        val cLp = LinearLayout.LayoutParams((26 * density()).toInt(), (26 * density()).toInt())
        cLp.marginEnd = (8 * density()).toInt()
        check.layoutParams = cLp
        check.setPadding((2 * density()).toInt(), (2 * density()).toInt(), (2 * density()).toInt(), (2 * density()).toInt())
        check.setOnClickListener {
            val idx = indexOfItem(item)
            if (idx >= 0) toggleDone(idx)
        }
        row.addView(check)

        // 3. 内容输入
        val edit = EditText(this)
        edit.background = null
        edit.setText(item.text)
        edit.textSize = 15f
        edit.setPadding(0, (8 * density()).toInt(), 0, (8 * density()).toInt())
        edit.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (rebuilding || applyingHistory) return
                item.text = s?.toString().orEmpty()
                recordChange()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        row.addView(edit)

        // 4. 删除按钮：仅未完成行在聚焦时显示（已完成行参考截图不显示）
        val del = ImageView(this)
        del.setImageResource(R.drawable.ic_close)
        del.imageTintList = android.content.res.ColorStateList.valueOf(0xFF424242.toInt())
        val dLp = LinearLayout.LayoutParams((30 * density()).toInt(), (30 * density()).toInt())
        dLp.marginStart = (4 * density()).toInt()
        del.layoutParams = dLp
        del.setPadding((7 * density()).toInt(), (7 * density()).toInt(), (7 * density()).toInt(), (7 * density()).toInt())
        del.visibility = View.GONE
        del.setOnClickListener {
            val idx = indexOfItem(item)
            if (idx >= 0) removeItem(idx)
        }
        edit.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            del.visibility = if (hasFocus && !item.done) View.VISIBLE else View.GONE
        }
        row.addView(del)
        refreshRow(row, item)
        return row
    }

    /** 刷新复用行的展示状态（勾选图标 / 文本 / 颜色与删除线 / 删除按钮） */
    private fun refreshRow(row: View, item: TodoItem) {
        val r = row as? LinearLayout ?: return
        val check = r.getChildAt(1) as? ImageView ?: return
        val edit = r.getChildAt(2) as? EditText ?: return
        check.setImageResource(if (item.done) R.drawable.ic_check_done else R.drawable.ic_check_empty)
        if (edit.text.toString() != item.text) edit.setText(item.text)
        edit.setTextColor(if (item.done) 0xFF9E9E9E.toInt() else 0xFF212121.toInt())
        if (item.done) {
            edit.paintFlags = edit.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            edit.paintFlags = edit.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        (r.getChildAt(3) as? ImageView)?.visibility = View.GONE
    }

    /** 按身份（===）解析条目的全局索引 */
    private fun indexOfItem(item: TodoItem): Int {
        for (i in todo.items.indices) if (todo.items[i] === item) return i
        return -1
    }

    /** 不可见拖影：尺寸必须为正（0 会抛 IllegalStateException），取 1px 且不绘制任何内容 */
    private class BlankDragShadow : View.DragShadowBuilder() {
        override fun onProvideShadowMetrics(
            shadowSize: android.graphics.Point, shadowTouchPoint: android.graphics.Point
        ) {
            shadowSize.set(1, 1)
            shadowTouchPoint.set(0, 0)
        }
    }

    // ---------- 条目操作 ----------

    /** 点击加一行：追加空条目并聚焦 */
    private fun addItem() {
        todo.items.add(TodoItem(""))
        rebuildRows()
        pushHistory(force = true)
        // 聚焦新行（最后一个未完成行，EditText 是行内第 3 个子视图）并弹键盘
        val childCount = rowsUndo.childCount
        if (childCount > 0) {
            val last = rowsUndo.getChildAt(childCount - 1) as? LinearLayout ?: return
            val edit = last.getChildAt(2) as? EditText ?: return
            edit.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** 删除一行 */
    private fun removeItem(globalIdx: Int) {
        if (globalIdx in todo.items.indices) {
            todo.items.removeAt(globalIdx)
            rebuildRows()
            pushHistory(force = true)
        }
    }

    /** 勾选/取消勾选：勾选后自动迁入/移出已完成区 */
    private fun toggleDone(globalIdx: Int) {
        if (globalIdx !in todo.items.indices) return
        val item = todo.items[globalIdx]
        item.done = !item.done
        rebuildRows()
        pushHistory(force = true)
    }

    // ---------- 拖拽排序（长按把手，其余行实时平滑腾位，松开落定） ----------

    /** 拖拽事件处理（挂在内容根容器）：
     *  - 深层视图（行、输入框）都没有 drag 监听，事件冒泡到根容器，全程不断流；
     *  - 手指拖到行区域下方空白（“＋添加目录”、时间等）也能继续计算目标，可一路拖到最底。
     *  拖拽中不改数据、不重建行：被拖行高亮并随目标槽位平移（墨水屏拖影不显示，以行本体为视觉主体），
     *  其余行用 translationY 动画实时腾位；落定时一次性改数据、复用缓存行重排——
     *  各行视觉上已在最终位置，无跳变 */
    private fun attachDragHandler(container: LinearLayout) {
        container.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    val item = event.localState as? TodoItem
                    if (item != null && indexOfItem(item) >= 0) {
                        draggingItem = item
                        dragTargetSlot = -1
                        // 行本体保持可见：高亮标记被拖行，随目标槽位平移（不依赖拖影）
                        rowCache[item]?.setBackgroundColor(0x339E9E9E.toInt())
                        true
                    } else {
                        draggingItem = null
                        dragTargetSlot = -1
                        false
                    }
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    updateDragTarget(event.y)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    endDrag()
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    endDrag()
                    true
                }
                else -> true
            }
        }
    }

    /** 手指 y（内容根坐标）→ 被拖条目组内插入槽，实时调整其余行的腾位动画 */
    private fun updateDragTarget(yRoot: Float) {
        val item = draggingItem ?: return
        val container = if (item.done) rowsDone else rowsUndo
        val dragRow = rowCache[item] ?: return
        val s = container.indexOfChild(dragRow)
        if (s < 0) return
        val others = (0 until container.childCount)
            .filter { it != s }
            .map { container.getChildAt(it) }
        val yc = yRoot - container.top
        // 前插语义：首个中点在手指之下的其余行序位；低于全部行 = 追加到组尾
        var t = others.size
        for (k in others.indices) {
            val r = others[k]
            if (yc < r.top + r.height / 2f) { t = k; break }
        }
        if (t == dragTargetSlot) return
        dragTargetSlot = t
        val h = dragRow.height.toFloat()
        for (k in others.indices) {
            val row = others[k]
            // 目标下移：夹在中间的行上移一格；目标上移：夹在中间的行下移一格
            val dy = when {
                t == s -> 0f
                t > s && k >= s && k < t -> -h
                t < s && k >= t && k < s -> h
                else -> 0f
            }
            if ((rowShifts[row] ?: 0f) == dy) continue
            rowShifts[row] = dy
            row.animate().translationY(dy).setDuration(150)
                .setInterpolator(dragDecelerate).start()
        }
        // 被拖行本体平移到目标槽位：与换位行互相让位，整套视图正好等于最终落位状态
        val dragDy = when {
            t > s -> (s until t).sumOf { others[it].height }.toFloat()
            t < s -> -(t until s).sumOf { others[it].height }.toFloat()
            else -> 0f
        }
        if ((rowShifts[dragRow] ?: 0f) != dragDy) {
            rowShifts[dragRow] = dragDy
            dragRow.animate().translationY(dragDy).setDuration(150)
                .setInterpolator(dragDecelerate).start()
        }
    }

    /** 拖拽结束：复位各行位移、被拖行恢复可见；按最终槽位一次性落定数据并入撤销栈 */
    private fun endDrag() {
        val item = draggingItem ?: return
        draggingItem = null
        val t = dragTargetSlot
        dragTargetSlot = -1
        val dragRow = rowCache[item]
        rowShifts.keys.forEach {
            it.animate().cancel()
            it.translationY = 0f
        }
        rowShifts.clear()
        dragRow?.animate()?.cancel()
        dragRow?.setBackgroundColor(Color.TRANSPARENT)
        if (t >= 0 && indexOfItem(item) >= 0) applyDragMove(item, t)
    }

    /** 落定：把被拖条目移到组内插入槽 t（前插语义），映射回全局列表一次性改数据 */
    private fun applyDragMove(item: TodoItem, t: Int) {
        val from = indexOfItem(item)
        if (from < 0) return
        // 组内当前序位（拖拽期间数据未变，等于开拖时行的序位）；与目标相同即原地放回
        val s = todo.items.take(from).count { it.done == item.done }
        if (t == s) return
        val others = todo.items.filter { it !== item && it.done == item.done }
        if (others.isEmpty()) return
        val target = if (t < others.size) indexOfItem(others[t]) else indexOfItem(others.last()) + 1
        if (target == from) return
        val moved = todo.items.removeAt(from)
        val insertAt = (if (target > from) target - 1 else target).coerceIn(0, todo.items.size)
        todo.items.add(insertAt, moved)
        pushHistory(force = true)
        rebuildRows()
    }

    // ---------- 已完成区 ----------

    /** 刷新已完成区整体可见性 / 标题 / 折叠箭头 */
    private fun refreshDoneArea() {
        val doneCount = todo.items.count { it.done }
        if (doneCount == 0) {
            divider.visibility = View.GONE
            doneHeader.visibility = View.GONE
            rowsDone.visibility = View.GONE
            return
        }
        divider.visibility = View.VISIBLE
        doneHeader.visibility = View.VISIBLE
        tvDoneTitle.text = if (todo.hideDone) "已完该做的事($doneCount) · 已隐藏" else "已完该做的事($doneCount)"
        btnDoneCollapse.rotation = if (doneCollapsed) 180f else 0f
        val showRows = !doneCollapsed && !todo.hideDone
        rowsDone.visibility = if (showRows) View.VISIBLE else View.GONE
    }

    private fun toggleDoneCollapsed() {
        doneCollapsed = !doneCollapsed
        refreshDoneArea()
    }

    /** 齿轮菜单：隐藏已完成 / 全部解除 / 删除已完成（参考截图弹窗，宽度约屏宽 78%） */
    private fun showDoneSettings() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_done_settings)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        // 固定弹窗宽度：默认 wrap_content 会把内容压成窄条
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.78f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.findViewById<ImageView>(R.id.btn_dialog_close).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.btn_hide_done).apply {
            text = if (todo.hideDone) "显示已完成的事情" else "隐藏已完成的事情"
            setOnClickListener {
                // hideDone 是视图设置，不在撤销快照内，不压栈（避免撤销出现无变化空步）
                todo.hideDone = !todo.hideDone
                refreshDoneArea()
                dialog.dismiss()
            }
        }
        dialog.findViewById<TextView>(R.id.btn_uncheck_all).setOnClickListener {
            todo.items.forEach { it.done = false }
            rebuildRows()
            pushHistory(force = true)
            dialog.dismiss()
        }
        dialog.findViewById<TextView>(R.id.btn_delete_done).setOnClickListener {
            todo.items.removeAll { it.done }
            rebuildRows()
            pushHistory(force = true)
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---------- 撤销 / 重做（快照 = 题目 + 全部条目的文本与勾选） ----------

    private fun setupHistory() {
        edTitle.addTextChangedListener(titleWatcher)
        // 初始 setText（bindViews/rebuildRows）先于 watcher 挂载：手动补一次行首空白宽度，
        // 与便签编辑页同因（详见 EditNoteActivity.setupHistory 注释）
        TextStyler.applyLeadingSpaceSpans(edTitle)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        btnUndo.setOnClickListener { undo() }
        btnRedo.setOnClickListener { redo() }
        undoStack.clear()
        redoStack.clear()
        undoStack.addLast(currentSnap())
        lastPushAt = 0L
        updateHistoryButtons()
    }

    private fun currentSnap(): TodoSnap =
        TodoSnap(todo.title, todo.items.map { it.text to it.done })

    /** 文本连续编辑：400ms 内合并为一步；离散操作走 pushHistory(force=true) */
    private fun recordChange() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (undoStack.isEmpty() || now - lastPushAt > 400L) {
            undoStack.addLast(currentSnap())
        } else {
            undoStack.removeLast()
            undoStack.addLast(currentSnap())
        }
        redoStack.clear()
        lastPushAt = now
        updateHistoryButtons()
    }

    private fun pushHistory(force: Boolean) {
        if (force) {
            undoStack.addLast(currentSnap())
            redoStack.clear()
            lastPushAt = 0L
        } else {
            recordChange()
        }
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

    private fun applySnap(snap: TodoSnap) {
        applyingHistory = true
        // 回放前快照，用于回放后按差异锚点定位本次改动的光标
        val oldTitle = todo.title
        val oldTexts = todo.items.map { it.text }
        todo.title = snap.title
        if (edTitle.text.toString() != snap.title) {
            edTitle.setText(snap.title)
            edTitle.setSelection(editAnchor(oldTitle, snap.title))
        }
        // 条目恢复：复用现有对象，多退少补
        while (todo.items.size > snap.items.size) todo.items.removeAt(todo.items.size - 1)
        snap.items.forEachIndexed { i, p ->
            if (i < todo.items.size) {
                todo.items[i].text = p.first
                todo.items[i].done = p.second
            } else {
                todo.items.add(TodoItem(p.first, p.second))
            }
        }
        rebuildRows()
        // 行重建会丢失焦点：把焦点与光标放回本次文本改动所在的行，跟随编辑位置
        for (i in todo.items.indices) {
            val old = oldTexts.getOrNull(i) ?: ""
            val new = snap.items[i].first
            if (old == new) continue
            val row = rowCache[todo.items[i]] as? LinearLayout ?: continue
            val edit = row.getChildAt(2) as? EditText ?: continue
            edit.requestFocus()
            edit.setSelection(editAnchor(old, new))
            break
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

    // ---------- 小部件设计样式面板（与便签编辑页同款） ----------

    private fun initStylePanel() {
        edTitle.setText(todo.title)

        seekAlpha.progress = (todo.alpha * 100).toInt()
        tvAlpha.text = if (seekAlpha.progress == 0) "全透明" else "${seekAlpha.progress}%"
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvAlpha.text = if (p == 0) "全透明" else "$p%"
                todo.alpha = p / 100f
                refreshPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_size_minus).setOnClickListener {
            todo.fontSize = (todo.fontSize - 1).coerceAtLeast(8)
            tvSize.text = "${todo.fontSize}sp"
            refreshPreview()
        }
        findViewById<Button>(R.id.btn_size_plus).setOnClickListener {
            todo.fontSize = (todo.fontSize + 1).coerceAtMost(48)
            tvSize.text = "${todo.fontSize}sp"
            refreshPreview()
        }
        tvSize.text = "${todo.fontSize}sp"

        toggleBold.addOnButtonCheckedListener { _, id, _ ->
            val btn = toggleBold.findViewById<com.google.android.material.button.MaterialButton>(id)
            when (id) {
                R.id.btn_bold -> todo.bold = btn?.isChecked == true
                R.id.btn_italic -> todo.italic = btn?.isChecked == true
                R.id.btn_underline -> todo.underline = btn?.isChecked == true
                R.id.btn_shadow -> todo.shadow = btn?.isChecked == true
            }
            refreshPreview()
        }
        groupH.addOnButtonCheckedListener { _, id, checked ->
            if (checked) todo.alignH = when (id) {
                R.id.btn_align_hc -> 1
                R.id.btn_align_hr -> 2
                else -> 0
            }
            refreshPreview()
        }
        groupV.addOnButtonCheckedListener { _, id, checked ->
            if (checked) todo.alignV = when (id) {
                R.id.btn_align_vt -> 0
                R.id.btn_align_vb -> 2
                else -> 1
            }
            refreshPreview()
        }
        chkScroll.setOnCheckedChangeListener { _, c -> todo.scrollEnabled = c }
        chkPin.setOnCheckedChangeListener { _, c -> todo.pinned = c }

        // 初始化选中态
        if (todo.bold) toggleBold.check(R.id.btn_bold) else toggleBold.uncheck(R.id.btn_bold)
        if (todo.italic) toggleBold.check(R.id.btn_italic) else toggleBold.uncheck(R.id.btn_italic)
        if (todo.underline) toggleBold.check(R.id.btn_underline) else toggleBold.uncheck(R.id.btn_underline)
        if (todo.shadow) toggleBold.check(R.id.btn_shadow) else toggleBold.uncheck(R.id.btn_shadow)
        groupH.check(when (todo.alignH) { 1 -> R.id.btn_align_hc; 2 -> R.id.btn_align_hr; else -> R.id.btn_align_hl })
        groupV.check(when (todo.alignV) { 0 -> R.id.btn_align_vt; 2 -> R.id.btn_align_vb; else -> R.id.btn_align_vc })
        chkScroll.isChecked = todo.scrollEnabled
        chkPin.isChecked = todo.pinned

        buildSwatch()
        refreshPreview()
    }

    private fun buildSwatch() {
        buildColorRow(cvBg, BG_COLORS, todo.bgColor, NoteStore.RECENT_BG, { todo.bgColor = it }, allowNone = true)
        buildColorRow(cvBorder, BORDER_COLORS, todo.borderColor, NoteStore.RECENT_BORDER, { todo.borderColor = it }, allowNone = true)
        buildColorRow(cvFont, FONT_COLORS, todo.fontColor, NoteStore.RECENT_FONT, { todo.fontColor = it }, allowNone = true)
    }

    /** 行内颜色变化后的统一刷新 */
    private fun afterColorChanged() {
        refreshPreview()
        refreshSwatch()
    }

    /** 统一构建一行颜色选择块：[无背景/无边框/无颜色?] + 预设×N + 最近使用 + [＋自定义] */
    private fun buildColorRow(
        container: LinearLayout,
        presets: IntArray,
        current: Int,
        recentKind: String,
        applyColor: (Int) -> Unit,
        allowNone: Boolean = false
    ) {
        if (allowNone) {
            container.addView(createTile(Color.TRANSPARENT, selected = current == 0, dashed = true) {
                applyColor(0)
                afterColorChanged()
            })
        }
        presets.forEach { color ->
            container.addView(createTile(color, selected = current == color, dashed = false) {
                applyColor(color)
                afterColorChanged()
            })
        }
        NoteStore.recentColors(recentKind).forEach { color ->
            container.addView(createTile(color, selected = current == color, dashed = false) {
                applyColor(color)
                afterColorChanged()
            })
        }
        val initial = if (current != 0) current
        else NoteStore.recentColors(recentKind).firstOrNull() ?: Color.GRAY
        container.addView(createPickerTile(initial) { picked ->
            NoteStore.pushRecentColor(recentKind, picked)
            applyColor(picked)
            afterColorChanged()
        })
    }

    /** “＋自定义取色”色块 */
    private fun createPickerTile(initialColor: Int, onPick: (Int) -> Unit): View {
        val size = (24 * density()).toInt()
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(Color.WHITE)
        d.setStroke((2 * density()).toInt(), android.content.res.ColorStateList.valueOf(0xFF424242.toInt()))
        val inner = View(this)
        inner.background = d
        val lp = LinearLayout.LayoutParams(size, size)
        lp.setMargins((4 * density()).toInt(), 0, (4 * density()).toInt(), 0)
        val wrap = FrameLayout(this)
        wrap.addView(inner, FrameLayout.LayoutParams(size, size))
        wrap.layoutParams = lp
        val plus = TextView(this).apply {
            text = "＋"
            textSize = 15f
            setTextColor(0xFF424242.toInt())
            gravity = Gravity.CENTER
        }
        wrap.addView(plus, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        wrap.setOnClickListener {
            ColorPickerDialog.show(this, initialColor, onPick)
        }
        return wrap
    }

    /** 单个色块：选中时右上角叠加 ✓ 角标 */
    private fun createTile(
        color: Int,
        selected: Boolean,
        dashed: Boolean,
        onClick: () -> Unit
    ): View {
        val size = (24 * density()).toInt()
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        val dash = 4 * density()
        when {
            dashed -> d.setStroke(
                (2 * density()).toInt(),
                android.content.res.ColorStateList.valueOf(if (color == Color.TRANSPARENT) Color.GRAY else Color.DKGRAY),
                dash, dash
            )
            else -> d.setStroke(1.dp(), android.content.res.ColorStateList.valueOf(Color.DKGRAY))
        }
        val inner = View(this)
        inner.background = d
        val lp = LinearLayout.LayoutParams(size, size)
        lp.setMargins((4 * density()).toInt(), 0, (4 * density()).toInt(), 0)
        val wrap = FrameLayout(this)
        wrap.addView(inner, FrameLayout.LayoutParams(size, size))
        wrap.layoutParams = lp
        wrap.setOnClickListener { onClick() }
        if (selected) {
            val badgeSize = (13 * density()).toInt()
            val badge = TextView(this).apply {
                text = "✓"
                textSize = 9f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xEE212121.toInt())
                }
            }
            wrap.addView(badge, FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END))
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
        preview.text = "小部件预览示例"
        val h = when (todo.alignH) {
            1 -> Gravity.CENTER_HORIZONTAL
            2 -> Gravity.RIGHT
            else -> Gravity.LEFT
        }
        val v = when (todo.alignV) {
            0 -> Gravity.TOP
            2 -> Gravity.BOTTOM
            else -> Gravity.CENTER_VERTICAL
        }
        preview.gravity = h or v
        TextStyler.applyText(preview, todo.styleNote())
        TextStyler.applyBackground(preview, todo.styleNote(), checkerPreviewTile)
    }

    private val checkerPreviewTile by lazy {
        TextStyler.makeCheckerTile(this, 12)
    }

    // ---------- 保存 / 时间 ----------

    private fun refreshTime() {
        val df = SimpleDateFormat("yyyy/MM/dd(E) HH:mm", Locale.CHINA)
        tvTime.text = df.format(Date(todo.updatedAt))
    }

    private fun save() {
        todo.title = edTitle.text.toString()
        // 全空条目不入库
        todo.items = todo.items.filter { it.text.isNotBlank() }.toMutableList()
        if (isNewDraft && todo.title.isBlank() && todo.items.isEmpty()) {
            exitAfterSave()
            return
        }
        // 修改时间由 TodoStore.update 按内容指纹判定刷新：原样点保存不再改时间
        if (isNewDraft) TodoStore.insertDraft(todo) else TodoStore.update(todo)
        MemoWidgetProvider.refreshAll(this)
        exitAfterSave()
    }

    private fun exitAfterSave() {
        if (fromWidget) finishAffinity() else finish()
    }

    private fun density(): Float = resources.displayMetrics.density

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
