package com.cczsx.notepad.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cczsx.notepad.R
import com.cczsx.notepad.data.Note
import com.cczsx.notepad.data.NoteStore
import com.cczsx.notepad.data.Todo
import com.cczsx.notepad.data.TodoStore
import com.cczsx.notepad.ui.EditNoteActivity

/**
 * 桌面小部件配置页：选择一个便签或待办绑定到当前小部件。
 * 勾选“设置为默认样式”可把所选对象的样式存为后续新建便签的默认样式。
 */
class MemoWidgetConfigActivity : AppCompatActivity() {

    /** 列表行：便签或待办（二选一） */
    private class Row(val note: Note?, val todo: Todo?)

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var resultValue = RESULT_CANCELED
    private lateinit var adapter: ConfigAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_config)
        NoteStore.init(this)
        TodoStore.init(this)

        appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(RESULT_CANCELED)
        findViewById<android.view.View>(R.id.btn_cancel).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.note_list)
        rv.layoutManager = LinearLayoutManager(this)
        val rows = NoteStore.getActive().map { Row(it, null) } +
            TodoStore.activeTodos().map { Row(null, it) }
        adapter = ConfigAdapter(rows) { row ->
            row.todo?.let { bindTodoAndFinish(it) } ?: bindAndFinish(row.note!!)
        }
        rv.adapter = adapter

        findViewById<android.view.View>(R.id.btn_new_note).setOnClickListener {
            val note = NoteStore.create()
            bindAndFinish(note)
        }
    }

    private fun bindAndFinish(note: Note) {
        NoteStore.bindToWidget(note.id, appWidgetId)
        if (findViewById<CheckBox>(R.id.cb_default).isChecked) {
            NoteStore.saveDefaultStyle(note)
        }
        refreshAfterBind()
        resultValue = RESULT_OK
        setResult(resultValue, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    private fun bindTodoAndFinish(todo: Todo) {
        TodoStore.bindToWidget(todo.id, appWidgetId)
        if (findViewById<CheckBox>(R.id.cb_default).isChecked) {
            NoteStore.saveDefaultStyle(todo.styleNote())
        }
        refreshAfterBind()
        resultValue = RESULT_OK
        setResult(resultValue, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    /** 绑定后立即 + 延迟双通道刷新小部件（覆盖 ROM 桌面缓存时序问题） */
    private fun refreshAfterBind() {
        MemoWidgetProvider.refreshAll(this)
        val appCtx = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            MemoWidgetProvider.refreshAll(appCtx)
        }, 400L)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val update = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = ComponentName(appCtx, MemoWidgetProvider::class.java)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            appCtx.sendBroadcast(update)
        }, 900L)
    }

    override fun finish() {
        val data = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(resultValue, data)
        super.finish()
    }

    /** 选择列表适配器：便签与待办合并渲染 */
    private class ConfigAdapter(
        private val items: List<Row>,
        private val onClick: (Row) -> Unit
    ) : RecyclerView.Adapter<PickHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PickHolder {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_widget_pick, parent, false)
            return PickHolder(v)
        }

        override fun onBindViewHolder(holder: PickHolder, position: Int) {
            val row = items[position]
            val todo = row.todo
            val note = row.note
            val tv = holder.itemView.findViewById<android.widget.TextView>(R.id.item_text)
            val preview = holder.itemView.findViewById<android.view.View>(R.id.item_preview)
            tv.text = if (todo != null) {
                "☑ " + todo.title.trim().ifBlank { "(待办)" }
            } else {
                val t = note!!.title.trim()
                val c = note.content.trim()
                when {
                    t.isBlank() && c.isBlank() -> "(空便签)"
                    t.isBlank() -> c
                    else -> t
                }
            }
            // 预览色块（圆角 + 边框，与卡片渲染一致；待办用其样式字段）
            val styleNote = if (todo != null) todo.styleNote() else note!!
            com.cczsx.notepad.ui.TextStyler.applyBackground(preview, styleNote)
            holder.itemView.setOnClickListener { onClick(row) }
        }

        override fun getItemCount() = items.size
    }
}

/** 选择列表的简单 ViewHolder */
private class PickHolder(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)
