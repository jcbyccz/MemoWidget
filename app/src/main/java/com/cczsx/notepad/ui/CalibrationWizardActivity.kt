package com.cczsx.notepad.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cczsx.notepad.R
import com.cczsx.notepad.widget.MemoWidgetProvider

/**
 * 排版校准向导（观感问答式）：顶部选择校准目标，通过用户对桌面部件满行右侧
 * 观感的反馈逐轮收敛三个排版系数之一：
 *  - budget_factor        滚动通道行宽预算（跨线捕获：满行越过物理行宽、由宿主
 *                         压字贴边，滚动行 maxLines=1 有压字兜底，可较激进到 1.15）
 *  - static_budget_factor 静态通道行宽捕获（静态超宽不压字、只会被宿主二次折行
 *                         出孤字行，步进与上限必须保守；本机安全值 1.02，≥1.04 出孤字）
 *  - static_font_scale    静态通道字号步进（估算字符宽 = sp×density×系数；偏小则
 *                         满行右空大，偏大则二次折行；本机 1.07）
 *
 * 背景：各设备桌面宿主对部件 TextView 的渲染口径不同（系统字体缩放是否生效、
 * 部件物理渲染宽与宿主规格宽的偏差方向与幅度），应用侧画笔测不到宿主行为，
 * 只能以观感反馈闭环校准。目标形态：满行右缘贴边或仅小空白、满行与短行字距
 * 一致、静态满行不被宿主二次折行（无孤字行）。
 *
 * 步进规律（2026-11-20 本机 MIUI 实测标定）：
 *  - 滚动行宽：“空得多”+0.03、“太挤”−0.07（1.05 附近为摊零头生效且零压字甜点区）；
 *  - 静态行宽：“空得多”+0.01、“太挤”−0.02（孤字行即视为太挤）；
 *  - 静态字号：±0.03。
 *
 * 保存即调用 refreshAll 自动刷新全部部件（滚动通道换新适配器 URI 重建数据连接，
 * 静态通道直接重建 RemoteViews）；MIUI 偶发吞推送，个别部件未变化时微调一次
 * 部件尺寸即可。写入 SharedPreferences 与部件渲染同进程，落盘即生效。
 */
class CalibrationWizardActivity : AppCompatActivity() {

    /** 校准目标定义：prefs 键 / 显示名 / 缺省值（<0 = 运行时取系统字体缩放）/
     *  向导步进（空得多 +stepUp / 太挤 −stepDown）与钳制区间 / 手动输入范围 */
    private data class FactorDef(
        val key: String,
        val label: String,
        val def: Float,
        val stepUp: Float,
        val stepDown: Float,
        val wizardMin: Float,
        val wizardMax: Float,
        val manualMin: Float,
        val manualMax: Float
    )

    private companion object {
        const val PREFS = "widget_calib"
        const val KEY_SCROLL = "budget_factor"
        const val KEY_STATIC_BUDGET = "static_budget_factor"
        const val KEY_STATIC_FONT = "static_font_scale"
        const val MAX_ROUNDS = 4
        const val ANSWER_BLANK = 0
        const val ANSWER_OK = 1
        const val ANSWER_TIGHT = 2

        // 滚动行宽：2026-11-20 定稿的步进与区间（1.05 甜点区），保持原值不动
        val DEF_SCROLL = FactorDef(
            KEY_SCROLL, "滚动·行宽", 1.02f,
            0.03f, 0.07f, 0.98f, 1.15f, 0.90f, 1.30f
        )

        // 静态行宽：上限 1.06（静态超宽不压字只二次折行，1.04 起本机实证出孤字行）
        val DEF_STATIC_BUDGET = FactorDef(
            KEY_STATIC_BUDGET, "静态·行宽", 1.0f,
            0.01f, 0.02f, 0.98f, 1.06f, 0.90f, 1.10f
        )

        // 静态字号：缺省未校准 = 取系统字体缩放（见 MemoWidgetProvider.staticScaleOf）
        val DEF_STATIC_FONT = FactorDef(
            KEY_STATIC_FONT, "静态·字号", -1f,
            0.03f, 0.03f, 0.95f, 1.30f, 0.80f, 1.60f
        )
    }

    private var def = DEF_SCROLL
    private var factor = DEF_SCROLL.def
    private var round = 0
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calib_wizard)
        ThemePrefs.apply(this)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        factor = readCurrent(def)

        findViewById<RadioGroup>(R.id.rg_target).setOnCheckedChangeListener { _, id ->
            def = when (id) {
                R.id.rb_static_budget -> DEF_STATIC_BUDGET
                R.id.rb_static_font -> DEF_STATIC_FONT
                else -> DEF_SCROLL
            }
            factor = readCurrent(def)
            round = 0
            showIntro()
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener { startWizard() }
        findViewById<Button>(R.id.btn_manual).setOnClickListener { showManualDialog() }
        findViewById<Button>(R.id.btn_blank).setOnClickListener { answer(ANSWER_BLANK) }
        findViewById<Button>(R.id.btn_ok).setOnClickListener { answer(ANSWER_OK) }
        findViewById<Button>(R.id.btn_tight).setOnClickListener { answer(ANSWER_TIGHT) }
        findViewById<Button>(R.id.btn_finish).setOnClickListener { finish() }

        showIntro()
    }

    /** 读当前系数：未设置时滚动/静态行宽用各自缺省，静态字号取系统字体缩放 */
    private fun readCurrent(d: FactorDef): Float {
        val v = prefs.getFloat(d.key, Float.NaN)
        if (!v.isNaN() && v > 0f) return norm(v)
        return if (d.def > 0f) d.def
        else norm(
            android.content.res.Resources.getSystem().configuration.fontScale
                .coerceIn(0.8f, 2.0f)
        )
    }

    /** 步进/输入后规整到 2 位小数：避免浮点累加出现 1.0299999 这类脏值 */
    private fun norm(f: Float): Float = Math.round(f * 100f) / 100f

    /** 展示用格式化（与 norm 配套，恒定 2 位小数） */
    private fun fmt(f: Float): String = String.format(java.util.Locale.US, "%.2f", f)

    /**
     * 直接设置系数（高级）：已知目标值或想自行实验时用。
     * 输入限制在该目标的 [manualMin, manualMax]，超出自动截断并提示；
     * 保存后调 refreshAll 立即刷新全部部件，个别部件未变化时微调尺寸即可。
     */
    private fun showManualDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(fmt(factor))
            setSelection(text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置${def.label}系数")
            .setMessage(
                "范围 ${fmt(def.manualMin)} ~ ${fmt(def.manualMax)}，超出将自动截断。" +
                    "保存后自动刷新部件；个别部件未变化时微调一次尺寸即可。"
            )
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().toFloatOrNull()
                if (v == null) {
                    android.widget.Toast.makeText(this, "输入无效", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val clamped = v.coerceIn(def.manualMin, def.manualMax)
                val changed = clamped != v
                factor = norm(clamped)
                saveFactor(manual = true)
                showIntro()
                android.widget.Toast.makeText(
                    this,
                    if (changed) "已截断到范围内，${def.label}系数 = ${fmt(factor)}；部件已刷新"
                    else "已保存，${def.label}系数 = ${fmt(factor)}；部件已刷新",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 落盘并立即刷新全部部件（滚动通道换新适配器 URI 重建数据连接，静态直接重建） */
    private fun saveFactor(manual: Boolean = false) {
        prefs.edit().putFloat(def.key, factor).apply()
        MemoWidgetProvider.diag(this, "calib key=${def.key} factor=$factor manual=$manual")
        MemoWidgetProvider.refreshAll(this)
    }

    private fun showIntro() {
        findViewById<View>(R.id.ll_intro).visibility = View.VISIBLE
        findViewById<View>(R.id.ll_ask).visibility = View.GONE
        findViewById<View>(R.id.ll_done).visibility = View.GONE
        findViewById<TextView>(R.id.tv_round).text = "准备校准（${def.label}）"
        findViewById<TextView>(R.id.tv_factor).text = "当前${def.label}系数：${fmt(factor)}"
    }

    private fun startWizard() {
        round = 1
        showAsk()
    }

    private fun showAsk() {
        findViewById<View>(R.id.ll_intro).visibility = View.GONE
        findViewById<View>(R.id.ll_done).visibility = View.GONE
        findViewById<View>(R.id.ll_ask).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_round).text =
            "${def.label} · 第 $round 轮（最多 $MAX_ROUNDS 轮）"
        findViewById<TextView>(R.id.tv_factor).text = "当前${def.label}系数：${fmt(factor)}"
    }

    /**
     * 观感作答：空得多 → 步进上调；刚好 → 保存并结束；
     * 太挤（含静态孤字行）→ 步进下调。未收敛且未达轮次上限则进入下一轮。
     */
    private fun answer(kind: Int) {
        when (kind) {
            ANSWER_BLANK -> factor = norm((factor + def.stepUp).coerceAtMost(def.wizardMax))
            ANSWER_OK -> {
                saveFactor()
                showDone(converged = true)
                return
            }
            ANSWER_TIGHT -> factor = norm((factor - def.stepDown).coerceAtLeast(def.wizardMin))
        }
        saveFactor()
        if (round >= MAX_ROUNDS) {
            showDone(converged = false)
        } else {
            round++
            showAsk()
        }
    }

    private fun showDone(converged: Boolean) {
        findViewById<View>(R.id.ll_intro).visibility = View.GONE
        findViewById<View>(R.id.ll_ask).visibility = View.GONE
        findViewById<View>(R.id.ll_done).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_round).text = if (converged) "校准完成" else "已到最大轮次"
        findViewById<TextView>(R.id.tv_factor).text = "当前${def.label}系数：${fmt(factor)}"
        findViewById<TextView>(R.id.tv_done).text = if (converged) {
            "校准完成，${def.label}系数 = ${fmt(factor)}。\n已自动刷新全部部件；若个别部件显示未变化，微调一次部件尺寸（拖动改变大小）即可生效。"
        } else {
            "已达最大轮次，${def.label}系数保留为 ${fmt(factor)}。\n观感仍不理想可重新运行向导，或换更长/更短的文本内容再试一轮。"
        }
    }
}
