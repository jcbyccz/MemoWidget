package com.cczsx.notepad.data

import android.content.Context
import org.json.JSONArray

/**
 * 待办数据仓库：SharedPreferences + JSON 持久化，风格与 NoteStore 一致。
 * 提供待办的增删改查、草稿、软删除回收站与恢复。
 */
object TodoStore {

    private const val PREFS = "notepad_prefs"
    private const val KEY_TODOS = "todos"
    private const val KEY_NEXT_ID = "todo_next_id"

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 草稿（未落库的新建） ----------

    fun draft(): Todo = Todo(id = -1L)

    // ---------- CRUD ----------

    fun nextId(): Long {
        val id = prefs().getLong(KEY_NEXT_ID, 100_000L)
        prefs().edit().putLong(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    fun getById(id: Long): Todo? =
        loadAll().firstOrNull { it.id == id }

    /** 新建并落库；返回带真实 id 的对象 */
    fun create(todo: Todo): Todo {
        val t = todo.copy(id = nextId())
        val list = loadAll()
        list.add(t)
        saveAll(list)
        return t
    }

    /** 新建草稿首次保存时调用：把当前 draft 内容原样入库（含空条目） */
    fun insertDraft(draft: Todo): Todo {
        val t = draft.copy(id = nextId(), createdAt = System.currentTimeMillis())
        val list = loadAll()
        list.add(t)
        saveAll(list)
        return t
    }

    fun update(todo: Todo) {
        val list = loadAll()
        val i = list.indexOfFirst { it.id == todo.id }
        if (i >= 0) {
            // 内容无变化（原样点保存）时保留原修改时间，不刷新为当前时间
            if (list[i].contentKey() != todo.contentKey()) {
                todo.updatedAt = System.currentTimeMillis()
            }
            list[i] = todo
            saveAll(list)
        }
    }

    fun togglePin(id: Long) {
        val t = getById(id) ?: return
        t.pinned = !t.pinned
        update(t)
    }

    fun softDelete(id: Long) {
        val t = getById(id) ?: return
        t.deleted = true
        t.deletedAt = System.currentTimeMillis()
        update(t)
    }

    fun restore(id: Long) {
        val t = getById(id) ?: return
        t.deleted = false
        t.deletedAt = 0L
        update(t)
    }

    fun permanentDelete(id: Long) {
        saveAll(loadAll().filter { it.id != id })
    }

    /** 清空回收站：移除所有已软删除的待办 */
    fun emptyTrash() {
        saveAll(loadAll().filter { !it.deleted })
    }

    // ---------- 桌面小部件绑定 ----------

    fun bindToWidget(id: Long, widgetId: Int) {
        val t = getById(id) ?: return
        t.widgetId = widgetId.toLong()
        update(t)
    }

    fun unbindWidget(widgetId: Int) {
        loadAll().filter { it.widgetId == widgetId.toLong() }.forEach {
            it.widgetId = -1L
            update(it)
        }
    }

    /** 查找绑定到指定小部件的待办 */
    fun todoForWidget(widgetId: Int): Todo? =
        loadAll().firstOrNull { it.widgetId == widgetId.toLong() && !it.deleted }

    // ---------- 查询 ----------

    fun activeTodos(order: Int = 0, desc: Boolean = true): List<Todo> =
        sortList(loadAll().filter { !it.deleted }, order, desc)

    fun deletedTodos(): List<Todo> =
        loadAll().filter { it.deleted }

    private fun sortList(list: List<Todo>, order: Int, desc: Boolean): List<Todo> {
        val sorted = when (order) {
            1 -> list.sortedBy { it.createdAt }
            2 -> list.sortedBy { it.title }
            else -> list.sortedBy { it.updatedAt }
        }
        val ordered = if (desc) sorted.reversed() else sorted
        // 置顶分组：置顶在前，组内保持排序
        return ordered.sortedByDescending { it.pinned }
    }

    // ---------- 备份支持 ----------

    /** 全量记录（含回收站软删除项）——导出/自动备份用 */
    fun allRecords(): List<Todo> = loadAll()

    /** 合并导入：按 id 覆盖同号待办、新增没有的；并同步 next_id */
    fun mergeRecords(todos: List<Todo>) {
        val list = loadAll()
        todos.forEach { src ->
            val idx = list.indexOfFirst { it.id == src.id }
            if (idx >= 0) list[idx] = src else list.add(src)
        }
        saveAll(list)
        todos.maxOfOrNull { it.id }?.let { maxId ->
            val cur = prefs().getLong(KEY_NEXT_ID, 100_000L)
            if (cur <= maxId) prefs().edit().putLong(KEY_NEXT_ID, maxId + 1).apply()
        }
    }

    // ---------- 持久化 ----------

    private fun saveAll(list: List<Todo>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs().edit().putString(KEY_TODOS, arr.toString()).apply()
    }

    private fun loadAll(): MutableList<Todo> {
        val raw = prefs().getString(KEY_TODOS, null) ?: return mutableListOf()
        val list = mutableListOf<Todo>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(Todo.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
            // 数据损坏时返回空列表，避免崩溃
        }
        return list
    }
}
