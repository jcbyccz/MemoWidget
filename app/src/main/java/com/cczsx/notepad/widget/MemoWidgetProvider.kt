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
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.cczsx.notepad.R
import com.cczsx.notepad.data.Note
import com.cczsx.notepad.data.NoteStore
import com.cczsx.notepad.data.PhotoStore
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

        /** 补推档位（毫秒）：本机 MIUI Home 在应用处于前台时不落推送，等宿主回到前台才重放，
         *  单次推送很容易“丢”——表现为回到桌面还是旧内容，要拖尺寸（宿主收到 options 变化）
         *  才重新加载。保存后按下面几档补推同一批内容，覆盖“返回桌面”的时序即可自愈。
         *  档位不宜过多：每档都是一次完整位图推送，过密会挤占系统侧位图缓存。 */
        private val REPUSH_DELAYS = longArrayOf(700L, 2200L)

        /** 补推世代号：连续多次 refreshAll（批量删除、连点保存）时，后一次直接作废前一次的补推 */
        private var refreshGen = 0

        private fun handler() = android.os.Handler(android.os.Looper.getMainLooper())

        /** 诊断时间戳：定位“推送了但没生效”的时序问题必需 */
        private fun now(): String =
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())

        /** 从组件选项取宽度（dp，尽量保真浮点）：
         *  MIUI 的 appWidgetMin/MaxWidth 键是截断后的整数（实宽 141.77dp 只剩 141），
         *  但 appWidgetSizes 键里保留了浮点真值（字符串形如 "[141.77237x156.95059]"）。
         *  宿主缩字阈值按浮点宽计算，用浮点做预算基准可少丢 ~2.5px（有时正好差一个字）。
         *  解析失败时退回整数键，完全不影响可用性。 */
        fun optionsWidthDp(opts: Bundle): Float {
            val intW = maxOf(
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            ).toFloat()
            val floatW = try {
                val s = opts.get("appWidgetSizes")?.toString() ?: ""
                Regex("(\\d+(?:\\.\\d+)?)x").findAll(s)
                    .maxOfOrNull { it.groupValues[1].toFloat() } ?: 0f
            } catch (_: Exception) {
                0f
            }
            return maxOf(intW, floatW)
        }

        /** 诊断落盘：本机 MIUI 压制三方应用所有日志级别（logcat 不可见），用文件兑底，
         *  读取：adb shell run-as com.cczsx.notepad cat files/diag_widget.log
         *  校准向导（CalibrationWizardActivity）也走此通道记录调整过程 */
        fun diag(context: Context, msg: String) {
            try {
                val f = java.io.File(context.filesDir, "diag_widget.log")
                // 上限 300KB：防止日志无限增长，超过则重新开始
                if (f.length() > 300 * 1024) f.delete()
                f.appendText("${now()} $msg\n")
            } catch (_: Exception) {
            }
        }

        /** 公开：把颜色与透明度(0..1)合成为带 alpha 的颜色（与颜色自身 alpha 相乘，支持半透明自定义色） */
        fun alphaColor(color: Int, alpha: Float): Int =
            applyAlpha(color, (alpha.coerceIn(0f, 1f) * 255).toInt())

/**
         * 把便签字形（加粗 / 斜体）包成 Spannable。
         * RemoteViews 只能通过 Spannable 把字形带到宿主，详见类注释。
         * 下划线仍走 setPaintFlags（该通道已验证可用），不在这里重复。
         */
        fun styledText(text: String, note: Note): CharSequence {
            // 全角空格渲染定稿（2026-11-19）：\u3000 步进=1em。宿主会剥离度量类 span
            // （ScaleXSpan 实测被丢弃、Style/Underline 存活），无法用 span 改写步进；
            // 改由 ideographicToFiller 把每个 \u3000 换成 1em 隐形填字（CJK 象形字
            // 步进恒 1em，透明 ForegroundColorSpan 隐形——画笔类 span，通道可用）
            val (replaced, fillers) = ideographicToFiller(text)
            if (!note.bold && !note.italic && !note.underline && fillers.isEmpty()) return replaced
            val sp = SpannableString(replaced)
            if (replaced.isNotEmpty()) {
                if (note.bold || note.italic) {
                    val style = when {
                        note.bold && note.italic -> Typeface.BOLD_ITALIC
                        note.bold -> Typeface.BOLD
                        else -> Typeface.ITALIC
                    }
                    sp.setSpan(StyleSpan(style), 0, replaced.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                // 下划线改走 UnderlineSpan（与加粗/斜体同一 Spannable 通道）：
                // 此前走 setPaintFlags，宿主(MIUI)会在绘制前重置画笔标志导致下划线消失
                if (note.underline) {
                    sp.setSpan(
                        android.text.style.UnderlineSpan(), 0, replaced.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                fillers.forEach { p ->
                    sp.setSpan(
                        ForegroundColorSpan(0), p, p + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            return sp
        }

        /** 数据是否有变更、桌面可能还没拿到：应用退到后台时据此决定是否补推 */
        private var dirty = false

        fun refreshAll(context: Context) {
            // 用 applicationContext：允许配置页/编辑页在 finish 后延迟补刷时仍安全
            val ctx = context.applicationContext
            NoteStore.init(ctx)
            TodoStore.init(ctx)
            dirty = true
            val gen = ++refreshGen
            val built = pushAll(ctx, "save")
            if (built.isEmpty()) return
            // 延迟补推同一批 RemoteViews：宿主在前台/切页过程中常把推送吞掉，
            // 单次推送会表现为“回到桌面还是旧内容”。补推不重建（不换适配器 URI，
            // 集合控件不会闪一下或跳回顶部），只让宿主再应用一次。
            REPUSH_DELAYS.forEach { delay ->
                handler().postDelayed({
                    if (gen == refreshGen) repush(ctx, built, "repush+$delay")
                }, delay)
            }
        }

        /**
         * 应用整体退到后台（用户要回桌面了）时调用：
         * 宿主这时才处于可见状态，推送最容易生效；只在本进程内有过变更时补推一次，
         * 避免每次切后台都重建位图。
         */
        fun onAppBackground(context: Context) {
            if (!dirty) return
            dirty = false
            val ctx = context.applicationContext
            NoteStore.init(ctx)
            TodoStore.init(ctx)
            pushAll(ctx, "bg")
        }

        /** 重建并推送所有已创建的小部件，返回本次下发的 RemoteViews（供补推复用） */
        private fun pushAll(ctx: Context, tag: String): Map<Int, RemoteViews> {
            val built = LinkedHashMap<Int, RemoteViews>()
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MemoWidgetProvider::class.java))
            if (ids.isEmpty()) return built
            diag(ctx, "push[$tag] n=${ids.size}")
            ids.forEach { id ->
                try {
                    updateWidget(ctx, manager, id)?.let { built[id] = it }
                } catch (e: Exception) {
                    // 单个小部件出错不能拖累其他小部件（例如照片文件损坏）
                    diag(ctx, "push[$tag] w=$id exc=${e.javaClass.simpleName}:${e.message}")
                }
            }
            return built
        }

        /** 补推：原样再下发一次已构建好的 RemoteViews（不重建、不换适配器 URI） */
        private fun repush(ctx: Context, built: Map<Int, RemoteViews>, tag: String) {
            val manager = AppWidgetManager.getInstance(ctx)
            var ok = 0
            built.forEach { (id, views) ->
                try {
                    manager.updateAppWidget(id, views)
                    ok++
                } catch (e: Exception) {
                    diag(ctx, "push[$tag] w=$id exc=${e.javaClass.simpleName}:${e.message}")
                }
            }
            diag(ctx, "push[$tag] n=$ok")
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ): RemoteViews? {
            NoteStore.init(context)
            TodoStore.init(context)
            val note = NoteStore.noteForWidget(widgetId)
            val todo = if (note == null) TodoStore.todoForWidget(widgetId) else null
            // 样式对象：便签直接用，待办转成同构 Note 视图（背景/边框/字号/对齐等字段一致）
            val style = note ?: todo?.styleNote()
            val opts = manager.getAppWidgetOptions(widgetId)
            // 尺寸取横竖两方向的较大值（dp），转像素后绘制位图，避免拉伸模糊
            val density = context.resources.displayMetrics.density
            val wDp = optionsWidthDp(opts).takeIf { it > 0f } ?: 180f
            val hDp = maxOf(
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110),
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            )
            val wPx = (wDp * density).toInt().coerceIn(40, 1600)
            val hPx = (hDp * density).toInt().coerceIn(40, 1600)
            // 诊断日志用 Log.e：本机 MIUI 对三方应用压制 Log.d，logcat 完全不可见
            Log.e(
                TAG,
                "upd w=$widgetId wDp=$wDp hDp=$hDp wPx=$wPx hPx=$hPx note=${note?.id} todo=${todo?.id} wrap=${(wPx - 4 * density).toInt()}"
            )
            diag(
                context,
                "upd w=$widgetId wDp=$wDp hDp=$hDp wPx=$wPx hPx=$hPx note=${note?.id} todo=${todo?.id} wrap=${(wPx - 4 * density).toInt()}"
            )
            val bitmap = renderBitmap(context, style, wPx, hPx)

            val views = RemoteViews(context.packageName, R.layout.widget_memo)
            views.setImageViewBitmap(R.id.widget_canvas, bitmap)

            if (style == null) {
                // 未绑定便签：占位提示，点击进入“选择便签”配置页（适配 MIUI 静默添加不弹配置页的场景）
                hideAllLists(views)
                val textId = showTextLayer(views, shadow = false)
                views.setTextViewText(textId, "点击选择便签")
                views.setTextViewTextSize(
                    textId, android.util.TypedValue.COMPLEX_UNIT_SP, 14f
                )
                views.setTextColor(textId, Color.GRAY)
                views.setOnClickPendingIntent(R.id.widget_root, openConfig(context, widgetId))
                manager.updateAppWidget(widgetId, views)
                Log.e(TAG, "update w=$widgetId unbound")
                return views
            }

            if (note != null) {
                updateNoteWidget(context, manager, widgetId, note, views, wPx, hPx, density, hDp)
            } else if (todo != null) {
                updateTodoWidget(context, manager, widgetId, todo, views, wPx, hPx, density, hDp)
            }
            return views
        }

        /** 滚动列表按垂直对齐方式选择：列表在布局里分别固定了顶/中/底重力，按 alignV 取其一 */
        private fun listIdFor(alignV: Int): Int = when (alignV) {
            2 -> R.id.widget_list_bottom
            1 -> R.id.widget_list_center
            else -> R.id.widget_list_top
        }

        /** 只显示选中的列表，其余隐藏（对齐方式切换后不能残留旧列表） */
        private fun showList(views: RemoteViews, listId: Int) {
            views.setViewVisibility(R.id.widget_list_top, if (listId == R.id.widget_list_top) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_list_center, if (listId == R.id.widget_list_center) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_list_bottom, if (listId == R.id.widget_list_bottom) View.VISIBLE else View.GONE)
        }

        private fun hideAllLists(views: RemoteViews) {
            views.setViewVisibility(R.id.widget_list_top, View.GONE)
            views.setViewVisibility(R.id.widget_list_center, View.GONE)
            views.setViewVisibility(R.id.widget_list_bottom, View.GONE)
        }

        /** 显示静态文字层并按「便签/待办 × 投影开关」选层，返回承载文字的 view id。
         *  投影只能靠布局属性承载（见 widget_memo.xml）；行距同理——待办层带
         *  lineSpacingExtra，便签层不带，四层互斥显示（2026-11-20） */
        private fun showTextLayer(views: RemoteViews, shadow: Boolean, todo: Boolean = false): Int {
            val id = when {
                todo && shadow -> R.id.widget_text_todo_shadow
                todo -> R.id.widget_text_todo
                shadow -> R.id.widget_text_shadow
                else -> R.id.widget_text
            }
            textLayerIds.forEach {
                views.setViewVisibility(it, if (it == id) View.VISIBLE else View.GONE)
            }
            return id
        }

        private fun hideTextLayer(views: RemoteViews) {
            textLayerIds.forEach { views.setViewVisibility(it, View.GONE) }
        }

        private val textLayerIds = intArrayOf(
            R.id.widget_text, R.id.widget_text_shadow,
            R.id.widget_text_todo, R.id.widget_text_todo_shadow
        )

    /** 文字层/列表层的内边距统一设为左 2dp、右 2dp、上 6dp、下 4dp（2026-11-20 用户定稿：
     *  上边距 4dp→6dp，文字整体下移一点更透气；与布局 XML 一致）：
     *  每次都显式设置——setViewPadding 的值会留在宿主视图上，不重设就还原不了
     *  （含旧版残留）。注：列表容器以 XML padding 为准且宿主仅在重新 inflate 时
     *  读取（改后需删掉重加部件）；此处运行时设置对静态文字层生效。 */
    private fun applyTextPadding(views: RemoteViews, viewId: Int, density: Float) {
        views.setViewPadding(
            viewId,
            (2 * density).toInt(), (6 * density).toInt(),
            (2 * density).toInt(), (4 * density).toInt()
        )
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
                // 滚动待办：隐藏静态文字层，ListView 逐行展示题目 + 条目，宿主原生滚动；
                // 按 alignV 选用对应重力的列表，垂直对齐由布局重力精确完成
                val listId = listIdFor(todo.alignV)
                hideTextLayer(views)
                showList(views, listId)
                applyTextPadding(views, listId, density)
                val seq = ++adapterSeq
                val svcIntent = android.content.Intent(context, MemoWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra("todo_id", todo.id)
                    // 列表内容区像素宽（扣容器左右内边距 2dp/2dp 共 4dp）：数据工厂按
                    // “宿主测量宽”预拆长条目，防止 maxLines=1 裁断超宽文字
                    .putExtra("wrap_px", (wPx - 4 * density).toInt())
                    // data 参与 Intent.filterEquals：唯一化才能让宿主重建数据连接、拿到最新内容
                    .setData(Uri.parse("todo://widget/$widgetId/${System.currentTimeMillis()}-$seq"))
                views.setRemoteAdapter(listId, svcIntent)
                // 数据更新后强制列表滚回顶部：宿主会保留旧滚动位置，样式切换后
                // “顶部文字消失”实为列表停在中间（滚动位置被保留）；API 31+ 才有该动作
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    views.setScrollPosition(listId, 0)
                }
                // 模板意图必须 FLAG_MUTABLE：Android 12+ 要求集合控件的行项 fill-in 依赖可变 PendingIntent
                views.setPendingIntentTemplate(
                    listId, openTodoEditor(context, widgetId, todo.id, mutable = true)
                )
                // 根布局兑底：点击行间空白同样打开待办
                views.setOnClickPendingIntent(R.id.widget_root, openTodoEditor(context, widgetId, todo.id))
                manager.updateAppWidget(widgetId, views)
                manager.notifyAppWidgetViewDataChanged(widgetId, listId)
                Log.e(TAG, "update w=$widgetId todo=${todo.id} scroll=true uri=${svcIntent.data}")
                return
            }
            val startSp = if (hDp in 1..240) {
                // 自适应起始字号下限 12sp（2026-11-19 用户定稿），不超过待办自身字号
                (hDp / 5f).coerceIn(12f.coerceAtMost(todo.fontSize.toFloat()), todo.fontSize.toFloat())
            } else {
                todo.fontSize.toFloat()
            }
            val fit = fitTodoText(todo, wPx, hPx, density, startSp, staticScaleOf(context), staticBudgetFactorOf(context), staticDrawnGapDpOf(context))
            hideAllLists(views)
            // 待办专用文字层：带 XML lineSpacingExtra=5dp 的行距（RemoteViews 无通道可设行距）
            val textId = showTextLayer(views, todo.shadow, todo = true)
            views.setTextViewText(textId, fit.second)
            views.setTextViewTextSize(
                textId, android.util.TypedValue.COMPLEX_UNIT_SP, fit.first
            )
            views.setTextColor(textId, todo.effectiveFontColor)
            views.setInt(
                textId, "setPaintFlags",
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
            views.setInt(textId, "setGravity", h or v)
            applyTextPadding(views, textId, density)
            views.setOnClickPendingIntent(
                R.id.widget_root, openTodoEditor(context, widgetId, todo.id)
            )
            manager.updateAppWidget(widgetId, views)
            Log.e(TAG, "update w=$widgetId todo=${todo.id} sp=${fit.first}")
        }

        /** 待办静态层行距（dp）：必须与布局 XML widget_text_todo 的 lineSpacingExtra 一致，
         *  容量估算按加宽后的行高算（2026-11-20 与滚动通道条目间隔 5dp 同节奏） */
        private val TODO_LINE_EXTRA_DP = 5f

        /** 待办展示文本：题目 + 条目行（圆点前缀），容量不够时逐条截断（不加省略号）。
         *  返回 (字号, Spannable)：已完成条目文字加删除线。
         *
         *  悬挂缩进（2026-11-20 用户定稿「折行后与上一行文字对齐」）：LeadingMarginSpan
         *  属度量类 span，宿主会整段剥离（实测无效）；改为应用侧预折行——条目按
         *  「行宽扣圆点宽」拆行（与滚动通道 textBudget 同口径），续行前插一个与首行
         *  同款的透明「 • 」前缀（ForegroundColorSpan(0) 属画笔类 span，宿主保留）。
         *  同串同字体，宽度天然等于首行圆点，对齐不依赖估算。 */
        private fun fitTodoText(
            todo: Todo, wPx: Int, hPx: Int, density: Float, startSp: Float, scale: Float,
            budgetFactor: Float, drawnGapDp: Float = 16f
        ): Pair<Float, CharSequence> {
            // 逻辑行：题目 + 各条目（条目带圆点前缀“ • ”，与滚动通道同款：前后各一空格使圆点在占字空间里水平居中）
            data class TodoLine(val text: String, val done: Boolean, val dot: Boolean = false)
            // 题目行首空格不过滤（trimEnd 只清尾部）；条目内容行首空格按需求保持 trim 过滤
            val title = todo.title.trimEnd().ifBlank { "待办" }
            // 隐藏已完成（2026-11-20 用户要求，与滚动通道 buildTodoLines 的 hideDone 同逻辑）
            val items = todo.items.filter { it.text.isNotBlank() && !(todo.hideDone && it.done) }
            val lines = mutableListOf<TodoLine>(TodoLine(title, false))
            // 与编辑页分区一致：未完成条目在前、已完成在后（组内保持原顺序）
            items.sortedBy { it.done }.forEach { lines.add(TodoLine(it.text.trim(), it.done, dot = true)) }
            // 圆点宽度用与渲染同字形的画笔实测（粗/斜体字宽不同，必须同源）
            val textStyle = when {
                todo.bold && todo.italic -> Typeface.BOLD_ITALIC
                todo.bold -> Typeface.BOLD
                todo.italic -> Typeface.ITALIC
                else -> 0
            }
            val prefixPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            prefixPaint.typeface = when (textStyle) {
                Typeface.BOLD -> Typeface.DEFAULT_BOLD
                Typeface.ITALIC -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                Typeface.BOLD_ITALIC -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                else -> Typeface.DEFAULT
            }
            fun dotPxOf(f: Float): Int {
                prefixPaint.textSize = f * density * scale
                return prefixPaint.measureText(" • ").toInt()
            }
            // 条目：预算扣一个圆点宽后预折行（滚动通道 textBudget 同口径），续行留同宽前缀
            fun wrapOf(line: TodoLine, f: Float): List<String> =
                if (line.dot) wrapLines(line.text, wPx - dotPxOf(f), density, f, scale, budgetFactor)
                else wrapLines(line.text, wPx, density, f, scale, budgetFactor)

            var fs = startSp
            fun wrappedCount(f: Float) = lines.sumOf { wrapOf(it, f).size }
            while (wrappedCount(fs) > capacityLines(hPx, density, fs, TODO_LINE_EXTRA_DP, drawnGapDp) && fs > 12f) {
                fs = (fs - 1f).coerceAtLeast(12f)
            }
            val cap = capacityLines(hPx, density, fs, TODO_LINE_EXTRA_DP, drawnGapDp)
            val shown = mutableListOf<TodoLine>()
            var used = 0
            for (line in lines) {
                val w = wrapOf(line, fs).size
                if (used + w > cap) break
                shown.add(line); used += w
            }

            val ssb = android.text.SpannableStringBuilder()
            val fsFinal = fs
            // 追加一段正文：全角空格换 1em 隐形填字，已完成加删除线（只描正文，不描透明前缀）
            fun appendBody(seg: String, strike: Boolean) {
                val bodyStart = ssb.length
                val (rep, fills) = ideographicToFiller(seg)
                ssb.append(rep)
                fills.forEach { p ->
                    ssb.setSpan(
                        ForegroundColorSpan(0), bodyStart + p, bodyStart + p + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (strike && seg.isNotEmpty()) {
                    ssb.setSpan(
                        android.text.style.StrikethroughSpan(), bodyStart, ssb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            shown.forEachIndexed { i, line ->
                if (i > 0) ssb.append("\n")
                if (line.dot) {
                    // 首行圆点可见；续行同款圆点全透明占位（同串同字体，宽度天然对齐）
                    wrapOf(line, fsFinal).forEachIndexed { j, seg ->
                        if (j > 0) ssb.append("\n")
                        val p0 = ssb.length
                        // “ • ”：前后各一空格使圆点在占字空间里水平居中（与滚动通道一致）
                        ssb.append(" • ")
                        if (j > 0) {
                            ssb.setSpan(
                                ForegroundColorSpan(0), p0, ssb.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        appendBody(seg, line.done)
                    }
                } else {
                    appendBody(line.text, false)
                }
            }
            // 粗斜体补上 StyleSpan（此前静态通道漏了：开了粗/斜不生效）
            if (textStyle != 0 && ssb.isNotEmpty()) {
                ssb.setSpan(
                    android.text.style.StyleSpan(textStyle), 0, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            // 下划线走 UnderlineSpan（setPaintFlags 会被宿主重置，span 才可靠生效）
            if (todo.underline && ssb.isNotEmpty()) {
                ssb.setSpan(
                    android.text.style.UnderlineSpan(), 0, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
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
                // 滚动便签：隐藏静态文字层，ListView 原生滚动展示全文；
                // 按 alignV 选用对应重力的列表，垂直对齐由布局重力精确完成
                val listId = listIdFor(note.alignV)
                hideTextLayer(views)
                showList(views, listId)
                applyTextPadding(views, listId, density)
                val seq = ++adapterSeq
                val svcIntent = android.content.Intent(context, MemoWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra("note_id", note.id)
                    // 列表内容区像素宽（扣容器左右内边距 2dp/2dp 共 4dp）：数据工厂
                    // 预拆超宽行，防止 maxLines=1 裁断超宽文字
                    .putExtra("wrap_px", (wPx - 4 * density).toInt())
                    // data 参与 Intent.filterEquals：唯一化才能让宿主重建数据连接、拿到最新内容
                    .setData(Uri.parse("memo://widget/$widgetId/${System.currentTimeMillis()}-$seq"))
                views.setRemoteAdapter(listId, svcIntent)
                // 数据更新后强制列表滚回顶部：宿主会保留旧滚动位置，样式切换后
                // “顶部文字消失”实为列表停在中间（滚动位置被保留）；API 31+ 才有该动作
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    views.setScrollPosition(listId, 0)
                }
                // 模板意图必须 FLAG_MUTABLE：Android 12+ 要求集合控件的行项 fill-in 依赖可变 PendingIntent
                views.setPendingIntentTemplate(
                    listId, openEditor(context, widgetId, note.id, mutable = true)
                )
                // 根布局兑底：点击行间空白同样打开便签
                views.setOnClickPendingIntent(R.id.widget_root, openEditor(context, widgetId, note.id))
                manager.updateAppWidget(widgetId, views)
                manager.notifyAppWidgetViewDataChanged(widgetId, listId)
                Log.e(TAG, "update w=$widgetId note=${note.id} scroll=true uri=${svcIntent.data}")
            } else {
                // 普通便签：静态文字层，字号按组件高度自适应
                val startSp = if (hDp in 1..240) {
                    // 自适应起始字号下限 12sp（2026-11-19 用户定稿），不超过便签自身字号
                    (hDp / 5f).coerceIn(12f.coerceAtMost(note.fontSize.toFloat()), note.fontSize.toFloat())
                } else {
                    note.fontSize.toFloat()
                }
                val fit = fitDisplayText(note, wPx, hPx, density, startSp, staticScaleOf(context), staticBudgetFactorOf(context), staticDrawnGapDpOf(context))
                hideAllLists(views)
                val textId = showTextLayer(views, note.shadow)
                views.setTextViewText(
                    textId,
                    styledText(
                        if (fit.second.isNotEmpty()) fit.second else normalizeLeadingIndent(buildDisplayText(note)),
                        note
                    )
                )
                views.setTextViewTextSize(
                    textId, android.util.TypedValue.COMPLEX_UNIT_SP, fit.first
                )
                views.setTextColor(textId, note.effectiveFontColor)
                views.setInt(
                    textId, "setPaintFlags",
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
                views.setInt(textId, "setGravity", h or v)
                applyTextPadding(views, textId, density)
                views.setOnClickPendingIntent(R.id.widget_root, openEditor(context, widgetId, note.id))
                manager.updateAppWidget(widgetId, views)
                Log.e(TAG, "update w=$widgetId note=${note.id} scroll=false sp=${fit.first} indentAdv=${indentAdvEm}")
            }
        }

    /** 按行宽预折行：逐字用画笔实测字形步进（2026-11-20 起对齐滚动通道口径，
     *  平铺模型对不了每行字形组合、宽字行会多装 1 字被宿主二次折行），
     *  可用宽度 = 布局实际内边距（左 2dp、右 2dp，共 4dp）× budgetFactor
     *  （跨线捕获系数，见 staticBudgetFactorOf）。
     *  字号换算用传入 scale（每设备可校准 static_font_scale：另一台取系统 fontScale，
     *  本机注入 1.07）。估算必须贴合宿主实际步进：偏大会超宽被宿主二次折行（孤字行），
     *  偏小则满行右空虚大（用户实证反馈）。估算与 TextView 实际换行位置保持一致，
     *  避免预折段过长被 TextView 二次换行——否则行尾参差、右侧留白虚大，
     *  还可能按偏少的行数选错字号导致内容被裁。 */
    /** 行首空白归一：宿主 TextView 对连续半角空格渲染极窄，按 4:1 换成全角空格保宽度 */
        private fun normalizeIndent(prefix: String): String {
            if (prefix.isEmpty()) return prefix
            val sb = StringBuilder()
            var spaces = 0
            fun flush() {
                while (spaces >= 2) {
                    sb.append('\u3000')
                    spaces -= 2
                }
                repeat(spaces) { sb.append('\u00A0') }
                spaces = 0
            }
            for (c in prefix) {
                when (c) {
                    ' ' -> spaces++
                    '\t' -> spaces += 4
                    else -> {
                        flush()
                        sb.append(c)
                    }
                }
            }
            flush()
            return sb.toString()
        }

        /** 静态宿主对全角空格 \u3000 的实际渲染步进（em 为单位，运行时实测）。
         *  设备字体不同步进不同：2026-11-19 本机 MIUI/MiSans 实测 0.5em（标题 10 单位
         *  缩进实测 74px=5×0.5em），另一测试机为 1em。应用画笔 Typeface.DEFAULT
         *  与宿主同源系统字体，实测值即宿主值，每次进程启动量一次缓存即可 */
        private val indentAdvEm: Float by lazy {
            val probe = Paint(Paint.ANTI_ALIAS_FLAG)
            probe.typeface = Typeface.DEFAULT
            probe.textSize = 100f
            val em = probe.measureText("永").coerceAtLeast(1f)
            (probe.measureText("\u3000") / em).coerceIn(0.25f, 1f)
        }

        /** 静态通道全角空格渲染定稿（2026-11-19）：\u3000 步进=1em。宿主对度量类 span
         *  会剥离（ScaleXSpan 实测被丢弃，Style/Underline 存活），无法用 span 改写步进；
         *  而 CJK 象形字步进在两台测试机恒为 1em（唯一稳定的宿主事实）。故把每个
         *  \u3000 替换为填字「一」并套透明 ForegroundColorSpan（画笔类 span，与
         *  UnderlineSpan 同通道可用）：所见即 1em 隐形空位，不随设备字体漂移。
         *  返回 (替换后文本, 填字起始位置列表)；\u00A0 余数单位保持自然步进（约 0.3em）。 */
        private fun ideographicToFiller(text: String): Pair<String, List<Int>> {
            if (text.indexOf('\u3000') < 0) return text to emptyList()
            val sb = StringBuilder(text.length)
            val at = mutableListOf<Int>()
            text.forEach { c ->
                if (c == '\u3000') { at.add(sb.length); sb.append('一') } else sb.append(c)
            }
            return sb.toString() to at
        }

        /** 行首空白宽度归一（静态通道专用）：编辑页（TextStyler.applyLeadingSpaceSpans）
         *  与列表通道（MemoWidgetService.indentWidth）对行首空白统一按 0.5 字宽/字符、
         *  Tab=2 字宽计（2026-11-19 用户定稿口径）。2026-11-19 定稿 \u3000 渲染步进=1em
         *  （styledText 换 1em 隐形填字渲染，不随设备字体漂移），故固定按 2 单位折 1 个
         *  全角空格：行首缩进 = 单位数×0.5em，与编辑页所见一致，两台测试机同结果；
         *  余 1 单位输出 \u00A0（保持自然步进，奇数单位缩进略有偏差）。
         *  仅作用于静态展示文本，不改保存的原文；列表通道读原文自算缩进像素，不经此处。 */
        private fun normalizeLeadingIndent(text: String): String {
            if (text.isEmpty()) return text
            val sb = StringBuilder()
            text.split("\n").forEachIndexed { idx, rawLine ->
                if (idx > 0) sb.append('\n')
                var i = 0
                var units = 0
                while (i < rawLine.length) {
                    val u = when (rawLine[i]) {
                        ' ', '\u3000' -> 1
                        '\t' -> 4
                        else -> 0
                    }
                    if (u == 0) break
                    units += u
                    i++
                }
                if (i == 0) {
                    sb.append(rawLine)
                } else {
                    repeat(units / 2) { sb.append('\u3000') }
                    if (units % 2 == 1) sb.append('\u00A0')
                    sb.append(rawLine, i, rawLine.length)
                }
            }
            return sb.toString()
        }

        /** 静态通道宽度估算的字体缩放：静态宿主 TextView 的实际渲染步进因设备而异
         *  （2026-11-20 修正：本机 16sp 满行实测每字 55.6px = sp×density×1.069，此前
         *  按 39px/12sp 反推的「忽略缩放=1.0」是误判——那次量的是字形墨高不是步进；
         *  1.0 系数低估 7%，330dp 宽部件满行被宿主二次折行出孤字行）。
         *  预折行估算必须用宿主实际步进才能贴边。校准：widget_calib 的
         *  static_font_scale（float，缺省/<=0 = 取系统 fontScale；本机注入 1.07） */
        private fun staticScaleOf(context: Context): Float {
            val calib = context.getSharedPreferences("widget_calib", Context.MODE_PRIVATE)
                .getFloat("static_font_scale", -1f)
            if (calib > 0f) return calib
            return android.content.res.Resources.getSystem().configuration.fontScale
                .coerceIn(0.8f, 2.0f)
        }

        /** 应用画笔量宽 / 宿主渲染宽 = 0.979（与滚动通道 MemoWidgetService.PAINT_W 同源：
         *  2026-11-18/19 实证 12sp 画笔量 CJK 42.0px、宿主渲染 42.9px；静态通道逐字
         *  实测换算宿主步进用，宿主宽 = 画笔宽 / PAINT_HOST_W）。 */
        private const val PAINT_HOST_W = 0.979f

        /** 静态通道折行预算系数（2026-11-20 引入，机制对齐滚动通道 budget_factor 的跨线捕获）：
         *  static_font_scale 对齐宿主步进、wrapLines 逐字实测（含 PAINT_HOST_W 换算）后，
         *  剩余系统性偏差只有一项：宿主规格宽(wPx)与物理渲染宽的差。本机实测该差随宽度
         *  浮动（236dp 物理比规格大 ~4.6%、330dp 只大 ~2.7%），取 f=1.02 后 236dp/330dp
         *  两宽度折行点均与宿主自然折行一致且零溢出；f≥1.04 时窄宽开始出现宿主二次
         *  折行（孤字行，2026-11-20 实证）。注意静态宿主对超宽行不会压字、只会二次折行，
         *  与滚动通道可用 1.15 靠宿主压字贴边的机制不同，上限必须保守。
         *  默认 1.0：未校准设备行为不变。调整方式同 budget_factor：run-as 写
         *  shared_prefs/widget_calib.xml 的 static_budget_factor（float）。 */
        private fun staticBudgetFactorOf(context: Context): Float {
            val f = context.getSharedPreferences("widget_calib", Context.MODE_PRIVATE)
                .getFloat("static_budget_factor", 1.0f)
            if (f <= 0f) return 1.0f
            return f.coerceIn(0.90f, 1.10f)
        }

        private fun wrapLines(
            text: String, wPx: Int, density: Float, fs: Float, scale: Float,
            budgetFactor: Float = 1f
        ): List<String> {
        val fontPx = fs * density * scale
        val usable = ((wPx - 4 * density) * budgetFactor).toInt()
            .coerceAtLeast(fontPx.toInt().coerceAtLeast(1))
        // 逐字实测（2026-11-20 对齐滚动通道 splitToLines 口径）：MiSans 每字形步进
        // 不同（同字号实测 火≈0.87em、红≈1.05em），平铺「CJK=1em/西文=0.55em」模型
        // 对不了每行的字形组合——宽字行比平均值宽 2~4%，预算乘系数后恰好多装进
        // 1 个宽字，宿主二次折行出孤字行（2026-11-20 236dp 宽部件实证）。
        // 画笔口径换算：应用画笔量宽比宿主渲染窄 2.15%（滚动通道 PAINT_W=0.979，
        // 2026-11-18/19 实证 12sp 画笔 42.0 vs 宿主 42.9），估算须除回
        // （宿主宽 = 画笔宽 / PAINT_HOST_W），否则逐字实测整体偏窄、每行恰好多装 1 字
        // （2026-11-20 二次实证：不除时 236dp/14sp 满行 15 字被宿主折出孤字「代」）。
        // \u3000 不按原始空格量宽（≈0.5em），按渲染时替换的 1em 隐形填字「一」量。
        val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        measurePaint.textSize = fontPx
        fun charW(c: Char): Float = when (c) {
            '\u3000' -> measurePaint.measureText("一") / PAINT_HOST_W
            else -> measurePaint.measureText(c.toString()) / PAINT_HOST_W
        }
        val out = mutableListOf<String>()
        text.split("\n").forEach { rawLine ->
            if (rawLine.isEmpty()) {
                out.add("")
                return@forEach
            }
            // 行首缩进保护：空白整段作为不可拆分前缀挂在首行，超过行宽 80% 才收敛到 35%
            val leadLen = rawLine.indexOfFirst { !it.isWhitespace() }.let {
                if (it < 0) rawLine.length else it
            }
            val bodyText = rawLine.substring(leadLen)
            if (bodyText.isEmpty()) {
                out.add(rawLine)
                return@forEach
            }
            var prefix = normalizeIndent(rawLine.substring(0, leadLen))
            var leadW = 0f
            prefix.forEach { leadW += charW(it) }
            if (leadW > usable * 0.8f) {
                val cap = usable * 0.35f
                var w2 = 0f
                var n = 0
                while (n < prefix.length) {
                    val cw = charW(prefix[n])
                    if (n > 0 && w2 + cw > cap) break
                    w2 += cw
                    n++
                }
                prefix = prefix.substring(0, n)
                leadW = w2
            }
            var w = leadW
            var line = StringBuilder(prefix)
            bodyText.forEach { c ->
                val cw = charW(c)
                if (w > 0f && w + cw > usable) {
                    out.add(line.toString())
                    line = StringBuilder()
                    w = 0f
                }
                line.append(c)
                w += cw
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

        /** 视口可容纳行数：可用高度按「绘制高」算——宿主把部件画得比规格大
         *  （本机 MIUI 实测三组部件宽高 6 点全部拟合 drawn = spec×0.98 + 51px，
         *  51px≈16dp，应为宿主给部件的外扩边距；大部件 251dp 只放大 4.5% 不足一行
         *  从未暴露，2 格小部件 62dp 高被放大 23% 恰好吞掉一行——2026-11-20
         *  “哈哈哈”便签第 4 行不显示实证）。drawnGapDp：外扩量校准键
         *  static_drawn_gap_dp（缺省 16dp=MIUI 实测，非 MIUI 宿主可调 0 回退规格基准）。
         *  再扣上 6dp、下 4dp 内边距（+1dp 余量，共 11dp，2026-11-20 上边距 4dp→6dp），
         *  行高按字号 1.22 倍（includeFontPadding=false 后实际行高≈1.17 倍，取 1.22
         *  留安全余量）；行高同样随系统 fontScale 放大。
         *  extraSpacingDp：待办静态层的行距加宽（XML lineSpacingExtra），逐行叠加 */
        private fun capacityLines(
            hPx: Int, density: Float, fs: Float, extraSpacingDp: Float = 0f,
            drawnGapDp: Float = 16f
        ): Int {
            val fontScale = android.content.res.Resources.getSystem().configuration.fontScale
                .coerceIn(0.8f, 2.0f)
            val lineH = fs * density * fontScale * 1.22f + extraSpacingDp * density
            val drawnH = hPx * 0.98f + drawnGapDp * density
            return (((drawnH - 11 * density) / lineH).toInt()).coerceAtLeast(1)
        }

        /** 宿主绘制外扩量（dp）：校准键 static_drawn_gap_dp，缺省 16dp（本机 MIUI 实测
         *  drawn = spec×0.98 + 51px）。容量按绘制高算才与物理一致；调 0 回退规格基准 */
        private fun staticDrawnGapDpOf(context: Context): Float {
            val v = context.getSharedPreferences("widget_calib", Context.MODE_PRIVATE)
                .getFloat("static_drawn_gap_dp", 16f)
            if (v < 0f) return 16f
            return v.coerceIn(0f, 40f)
        }

        /**
         * 视口自适应排版：内容放不下时逐级缩小字号（下限 12sp，2026-11-19 用户定稿）
         * 尽量多显示；最小字号仍放不下才截断（不加省略号，2026-11-19 用户定稿）。返回 (适配字号, 展示文本)。
         * 需要看全文时把便签设为“滚动”即可用原生滚动。
         */
        private fun fitDisplayText(
            note: Note, wPx: Int, hPx: Int, density: Float, startSp: Float, scale: Float,
            budgetFactor: Float = 1f, drawnGapDp: Float = 16f
        ): Pair<Float, String> {
            // 行首空白归一后参与适配与折行：装得下/截断两条分支同口径（编辑页 0.5 字宽/字符）
            val text = normalizeLeadingIndent(buildDisplayText(note))
            var fs = startSp
            var wrapped = wrapLines(text, wPx, density, fs, scale, budgetFactor)
            // 逐级缩小字号直到放得下或到下限 12sp
            while (wrapped.size > capacityLines(hPx, density, fs, drawnGapDp = drawnGapDp) && fs > 12f) {
                fs = (fs - 1f).coerceAtLeast(12f)
                wrapped = wrapLines(text, wPx, density, fs, scale, budgetFactor)
            }
            return if (wrapped.size <= capacityLines(hPx, density, fs, drawnGapDp = drawnGapDp)) {
                fs to text
            } else {
                val maxLines = capacityLines(hPx, density, fs, drawnGapDp = drawnGapDp)
                val shown = wrapped.take(maxLines).toMutableList()
                shown[shown.size - 1] = shown.last().trimEnd()
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
            // 背景照片（小部件背景设置）：解码即按组件尺寸 centerCrop 好再画，
            // 与原 drawCenterCrop 数学等价（等比缩放 + 居中裁剪），位图更小更省内存；
            // 不透明度控制照片浓度；照片绘制成功后跳过颜色层，避免盖住照片
            var photoDrawn = false
            note?.bgPhoto?.takeIf { it.isNotBlank() }?.let { name ->
                PhotoStore.loadBitmap(context, name, wPx, hPx, centerCrop = true)?.let { photo ->
                    val saveCount = canvas.save()
                    canvas.clipPath(
                        PhotoStore.roundedClipPath(fillInset, fillInset, wPx - fillInset, hPx - fillInset, fillRadius)
                    )
                    paint.isFilterBitmap = true
                    paint.alpha = ((note.alpha.coerceIn(0f, 1f)) * 255).toInt()
                    PhotoStore.drawCenterCrop(
                        canvas, photo, fillInset, fillInset, wPx - fillInset, hPx - fillInset, paint
                    )
                    paint.alpha = 255
                    canvas.restoreToCount(saveCount)
                    photoDrawn = true
                }
            }

            val effective = note?.let { alphaColor(it.bgColor, it.alpha) } ?: Color.WHITE
            if (!photoDrawn && Color.alpha(effective) > 0) {
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

        /** 小部件展示文本：标题 + 正文（保留行首空白：trimEnd 只清尾部，
         *  空格/Tab 缩进原样下发，供小部件按原样渲染段落缩进） */
        private fun buildDisplayText(note: Note): String {
            val t = note.title.trimEnd()
            val c = note.content.trimEnd()
            return when {
                t.isBlank() && c.isBlank() -> "空便签"
                t.isBlank() -> c
                c.isBlank() -> t
                else -> "$t\n$c"
            }
        }
    }
}
