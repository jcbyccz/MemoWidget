package com.cczsx.notepad.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.cczsx.notepad.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * 应用主题色管理：工具栏 / 状态栏 / FAB / 保存按钮统一取色。
 * 颜色持久化在 SharedPreferences；文字与图标颜色按背景亮度自动适配（浅底深字、深底白字）。
 */
object ThemePrefs {

    private const val PREFS = "theme_prefs"
    private const val KEY_COLOR = "primary_color"

    /** 默认主题色（即 v1.5.0 的淡蓝） */
    const val DEFAULT = 0xFFA8D5F0.toInt()

    /** 预设主题色：淡蓝 / 淡绿 / 淡黄 / 淡粉 / 淡灰 / 深蓝 */
    val PRESETS = intArrayOf(
        0xFFA8D5F0.toInt(), 0xFFB7E4C7.toInt(), 0xFFF6E7B2.toInt(),
        0xFFF3C6D8.toInt(), 0xFFD9DEE3.toInt(), 0xFF4A6FA5.toInt()
    )

    fun load(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COLOR, DEFAULT)

    fun save(context: Context, color: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COLOR, color).apply()
    }

    /** 按亮度自适应的前景色：浅色底返回深蓝灰，深色底返回白 */
    fun contentColor(bg: Int): Int =
        if (brightness(bg) >= 150) 0xFF17435E.toInt() else Color.WHITE

    /** 状态栏取主题色加深一档，形成层次 */
    fun darken(color: Int, ratio: Float = 0.18f): Int {
        fun mix(c: Int): Int = (c * (1 - ratio)).toInt()
        return Color.argb(
            Color.alpha(color), mix(Color.red(color)),
            mix(Color.green(color)), mix(Color.blue(color))
        )
    }

    /** 在指定 Activity 应用主题：状态栏 + 工具栏及其子控件 + FAB + 保存按钮 */
    fun apply(activity: Activity) {
        val color = load(activity)
        val fg = contentColor(color)
        activity.window.statusBarColor = darken(color)

        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
        if (toolbar != null) {
            toolbar.setBackgroundColor(color)
            toolbar.setTitleTextColor(fg)
            try {
                toolbar.setNavigationIconTint(fg)
            } catch (_: Exception) {
            }
        }

        // 各页常见子控件：主界面三横线/搜索/菜单、编辑页确认按钮、回收站清空按钮、
        // 搜索行返回/清空按钮（背景改主题色后图标也需自适应）
        intArrayOf(
            R.id.btn_nav, R.id.btn_search, R.id.btn_menu, R.id.btn_confirm, R.id.btn_empty,
            R.id.btn_search_back, R.id.btn_search_clear
        ).forEach { id ->
            when (val v = activity.findViewById<android.view.View>(id)) {
                is TextView -> v.setTextColor(fg)
                is ImageView -> v.setColorFilter(fg)
            }
        }

        // 搜索行（主界面）：背景随主题色，输入文字同色、提示文字半透明
        activity.findViewById<android.view.View>(R.id.search_row)?.setBackgroundColor(color)
        activity.findViewById<TextView>(R.id.search_input)?.let {
            it.setTextColor(fg)
            it.setHintTextColor((fg and 0x00FFFFFF) or 0x80FF0000.toInt())
        }

        // FAB（主界面）
        activity.findViewById<FloatingActionButton>(R.id.fab)?.let {
            it.backgroundTintList = ColorStateList.valueOf(color)
            it.imageTintList = ColorStateList.valueOf(fg)
        }

        // 新建菜单两钮（待办 / 便签）：与 FAB 同形状同主题色
        intArrayOf(R.id.btn_new_todo, R.id.btn_new_note).forEach { id ->
            activity.findViewById<FloatingActionButton>(id)?.let {
                it.backgroundTintList = ColorStateList.valueOf(color)
                it.imageTintList = ColorStateList.valueOf(fg)
            }
        }

        // 编辑页“保存”按钮跟随主题色
        activity.findViewById<Button>(R.id.btn_save)?.let {
            it.backgroundTintList = ColorStateList.valueOf(color)
            it.setTextColor(fg)
        }
    }

    /** 近似感知亮度 0..255 */
    private fun brightness(c: Int): Int =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
}
