package com.cczsx.notepad.ui

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.cczsx.notepad.data.Note
import com.google.android.material.card.MaterialCardView

/**
 * 统一把便签样式应用到 TextView/View，供主界面卡片、编辑预览、回收站与小部件配置页共用，
 * 保证各处渲染效果一致。
 */
object TextStyler {

    /** 给 TextView 套用字体颜色、大小、加粗/斜体/下划线/投影 */
    fun applyText(tv: TextView, note: Note) {
        tv.setTextColor(note.effectiveFontColor)
        tv.textSize = note.fontSize.toFloat()
        val style = when {
            note.bold && note.italic -> Typeface.BOLD_ITALIC
            note.bold -> Typeface.BOLD
            note.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        // 关键：必须基于“默认字体”重建样式（传 null），不能基于 tv.typeface 叠加——
        // 否则加粗/斜体样式会随历史操作累积、取消不掉（如取消斜体后文字仍倾斜）
        tv.setTypeface(null, style)
        val paint = tv.paint
        if (note.underline) paint.flags = paint.flags or Paint.UNDERLINE_TEXT_FLAG
        else paint.flags = paint.flags and Paint.UNDERLINE_TEXT_FLAG.inv()
        if (note.shadow) {
            tv.setShadowLayer(4f, 1f, 2f, 0x33000000)
        } else {
            tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    /**
     * 给 View 套用背景（含透明度）与边框。
     * MaterialCardView → 卡片背景色 + 描边；普通 View → 圆角渐变背景（预览、配置页色块）。
     * checkerTile 非空时（编辑页预览），背景色叠在棋盘格上，透明效果一目了然。
     */
    fun applyBackground(v: View, note: Note, checkerTile: android.graphics.drawable.Drawable? = null) {
        val bg = com.cczsx.notepad.widget.MemoWidgetProvider.alphaColor(note.bgColor, note.alpha)
        val borderPx = dp(1)
        if (v is MaterialCardView) {
            v.setCardBackgroundColor(bg)
            if (note.borderColor != 0) {
                v.setStrokeColor(note.borderColor)
                v.strokeWidth = borderPx
            } else {
                v.strokeWidth = 0
            }
        } else {
            if (note.borderColor == 0) {
                val d = GradientDrawable()
                d.cornerRadius = dp(12).toFloat()
                d.setColor(bg)
                v.background = if (checkerTile != null) {
                    android.graphics.drawable.LayerDrawable(arrayOf(checkerTile.mutate(), d))
                } else {
                    d
                }
            } else {
                // 与小部件渲染保持一致：背景内缩 2dp，边框环同心包在背景外侧，
                // 两者边缘相接不叠加，圆角处不出现“边框×背景”混色
                val strokePx = dp(2)
                val fill = GradientDrawable()
                fill.cornerRadius = dp(10).toFloat()
                fill.setColor(bg)
                val ring = GradientDrawable()
                ring.cornerRadius = dp(11).toFloat()
                ring.setColor(Color.TRANSPARENT)
                ring.setStroke(strokePx, note.borderColor)
                val layers = ArrayList<android.graphics.drawable.Drawable>()
                if (checkerTile != null) layers.add(checkerTile.mutate())
                layers.add(fill)
                layers.add(ring)
                val ld = android.graphics.drawable.LayerDrawable(layers.toTypedArray())
                ld.setLayerInset(layers.size - 2, strokePx, strokePx, strokePx, strokePx)
                v.background = ld
            }
        }
    }

    /** 生成棋盘格平铺图（透明底纹），供预览背景使用 */
    fun makeCheckerTile(context: android.content.Context, cellDp: Int): android.graphics.drawable.BitmapDrawable {
        val cell = (cellDp * context.resources.displayMetrics.density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(cell * 2, cell * 2, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint()
        p.color = android.graphics.Color.WHITE
        c.drawPaint(p)
        p.color = android.graphics.Color.rgb(224, 224, 224)
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        c.drawRect(cell.toFloat(), cell.toFloat(), (cell * 2).toFloat(), (cell * 2).toFloat(), p)
        val d = android.graphics.drawable.BitmapDrawable(context.resources, bmp)
        d.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        return d
    }

    private fun dp(v: Int): Int = (v * Resources.getSystem().displayMetrics.density).toInt()
}