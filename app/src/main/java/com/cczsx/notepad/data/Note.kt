package com.cczsx.notepad.data

import org.json.JSONObject

/**
 * 便签实体。除基础内容外，还保存桌面小部件所需的全部样式设置：
 * 背景色/透明度、字体颜色/大小、加粗/斜体/下划线/投影、水平/垂直对齐、滚动开关，
 * 以及置顶与回收站状态。
 */
data class Note(
    var id: Long,
    var title: String = "",
    var content: String = "",
    // 背景（颜色 + 透明度，alpha 0..1）；bgColor = 0 表示“无背景”
    var bgColor: Int = 0, // 默认无背景（配森绿边框 + 墨黑字体）
    var alpha: Float = 1.0f,
    // 边框颜色（0 = 无边框）
    var borderColor: Int = 0xFF4C8C6A.toInt(), // 默认森绿
    // 文字样式
    var fontColor: Int = 0xFF1F1F1F.toInt(), // 默认墨黑
    var fontSize: Int = 16,          // sp
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var shadow: Boolean = false,
    // 对齐：水平 0左 1中 2右；垂直 0上 1中 2下；新建默认水平居中、垂直顶部，超出可滚动
    var alignH: Int = 1,
    var alignV: Int = 0,
    var scrollEnabled: Boolean = true,
    // 状态
    var pinned: Boolean = false,
    var deleted: Boolean = false,
    var deletedAt: Long = 0L,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    // 绑定到哪个桌面小部件（-1 表示未绑定）
    var widgetId: Long = -1L
) {
    /** 渲染用字体颜色：fontColor = 0 表示“无颜色”（用户未指定），显示按默认墨黑处理 */
    val effectiveFontColor: Int
        get() = if (fontColor == 0) 0xFF1F1F1F.toInt() else fontColor

    /** 内容指纹：标题/正文/样式字段拼接。保存时与库中旧值对比，相同则不刷新修改时间 */
    fun contentKey(): String = listOf(
        title, content, bgColor, alpha, borderColor, fontColor, fontSize,
        bold, italic, underline, shadow, alignH, alignV, scrollEnabled
    ).joinToString("§")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("content", content)
        put("bgColor", bgColor)
        put("alpha", alpha.toDouble())
        put("borderColor", borderColor)
        put("fontColor", fontColor)
        put("fontSize", fontSize)
        put("bold", bold)
        put("italic", italic)
        put("underline", underline)
        put("shadow", shadow)
        put("alignH", alignH)
        put("alignV", alignV)
        put("scrollEnabled", scrollEnabled)
        put("pinned", pinned)
        put("deleted", deleted)
        put("deletedAt", deletedAt)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("widgetId", widgetId)
    }

    companion object {
        fun fromJson(o: JSONObject): Note = Note(
            id = o.optLong("id", 0),
            title = o.optString("title", ""),
            content = o.optString("content", ""),
            bgColor = o.optInt("bgColor", 0),
            alpha = o.optDouble("alpha", 1.0).toFloat(),
            borderColor = o.optInt("borderColor", 0xFF4C8C6A.toInt()),
            fontColor = o.optInt("fontColor", 0xFF1F1F1F.toInt()),
            fontSize = o.optInt("fontSize", 16),
            bold = o.optBoolean("bold", false),
            italic = o.optBoolean("italic", false),
            underline = o.optBoolean("underline", false),
            shadow = o.optBoolean("shadow", false),
            alignH = o.optInt("alignH", 1),
            alignV = o.optInt("alignV", 0),
            scrollEnabled = o.optBoolean("scrollEnabled", true),
            pinned = o.optBoolean("pinned", false),
            deleted = o.optBoolean("deleted", false),
            deletedAt = o.optLong("deletedAt", 0L),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            widgetId = o.optLong("widgetId", -1L)
        )
    }
}