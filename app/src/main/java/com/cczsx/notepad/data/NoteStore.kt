package com.cczsx.notepad.data

import android.content.Context
import org.json.JSONArray

/**
 * 数据仓库：使用 SharedPreferences + JSON 持久化，零额外依赖。
 * 提供便签的增删改查、置顶、回收站（软删除/恢复/彻底删除）以及小部件绑定。
 */
object NoteStore {

    private const val PREFS = "notepad_prefs"
    private const val KEY_NOTES = "notes"
    private const val KEY_NEXT_ID = "next_id"

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 默认样式（新建便签 / 小部件“设置为默认”） ----------
    private const val KEY_DEFAULT = "default_note"

    fun defaultStyle(): Note = Note(id = -1L, content = "").also {
        prefs().getString(KEY_DEFAULT, null)?.let { raw ->
            try { it.applyStyle(Note.fromJson(org.json.JSONObject(raw))) } catch (_: Exception) {}
        }
    }

    fun saveDefaultStyle(note: Note) {
        prefs().edit().putString(KEY_DEFAULT, note.toJson().toString()).apply()
    }

    // ---------- 最近使用的自定义颜色（编辑页每行独立记录，最多 3 个） ----------

    /** 最近颜色所属行：背景 / 边框 / 字体 */
    const val RECENT_BG = "bg"
    const val RECENT_BORDER = "border"
    const val RECENT_FONT = "font"

    private const val RECENT_MAX = 3

    /** 读取某行最近用过的取色器颜色（最新在前） */
    fun recentColors(kind: String): List<Int> {
        val raw = prefs().getString("recent_$kind", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getInt(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 记录一次取色器选择：去重后置顶，超出上限丢弃最旧的；0 表示“无颜色”不入记录 */
    fun pushRecentColor(kind: String, color: Int) {
        if (color == 0) return
        val list = (recentColors(kind) - color).toMutableList()
        list.add(0, color)
        val arr = org.json.JSONArray()
        list.take(RECENT_MAX).forEach { arr.put(it) }
        prefs().edit().putString("recent_$kind", arr.toString()).apply()
    }

    /** 把另一条便签的样式字段复制到本便签（不复制 id/内容/状态） */
    private fun Note.applyStyle(src: Note) {
        bgColor = src.bgColor
        alpha = src.alpha
        borderColor = src.borderColor
        fontColor = src.fontColor
        fontSize = src.fontSize
        bold = src.bold
        italic = src.italic
        underline = src.underline
        shadow = src.shadow
        alignH = src.alignH
        alignV = src.alignV
        scrollEnabled = src.scrollEnabled
    }

    private fun nextId(): Long {
        val p = prefs()
        val id = p.getLong(KEY_NEXT_ID, 1L)
        p.edit().putLong(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    private fun saveAll(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs().edit().putString(KEY_NOTES, arr.toString()).apply()
    }

    private fun loadAll(): MutableList<Note> {
        val raw = prefs().getString(KEY_NOTES, null) ?: return mutableListOf()
        val list = mutableListOf<Note>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(Note.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
            // 数据损坏时返回空列表，避免崩溃
        }
        return list
    }

    // ---------- 便签 CRUD ----------

    fun create(content: String = ""): Note {
        val n = Note(id = nextId())
        n.content = content
        // 新建便签沿用“默认样式”
        n.applyStyle(defaultStyle())
        val list = loadAll()
        list.add(n)
        saveAll(list)
        return n
    }

    /**
     * 新建草稿：分配 id + 默认样式但不落库。
     * 打开编辑页时用草稿代替 create()，用户返回不保存就不会残留“空便签”；
     * 首次点保存时经 update() 的 upsert 语义入库。
     */
    fun draft(): Note {
        val n = Note(id = nextId())
        n.applyStyle(defaultStyle())
        return n
    }

    fun update(note: Note) {
        // 内容无变化（原样点保存）时保留原修改时间，不刷新为当前时间
        val old = getById(note.id)
        if (old == null || old.contentKey() != note.contentKey()) {
            note.updatedAt = System.currentTimeMillis()
        }
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == note.id }
        if (idx >= 0) list[idx] = note else list.add(note)
        saveAll(list)
    }

    fun getById(id: Long): Note? = loadAll().firstOrNull { it.id == id }

    /** 普通列表（未删除），置顶优先，再按用户设置排序 */
    fun getActive(orderMode: Int = 0, descending: Boolean = true): List<Note> {
        var list = loadAll().filter { !it.deleted }
        list = when (orderMode) {
            1 -> list.sortedBy { it.createdAt }            // 按添加时间
            2 -> list.sortedBy { (it.title.ifBlank { it.content }).lowercase() } // 按标题排序
            else -> list.sortedBy { it.updatedAt }        // 按修改时间
        }
        if (descending) list = list.reversed()
        // 置顶组永远在最前
        return list.sortedWith(compareByDescending<Note> { it.pinned })
    }

    /** 回收站：被软删除的便签，按删除时间倒序 */
    fun deletedNotes(): List<Note> = loadAll()
        .filter { it.deleted }
        .sortedByDescending { it.deletedAt }

    /** 软删除：移入回收站 */
    fun softDelete(id: Long) {
        getById(id)?.let {
            it.deleted = true
            it.deletedAt = System.currentTimeMillis()
            update(it)
        }
    }

    /** 恢复 */
    fun restore(id: Long) {
        getById(id)?.let {
            it.deleted = false
            it.deletedAt = 0L
            update(it)
        }
    }

    /** 彻底删除单条 */
    fun permanentDelete(id: Long) {
        val list = loadAll()
        list.removeAll { it.id == id }
        saveAll(list)
    }

    /** 清空回收站 */
    fun emptyTrash() {
        val list = loadAll()
        list.removeAll { it.deleted }
        saveAll(list)
    }

    /** 切换置顶 */
    fun togglePin(id: Long) {
        getById(id)?.let {
            it.pinned = !it.pinned
            update(it)
        }
    }

    // ---------- 备份支持 ----------

    /** 全量记录（含回收站软删除项）——导出/自动备份用 */
    fun allRecords(): List<Note> = loadAll()

    /**
     * 合并导入（备份恢复用）：按 id 覆盖同号便签、新增没有的；
     * 不删除现有其他便签，并把 next_id 顶到不小于导入最大 id，防止后续新建撞 id
     */
    fun mergeRecords(notes: List<Note>) {
        val list = loadAll()
        notes.forEach { src ->
            val idx = list.indexOfFirst { it.id == src.id }
            if (idx >= 0) list[idx] = src else list.add(src)
        }
        saveAll(list)
        notes.maxOfOrNull { it.id }?.let { maxId ->
            val cur = prefs().getLong(KEY_NEXT_ID, 1L)
            if (cur <= maxId) prefs().edit().putLong(KEY_NEXT_ID, maxId + 1).apply()
        }
    }

    // ---------- 小部件绑定 ----------

    /** 把某个便签绑定到指定小部件（并解除旧绑定） */
    fun bindToWidget(noteId: Long, widgetId: Int) {
        val list = loadAll()
        list.forEach { if (it.widgetId == widgetId.toLong() && it.id != noteId) it.widgetId = -1L }
        list.firstOrNull { it.id == noteId }?.widgetId = widgetId.toLong()
        saveAll(list)
    }

    /** 根据小部件 id 取当前要展示的便签（仅取绑定便签；未配置的显示“点击选择便签”占位，
     *  不再回退到第一条活动便签——避免桌面多个未配置小部件内容重复） */
    fun noteForWidget(widgetId: Int): Note? =
        loadAll().firstOrNull { it.widgetId == widgetId.toLong() && !it.deleted }

    /** 解除小部件绑定（小部件被删除时调用） */
    fun unbindWidget(widgetId: Int) {
        val list = loadAll()
        list.forEach { if (it.widgetId == widgetId.toLong()) it.widgetId = -1L }
        saveAll(list)
    }
}