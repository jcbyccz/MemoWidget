package com.cczsx.notepad.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份管理：
 * - 自动备份：按设置间隔，应用启动时检查并写入应用内 backups/ 目录（只保留最近 10 份）
 * - 手动导出/导入：JSON 快照（全部便签 + 待办，含回收站软删除项与小部件绑定）
 * - 恢复语义：按 id 合并（同号覆盖、缺失新增），不删除现有其他数据
 */
object BackupManager {

    /** 可选备份间隔（天） */
    val INTERVALS = intArrayOf(1, 3, 7, 30, 999)
    private const val DEFAULT_INTERVAL = 1
    private const val KEEP = 10

    private const val PREFS = "backup_prefs"
    private const val KEY_INTERVAL = "interval_days"
    private const val KEY_LAST = "last_auto_time"

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun backupDir(): File = File(appContext.filesDir, "backups").apply { mkdirs() }

    // ---------- 间隔设置 ----------

    fun intervalDays(): Int = prefs().getInt(KEY_INTERVAL, DEFAULT_INTERVAL)

    fun setIntervalDays(days: Int) {
        prefs().edit().putInt(KEY_INTERVAL, days).apply()
    }

    // ---------- 快照 / 导入 ----------

    /** 生成完整快照 JSON 文本 */
    fun snapshotJson(): String {
        val notes = NoteStore.allRecords()
        val todos = TodoStore.allRecords()
        val a = JSONArray().also { arr -> notes.forEach { arr.put(it.toJson()) } }
        val b = JSONArray().also { arr -> todos.forEach { arr.put(it.toJson()) } }
        return JSONObject()
            .put("app", "正闲拾笺")
            .put("backupAt", System.currentTimeMillis())
            .put("noteCount", notes.size)
            .put("todoCount", todos.size)
            .put("notes", a)
            .put("todos", b)
            .toString()
    }

    /**
     * 合并导入快照。返回导入条数（notes+todos），解析失败返回 -1。
     * 同 id 覆盖、新 id 新增；软删除状态与小部件绑定随快照还原。
     */
    fun mergeImport(json: String): Int {
        return try {
            val o = JSONObject(json)
            val notes = o.optJSONArray("notes") ?: JSONArray()
            val todos = o.optJSONArray("todos") ?: JSONArray()
            val noteList = (0 until notes.length()).map { Note.fromJson(notes.getJSONObject(it)) }
            val todoList = (0 until todos.length()).map { Todo.fromJson(todos.getJSONObject(it)) }
            NoteStore.mergeRecords(noteList)
            TodoStore.mergeRecords(todoList)
            noteList.size + todoList.size
        } catch (_: Exception) {
            -1
        }
    }

    // ---------- 自动备份 ----------

    /** 应用启动时调用：距上次备份达到设定间隔才执行，备份后同步记录时间并裁剪旧备份 */
    fun maybeAutoBackup(context: Context) {
        init(context)
        val interval = intervalDays()
        val last = prefs().getLong(KEY_LAST, 0L)
        val now = System.currentTimeMillis()
        if (now - last < interval * 86_400_000L) return
        writeBackupFile()
        prefs().edit().putLong(KEY_LAST, now).apply()
    }

    /** 写入一份自动备份文件（文件名含时间戳，按名字排序即按时间排序） */
    private fun writeBackupFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val f = File(backupDir(), "auto_$stamp.json")
        f.writeText(snapshotJson())
        trimOldBackups()
        return f
    }

    /** 只保留最近 KEEP 份自动备份 */
    private fun trimOldBackups() {
        backupDir().listFiles()
            ?.filter { it.name.startsWith("auto_") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    /** 最近一次自动备份的时间（无备份返回 0） */
    fun latestBackupTime(): Long {
        val f = latestBackupFile() ?: return 0L
        return try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .parse(f.name.removePrefix("auto_").removeSuffix(".json"))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun latestBackupFile(): File? =
        backupDir().listFiles()
            ?.filter { it.name.startsWith("auto_") }
            ?.maxByOrNull { it.name }

    /**
     * 从最近一次自动备份恢复（合并导入）。
     * 返回导入条数，>0 成功；0 = 没有可用备份；-1 = 文件损坏。
     */
    fun restoreLatestBackup(): Int {
        val f = latestBackupFile() ?: return 0
        return mergeImport(f.readText())
    }
}
