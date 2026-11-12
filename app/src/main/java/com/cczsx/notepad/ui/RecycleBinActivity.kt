package com.cczsx.notepad.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cczsx.notepad.R
import com.cczsx.notepad.data.Note
import com.cczsx.notepad.data.NoteStore
import com.cczsx.notepad.data.Todo
import com.cczsx.notepad.data.TodoStore
import com.cczsx.notepad.widget.MemoWidgetProvider

/**
 * 回收站：查看已删除的便签与待办，支持恢复、彻底删除与一键清空。
 */
class RecycleBinActivity : AppCompatActivity() {

    /** 行：便签或待办（二选一） */
    private class Row(val note: Note?, val todo: Todo?)

    private lateinit var adapter: TrashAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recycle)
        NoteStore.init(this)
        TodoStore.init(this)
        ThemePrefs.apply(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = "回收站"
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.rv_trash)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = TrashAdapter(object : TrashAdapter.Callbacks {
            override fun onRestore(row: Row) {
                row.todo?.let { TodoStore.restore(it.id) } ?: NoteStore.restore(row.note!!.id)
                reload()
            }
            override fun onDelete(row: Row) {
                row.todo?.let { TodoStore.permanentDelete(it.id) } ?: NoteStore.permanentDelete(row.note!!.id)
                reload()
            }
        })
        rv.adapter = adapter
        tvEmpty = findViewById(R.id.tv_empty)

        findViewById<ImageView>(R.id.btn_empty).setOnClickListener {
            NoteStore.emptyTrash()
            TodoStore.emptyTrash()
            MemoWidgetProvider.refreshAll(this)
            reload()
            Toast.makeText(this, "回收站已清空", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val notes = NoteStore.deletedNotes()
        val todos = TodoStore.deletedTodos()
        val rows = notes.map { Row(it, null) } + todos.map { Row(null, it) }
        adapter.submit(rows)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private class TrashAdapter(private val cb: Callbacks) : RecyclerView.Adapter<TrashAdapter.Holder>() {

        interface Callbacks {
            fun onRestore(row: Row)
            fun onDelete(row: Row)
        }

        private val items = mutableListOf<Row>()

        fun submit(list: List<Row>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trash, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        override fun getItemCount() = items.size

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            private val content: TextView = v.findViewById(R.id.item_content)
            private val btnRestore: ImageView = v.findViewById(R.id.btn_restore)
            private val btnDelete: ImageView = v.findViewById(R.id.btn_delete)

            fun bind(row: Row) {
                val todo = row.todo
                val note = row.note
                content.text = if (todo != null) {
                    val t = todo.title.trim()
                    "［待办］" + (t.ifBlank { "无题目" })
                } else {
                    val t = note!!.title.trim()
                    val c = note.content.trim()
                    when {
                        t.isBlank() && c.isBlank() -> "空便签"
                        t.isBlank() -> c
                        c.isBlank() -> t
                        else -> "$t\n$c"
                    }
                }
                btnRestore.setOnClickListener { cb.onRestore(row) }
                btnDelete.setOnClickListener { cb.onDelete(row) }
            }
        }
    }
}
