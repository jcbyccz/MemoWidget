package com.cczsx.notepad.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.cczsx.notepad.R
import com.cczsx.notepad.data.Note
import com.cczsx.notepad.data.NoteStore
import com.cczsx.notepad.data.Todo
import com.cczsx.notepad.data.TodoStore

/**
 * 桌面文字小部件。背景采用「位图画布」方案：应用内用 Canvas 绘制圆角背景 + 细描边环，
 * 再通过 setImageViewBitmap 传入桌面，只使用 RemoteViews 白名单内的基础动作，
 * 在 Android 12+ 的严格校验下稳定；背景全透明时壁纸透出、描边环独立渲染。
 *
 * 内容展示有两条通道，共用同一个布局（宿主不必换布局重建视图）：
 *  - 普通便签：静态文字层，视口放不下时逐级缩小字号，最小字号仍放不下才截断；
 *  - 滚动便签：ListView 原生滚动（RemoteViews 官方允许的集合控件），数据由 MemoWidgetService 供数。
 *
 * 加粗 / 斜体 / 下划线的实现：RemoteViews 无法调用 setTypeface（要传 Typeface 对象），
 * MIUI 的 HomeMIUIWidgetTextView 也不放行 setTextAppearance；但 setTextViewText 接收的是
 * CharSequence，Spannable 的 StyleSpan / UnderlineSpan 会随 Parcel 一起传到宿主并生效。
 * 因此把文字包成 Spannable 下发，字形由此保证。
 *
 * 适配器 Intent 的 data 每次更新都唯一：宿主的 RemoteViewsAdapter 按 Intent.filterEquals
 * 判定是否复用连接，过滤条件只比对 action/data/type/component/categories、**不比对 extras**，
 * 所以仅靠时间戳 extra 会一直复用旧连接（表现为绑定后内容不刷新），唯一化 data 才能重建工厂。
 */
class MemoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        NoteStore.init(context)
        TodoStore.init(context)
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        NoteStore.init(context)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        NoteStore.init(context)
        TodoStore.init(context)
        appWidgetIds.forEach {
            NoteStore.unbindWidget(it)
            TodoStore.unbindWidget(it)
        }
    }

    companion object {
        private const val TAG = "MemoWidget"

        /** 适配器 Intent 唯一化序号：保证同毫秒内也不会重复 */
        private var adapterSeq = 0L

        /** 公开：把颜色与透明度(0..1)合成为带 alpha 的颜色（与颜色自身 alpha 相乘，支持半透明自定义色） */
        fun alphaColor(color: Int, alpha: Float): Int =
            applyAlpha(color, (alpha.coerceIn(0f, 1f) * 255).toInt())

/**
         * 把便签字形（加粗 / 斜体）包成 Spannable。
         * RemoteViews 只能通过 Spannable 把字形带到宿主，详见类注释。
         * 下划线仍走 setPaintFlags（该通道已验证可用），不在这里重复。
         */
        fun styledText(text: String, note: Note): CharSequence {
            if (!note.bold && !note.italic) return text
            val sp = SpannableString(text)
            val style = when {
                note.bold && note.italic -> Typeface.BOLD_ITALIC
                note.bold -> Typeface.BOLD
                else -> Typeface.ITALIC
            }
            if (text.isNotEmpty()) {
                sp.setSpan(StyleSpan(style), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sp
        }

        fun refreshAll(context: Context) {
            // 用 applicationContext：允许配置页/编辑页在 finish 后延迟补刷时仍安全
            val ctx = context.applicationContext
            NoteStore.init(ctx)
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MemoWidgetProvider::class.java))
            ids.forEach { updateWidget(ctx, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            NoteStore.init(context)
            TodoStore.init(context)
            val note = NoteStore.noteForWidget(widgetId)
            val todo = if (note == null) TodoStore.todoForWidget(widgetId) else null
            // 样式对象：便签直接用，待办转成同构 Note 视图（背景/边框/字号/对齐等字段一致）
            val style = note ?: todo?.styleNote()
            val opts = manager.getAppWidgetOptions(widgetId)
            // 尺寸取横竖两方向的较大值（dp），转像素后绘制位图，避免拉伸模糊
            val density = context.resources.displayMetrics.density
            val wDp = maxOf(
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180),
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 180)
            )
            val hDp = maxOf(
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110),
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            )
            val wPx = (wDp * density).toInt().coerceIn(40, 1600)
            val hPx = (hDp * density).toInt().coerceIn(40, 1600)
            val bitmap = renderBitmap(context, style, wPx, hPx)

            val views = RemoteViews(context.packageName, R.layout.widget_memo)
            views.setImageViewBitmap(R.id.widget_canvas, bitmap)

            if (style == null) {
                // 未绑定便签：占位提示，点击进入“选择便签”配置页（适配 MIUI 静默添加不弹配置页的场景）
                views.setViewVisibility(R.id.widget_list, View.GONE)
                views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                views.setTextViewText(R.id.widget_text, "点击选择便签")
                views.setTextViewTextSize(
                    R.id.widget_text, android.util.TypedValue.COMPLEX_UNIT_SP, 14f
                )
                views.setTextColor(R.id.widget_text, Color.GRAY)
                views.setOnClickPendingIntent(R.id.widget_root, openConfig(context, widgetId))
                manager.updateAppWidget(widgetId, views)
                Log.d(TAG, "update w=$widgetId unbound")
                return
            }

            if (note != null) {
                updateNoteWidget(context, manager, widgetId, note, views, wPx, hPx, density, hDp)
            } else if (todo != null) {
                updateTodoWidget(context, manager, widgetId, todo, views, wPx, hPx, density, hDp)
            }
        }

        /** 待办小部件：开启“内容超出后滚动显示”时走 ListView 原生滚动通道（与便签滚动同机制）；
         *  否则静态文字层渲染题目 + 条目（圆点前缀，已完成带删除线），字号按视口自适应 */
        private fun updateTodoWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            todo: Todo,
            views: RemoteViews,
            wPx: Int,
            hPx: Int,
            density: Float,
            hDp: Int
        ) {
            if (todo.scrollEnabled) {
                // 滚动待办：隐藏静态文字层，ListView 逐行展示题目 + 条目，宿主原生滚动
                views.setViewVisibility(R.id.widget_text, View.GONE)
                views.setViewVisibility(R.id.widget_list, View.VISIBLE)
                val seq = ++adapterSeq
                val svcIntent = android.content.Intent(context, MemoWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra("todo_id", todo.id)
                    // 组件高度：供数据端计算垂直对齐填充行（内容不足一屏时按 alignV 对齐）
                    .putExtra("h_px", hPx)
                    // data 参与 Intent.filterEquals：唯一化才能让宿主重建数据连接、拿到最新内容
                    .setData(Uri.parse("todo://widget/$widgetId/${System.currentTimeMillis()}-$seq"))
                views.setRemoteAdapter(R.id.widget_list, svcIntent)
                // 模板意图必须 FLAG_MUTABLE：Android 12+ 要求集合控件的行项 fill-in 依赖可变 PendingIntent
                views.setPendingIntentTemplate(
                    R.id.widget_list, openTodoEditor(context, widgetId, todo.id, mutable = true)
                )
                // 根布局兑底：点击行间空白同样打开待办
                views.setOnClickPendingIntent(R.id.widget_root, openTodoEditor(context, widgetId, todo.id))
                manager.updateAppWidget(widgetId, views)
                manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
                Log.d(TAG, "update w=$widgetId todo=${todo.id} scroll=true uri=${svcIntent.data}")
                return
            }
            val startSp = if (hDp in 1..240) {
                (hDp / 5f).coerceIn(9f, todo.fontSize.toFloat())
            } else {
                todo.fontSize.toFloat()
            }
            val fit = fitTodoText(todo, wPx, hPx, density, startSp)
            views.setViewVisibility(R.id.widget_list, View.GONE)
            views.setViewVisibility(R.id.widget_text, View.VISIBLE)
            views.setTextViewText(R.id.widget_text, fit.second)
            views.setTextViewTextSize(
                R.id.widget_text, android.util.TypedValue.COMPLEX_UNIT_SP, fit.first
            )
            views.setTextColor(R.id.widget_text, todo.effectiveFontColor)
            views.setInt(
                R.id.widget_text, "setPaintFlags",
                if (todo.underline) Paint.UNDERLINE_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
                else Paint.ANTI_ALIAS_FLAG
            )
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
            views.setInt(R.id.widget_text, "setGravity", h or v)
            views.setOnClickPendingIntent(
                R.id.widget_root, openTodoEditor(context, widgetId, todo.id)
            )
            manager.updateAppWidget(widgetId, views)
            Log.d(TAG, "update w=$widgetId todo=${todo.id} sp=${fit.first}")
        }

        /** 待办展示文本：题目 + 条目行（圆点前缀），容量不够时逐条截断并加省略号。
         *  返回 (字号, Spannable)：已完成条目文字加删除线。 */
        private fun fitTodoText(
            todo: Todo, wPx: Int, hPx: Int, density: Float, startSp: Float
        ): Pair<Float, CharSequence> {
            // 逻辑行：题目 + 各条目（条目带圆点前缀，渲染时圆点缩小到约 1/3 字号）
            data class TodoLine(val text: String, val done: Boolean, val dot: Boolean = false)
            val title = todo.title.trim().ifBlank { "待办" }
            val items = todo.items.filter { it.text.isNotBlank() }
            val lines = mutableListOf<TodoLine>(TodoLine(title, false))
            // 与编辑页分区一致：未完成条目在前、已完成在后（组内保持原顺序）
            items.sortedBy { it.done }.forEach { lines.add(TodoLine(it.text.trim(), it.done, dot = true)) }
            // 宽度估算仍按“● + 文本”整行计（略保守：圆点缩小后实际更窄，不会溢出）
            fun measure(line: TodoLine) = if (line.dot) "● " + line.text else line.text

            var fs = startSp
            fun wrappedCount(f: Float) = lines.sumOf { wrapLines(measure(it), wPx, density, f).size }
            while (wrappedCount(fs) > capacityLines(hPx, density, fs) && fs > 9f) {
                fs = (fs - 1f).coerceAtLeast(9f)
            }
            val cap = capacityLines(hPx, density, fs)
            val shown = mutableListOf<TodoLine>()
            var used = 0
            var truncated = false
            for (line in lines) {
                val w = wrapLines(measure(line), wPx, density, fs).size
                if (used + w > cap) { truncated = true; break }
                shown.add(line); used += w
            }

            val ssb = android.text.SpannableStringBuilder()
            shown.forEachIndexed { i, line ->
                if (i > 0) ssb.append("\n")
                if (line.dot) {
                    // “•”字形：天然垂直居中的小圆点（约 1/3 字高），无需缩放 span
                    ssb.append("• ")
                }
                val start = ssb.length
                ssb.append(line.text)
                if (line.done && line.text.isNotEmpty()) {
                    ssb.setSpan(
                        android.text.style.StrikethroughSpan(), start, ssb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            if (truncated) {
                if (ssb.isNotEmpty()) ssb.append("\n")
                ssb.append("…")
            }
            return fs to ssb
        }

        /** 打开待办编辑页的 PendingIntent（从小部件进入，保存后直接回桌面）。
         *  mutable=true 供 ListView 模板意图：Android 12+ 要求 fill-in 必须可变；
         *  与不可变意图使用不同 requestCode，避免同键不同可变性的系统缓存冲突。 */
        private fun openTodoEditor(
            context: Context, widgetId: Int, todoId: Long, mutable: Boolean = false
        ): android.app.PendingIntent {
            val requestCode = if (mutable) widgetId + 3_100_000 else widgetId + 3_000_000
            val flag = if (mutable) android.app.PendingIntent.FLAG_MUTABLE
            else android.app.PendingIntent.FLAG_IMMUTABLE
            return android.app.PendingIntent.getActivity(
                context, requestCode,
                android.content.Intent(context, com.cczsx.notepad.ui.TodoEditActivity::class.java)
                    .putExtra(com.cczsx.notepad.ui.TodoEditActivity.EXTRA_FROM_WIDGET, true)
                    .apply { if (todoId >= 0) putExtra(com.cczsx.notepad.ui.TodoEditActivity.EXTRA_ID, todoId) }
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or flag
            )
        }

        /** 便签小部件：滚动 ListView / 静态文字层两条通道（原实现逻辑） */
        private fun updateNoteWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            note: Note,
            views: RemoteViews,
            wPx: Int,
            hPx: Int,
            density: Float,
            hDp: Int
        ) {
            if (note.scrollEnabled) {
                // 滚动便签：隐藏静态文字层，ListView 原生滚动展示全文
                views.setViewVisibility(R.id.widget_text, View.GONE)
                views.setViewVisibility(R.id.widget_list, View.VISIBLE)
                val seq = ++adapterSeq
                val svcIntent = android.content.Intent(context, MemoWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra("note_id", note.id)
                    // 组件高度：供数据端计算垂直对齐填充行
                    .putExtra("h_px", hPx)
                    // data 参与 Intent.filterEquals：唯一化才能让宿主重建数据连接、拿到最新内容
                    .setData(Uri.parse("memo://widget/$widgetId/${System.currentTimeMillis()}-$seq"))
                views.setRemoteAdapter(R.id.widget_list, svcIntent)
                // 模板意图必须 FLAG_MUTABLE：Android 12+ 要求集合控件的行项 fill-in 依赖可变 PendingIntent
                views.setPendingIntentTemplate(
                    R.id.widget_list, openEditor(context, widgetId, note.id, mutable = true)
                )
                // 根布局兜底：点击行间空白同样打开便签
                views.setOnClickPendingIntent(R.id.widget_root, openEditor(context, widgetId, note.id))
                manager.updateAppWidget(widgetId, views)
                manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
                Log.d(TAG, "update w=$widgetId note=${note.id} scroll=true uri=${svcIntent.data}")
            } else {
                // 普通便签：静态文字层，字号按组件高度自适应
                val startSp = if (hDp in 1..240) {
                    (hDp / 5f).coerceIn(9f, note.fontSize.toFloat())
                } else {
                    note.fontSize.toFloat()
                }
                val fit = fitDisplayText(note, wPx, hPx, density, startSp)
                views.setViewVisibility(R.id.widget_list, View.GONE)
                views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_text,
                    styledText(
                        if (fit.second.isNotEmpty()) fit.second else buildDisplayText(note),
                        note
                    )
                )
                views.setTextViewTextSize(
                    R.id.widget_text, android.util.TypedValue.COMPLEX_UNIT_SP, fit.first
                )
                views.setTextColor(R.id.widget_text, note.effectiveFontColor)
views.setInt(
                    R.id.widget_text, "setPaintFlags",
                    if (note.underline) Paint.UNDERLINE_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
                    else Paint.ANTI_ALIAS_FLAG
                )
                val h = when (note.alignH) {
                    1 -> Gravity.CENTER_HORIZONTAL
                    2 -> Gravity.RIGHT
                    else -> Gravity.LEFT
                }
                val v = when (note.alignV) {
                    0 -> Gravity.TOP
                    2 -> Gravity.BOTTOM
                    else -> Gravity.CENTER_VERTICAL
                }
                views.setInt(R.id.widget_text, "setGravity", h or v)
                views.setOnClickPendingIntent(R.id.widget_root, openEditor(context, widgetId, note.id))
                manager.updateAppWidget(widgetId, views)
                Log.d(TAG, "update w=$widgetId note=${note.id} scroll=false sp=${fit.first}")
            }
        }

        /** 按行宽预折行（中文为主按 0.7 倍字宽估算；两侧留 10dp 内边距） */
        private fun wrapLines(text: String, wPx: Int, density: Float, fs: Float): List<String> {
            val fontPx = fs * density
            val maxChars = (((wPx - 20 * density) / (fontPx * 0.7f))).toInt().coerceAtLeast(3)
            val out = mutableListOf<String>()
            text.split("\n").forEach { line ->
                if (line.isEmpty()) {
                    out.add("")
                } else {
                    var i = 0
                    while (i < line.length) {
                        out.add(line.substring(i, minOf(i + maxChars, line.length)))
                        i += maxChars
                    }
                }
            }
            return out
        }

        /** 视口可容纳行数：上留 7dp、下留 4dp 内边距（+1dp 余量），行高按字号 1.22 倍
         *  （includeFontPadding=false 后实际行高≈1.17 倍，取 1.22 留安全余量） */
        private fun capacityLines(hPx: Int, density: Float, fs: Float): Int =
            (((hPx - 12 * density) / (fs * density * 1.22f)).toInt()).coerceAtLeast(1)

        /**
         * 视口自适应排版：内容放不下时逐级缩小字号（下限 9sp）尽量多显示；
         * 最小字号仍放不下才截断加省略号。返回 (适配字号, 展示文本)。
         * 需要看全文时把便签设为“滚动”即可用原生滚动。
         */
        private fun fitDisplayText(
            note: Note, wPx: Int, hPx: Int, density: Float, startSp: Float
        ): Pair<Float, String> {
            val text = buildDisplayText(note)
            var fs = startSp
            var wrapped = wrapLines(text, wPx, density, fs)
            // 逐级缩小字号直到放得下或到下限 9sp
            while (wrapped.size > capacityLines(hPx, density, fs) && fs > 9f) {
                fs = (fs - 1f).coerceAtLeast(9f)
                wrapped = wrapLines(text, wPx, density, fs)
            }
            return if (wrapped.size <= capacityLines(hPx, density, fs)) {
                fs to text
            } else {
                val maxLines = capacityLines(hPx, density, fs)
                val shown = wrapped.take(maxLines).toMutableList()
                shown[shown.size - 1] = shown.last().trimEnd() + "…"
                fs to shown.joinToString("\n")
            }
        }

        /**
         * 位图渲染：圆角背景（含透明度，可全透明）+ 细描边环。
         * 全透明背景时位图中间不绘制任何内容，壁纸透出，描边环照常渲染。
         *
         * 背景与边框互不叠加：背景整体内缩一个描边宽度，边框环同心包在背景外侧，
         * 两者边缘恰好相接——半透明背景下圆角处不再出现“边框×背景”的混色暗带。
         */
        private fun renderBitmap(context: Context, note: Note?, wPx: Int, hPx: Int): Bitmap {
            val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val density = context.resources.displayMetrics.density
            val radius = 14f * density
            val stroke = 2f * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // 背景：内缩 stroke，圆角相应减小，保持圆角中心与边框同心
            val fillInset = stroke
            val fillRadius = (radius - stroke).coerceAtLeast(2f * density)
            val effective = note?.let { alphaColor(it.bgColor, it.alpha) } ?: Color.WHITE
            if (Color.alpha(effective) > 0) {
                paint.style = Paint.Style.FILL
                paint.color = effective
                canvas.drawRoundRect(
                    fillInset, fillInset,
                    wPx - fillInset, hPx - fillInset,
                    fillRadius, fillRadius, paint
                )
            }

            val border = note?.borderColor ?: 0
            if (border != 0) {
                // 边框：中心线内缩 stroke/2，圆角 = 背景圆角 + stroke/2（同心），内缘正好贴住背景外缘
                val center = stroke / 2f
                val borderRadius = (radius - stroke / 2f).coerceAtLeast(2f * density)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.color = border
                canvas.drawRoundRect(
                    center, center,
                    wPx - center, hPx - center,
                    borderRadius, borderRadius, paint
                )
            }
            return bmp
        }

        /** 打开编辑页的 PendingIntent（从小部件进入，保存后直接回桌面）。
         *  mutable=true 供 ListView 的模板意图：Android 12+ 要求 fill-in 必须可变；
         *  与不可变意图使用不同 requestCode，避免同键不同可变性的系统缓存冲突。 */
        private fun openEditor(
            context: Context, widgetId: Int, noteId: Long, mutable: Boolean = false
        ): android.app.PendingIntent {
            val requestCode = if (mutable) widgetId + 1_000_000 else widgetId
            val flag = if (mutable) android.app.PendingIntent.FLAG_MUTABLE
            else android.app.PendingIntent.FLAG_IMMUTABLE
            return android.app.PendingIntent.getActivity(
                context, requestCode,
                android.content.Intent(context, com.cczsx.notepad.ui.EditNoteActivity::class.java)
                    .putExtra(com.cczsx.notepad.ui.EditNoteActivity.EXTRA_FROM_WIDGET, true)
                    .apply { if (noteId >= 0) putExtra(com.cczsx.notepad.ui.EditNoteActivity.EXTRA_ID, noteId) }
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or flag
            )
        }

        /** 未绑定小部件的占位点击：打开“选择便签”配置页（带小部件 id） */
        private fun openConfig(context: Context, widgetId: Int): android.app.PendingIntent {
            return android.app.PendingIntent.getActivity(
                context, widgetId + 2_000_000,
                android.content.Intent(context, MemoWidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun applyAlpha(color: Int, alpha: Int): Int {
            val combined = (alpha.coerceIn(0, 255) * Color.alpha(color) / 255).coerceIn(0, 255)
            return Color.argb(combined, Color.red(color), Color.green(color), Color.blue(color))
        }

        /** 小部件展示文本：标题 + 正文 */
        private fun buildDisplayText(note: Note): String {
            val t = note.title.trim()
            val c = note.content.trim()
            return when {
                t.isBlank() && c.isBlank() -> "空便签"
                t.isBlank() -> c
                c.isBlank() -> t
                else -> "$t\n$c"
            }
        }
    }
}