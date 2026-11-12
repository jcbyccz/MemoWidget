package com.cczsx.notepad.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.cczsx.notepad.R
import com.cczsx.notepad.data.Note
import com.cczsx.notepad.data.NoteStore
import com.cczsx.notepad.data.Todo
import com.cczsx.notepad.data.TodoStore
import com.cczsx.notepad.ui.EditNoteActivity
import com.cczsx.notepad.ui.TodoEditActivity

/**
 * 小部件滚动数据服务：把便签文本按行 / 待办条目按行拆成 ListView 数据。
 * ListView 是 RemoteViews 官方允许的集合控件，滚动由系统原生完成，体验与桌面一致。
 *
 * 便签行（intent extra note_id）与待办行（intent extra todo_id）共用本服务：
 *  - 便签：全文按 \n 拆行，加粗 / 斜体 / 下划线通过 Spannable 下发；
 *  - 待办：题目一行 + 各条目一行（小圆点前缀约 1/3 字号，已完成带删除线，未完成在前）。
 *
 * Provider 每次更新都会给适配器 Intent 换一个唯一的 data URI，宿主据此重建本工厂，
 * 从而每次都能拿到最新内容（extras 不参与 Intent 过滤比较，旧做法会一直复用旧连接）。
 */
class MemoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        MemoWidgetFactory(applicationContext, intent)
}

private class MemoWidgetFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val TAG = "MemoWidget"

    private val widgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
    )
    // Provider 计算好直接下发的便签 id / 待办 id：优先按 id 取数（todo_id >= 0 即待办模式）
    private val noteId = intent.getLongExtra("note_id", -1L)
    private val todoId = intent.getLongExtra("todo_id", -1L)
    // Provider 下发的组件高度（px）：用于垂直对齐时在列表前/后补空白行
    private val hPx = intent.getIntExtra("h_px", 0)
    private val dataUri = intent.dataString

    private var noteLines: List<String> = listOf("点击新建便签")
    private var note: Note? = null
    private var todoLines: List<CharSequence> = emptyList()
    private var todo: Todo? = null

    override fun onCreate() {
        Log.d(TAG, "factory create w=$widgetId note=$noteId todo=$todoId data=$dataUri")
    }

    override fun onDataSetChanged() {
        NoteStore.init(context)
        TodoStore.init(context)
        if (todoId >= 0) {
            note = null
            todo = TodoStore.getById(todoId) ?: TodoStore.todoForWidget(widgetId)
            todoLines = withVAlignPadding(todo, buildTodoLines(todo), todo?.fontSize ?: 14)
            Log.d(TAG, "dataset w=$widgetId todo=${todo?.id} lines=${todoLines.size}")
        } else {
            todo = null
            note = if (noteId >= 0) NoteStore.getById(noteId) else NoteStore.noteForWidget(widgetId)
            val text = note?.let { buildDisplayText(it) } ?: ""
            noteLines = if (text.isBlank()) {
                listOf("点击新建便签")
            } else {
                // 空行用单空格占位：否则行高塌成 0，段落间距丢失
                withVAlignPadding(note, text.split("\n").map { if (it.isEmpty()) " " else it }, note?.fontSize ?: 16)
            }
            Log.d(
                TAG, "dataset w=$widgetId note=${note?.id} title=${note?.title} lines=${noteLines.size}"
            )
        }
    }

    override fun getCount(): Int = if (todoId >= 0) todoLines.size else noteLines.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.item_widget_line)

        // 待办行：题目 / 条目（Spannable 已含小圆点、删除线、加粗斜体）
        val t = todo
        if (t != null) {
            rv.setTextViewText(R.id.widget_line_text, todoLines[position])
            rv.setTextViewTextSize(
                R.id.widget_line_text, TypedValue.COMPLEX_UNIT_SP, t.fontSize.toFloat()
            )
            rv.setTextColor(R.id.widget_line_text, t.effectiveFontColor)
            rv.setInt(
                R.id.widget_line_text, "setPaintFlags",
                if (t.underline) Paint.UNDERLINE_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
                else Paint.ANTI_ALIAS_FLAG
            )
            rv.setInt(
                R.id.widget_line_text, "setGravity",
                when (t.alignH) {
                    1 -> Gravity.CENTER_HORIZONTAL
                    2 -> Gravity.RIGHT
                    else -> Gravity.LEFT
                }
            )
            val fill = Intent()
            fill.putExtra(TodoEditActivity.EXTRA_ID, t.id)
            fill.putExtra(TodoEditActivity.EXTRA_FROM_WIDGET, true)
            rv.setOnClickFillInIntent(R.id.widget_line_text, fill)
            return rv
        }

        val n = note
        if (n == null) {
            rv.setTextViewText(R.id.widget_line_text, noteLines[position])
            rv.setTextViewTextSize(
                R.id.widget_line_text, TypedValue.COMPLEX_UNIT_SP, 14f
            )
            rv.setTextColor(R.id.widget_line_text, Color.GRAY)
            return rv
        }

        // 便签行：文字包成 Spannable，加粗 / 斜体 / 下划线随 CharSequence 一起下发
        rv.setTextViewText(
            R.id.widget_line_text, MemoWidgetProvider.styledText(noteLines[position], n)
        )
        rv.setTextViewTextSize(
            R.id.widget_line_text, TypedValue.COMPLEX_UNIT_SP, n.fontSize.toFloat()
        )
        rv.setTextColor(R.id.widget_line_text, n.effectiveFontColor)
        rv.setInt(
            R.id.widget_line_text, "setPaintFlags",
            if (n.underline) Paint.UNDERLINE_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
            else Paint.ANTI_ALIAS_FLAG
        )
        val h = when (n.alignH) {
            1 -> Gravity.CENTER_HORIZONTAL
            2 -> Gravity.RIGHT
            else -> Gravity.LEFT
        }
        rv.setInt(R.id.widget_line_text, "setGravity", h)

        // 行点击 → 打开编辑页（模板 PendingIntent 由 Provider 设置）
        val fill = Intent()
        fill.putExtra(EditNoteActivity.EXTRA_ID, n.id)
        fill.putExtra(EditNoteActivity.EXTRA_FROM_WIDGET, true)
        rv.setOnClickFillInIntent(R.id.widget_line_text, fill)
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true

    override fun onDestroy() {
        Log.d(TAG, "factory destroy w=$widgetId data=$dataUri")
    }

    private fun buildDisplayText(note: Note): String {
        val t = note.title.trim()
        val c = note.content.trim()
        return when {
            t.isBlank() && c.isBlank() -> ""
            t.isBlank() -> c
            c.isBlank() -> t
            else -> "$t\n$c"
        }
    }

    /** 滚动模式的垂直对齐：内容不足一屏时按 alignV 在列表前/后补空行（空白行与真实行同字号同高）；
     *  内容超出一屏时不补行，滚动不受影响。alignV：0=顶 1=中 2=底 */
    private fun <T> withVAlignPadding(
        src: Any?, lines: List<T>, fontSizeSp: Int
    ): List<T> {
        if (hPx <= 0 || src == null || lines.isEmpty()) return lines
        val alignV = when (src) {
            is Todo -> src.alignV
            is Note -> src.alignV
            else -> return lines
        }
        if (alignV == 0) return lines // 顶对齐：ListView 默认从顶开始，无需补行
        val density = context.resources.displayMetrics.density
        val rowPx = fontSizeSp * density * 1.45f // 行高估算：字号×字距系数
        if (rowPx <= 0f) return lines
        val avail = (hPx - 16 * density).toInt().coerceAtLeast(0) // 减去 ListView 上下 8dp padding
        val capacity = (avail / rowPx).toInt()
        val pad = (capacity - lines.size).coerceAtLeast(0)
        if (pad == 0) return lines
        @Suppress("UNCHECKED_CAST")
        val blank = " " as T
        return when (alignV) {
            2 -> { // 底对齐：头部补满，内容沉底
                List(pad) { blank } + lines
            }
            else -> { // 居中：前后各补一半
                val front = pad / 2
                List(front) { blank } + lines + List(pad - front) { blank }
            }
        }
    }

    /** 待办行数据：题目一行 + 条目逐行（未完成在前、已完成在后，组内保持原顺序；
     *  齿轮菜单设置了隐藏已完成时，已完成条目不进列表） */
    private fun buildTodoLines(t: Todo?): List<CharSequence> {
        if (t == null) return listOf("点击选择待办")
        val style = when {
            t.bold && t.italic -> Typeface.BOLD_ITALIC
            t.bold -> Typeface.BOLD
            t.italic -> Typeface.ITALIC
            else -> 0
        }
        val out = mutableListOf<CharSequence>()
        out.add(todoRow(t.title.trim().ifBlank { "待办" }, hasDot = false, done = false, style))
        t.items.filter { it.text.isNotBlank() && !(t.hideDone && it.done) }
            .sortedBy { it.done }
            .forEach { out.add(todoRow(it.text.trim(), hasDot = true, done = it.done, style)) }
        return out
    }

    /** 单行组装：“•”小圆点前缀（天然垂直居中、约 1/3 字高）+ 文本；已完成加删除线；加粗/斜体整行生效 */
    private fun todoRow(text: String, hasDot: Boolean, done: Boolean, style: Int): CharSequence {
        val ssb = SpannableStringBuilder()
        if (hasDot) {
            ssb.append("• ")
        }
        val t0 = ssb.length
        ssb.append(text)
        if (done && text.isNotEmpty()) {
            ssb.setSpan(StrikethroughSpan(), t0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style != 0 && ssb.isNotEmpty()) {
            ssb.setSpan(StyleSpan(style), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return ssb
    }
}
