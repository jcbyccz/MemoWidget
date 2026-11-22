package com.cczsx.notepad.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
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
 *  - 便签：全文按 \n 拆行；待办：题目一行 + 各条目一行（小圆点前缀约 1/3 字号，已完成带删除线，未完成在前）。
 *  - 加粗 / 斜体走 Spannable（宿主可渲染）；下划线 / 投影 / 删除线按估算位图总量分两档渲染，
 *    见 useBitmapRows 注释与 onDataSetChanged 末尾的分档决策。
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
    // 列表内容区像素宽（Provider 已扣内边距：左右各 5dp，共 10dp）：预拆行的宽度基准；0 表示未传、不拆
    private val wrapPx = intent.getIntExtra("wrap_px", 0)
    private val dataUri = intent.dataString

    /** 诊断落盘：本机 MIUI 压制三方应用所有日志级别（logcat 不可见），用文件兑底，
     *  读取：adb shell run-as com.cczsx.notepad cat files/diag_widget.log */
    private fun diag(msg: String) {
        try {
            val f = java.io.File(context.filesDir, "diag_widget.log")
            // 上限 300KB：防止日志无限增长，超过则重新开始
            if (f.length() > 300 * 1024) f.delete()
            f.appendText(msg + "\n")
        } catch (_: Exception) {
        }
    }

    private var noteLines: List<LineSpec> = listOf(LineSpec("点击新建便签", 0f))
    private var note: Note? = null
    /** 便签行渲染参数（行首缩进按像素扣减、绘制时用，避免依赖宿主空格字形宽度） */
    private var noteFontPx = 42f
    private var noteBudgetPx = 0f
    private var todoLines: List<TodoRow> = emptyList()
    private var todo: Todo? = null

    /** 效果行的渲染分档开关（onDataSetChanged 末尾决策）：
     *  - true（小部件）：整行文字自绘位图，下划线/投影/删除线/粗斜体所见即所得；
     *  - false（大部件或效果全关）：TextView 行承载文字（粗斜体 StyleSpan、删除线 span 走
     *    CharSequence；下划线改由低墨迹细条位图兜底、投影由布局 shadow 属性的 TextView 层承载）。
     *  依据（像素实证）：MIUI 宿主对列表行 TextView 会丢 UnderlineSpan / setPaintFlags 下划线
     *  （粗体/斜体的 StyleSpan 例外，能生效）；整行文字位图像素宿主丢不了，但总量过大
     *  （44 行 ≈ 4MB）时会被 MIUI 静默整列表丢弃。 */
    private var useBitmapRows = false

    // 位图行渲染参数（onDataSetChanged 里随样式一起算好，逐行复用）
    private var rowFontPx = 0f
    private var rowStyle = 0
    private var rowColor = 0
    private var rowUnderline = false
    private var rowShadow = false
    private var rowAlignH = 0
    private var rowWidthPx = 0
    /** 纯色背景（无背景照片、合成后不透明）时为部件背景色，行位图改用 RGB_565 实底
     *  （字节减半、无 alpha 需求，底色与部件背景无缝融合）；0 = 背景非纯色，用透明 ARGB_8888 */
    private var rowSolidBg = 0

    /** 拆行产物：行文本 + 满行摊零头所需的字距增量（em，0=自然字距）。
     *  宿主支持 RemoteViews.setFloat(setLetterSpacing)（2026-11-19 实证：+0.05em
     *  各行渲染宽实测加宽 3~3.5%），零头摊进字距让每行右缘精确对齐，不靠宿主压字。 */
    private data class LineSpec(val text: String, val spacingEm: Float)

    /** 待办单行数据：行文本 + 续行左缩进（悬挂缩进：续行文字与首行圆点后的文字上下对齐，0=不缩进）
     *  + 行顶留白（条目首行加一点行距，条目之间更透气）+ 删除线（已完成条目，位图行用）
     *  + 摊零字距（em） */
    private data class TodoRow(
        val text: CharSequence, val indentPx: Int,
        val topPadPx: Int = 0, val strike: Boolean = false,
        val spacingEm: Float = 0f
    )

    override fun onCreate() {
        Log.e(TAG, "factory create w=$widgetId note=$noteId todo=$todoId data=$dataUri")
        diag("factory create w=$widgetId note=$noteId todo=$todoId data=$dataUri")
    }

    override fun onDataSetChanged() {
        NoteStore.init(context)
        TodoStore.init(context)
        // 先按下发 id 预载样式对象（便签/待办），供预算计算取字号
        if (todoId >= 0) {
            note = null
            todo = TodoStore.getById(todoId) ?: TodoStore.todoForWidget(widgetId)
        } else {
            todo = null
            note = if (noteId >= 0) NoteStore.getById(noteId) else NoteStore.noteForWidget(widgetId)
        }
        // 预拆宽度基准：逐字累加真实字体宽度、装不下才断行——字号变化、中英混排自动适配。
        // 预算的取值依据见下方 budgetPx 处的宿主行为模型注释。
        // 行布局 maxLines=1 已锁定行高（空白行不可能出现）。
        // 字号换算 = sp × 物理 density × 系统 fontScale：
        // 2026-11-17 实测——系统字体 125% 时宿主渲染的行明显变大，长行被宿主逐行
        // 自动缩小（同一部件内大小字混杂）。此前「宿主忽略系统字体缩放」的判断
        // 是 110% 下差异小、被墨迹测量误差掩盖所致，实为误判。宿主按
        // sp×density×fontScale 渲染与测量，预拆画笔必须同倍缩放，否则我们算 9 字
        // 装得下、宿主实际超宽，逐行缩字又回来了。
        val hostDensity = context.resources.displayMetrics.density
        val fontScale = context.resources.configuration.fontScale.coerceIn(0.8f, 2.0f)
        val fontPxGuess = when {
            todo != null -> todo!!.fontSize * hostDensity * fontScale
            note != null -> note!!.fontSize * hostDensity * fontScale
            else -> 14f * hostDensity * fontScale
        }
        // 内容区宽：实时查询组件选项，不再直接信任 Intent extras——MIUI 在应用更新后
        // 可能不重发 RemoteViews，连接里带的 wrap_px 会停留在旧版本值（实测出现过
        // 旧参数导致便签折行点过早、右侧留白虚大）。onDataSetChanged 时重查选项，
        // 应用进程每次重启后渲染即自愈；查不到再退回 extras，最后退 9 字宽兜底。
        val physDensity = context.resources.displayMetrics.density
        var liveWrapPx = 0
        try {
            val opts = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val maxW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val maxH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            // 诊断日志用 Log.e：本机 MIUI 对三方应用压制 Log.d，logcat 完全不可见
            Log.e(
                TAG,
                "opts w=$widgetId minW=$minW maxW=$maxW minH=$minH maxH=$maxH keys=${opts.keySet()}"
            )
            val bundleStr = opts.keySet().joinToString(",") { k ->
                "$k=" + (try { opts.get(k).toString() } catch (e: Exception) { "?" })
            }
            diag("opts w=$widgetId minW=$minW maxW=$maxW minH=$minH maxH=$maxH")
            diag("optbundle w=$widgetId $bundleStr")
            // MIUI 的 min/max 宽键是截断后的整数（实宽 141.77dp 只剩 141），
            // 浮点真值在 appWidgetSizes 键里；宿主缩字阈值按浮点宽计算，
            // 用浮点做预算基准可少丢 ~2.5px（有时正好差一个字）。
            // 2026-11-19 实验结论：appWidgetSizes 规格值比渲染物理宽虚报 ~65px，
            // 但压字贴边方案（×budget_factor 跨线捕获）依赖规格基准：基准压到物理估后
            // 拆行口径差（单字累加比整串宽 ~4%）使满行不再溢出、右空白回到 ~1 字，
            // 与拍板观感（右缘贴边 0~17px、可接受压字）不符，故维持规格基准。
            // 扣宽 = 容器左右内边距 2dp/2dp 共 4dp（2026-11-19 定稿回归，与 XML 一致）。
            val wDpF = MemoWidgetProvider.optionsWidthDp(opts)
            if (wDpF > 0) liveWrapPx = (wDpF * physDensity).toInt() - (4 * physDensity).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "opts w=$widgetId exc=${e.message}")
            diag("opts w=$widgetId exc=${e.message}")
        }
        val wrapPxEff = when {
            liveWrapPx > 0 -> liveWrapPx
            wrapPx > 0 -> wrapPx
            else -> 0
        }
        val td = todo
        val nt = note
        // 删除线只在待办有已完成条目且未被隐藏时需要（便签无删除线）
        val strikeNeeded = td != null && !td.hideDone && td.items.any { it.done }
        rowFontPx = fontPxGuess
        rowStyle = when {
            td != null && td.bold && td.italic -> Typeface.BOLD_ITALIC
            td != null && td.bold -> Typeface.BOLD
            td != null && td.italic -> Typeface.ITALIC
            nt != null && nt.bold && nt.italic -> Typeface.BOLD_ITALIC
            nt != null && nt.bold -> Typeface.BOLD
            nt != null && nt.italic -> Typeface.ITALIC
            else -> 0
        }
        rowColor = when {
            td != null -> td.effectiveFontColor
            nt != null -> nt.effectiveFontColor
            else -> 0xFF1F1F1F.toInt()
        }
        rowUnderline = td?.underline == true || nt?.underline == true
        rowShadow = td?.shadow == true || nt?.shadow == true
        rowAlignH = td?.alignH ?: nt?.alignH ?: 0
        rowWidthPx = wrapPxEff
        rowSolidBg = when {
            td != null -> solidBgOf(td.bgColor, td.alpha, td.bgPhoto)
            nt != null -> solidBgOf(nt.bgColor, nt.alpha, nt.bgPhoto)
            else -> 0
        }
        // 预算系数：每设备可校准（2026-11-19 引入）。跨设备宿主行为实测相反，
        // 固定值无法两全：另一测试机宿主遵循系统字体缩放、规格宽虚报 ~65px，
        // ×1.02 为其拍板值（默认保持不变）；本机(25053RT47C)宿主忽略字体缩放
        //（渲染步进=sp×density≈45.5px/字，画笔 50.05 高估 10%）且规格偏小 4.5%
        //（规格 767px vs 物理渲染 802px），实测 ×1.15 才能让满行越过物理行宽、
        // 由宿主压字贴边（缩字≤4%）。校准方法（免 UI，开发者向）：
        //   adb shell run-as com.cczsx.notepad 会写入/读取
        //   shared_prefs/widget_calib.xml 的 budget_factor（float）。
        val budgetFactor = context.getSharedPreferences("widget_calib", Context.MODE_PRIVATE)
            .getFloat("budget_factor", 1.02f)
        val budgetPx = when {
            wrapPxEff > 0 -> wrapPxEff * budgetFactor
            else -> 9.0f * fontPxGuess
        }
        Log.e(
            TAG,
            "budget w=$widgetId live=$liveWrapPx extra=$wrapPx eff=$wrapPxEff budget=${budgetPx.toInt()} fontPx=$fontPxGuess density=$hostDensity fontScale=$fontScale"
        )
        diag(
            "budget w=$widgetId live=$liveWrapPx extra=$wrapPx eff=$wrapPxEff budget=${budgetPx.toInt()} fontPx=$fontPxGuess density=$hostDensity fontScale=$fontScale"
        )
        if (todoId >= 0) {
            todoLines = buildTodoLines(todo, budgetPx, hostDensity, fontScale)
            Log.e(TAG, "dataset w=$widgetId todo=${todo?.id} lines=${todoLines.size} budget=${budgetPx.toInt()}")
            diag("dataset w=$widgetId todo=${todo?.id} lines=${todoLines.size} budget=${budgetPx.toInt()}")
        } else {
            val text = note?.let { buildDisplayText(it) } ?: ""
            val fontPx = fontPxGuess
            val nStyle = note?.let {
                when {
                    it.bold && it.italic -> Typeface.BOLD_ITALIC
                    it.bold -> Typeface.BOLD
                    it.italic -> Typeface.ITALIC
                    else -> 0
                }
            } ?: 0
            val paint = linePaint(fontPx, nStyle)
            noteFontPx = fontPx
            noteBudgetPx = budgetPx
            // 便签同样预拆超宽行（机制同待办），空行用单空格占位：否则行高塌成 0，段落间距丢失。
            // 垂直对齐由三个列表的 layout_gravity 精确完成，不再补填充行
            noteLines = if (text.isBlank()) {
                listOf(LineSpec("点击新建便签", 0f))
            } else {
                text.split("\n").flatMap { line ->
                    if (line.isEmpty()) listOf(LineSpec(" ", 0f))
                    else splitToLines(line, budgetPx, paint)
                }
            }
            Log.e(
                TAG, "dataset w=$widgetId note=${note?.id} title=${note?.title} lines=${noteLines.size} budget=${budgetPx.toInt()}"
            )
            diag("dataset w=$widgetId note=${note?.id} lines=${noteLines.size} budget=${budgetPx.toInt()}")
        }
        // —— 效果行的渲染分档（行数出来后才能估算位图总量）：
        //  小部件 → 整行文字自绘位图（下划线/投影/删除线/粗斜体所见即所得）；
        //  大部件 → 降级混合档：TextView 承载文字 + 下划线细条位图 + 布局 shadow 层 + 删除线 span，
        //  粗斜体在混合档必然丢失（宿主丢 Spannable、忽略 XML textStyle，实证无解）。
        //  2026-11-19 修复：粗斜体（rowStyle != 0）原先只随其他效果顺带入档，单独开加粗时
        //  走 TextView+StyleSpan 被宿主忽略、视觉无变化（用户实证反馈）；现在作为
        //  独立触发条件入位图档。大部件超 1MB 仍降级丢粗斜体（物理限制，无法绕过）。
        //  MIUI 对列表位图批量有隐性上限（Binder 量级）：实测 181KB / 590KB 稳定，
        //  1.6MB 出现首部行缺失，~5.3MB 整列表被静默丢弃；1.0MB 卡在安全线内。
        //  估算按每行真实文字宽 × 行高(fontPx×1.3) × 字节（565 时 2 字节）累加，
        //  超限降级混合档只丢粗斜体，不丢行。
        val effectsNeeded = rowUnderline || rowShadow || strikeNeeded || rowStyle != 0
        val lineCount = if (todoId >= 0) todoLines.size else noteLines.size
        val estPaint = linePaint(rowFontPx, rowStyle)
        // 纯色背景行位图走 RGB_565（2 字节/像素），其余透明位图 4 字节/像素
        val estBpp = if (rowSolidBg != 0) 2f else 4f
        var estBytes = 0f
        if (todoId >= 0) {
            for (row in todoLines) {
                estBytes += (row.indentPx + estPaint.measureText(row.text.toString()) + 2f) *
                    (rowFontPx * 1.3f) * estBpp
            }
        } else {
            // 估算未含摊零字距增量（≤4% 行宽），1MB 安全线内可忽略
            for (line in noteLines) {
                estBytes += (estPaint.measureText(line.text) + 2f) * (rowFontPx * 1.3f) * estBpp
            }
        }
        useBitmapRows = effectsNeeded && wrapPxEff > 0 &&
            estBytes <= 1.0f * 1024f * 1024f
        if (effectsNeeded) {
            diag(
                "bmp mode w=$widgetId use=$useBitmapRows lines=$lineCount est=${(estBytes / 1024f).toInt()}KB " +
                    "fs=${rowFontPx.toInt()} style=$rowStyle color=${java.lang.Integer.toHexString(rowColor)} " +
                    "align=$rowAlignH width=$rowWidthPx ul=$rowUnderline sh=$rowShadow strike=$strikeNeeded"
            )
        }
    }

    override fun getCount(): Int = if (todoId >= 0) todoLines.size else noteLines.size

    override fun getViewAt(position: Int): RemoteViews =
        // 整行位图档：小部件效果行自绘；混合档：TextView 文字 + 细条位图/布局投影层
        if (useBitmapRows) bitmapRowAt(position) else textRowAt(position)

    /** 混合档行：TextView 承载文字（粗斜体的 StyleSpan 宿主可渲染；下划线宿主不渲染 →
     *  低墨迹细条位图兜底；投影用布局 shadow 属性的 TextView 层；删除线走 StrikethroughSpan） */
    private fun textRowAt(position: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.item_widget_line)

        // 文字层 8 选 1（4 种 textStyle × 2 种投影）：MIUI 宿主对列表行 TextView 连
        // StyleSpan（粗斜体）也丢（实测），RemoteViews 又设不了 setShadowLayer（投影），
        // 两者都只能靠预置 XML 属性层切换；全部层显式设置可见性——
        // 宿主复用行 convertView，未触及的层会残留上次的可见状态
        val textId = textLayerId(rowStyle, rowShadow)
        for (id in textLayerIds) {
            rv.setViewVisibility(id, if (id == textId) android.view.View.VISIBLE else android.view.View.GONE)
        }

        // 下划线细条位图：宿主丢 UnderlineSpan / setPaintFlags 下划线（像素实证），
        // 细条位图是像素级的丢不了；线在位图内从缩进处画起，水平对位交给重力档位
        val stripId = if (rowUnderline) imgSlotId(rowAlignH) else 0
        if (rowUnderline) {
            try {
                val rowText: String
                val indentPx: Int
                val spacingEm: Float
                if (todoId >= 0) {
                    val row = todoLines[position]
                    rowText = row.text.toString()
                    indentPx = row.indentPx
                    spacingEm = row.spacingEm
                } else {
                    val spec = noteLines[position]
                    val p = noteIndentOf(spec.text)
                    rowText = p.second
                    indentPx = p.first
                    spacingEm = spec.spacingEm
                }
                val strip = renderUnderlineStrip(rowText, indentPx, spacingEm)
                rv.setImageViewBitmap(stripId, strip)
            } catch (e: Exception) {
                diag("strip exc w=$widgetId pos=$position ${e.javaClass.simpleName}:${e.message}")
            }
        }
        // 位图档位也全显式设置（防 convertView 残留）；无下划线时三个档位全 GONE
        for (id in imgSlotIds) {
            rv.setViewVisibility(id, if (id == stripId) android.view.View.VISIBLE else android.view.View.GONE)
        }

        // 待办行：题目 / 条目（Spannable 已含小圆点、删除线、加粗斜体、下划线）
        val t = todo
        if (t != null) {
            val row = todoLines[position]
            rv.setTextViewText(textId, row.text)
            // 悬挂缩进 + 条目顶留白：左右内边距由列表容器承载（XML 2dp/2dp，总宽 4dp），
            // 这里只管行内容缩进；无条件显式设置防 convertView 复用残留
            rv.setViewPadding(textId, row.indentPx, row.topPadPx, 0, 0)
            // 满行零头摊进字距，右缘精确对齐；末行/短行自然字距
            rv.setFloat(textId, "setLetterSpacing", row.spacingEm)
            rv.setTextViewTextSize(
                textId, TypedValue.COMPLEX_UNIT_SP, t.fontSize.toFloat()
            )
            rv.setTextColor(textId, t.effectiveFontColor)
            // 下划线不再走 paint flag（宿主会丢弃，混合档由细条位图承载）
            rv.setInt(textId, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)
            rv.setInt(
                textId, "setGravity",
                when (t.alignH) {
                    1 -> Gravity.CENTER_HORIZONTAL
                    2 -> Gravity.RIGHT
                    else -> Gravity.LEFT
                }
            )
            val fill = Intent()
            fill.putExtra(TodoEditActivity.EXTRA_ID, t.id)
            fill.putExtra(TodoEditActivity.EXTRA_FROM_WIDGET, true)
            rv.setOnClickFillInIntent(textId, fill)
            return rv
        }

        val n = note
        if (n == null) {
            rv.setTextViewText(textId, noteLines[position].text)
            rv.setViewPadding(textId, 0, 0, 0, 0)
            rv.setTextViewTextSize(
                textId, TypedValue.COMPLEX_UNIT_SP, 14f
            )
            rv.setTextColor(textId, Color.GRAY)
            return rv
        }

        // 便签行：行首缩进走像素内边距（宿主空格字形宽度不可控）；左右内边距由容器承载，
        // 无条件显式设置防复用残留。文字包成 Spannable，加粗 / 斜体 / 下划线随 CharSequence 下发
        val spec = noteLines[position]
        val (noteIndentPx, noteBody) = noteIndentOf(spec.text)
        rv.setTextViewText(
            textId, MemoWidgetProvider.styledText(noteBody, n)
        )
        rv.setViewPadding(textId, noteIndentPx, 0, 0, 0)
        rv.setTextViewTextSize(
            textId, TypedValue.COMPLEX_UNIT_SP, n.fontSize.toFloat()
        )
        rv.setTextColor(textId, n.effectiveFontColor)
        rv.setInt(textId, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)
        // 满行零头摊进字距（splitToLines 计算），右缘精确对齐；段落末行保持自然字距
        rv.setFloat(textId, "setLetterSpacing", spec.spacingEm)
        val h = when (n.alignH) {
            1 -> Gravity.CENTER_HORIZONTAL
            2 -> Gravity.RIGHT
            else -> Gravity.LEFT
        }
        rv.setInt(textId, "setGravity", h)

        // 行点击 → 打开编辑页（模板 PendingIntent 由 Provider 设置）
        val fill = Intent()
        fill.putExtra(EditNoteActivity.EXTRA_ID, n.id)
        fill.putExtra(EditNoteActivity.EXTRA_FROM_WIDGET, true)
        rv.setOnClickFillInIntent(textId, fill)
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null

    /** 单一行布局：item_widget_line 同时承载文字行与效果位图（可见性切换），不再有独立位图行布局 */
    override fun getViewTypeCount(): Int = 1

    /** 整行位图档（小部件）：单行文字（含下划线/投影/删除线/粗斜体）自绘成「文字宽」位图，
     *  两个 TextView 隐藏。位图宽 = 缩进 + 文字宽 + 2（ImageView wrap_content），
     *  水平对位交给布局重力档位；位图总量的分档决策见 onDataSetChanged 末尾
     *  （总量过大会被 MIUI 静默丢弃，所以大部件不用本档）。 */
    private fun bitmapRowAt(position: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.item_widget_line)
        for (id in textLayerIds) {
            rv.setViewVisibility(id, android.view.View.GONE)
        }
        var text = ""
        var indentPx = 0
        var topPadPx = 0
        var strike = false
        var spacingEm = 0f
        if (todoId >= 0) {
            val row = todoLines[position]
            text = row.text.toString()
            indentPx = row.indentPx
            topPadPx = row.topPadPx
            strike = row.strike
            spacingEm = row.spacingEm
        } else {
            val spec = noteLines[position]
            val p = noteIndentOf(spec.text)
            indentPx = p.first
            text = p.second
            spacingEm = spec.spacingEm
        }
        val imgId = imgSlotId(rowAlignH)
        try {
            val bmp = renderRowBitmap(text, indentPx, topPadPx, strike, spacingEm)
            rv.setImageViewBitmap(imgId, bmp)
            for (id in imgSlotIds) {
                rv.setViewVisibility(id, if (id == imgId) android.view.View.VISIBLE else android.view.View.GONE)
            }
            rv.setOnClickFillInIntent(imgId, fillIntent())
        } catch (e: Exception) {
            // 自绘失败退化为同款文字层（粗斜/投影 XML 层仍生效，无下划线），点击仍可用
            diag("bmprow exc w=$widgetId pos=$position ${e.javaClass.simpleName}:${e.message}")
            for (id in imgSlotIds) {
                rv.setViewVisibility(id, android.view.View.GONE)
            }
            val fallbackId = textLayerId(rowStyle, rowShadow)
            rv.setTextViewText(fallbackId, text)
            rv.setViewVisibility(fallbackId, android.view.View.VISIBLE)
            rv.setOnClickFillInIntent(fallbackId, fillIntent())
        }
        return rv
    }

    /** 行点击 → 打开编辑页的 fill-in Intent（模板 PendingIntent 由 Provider 设置） */
    private fun fillIntent(): Intent {
        val fill = Intent()
        val t = todo
        val n = note
        if (t != null) {
            fill.putExtra(TodoEditActivity.EXTRA_ID, t.id)
            fill.putExtra(TodoEditActivity.EXTRA_FROM_WIDGET, true)
        } else if (n != null) {
            fill.putExtra(EditNoteActivity.EXTRA_ID, n.id)
            fill.putExtra(EditNoteActivity.EXTRA_FROM_WIDGET, true)
        }
        return fill
    }

    /** 效果位图的重力档位 id：与文字水平对齐方式一致（左/中/右），未选中的档位保持布局默认 GONE */
    private fun imgSlotId(alignH: Int): Int = when (alignH) {
        1 -> R.id.widget_line_img_c
        2 -> R.id.widget_line_img_r
        else -> R.id.widget_line_img_l
    }

    /** 背景为纯色（无照片、合成后不透明）时返回该背景色（行位图可填底走 RGB_565），否则 0 */
    private fun solidBgOf(bgColor: Int, alpha: Float, bgPhoto: String): Int {
        if (bgPhoto.isNotBlank() || bgColor == 0) return 0
        val effective = MemoWidgetProvider.alphaColor(bgColor, alpha)
        return if (Color.alpha(effective) >= 255) effective else 0
    }

    /** 文字层档位 id：4 种 textStyle × 2 种投影共 8 层，行渲染时按部件样式取其一显示。
     *  MIUI 宿主列表行丢 StyleSpan（粗斜体），RemoteViews 设不了 setShadowLayer（投影），
     *  两者都只能靠预置 XML 属性层承载 */
    private fun textLayerId(style: Int, shadow: Boolean): Int = when {
        shadow && style == Typeface.BOLD_ITALIC -> R.id.widget_line_text_shadow_bi
        shadow && style == Typeface.BOLD -> R.id.widget_line_text_shadow_b
        shadow && style == Typeface.ITALIC -> R.id.widget_line_text_shadow_i
        shadow -> R.id.widget_line_text_shadow
        style == Typeface.BOLD_ITALIC -> R.id.widget_line_text_bi
        style == Typeface.BOLD -> R.id.widget_line_text_b
        style == Typeface.ITALIC -> R.id.widget_line_text_i
        else -> R.id.widget_line_text
    }

    /** 全部文字层 id：每行显式重置全部层可见性（宿主复用行 convertView，未触及的层会残留上次状态） */
    private val textLayerIds = intArrayOf(
        R.id.widget_line_text, R.id.widget_line_text_b, R.id.widget_line_text_i, R.id.widget_line_text_bi,
        R.id.widget_line_text_shadow, R.id.widget_line_text_shadow_b,
        R.id.widget_line_text_shadow_i, R.id.widget_line_text_shadow_bi
    )

    /** 全部效果位图档位 id（同上，显式重置防残留） */
    private val imgSlotIds = intArrayOf(
        R.id.widget_line_img_l, R.id.widget_line_img_c, R.id.widget_line_img_r
    )

    /** 单行自绘：行高 = 条目留白 + 字高，垂直节奏与 TextView 行（maxLines=1、
     *  includeFontPadding=false）一致；行宽 = 文字实际宽（见函数内注释）。 */
    private fun renderRowBitmap(
        text: String, indentPx: Int, topPadPx: Int, strike: Boolean, spacingEm: Float
    ): Bitmap {
        val p = Paint(
            Paint.ANTI_ALIAS_FLAG or
                (if (rowUnderline) Paint.UNDERLINE_TEXT_FLAG else 0) or
                (if (strike) Paint.STRIKE_THRU_TEXT_FLAG else 0)
        )
        p.textSize = rowFontPx
        p.typeface = when (rowStyle) {
            Typeface.BOLD -> Typeface.DEFAULT_BOLD
            Typeface.ITALIC -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            Typeface.BOLD_ITALIC -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            else -> Typeface.DEFAULT
        }
        // 本机 CJK 字体无真粗体：Typeface 的 bold 变体解析后与常规几乎同粗，
        // 而 Canvas.drawText 不像 TextView 那样自动伪粗——必须显式开伪粗，否则肉眼无变化
        p.isFakeBoldText = rowStyle == Typeface.BOLD || rowStyle == Typeface.BOLD_ITALIC
        p.color = rowColor
        // 与 TextView 行同款摊零字距（位图行右缘与文字行一致对齐）
        p.letterSpacing = spacingEm
        // 投影参数与应用内一致（TextStyler.applyText：半径 4 / 偏移 1,2 px / 20% 黑）
        if (rowShadow) p.setShadowLayer(4f, 1f, 2f, 0x33000000)
        val fm = p.fontMetrics
        val textW = p.measureText(text)
        // 位图宽裁成文字实际宽（+2 AA 余量）：水平对位交给 ImageView 重力档位，
        // 与宿主上报宽无关（上报宽 ≠ 真实行宽，按上报宽算居中会偏 ~20px）
        val w = (indentPx + textW + 2f).toInt().coerceAtLeast(1)
        val h = (topPadPx - fm.ascent + fm.descent).toInt().coerceAtLeast(1)
        // 纯色背景：RGB_565 实底（字节减半、底色与部件背景无缝；565 色彩量化的
        // 细微偏差在墨水屏灰度渲染下不可见）；否则透明 ARGB_8888
        val bmp = if (rowSolidBg != 0) {
            Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { it.eraseColor(rowSolidBg) }
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val c = Canvas(bmp)
        // 文字一律从位图左缘的缩进处画起；居中/右对齐时位图右缘余量带来 ≤1px 偏差，可忽略
        c.drawText(text, indentPx.toFloat(), topPadPx - fm.ascent, p)
        return bmp
    }

    /** 低墨迹下划线细条（大部件兜底）：整行文字位图总量超限会被 MIUI 静默丢弃，
     *  但与「空行占位小横线」同量级的低墨迹位图实测可存活。
     *  宽 = 缩进 + 文字宽 + 2；高 = descent + 5（条高远小于行高，重力 bottom 贴行底不撑行）；
     *  线厚 ≈ 字号/16（≥2px），线顶在基线下 1px（基线距行底 descent，线顶距条底 descent-1）。 */
    private fun renderUnderlineStrip(text: String, indentPx: Int, spacingEm: Float): Bitmap {
        // 与文字行同款测量画笔：粗/斜体字宽不同，线长必须按同字形测量；
        // 摊零字距同步，否则线长与文字宽不齐
        val p = linePaint(rowFontPx, rowStyle)
        p.letterSpacing = spacingEm
        val textW = p.measureText(text)
        val fm = p.fontMetrics
        val w = (indentPx + textW + 2f).toInt().coerceAtLeast(1)
        val h = (fm.descent + 5f).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val thickness = (rowFontPx / 16f).coerceAtLeast(2f)
        val top = h - fm.descent + 1f
        val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        ink.color = rowColor
        c.drawRect(indentPx.toFloat(), top, indentPx + textW, top + thickness, ink)
        return bmp
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true

    override fun onDestroy() {
        Log.e(TAG, "factory destroy w=$widgetId data=$dataUri")
    }

    private fun buildDisplayText(note: Note): String {
        // 保留行首空白（空格/Tab 缩进）：trimEnd 只清尾部，缩进原样参与预拆与渲染
        val t = note.title.trimEnd()
        val c = note.content.trimEnd()
        return when {
            t.isBlank() && c.isBlank() -> ""
            t.isBlank() -> c
            c.isBlank() -> t
            else -> "$t\n$c"
        }
    }

    /** 待办行数据：题目逐行 + 条目逐行（未完成在前、已完成在后，组内保持原顺序；
     *  齿轮菜单设置了隐藏已完成时，已完成条目不进列表）。
     *  budgetPx > 0 时每段文本按像素预算预拆为多个单行 item（行布局 maxLines=1 会
     *  裁断超宽内容，预拆保证不丢字）。
     *  条目文本支持手动换行（\n，编辑页回车）：必须先按 \n 拆段再预拆宽度——
     *  若片段横跨 \n，宿主按多行测量会触发自动缩字号（行与行字号不一），
     *  maxLines=1 还会裁掉 \n 之后的内容（丢字）。只有条目第一段首行带圆点。 */
    private fun buildTodoLines(t: Todo?, budgetPx: Float, hostDensity: Float, fontScale: Float): List<TodoRow> {
        if (t == null) return listOf(TodoRow("点击选择待办", 0))
        val style = when {
            t.bold && t.italic -> Typeface.BOLD_ITALIC
            t.bold -> Typeface.BOLD
            t.italic -> Typeface.ITALIC
            else -> 0
        }
        val fontPx = t.fontSize * hostDensity * fontScale
        val paint = linePaint(fontPx, style)
        // 圆点前缀“ • ”（前后各一个半角空格）的真实宽度：圆点在占字空间里水平居中；
        // 条目首行从预算里扣掉，保证“圆点+文字”整体也刚好填满
        val dotW = paint.measureText(" • ")
        // 诊断：真实汉字/逗号字宽，用于校准预算（读 files/diag_widget.log）
        diag(
            "measure font=$fontPx cjk=${paint.measureText("中")} comma=${paint.measureText("，")} dot=$dotW budget=$budgetPx"
        )
        // 悬挂缩进：后续行（折行续行/手动换行的后续段）左缩进一个“• ”宽度，与首行圆点后的
        // 第一个字上下对齐；其预拆预算同样扣除缩进宽，各行右端依然贴近边框
        val textBudget = (budgetPx - dotW).coerceAtLeast(fontPx)
        val indent = dotW.toInt()
        // 条目之间的行距：每个条目的首行加 5dp（随系统字体缩放等比）顶部留白，
        // 条目与条目、题目与条目之间更透气；同一条目的折行/手动换行续行不加，保持紧凑
        val itemGapPx = (5 * hostDensity * fontScale).toInt()
        val out = mutableListOf<TodoRow>()
        // 题目：行首空格不过滤——与便签同规则换算成像素缩进（宿主对连续空格的字形宽度
        // 不可控；2026-11-19 统一 0.5 字宽/字符、取消 35% 上限，与编辑页同口径），
        // 题目首行带缩进、折行续行顶格；条目内容的行首空格仍保持 trim 过滤（需求）
        t.title.trimEnd().ifBlank { "待办" }.split('\n').forEach { para ->
            val leadLen = para.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) para.length else it }
            val body = para.substring(leadLen).trimEnd()
            if (body.isNotEmpty()) {
                val titleIndent = if (leadLen <= 0) 0
                    else indentWidth(para.substring(0, leadLen), fontPx).toInt()
                splitToLines(body, budgetPx, paint, (budgetPx - titleIndent).coerceAtLeast(1f))
                        .forEachIndexed { i, seg ->
                            out.add(
                                TodoRow(
                                    todoRow(seg.text, false, false, style, t.underline),
                                    if (i == 0) titleIndent else 0, 0,
                                    spacingEm = seg.spacingEm
                                )
                            )
                        }
            }
        }
        t.items.filter { it.text.isNotBlank() && !(t.hideDone && it.done) }
            .sortedBy { it.done }
            .forEach { item ->
                val paragraphs = item.text.trim().split('\n')
                paragraphs.forEachIndexed { pi, raw ->
                    val para = raw.trim()
                    if (para.isEmpty()) {
                        // 用户手动敲的空行：用单空格占位保留行位（段落间距），否则行高塌陷
                        out.add(TodoRow(todoRow(" ", false, item.done, style, t.underline), indent, 0))
                        return@forEachIndexed
                    }
                    splitToLines(para, textBudget, paint, textBudget).forEachIndexed { si, seg ->
                        val first = pi == 0 && si == 0
                        out.add(
                            TodoRow(
                                todoRow(seg.text, first, item.done, style, t.underline),
                                if (first) 0 else indent,
                                if (first) itemGapPx else 0,
                                strike = item.done,
                                // 条目行不摊零头（2026-11-20 实证）：整行含「 • 」前缀，
                                // 字距会把前缀的 3 个字符也各摊宽一份，首行文字被额外右推
                                // 3×字距×字号（本机实测 8~12px）；而续行缩进是固定圆点宽，
                                // 不随字距变化 → 上下行必然错位。静态通道同样不摊字距，
                                // 两通道对齐口径一致。标题行无前缀，保留摊零头。
                                spacingEm = 0f
                            )
                        )
                    }
                }
            }
        return out
    }

    /** 切点修正时视为标点的字符（避免下一行以标点开头） */
    private val BREAK_CHARS = "，。！？；：、,.!?;:"

    /** 画笔口径 / 宿主渲染口径 = 0.979：画笔量宽比宿主渲染窄 2.15%
     *  （2026-11-18/19 实证：12sp paint CJK 42.0 vs 宿主渲染 42.9；14sp 反推同比例）。
     *  拆行判据 = 目标宿主宽 × PAINT_W；摊零字距时宿主宽 = 画笔宽 / PAINT_W。 */
    private val PAINT_W = 0.979f

    /** 与列表行 TextView 同款的测量画笔：同字号、同字形（粗/斜体宽度不同，必须一致） */
    private fun linePaint(fontPx: Float, style: Int): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = fontPx
        p.typeface = when (style) {
            Typeface.BOLD -> Typeface.DEFAULT_BOLD
            Typeface.ITALIC -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            Typeface.BOLD_ITALIC -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            else -> Typeface.DEFAULT
        }
        return p
    }

    /** 行首空白视觉宽度（2026-11-19 用户定稿）：半角空格与全角空格 \u3000 统一按
     *  0.5 字宽计（编辑页系统字体对两者渲染也约为 0.5em，两边所见一致），
     *  Tab 保持 2 字宽。不用 Paint.measureText——宿主对连续空格的
     *  字形宽度与自绘测量不一致。 */
    private fun indentWidth(prefix: String, emPx: Float): Float {
        var w = 0f
        for (c in prefix) {
            w += when (c) {
                '\u3000' -> emPx * 0.5f
                '\t' -> emPx * 2f
                else -> emPx * 0.5f
            }
        }
        return w
    }

    /** 便签行缩进：按行首空白的像素宽换算（2026-11-19 取消 35% 上限，用户指定：
     *  行首空白全部按 0.5 字宽/字符，编辑页同口径见 TextStyler.applyLeadingSpaceSpans），
     *  正文剥离空白后用 TextView 左内边距精确定位——宿主对连续空格的字形宽度不可控，
     *  padding 最稳。 */
    private fun noteIndentOf(line: String): Pair<Int, String> {
        val leadLen = line.indexOfFirst { !it.isWhitespace() }.let {
            if (it < 0) line.length else it
        }
        if (leadLen <= 0) return 0 to line
        val body = line.substring(leadLen)
        if (body.isEmpty()) return 0 to line
        val w = indentWidth(line.substring(0, leadLen), noteFontPx).toInt()
        return w to body
    }

    /** 行首缩进保护：空白整段不参与折行，作为不可拆分前缀挂在首行——
     *  避免“整行都是空格”的首段被 trimEnd 丢弃、缩进被吃掉。
     *  2026-11-19 用户定稿：行首空白统一 0.5 字宽/字符、取消 35% 上限
     *  （首行预算扣减 leadW 后 coerceAtLeast(1f) 兑底，无死循环；缩进超宽时
     *  首行正文会极少甚至被挤出部件右缘，用户自担）。后续行宽预算仍为
     *  规格基准 × budget_factor 跨线捕获（每设备可校准，见 onDataSetChanged 注释）。 */
    private fun splitToLines(
        text: String, budgetPx: Float, paint: Paint, firstBudget: Float = budgetPx
    ): List<LineSpec> {
        if (budgetPx <= 0f || text.isEmpty()) return listOf(LineSpec(text, 0f))
        val leadLen = text.indexOfFirst { !it.isWhitespace() }.let {
            if (it < 0) text.length else it
        }
        val body = text.substring(leadLen)
        if (body.isEmpty()) return listOf(LineSpec(text, 0f))
        val prefix = text.substring(0, leadLen)
        val leadW = indentWidth(prefix, paint.textSize)
        val out = mutableListOf<LineSpec>()
        var i = 0
        var budget = (firstBudget - leadW).coerceAtLeast(1f)
        while (i < body.length) {
            var w = 0f
            var end = i
            while (end < body.length) {
                val cw = paint.measureText(body, end, end + 1)
                if (w + cw > budget && end > i) break
                w += cw
                end++
            }
            // 本段后还有正文 = 宽驱换行的满行（段末行不摊字距）
            val fullLine = end < body.length
            // 切点修正：若下一行首字符是标点/空白，切点前移 1 字（标点连同前字一起下段，避免行首标点）
            if (end < body.length && end - i > 1 &&
                (body[end] == ' ' || BREAK_CHARS.indexOf(body[end]) >= 0)
            ) {
                end--
            }
            val segA = body.substring(i, end).trimEnd()
            if (segA.isNotEmpty()) {
                // 满行摊零头（2026-11-19 启用）：把行宽与预算的零头（≤2em，含切点让字）
                // 摊进字距，右缘精确对齐；段末/短行保持自然字距。目标为画笔口径预算：
                // 另一台宿主按 PAINT_W 放大（宿主宽=画笔宽/0.979）。字距(em)=零头/(字数×em)。
                // 本机(25053RT47C MIUI)两轮实证（2026-11-19 晚）：setLetterSpacing 大值生效
                // （0.3em 行宽膨胀、触发宿主压缩），小值被静默丢弃（0.024em、0.039em 档均无
                // 像素变化）——本机 TextView 行摊不了零头，机制保留给位图行（应用侧自绘
                // 恒生效）与非 MIUI 宿主；实测无副作用（渲染与未开时一致，不触发压缩混排）。
                var spacing = 0f
                if (fullLine) {
                    val slack = budget - paint.measureText(segA)
                    if (slack > 0f && slack <= paint.textSize * 2f) {
                        spacing = slack / (segA.length * paint.textSize)
                    }
                }
                out.add(LineSpec(segA, spacing))
            }
            i = end
            budget = budgetPx   // 后续段用满预算
        }
        if (out.isEmpty()) return listOf(LineSpec(text, 0f))
        out[0] = LineSpec(prefix + out[0].text, out[0].spacingEm)
        return out
    }

    /** 单行组装：“ • ”小圆点前缀（前后各一空格使圆点在占字空间里水平居中、
     *  字形天然垂直居中、约 1/3 字高）+ 文本；已完成加删除线；
     *  加粗/斜体走 StyleSpan（宿主可渲染）。下划线 span 宿主实际不渲染（像素实证），
     *  保留作非 MIUI 宿主的兑底；MIUI 上由细条位图（混合档）/整行位图（小部件档）呈现 */
    private fun todoRow(
        text: String, hasDot: Boolean, done: Boolean, style: Int, underline: Boolean
    ): CharSequence {
        val ssb = SpannableStringBuilder()
        if (hasDot) {
            ssb.append(" • ")
        }
        val t0 = ssb.length
        ssb.append(text)
        if (done && text.isNotEmpty()) {
            ssb.setSpan(StrikethroughSpan(), t0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style != 0 && ssb.isNotEmpty()) {
            ssb.setSpan(StyleSpan(style), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (underline && ssb.isNotEmpty()) {
            ssb.setSpan(
                android.text.style.UnderlineSpan(), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return ssb
    }
}
