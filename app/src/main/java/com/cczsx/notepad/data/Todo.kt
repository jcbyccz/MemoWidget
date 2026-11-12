package com.cczsx.notepad.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 待办条目：一行可勾选的事项。
 */
data class TodoItem(
    var text: String = "",
    var done: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("done", done)
    }

    companion object {
        fun fromJson(o: JSONObject): TodoItem = TodoItem(
            text = o.optString("text", ""),
            done = o.optBoolean("done", false)
        )
    }
}

/**
 * 待办：题目 + 多行可勾选条目，样式字段与便签一致（用于“设置小部件设计”）。
 * items 为单一有序列表，渲染时按 done 分成“未完成 / 已完成”两组，组内保持列表顺序。
 */
data class Todo(
    var id: Long,
    var title: String = "",
    var items: MutableList<TodoItem> = mutableListOf(),
    // 样式（与 Note 同款语义：0 = 无 / 默认）
    var bgColor: Int = 0,
    var alpha: Float = 1.0f,
    var borderColor: Int = 0xFF4C8C6A.toInt(),
    var fontColor: Int = 0xFF1F1F1F.toInt(),
    var fontSize: Int = 14,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var shadow: Boolean = false,
    var alignH: Int = 1,
    var alignV: Int = 0,
    var scrollEnabled: Boolean = true,
    // 状态
    var pinned: Boolean = false,
    /** 齿轮菜单：隐藏已完成的事情 */
    var hideDone: Boolean = false,
    var deleted: Boolean = false,
    var deletedAt: Long = 0L,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var widgetId: Long = -1L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        put("items", arr)
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
        put("hideDone", hideDone)
        put("deleted", deleted)
        put("deletedAt", deletedAt)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("widgetId", widgetId)
    }

    /** 渲染用字体颜色：0 表示“无颜色”，显示按默认墨黑处理 */
    val effectiveFontColor: Int
        get() = if (fontColor == 0) 0xFF1F1F1F.toInt() else fontColor

    /** 内容指纹：题目/条目/样式/隐藏设置拼接。保存时与库中旧值对比，相同则不刷新修改时间 */
    fun contentKey(): String = listOf(
        title,
        items.joinToString("|") { "${it.text}=${it.done}" },
        bgColor, alpha, borderColor, fontColor, fontSize,
        bold, italic, underline, shadow, alignH, alignV, scrollEnabled, hideDone
    ).joinToString("§")

    /** 转成仅含样式字段的 Note 视图对象，复用 TextStyler / 颜色行等既有渲染逻辑 */
    fun styleNote(): Note = Note(
        id = id, title = title,
        bgColor = bgColor, alpha = alpha, borderColor = borderColor,
        fontColor = fontColor, fontSize = fontSize,
        bold = bold, italic = italic, underline = underline, shadow = shadow,
        alignH = alignH, alignV = alignV, scrollEnabled = scrollEnabled,
        pinned = pinned
    )

    companion object {
        fun fromJson(o: JSONObject): Todo = Todo(
            id = o.optLong("id", 0),
            title = o.optString("title", ""),
            items = mutableListOf<TodoItem>().also { arr ->
                val a = o.optJSONArray("items")
                if (a != null) for (i in 0 until a.length()) arr.add(TodoItem.fromJson(a.getJSONObject(i)))
            },
            bgColor = o.optInt("bgColor", 0),
            alpha = o.optDouble("alpha", 1.0).toFloat(),
            borderColor = o.optInt("borderColor", 0xFF4C8C6A.toInt()),
            fontColor = o.optInt("fontColor", 0xFF1F1F1F.toInt()),
            fontSize = o.optInt("fontSize", 14),
            bold = o.optBoolean("bold", false),
            italic = o.optBoolean("italic", false),
            underline = o.optBoolean("underline", false),
            shadow = o.optBoolean("shadow", false),
            alignH = o.optInt("alignH", 1),
            alignV = o.optInt("alignV", 0),
            scrollEnabled = o.optBoolean("scrollEnabled", true),
            pinned = o.optBoolean("pinned", false),
            hideDone = o.optBoolean("hideDone", false),
            deleted = o.optBoolean("deleted", false),
            deletedAt = o.optLong("deletedAt", 0L),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            widgetId = o.optLong("widgetId", -1L)
        )
    }
}
