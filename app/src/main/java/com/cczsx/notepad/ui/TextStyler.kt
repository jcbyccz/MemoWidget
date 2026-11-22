package com.cczsx.notepad.ui

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.Spanned
import android.text.style.ScaleXSpan
import android.view.View
import android.widget.EditText
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
     * 给 View 套上背景（含透明度）与边框。
     * MaterialCardView → 卡片背景色 + 描边；普通 View → 圆角渐变背景（预览、配置页色块）。
     * 设置了背景照片时（非卡片视图）：照片即背景主体，与小部件画布同语义——
     * 「不透明度」控制照片浓度（调低透出底下的棋盘格/壁纸），背景颜色层不再叠加；
     * checkerTile 非空时（编辑页预览）垫在照片底下，照片半透明时透明效果一目了然。
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
            return
        }
        val layers = ArrayList<android.graphics.drawable.Drawable>()
        if (checkerTile != null) layers.add(checkerTile.mutate())
        // 背景照片：解码成功且不透明度 > 0 时取代颜色层；读取失败回退颜色背景
        val photoBmp = if (note.bgPhoto.isNotBlank()) {
            com.cczsx.notepad.data.PhotoStore.loadBitmap(v.context, note.bgPhoto, 640, 320, centerCrop = true)
        } else null
        val photoAlpha = (note.alpha.coerceIn(0f, 1f) * 255).toInt()
        if (photoBmp != null && photoAlpha > 0) {
            val pd = android.graphics.drawable.BitmapDrawable(v.resources, photoBmp)
            pd.gravity = android.view.Gravity.FILL
            pd.alpha = photoAlpha
            layers.add(pd)
            if (note.borderColor == 0) {
                v.background = if (layers.size == 1) layers[0]
                else android.graphics.drawable.LayerDrawable(layers.toTypedArray())
            } else {
                // 与小部件渲染保持一致：照片整体内缩 2dp，边框环同心包在照片外侧
                val strokePx = dp(2)
                val ring = GradientDrawable()
                ring.cornerRadius = dp(11).toFloat()
                ring.setColor(Color.TRANSPARENT)
                ring.setStroke(strokePx, note.borderColor)
                layers.add(ring)
                val ld = android.graphics.drawable.LayerDrawable(layers.toTypedArray())
                ld.setLayerInset(layers.size - 2, strokePx, strokePx, strokePx, strokePx)
                v.background = ld
            }
            return
        }
        if (note.borderColor == 0) {
            val d = GradientDrawable()
            d.cornerRadius = dp(12).toFloat()
            d.setColor(bg)
            layers.add(d)
            v.background = if (layers.size == 1) layers[0]
            else android.graphics.drawable.LayerDrawable(layers.toTypedArray())
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
            layers.add(fill)
            layers.add(ring)
            val ld = android.graphics.drawable.LayerDrawable(layers.toTypedArray())
            ld.setLayerInset(layers.size - 2, strokePx, strokePx, strokePx, strokePx)
            v.background = ld
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

    /** 行首空白统一宽度：空格与全角空格 \u3000 都按 0.5 字宽/字符（2026-11-19
     *  用户定稿，与小部件 MemoWidgetService.indentWidth 同口径，编辑页所见即部件所得）；
     *  Tab 不参与（保持自然字形，小部件侧为 2 字宽，罕见用法不同步）。
     *  逐字符套 ScaleXSpan：scale = 0.5em / 该字符自然宽（编辑页画笔实测），
     *  只改显示度量、不动文本内容，保存的原文不受影响；每次文本变化后调用重建。
     *  （2026-11-19 换代：旧 ReplacementSpan 版把整段空白捆成一个组合编辑单元，
     *  一次退格整段删除——用户实证反馈；ScaleXSpan 不替换渲染，编辑行为与普通文本一致：
     *  退格逐空格删、光标可停任意字符间） */
    fun applyLeadingSpaceSpans(ed: EditText) {
        val text: Editable = ed.text ?: return
        text.getSpans(0, text.length, LeadingSpaceSpan::class.java).forEach { text.removeSpan(it) }
        if (text.isEmpty()) return
        val emPx = ed.textSize
        val paint = ed.paint
        var lineStart = 0
        val len = text.length
        while (lineStart <= len) {
            var j = lineStart
            while (j < len && (text[j] == ' ' || text[j] == '\u3000')) j++
            for (k in lineStart until j) {
                val w0 = paint.measureText(text[k].toString())
                val scale = if (w0 > 0f) (0.5f * emPx) / w0 else 1f
                text.setSpan(LeadingSpaceSpan(scale), k, k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val nl = text.indexOf('\n', j)
            if (nl < 0) break
            lineStart = nl + 1
        }
    }
}

/** 行首空白单字符宽度缩放 span：scale = 0.5em / 字符自然宽。包一层便于按类型清理重建；
 *  ScaleXSpan 只改测量/绘制的水平缩放，不捆绑字符，编辑单元不变 */
class LeadingSpaceSpan(scaleX: Float) : ScaleXSpan(scaleX)