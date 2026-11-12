package com.cczsx.notepad.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cczsx.notepad.R
import com.cczsx.notepad.data.Note
import com.cczsx.notepad.data.NoteStore
import com.cczsx.notepad.data.Todo
import com.cczsx.notepad.data.TodoStore
import com.cczsx.notepad.widget.MemoWidgetProvider
import com.google.android.material.card.MaterialCardView
import java.util.Locale

/**
 * 主界面：便签 + 待办 合并列表。
 * 支持查看方式（列表/双列卡片/网格）、排序、置顶、长按删除、
 * 标题+正文搜索、回收站入口与设置入口。
 * 新建按钮点击后弹出三圆钮菜单：待办 / 便签 / 关闭。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_VIEW = "view_prefs"
        const val KEY_VIEW_MODE = "view_mode"   // 0列表 1卡片 2网格
        const val KEY_ORDER = "order"           // 0修改 1添加 2标题
        const val KEY_ORDER_DESC = "order_desc"

        /** 卡片底部时间（参考截图）：当天 hh:mm 上午/下午；今年更早 M月d；往年 yyyy年M月d */
        fun cardTime(ts: Long): String {
            if (ts <= 0) return ""
            val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            val now = java.util.Calendar.getInstance()
            return if (c.get(java.util.Calendar.YEAR) != now.get(java.util.Calendar.YEAR)) {
                "${c.get(java.util.Calendar.YEAR)}年${c.get(java.util.Calendar.MONTH) + 1}月${c.get(java.util.Calendar.DAY_OF_MONTH)}"
            } else if (c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)) {
                val h12 = c.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
                val amPm = if (c.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "上午" else "下午"
                String.format(Locale.getDefault(), "%02d:%02d %s", h12, c.get(java.util.Calendar.MINUTE), amPm)
            } else {
                "${c.get(java.util.Calendar.MONTH) + 1}月${c.get(java.util.Calendar.DAY_OF_MONTH)}"
            }
        }
    }

    /** 列表行：便签或待办（二选一） */
    private class Row(val note: Note?, val todo: Todo?) {
        val pinned: Boolean get() = todo?.pinned ?: note!!.pinned
        val updatedAt: Long get() = todo?.updatedAt ?: note!!.updatedAt
        val createdAt: Long get() = todo?.createdAt ?: note!!.createdAt
        val titleKey: String get() = (todo?.title ?: note!!.title).ifBlank { "Untitled" }
        fun matchQuery(q: String): Boolean = if (todo != null) {
            todo.title.contains(q, true) || todo.items.any { it.text.contains(q, true) }
        } else {
            val n = note!!
            n.title.contains(q, true) || n.content.contains(q, true)
        }
        /** 批量选择用唯一 key：t<id>=待办，n<id>=便签 */
        fun key(): String = if (todo != null) "t${todo.id}" else "n${note!!.id}"
    }

    private lateinit var rv: RecyclerView
    private lateinit var adapter: NoteAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var searchRow: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var btnSearchClear: ImageView
    private lateinit var fabMenu: LinearLayout
    private lateinit var fab: com.google.android.material.floatingactionbutton.FloatingActionButton
    // 批量删除模式（⋮ 菜单「删除便签」进入）
    private var batchMode = false
    private val batchSelected = mutableSetOf<String>()
    private lateinit var btnNav: ImageView
    private lateinit var btnSearch: ImageView
    private lateinit var btnMenu: ImageView
    private lateinit var batchTitle: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var batchDelete: TextView
    private var searchQuery: String = ""
    private var viewMode = 0
    private var order = 0
    private var orderDesc = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NoteStore.init(this)
        TodoStore.init(this)
        ThemePrefs.apply(this)
        // 自动备份：到达设定间隔（设置页可调）在启动时备份一次
        com.cczsx.notepad.data.BackupManager.maybeAutoBackup(this)

        val prefs = getSharedPreferences(PREFS_VIEW, MODE_PRIVATE)
        viewMode = prefs.getInt(KEY_VIEW_MODE, 1)
        order = prefs.getInt(KEY_ORDER, 0)
        orderDesc = prefs.getBoolean(KEY_ORDER_DESC, true)

        rv = findViewById(R.id.rv_notes)
        adapter = NoteAdapter(emptyList(), object : NoteAdapter.Callbacks {
            override fun onOpenNote(note: Note) = openEditor(note)
            override fun onOpenTodo(todo: Todo) = openTodoEditor(todo)
            override fun onPin(note: Note) {
                NoteStore.togglePin(note.id); reload(); MemoWidgetProvider.refreshAll(this@MainActivity)
            }
            override fun onPinTodo(todo: Todo) {
                TodoStore.togglePin(todo.id); reload(); MemoWidgetProvider.refreshAll(this@MainActivity)
            }
            override fun onDelete(note: Note) {
                NoteStore.softDelete(note.id); reload(); MemoWidgetProvider.refreshAll(this@MainActivity)
            }
            override fun onDeleteTodo(todo: Todo) {
                TodoStore.softDelete(todo.id); reload(); MemoWidgetProvider.refreshAll(this@MainActivity)
            }
            override fun onBatchToggle(key: String) = toggleBatchSelect(key)
        })
        rv.adapter = adapter
        applyLayout()
        tvEmpty = findViewById(R.id.tv_empty)

        // 新建按钮：点击弹出两钮菜单（从上往下：待办 / 便签）。
        // 弹菜单时 FAB 本体变成灰色 X 关闭钮，再点它收起并恢复新建样式
        fabMenu = findViewById(R.id.fab_menu)
        fab = findViewById(R.id.fab)
        fab.setOnClickListener { toggleFabMenu() }
        findViewById<View>(R.id.toolbar).setOnClickListener { if (fabMenu.visibility == View.VISIBLE) toggleFabMenu() }
        findViewById<View>(R.id.rv_notes).setOnClickListener { if (fabMenu.visibility == View.VISIBLE) toggleFabMenu() }
        findViewById<View>(R.id.btn_new_todo).setOnClickListener {
            closeFabMenu()
            openTodoEditor(null)
        }
        findViewById<View>(R.id.btn_new_note).setOnClickListener {
            closeFabMenu()
            openEditor(null)
        }

        // 顶栏：三横线进设置（批量模式下变为返回箭头退出多选）；⋮ 弹菜单
        btnNav = findViewById(R.id.btn_nav)
        btnNav.setOnClickListener {
            if (batchMode) exitBatchMode()
            else startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        btnSearch = findViewById(R.id.btn_search)
        btnMenu = findViewById(R.id.btn_menu)
        btnMenu.setOnClickListener { showMenu(it) }

        // 批量删除：选中计数标题 / 全选切换 / 底部通栏删除按钮
        batchTitle = findViewById(R.id.batch_title)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnSelectAll.setOnClickListener { toggleSelectAll() }
        batchDelete = findViewById(R.id.batch_delete)
        batchDelete.setOnClickListener { deleteSelected() }

        bindSearch()
    }

    // ---------- 搜索 ----------

    private fun bindSearch() {
        searchRow = findViewById(R.id.search_row)
        searchInput = findViewById(R.id.search_input)
        btnSearchClear = findViewById(R.id.btn_search_clear)

        findViewById<ImageView>(R.id.btn_search).setOnClickListener { openSearch() }
        findViewById<ImageView>(R.id.btn_search_back).setOnClickListener { closeSearch() }
        btnSearchClear.setOnClickListener { searchInput.setText("") }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                btnSearchClear.visibility =
                    if (searchQuery.isNotEmpty()) View.VISIBLE else View.INVISIBLE
                reload()
            }
        })
    }

    private fun openSearch() {
        // 搜索框置顶：搜索时隐藏顶部工具栏，搜索行上移到顶栏位置独占顶部
        findViewById<View>(R.id.toolbar).visibility = View.GONE
        searchRow.visibility = View.VISIBLE
        searchInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchInput.setText("")
        searchQuery = ""
        searchRow.visibility = View.GONE
        findViewById<View>(R.id.toolbar).visibility = View.VISIBLE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        reload()
    }

    // ---------- 数据 ----------

    override fun onResume() {
        super.onResume()
        // 从设置页/编辑页返回：重新应用主题色；再按菜单当前状态重设 FAB 形态，
        // 避免 apply 的主题色 tint 覆盖掉菜单打开时的灰色 X 关闭样式
        ThemePrefs.apply(this)
        morphFabToClose(fabMenu.visibility == View.VISIBLE)
        reload()
    }

    /** 弹出/收起新建菜单：FAB 本体在“新建（+）/关闭（灰底 X）”两态间切换，
     *  两钮（待办/便签）依次缩放弹出（参考录屏节奏） */
    private fun toggleFabMenu() {
        if (fabMenu.visibility == View.VISIBLE) {
            closeFabMenu()
        } else {
            fabMenu.visibility = View.VISIBLE
            morphFabToClose(true)
            listOf(R.id.btn_new_todo, R.id.btn_new_note)
                .forEachIndexed { i, id ->
                    val b = findViewById<View>(id)
                    b.scaleX = 0f
                    b.scaleY = 0f
                    b.alpha = 0f
                    b.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(150L)
                        .setStartDelay(i * 60L)
                        .start()
                }
        }
    }

    private fun closeFabMenu() {
        fabMenu.visibility = View.GONE
        morphFabToClose(false)
    }

    /** FAB 形态切换：true=灰色关闭样式（X），false=恢复主题色新建样式（+）。
     *  恢复时按 ThemePrefs 当前主题色实时计算（与 ThemePrefs.apply 同源，换主题后不会过期） */
    private fun morphFabToClose(close: Boolean) {
        if (close) {
            fab.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF9E9E9E.toInt())
            fab.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE))
            fab.setImageResource(R.drawable.ic_close)
        } else {
            val color = ThemePrefs.load(this)
            fab.backgroundTintList =
                android.content.res.ColorStateList.valueOf(color)
            fab.setImageTintList(
                android.content.res.ColorStateList.valueOf(ThemePrefs.contentColor(color))
            )
            fab.setImageResource(R.drawable.ic_add)
        }
    }

    private fun reload() {
        val notes = NoteStore.getActive(order, orderDesc)
        val todos = TodoStore.activeTodos(order, orderDesc)
        var rows = (notes.map { Row(it, null) } + todos.map { Row(null, it) })
        // 合并后统一按当前排序规则重排（两个 store 的分组规则一致：置顶在前）
        rows = when (order) {
            1 -> if (orderDesc) rows.sortedByDescending { it.createdAt } else rows.sortedBy { it.createdAt }
            2 -> if (orderDesc) rows.sortedByDescending { it.titleKey } else rows.sortedBy { it.titleKey }
            else -> if (orderDesc) rows.sortedByDescending { it.updatedAt } else rows.sortedBy { it.updatedAt }
        }.sortedByDescending { it.pinned }

        val q = searchQuery.trim()
        if (q.isNotEmpty()) rows = rows.filter { it.matchQuery(q) }
        adapter.submit(rows)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = if (q.isNotEmpty()) "未找到匹配的内容" else "还没有便签\n点击右下角 + 新建便签或待办"
    }

    private fun openEditor(note: Note?) {
        val i = android.content.Intent(this, EditNoteActivity::class.java)
        if (note != null) i.putExtra(EditNoteActivity.EXTRA_ID, note.id)
        startActivity(i)
    }

    private fun openTodoEditor(todo: Todo?) {
        val i = android.content.Intent(this, TodoEditActivity::class.java)
        if (todo != null) i.putExtra(TodoEditActivity.EXTRA_ID, todo.id)
        startActivity(i)
    }

    private fun applyLayout() {
        when (viewMode) {
            0 -> rv.layoutManager = LinearLayoutManager(this)
            1 -> rv.layoutManager = GridLayoutManager(this, 2)
            2 -> rv.layoutManager = GridLayoutManager(this, 3)
            // 标题视图：单列扁平行，只显示标题
            else -> rv.layoutManager = LinearLayoutManager(this)
        }
        adapter.mode = viewMode
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        // 选中项打勾：查看方式 / 排列方式分组互斥勾选，倒序为开关勾选
        popup.menu.findItem(
            when (viewMode) {
                0 -> R.id.action_view_list
                1 -> R.id.action_view_card
                2 -> R.id.action_view_grid
                else -> R.id.action_view_title
            }
        ).isChecked = true
        popup.menu.findItem(
            when (order) {
                0 -> R.id.action_order_modified
                1 -> R.id.action_order_added
                else -> R.id.action_order_title
            }
        ).isChecked = true
        popup.menu.findItem(R.id.action_sort_desc).isChecked = orderDesc

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_view_list -> { viewMode = 0; applyLayout(); savePrefs(); true }
                R.id.action_view_card -> { viewMode = 1; applyLayout(); savePrefs(); true }
                R.id.action_view_grid -> { viewMode = 2; applyLayout(); savePrefs(); true }
                R.id.action_view_title -> { viewMode = 3; applyLayout(); savePrefs(); true }
                R.id.action_order_modified -> { order = 0; savePrefs(); reload(); true }
                R.id.action_order_added -> { order = 1; savePrefs(); reload(); true }
                R.id.action_order_title -> { order = 2; savePrefs(); reload(); true }
                R.id.action_sort_desc -> { orderDesc = !orderDesc; savePrefs(); reload(); true }
                R.id.action_batch_delete -> { enterBatchMode(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS_VIEW, MODE_PRIVATE).edit()
            .putInt(KEY_VIEW_MODE, viewMode)
            .putInt(KEY_ORDER, order)
            .putBoolean(KEY_ORDER_DESC, orderDesc)
            .apply()
    }

    // ---------- 批量删除模式（⋮ 菜单「删除便签」进入） ----------

    /** 进入多选：顶栏变返回+计数+全选，隐藏搜索/菜单/FAB，显示底部删除栏，卡片出复选框 */
    private fun enterBatchMode() {
        batchMode = true
        batchSelected.clear()
        if (searchRow.visibility == View.VISIBLE) closeSearch()
        if (fabMenu.visibility == View.VISIBLE) closeFabMenu()
        adapter.selectedKeys = batchSelected
        adapter.batchMode = true
        btnNav.setImageResource(R.drawable.ic_arrow_back_dark)
        batchTitle.visibility = View.VISIBLE
        updateBatchTitle()
        btnSearch.visibility = View.GONE
        btnMenu.visibility = View.GONE
        btnSelectAll.visibility = View.VISIBLE
        batchDelete.visibility = View.VISIBLE
        fab.visibility = View.GONE
    }

    /** 退出多选：全部还原普通模式（卡片背景随重新绑定恢复样式色） */
    private fun exitBatchMode() {
        batchMode = false
        batchSelected.clear()
        adapter.batchMode = false
        btnNav.setImageResource(R.drawable.ic_drawer)
        batchTitle.visibility = View.GONE
        btnSearch.visibility = View.VISIBLE
        btnMenu.visibility = View.VISIBLE
        btnSelectAll.visibility = View.GONE
        batchDelete.visibility = View.GONE
        fab.visibility = View.VISIBLE
    }

    private fun updateBatchTitle() {
        batchTitle.text = "选择的便签:${batchSelected.size}个"
    }

    /** 全选/全不选切换 */
    private fun toggleSelectAll() {
        val all = adapter.allKeys()
        if (all.isNotEmpty() && batchSelected.containsAll(all)) {
            batchSelected.clear()
        } else {
            batchSelected.clear()
            batchSelected.addAll(all)
        }
        adapter.notifyDataSetChanged()
        updateBatchTitle()
    }

    private fun toggleBatchSelect(key: String) {
        if (!batchSelected.remove(key)) batchSelected.add(key)
        adapter.notifyDataSetChanged()
        updateBatchTitle()
    }

    /** 删除所选（软删除进回收站）：t<id>=待办，n<id>=便签 */
    private fun deleteSelected() {
        if (batchSelected.isEmpty()) {
            android.widget.Toast.makeText(this, "请先选择要删除的便签", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        batchSelected.toList().forEach { key ->
            val id = key.drop(1).toLongOrNull() ?: return@forEach
            if (key.startsWith("t")) TodoStore.softDelete(id) else NoteStore.softDelete(id)
        }
        MemoWidgetProvider.refreshAll(this)
        exitBatchMode()
        reload()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            batchMode -> exitBatchMode()
            // 搜索框打开时：返回键/返回手势先收起搜索框（恢复顶部工具栏）
            searchRow.visibility == View.VISIBLE -> closeSearch()
            else -> super.onBackPressed()
        }
    }

    /** 列表适配器：同一卡片布局渲染便签与待办 */
    private class NoteAdapter(
        initial: List<Row>,
        private val cb: Callbacks
    ) : RecyclerView.Adapter<NoteAdapter.Holder>() {
        var mode = 1
            set(value) { field = value; notifyDataSetChanged() }
        /** 批量多选模式：true 时卡片显示复选框、点击整卡切换勾选 */
        var batchMode = false
            set(value) { field = value; notifyDataSetChanged() }
        /** 批量模式下已勾选的行 key 集合（与 MainActivity 同一引用，实时联动） */
        var selectedKeys: MutableSet<String>? = null
        private val items = initial.toMutableList()

        interface Callbacks {
            fun onOpenNote(note: Note)
            fun onOpenTodo(todo: Todo)
            fun onPin(note: Note)
            fun onPinTodo(todo: Todo)
            fun onDelete(note: Note)
            fun onDeleteTodo(todo: Todo)
            fun onBatchToggle(key: String)
        }

        /** 全部行的 key（全选用）：t<id>=待办，n<id>=便签 */
        fun allKeys() = items.map { it.key() }

        fun submit(list: List<Row>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val layout = when (viewType) {
                1 -> R.layout.item_todo
                2 -> R.layout.item_title
                else -> R.layout.item_note
            }
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return Holder(v)
        }

        // 标题视图（mode==3）不区分便签/待办，共用 item_title 行布局
        override fun getItemViewType(position: Int) =
            if (mode == 3) 2 else if (items[position].todo != null) 1 else 0

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], cb, mode, batchMode, selectedKeys)
        }

        override fun getItemCount() = items.size

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            private val root = v
            private val title: TextView = v.findViewById(R.id.item_title)
            private val date: TextView = v.findViewById(R.id.item_date)
            private val pinIcon: TextView = v.findViewById(R.id.item_pin)
            // 便签卡片专属
            private val content: TextView? = v.findViewById(R.id.item_content)
            // 待办卡片专属
            private val todoIcon: android.widget.ImageView? = v.findViewById(R.id.item_todo_icon)
            private val todoLines: LinearLayout? = v.findViewById(R.id.item_lines)
            private val count: TextView? = v.findViewById(R.id.item_count)
            // 批量选择复选框（两种卡片布局均有）
            private val check: TextView? = v.findViewById(R.id.item_check)

            fun bind(row: Row, cb: Callbacks, mode: Int, batch: Boolean, selected: MutableSet<String>?) {
                val note = row.note
                val todo = row.todo
                if (todo != null) bindTodo(todo) else bindNote(note!!, mode)
                date.text = cardTime(if (todo != null) todo.updatedAt else note!!.updatedAt)
                pinIcon.visibility = if (row.pinned) View.VISIBLE else View.GONE
                val key = row.key()
                if (batch && selected != null) {
                    // 多选：卡片右上角复选框，点击整卡切换勾选；选中的卡片变灰。
                    // 灰色在样式背景之后覆盖，取消选中重新绑定时由 applyBackground 恢复原色
                    val checked = key in selected
                    check?.visibility = View.VISIBLE
                    check?.text = if (checked) "✓" else ""
                    check?.setBackgroundResource(
                        if (checked) R.drawable.bg_check_checked else R.drawable.bg_check_hollow
                    )
                    if (checked) (root as MaterialCardView).setCardBackgroundColor(0xFFE0E0E0.toInt())
                    root.setOnClickListener { cb.onBatchToggle(key) }
                    root.setOnLongClickListener { true }
                } else {
                    check?.visibility = View.GONE
                    root.setOnClickListener {
                        if (todo != null) cb.onOpenTodo(todo) else cb.onOpenNote(note!!)
                    }
                    root.setOnLongClickListener {
                        showActions(row, cb); true
                    }
                }
            }

            /** 待办卡片：勾选框图标 + 题目 + 条目预览（方框图标逐条）+ 统计（参考截图） */
            private fun bindTodo(todo: Todo) {
                val ctx = root.context
                val density = ctx.resources.displayMetrics.density
                title.text = todo.title.trim().ifBlank { "待办" }
                todoIcon?.setColorFilter(0xFF212121.toInt())

                val lines = todoLines
                if (lines != null) {
                    lines.removeAllViews()
                    // 已完成条目排到预览最后（不按时间/原始顺序混排），组内保持待办内顺序
                    todo.items.sortedBy { it.done }.take(3).forEach { item ->
                        val rowLp = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }
                        val icon = android.widget.ImageView(ctx).apply {
                            setImageResource(if (item.done) R.drawable.ic_check_done else R.drawable.ic_check_empty)
                            layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt())
                        }
                        val tv = TextView(ctx).apply {
                            text = item.text.trim()
                            textSize = 13f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setSingleLine()
                            if (item.done) {
                                setTextColor(0xFF9E9E9E.toInt())
                                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                            } else {
                                setTextColor(0xFF424242.toInt())
                            }
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginStart = (6 * density).toInt()
                            }
                        }
                        rowLp.addView(icon)
                        rowLp.addView(tv)
                        lines.addView(rowLp)
                    }
                }
                val done = todo.items.count { it.done }
                count?.text = "$done/${todo.items.size}"
                count?.visibility = View.VISIBLE
                // 标题视图行：待办图标需显式显示（与便签行共用布局）
                todoIcon?.visibility = View.VISIBLE

                // 样式：题目字色/字号/效果 + 卡片背景边框（同便签卡片）
                val styleNote = todo.styleNote()
                TextStyler.applyText(title, styleNote)
                TextStyler.applyBackground(root as MaterialCardView, styleNote)
            }

            private fun bindNote(note: Note, mode: Int) {
                // 双列/网格下同排卡片等高：便签预览行数增加到 4 行，
                // 与待办侧（3 条目 + 统计行）内容量平衡，避免大片空白
                content?.maxLines = if (mode >= 1) 4 else 2
                val t = note.title.trim()
                val c = note.content.trim()
                when {
                    t.isBlank() && c.isBlank() -> {
                        title.text = "空便签"
                        content?.visibility = View.GONE
                    }
                    t.isBlank() -> {
                        // 无标题：正文升为标题行
                        title.text = c
                        content?.visibility = View.GONE
                    }
                    else -> {
                        title.text = t
                        content?.text = c
                        content?.visibility = View.VISIBLE
                    }
                }
                TextStyler.applyText(title, note)
                // 标题视图行：便签行隐藏待办图标（与待办行共用布局）
                todoIcon?.visibility = View.GONE
                // 正文预览：标题色淡化 + 字号略小（“无颜色”按默认墨黑处理）
                content?.setTextColor(Color.argb(
                    150,
                    Color.red(note.effectiveFontColor), Color.green(note.effectiveFontColor), Color.blue(note.effectiveFontColor)
                ))
                content?.textSize = (note.fontSize - 3).coerceAtLeast(11).toFloat()
                TextStyler.applyBackground(root as MaterialCardView, note)
            }

            private fun showActions(row: Row, cb: Callbacks) {
                val popup = PopupMenu(root.context, root)
                popup.menu.add(0, 1, 0, if (row.pinned) "取消置顶" else "置顶")
                popup.menu.add(0, 2, 1, "删除")
                popup.setOnMenuItemClickListener { m ->
                    when (m.itemId) {
                        1 -> if (row.todo != null) cb.onPinTodo(row.todo) else cb.onPin(row.note!!)
                        2 -> if (row.todo != null) cb.onDeleteTodo(row.todo) else cb.onDelete(row.note!!)
                    }
                    true
                }
                popup.show()
            }
        }
    }
}
