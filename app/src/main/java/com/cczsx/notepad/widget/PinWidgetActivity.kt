package com.cczsx.notepad.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cczsx.notepad.R

/**
 * “添加小部件”引导页（可见页面，替代早先透明快闪方案）：
 * Android 8+ 且启动器支持时调用系统 requestPinAppWidget 弹系统确认框，
 * 小部件配置了 configure activity，添加后系统自动弹出“选择便签”页；
 * 不支持或请求失败时显示手动添加引导，结果始终可见、无静默失败。
 */
class PinWidgetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_widget)

        val status = findViewById<TextView>(R.id.tv_pin_status)
        findViewById<android.view.View>(R.id.btn_pin_close).setOnClickListener { finish() }

        val ok = try {
            val mgr = AppWidgetManager.getInstance(this)
            val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                mgr.isRequestPinAppWidgetSupported
            if (supported) {
                val provider = ComponentName(this, MemoWidgetProvider::class.java)
                mgr.requestPinAppWidget(provider, null, null)
            } else false
        } catch (_: Exception) {
            false
        }

        status.text = if (ok) {
            "已发起添加请求。\n\n支持确认框的桌面：请在弹出的确认框中点“添加”，随后自动打开“选择便签”页。\n\n部分桌面（如 MIUI）不弹确认框、直接把小部件放到桌面：若看到显示“点击选择便签”的小部件，点它即可选择要显示的便签。"
        } else {
            "当前桌面不支持一键添加小部件。\n\n请手动添加：长按桌面空白处 → 选择“窗口小部件” → 找到“正闲拾笺”拖到桌面即可。"
        }
    }
}
