package com.cczsx.notepad.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cczsx.notepad.R

/**
 * “添加小部件”引导页：
 * 展示说明 +「一键添加到桌面」按钮，用户点击后才发起系统 pin 请求。
 *
 * 不能在 onCreate 里自动请求：MIUI 收到请求会立即拉起系统确认框，
 * 确认框覆盖并关闭本页，用户看到引导页“一闪而过”、什么都没看清。
 * 改为按钮触发后页面稳定停留，用户理解流程再操作，任何结果都有文字反馈。
 *
 * successCallback 必须传 PendingIntent（空回调在 MIUI 上 pin 请求被静默丢弃，
 * “添加小部件点上没反应”就是那个问题）。
 */
class PinWidgetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_widget)

        val status = findViewById<TextView>(R.id.tv_pin_status)
        val btnPin = findViewById<Button>(R.id.btn_pin_action)
        findViewById<android.view.View>(R.id.btn_pin_close).setOnClickListener { finish() }

        btnPin.text = "一键添加到桌面"
        btnPin.setOnClickListener {
            val ok = try {
                val mgr = AppWidgetManager.getInstance(this)
                val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    mgr.isRequestPinAppWidgetSupported
                if (supported) {
                    val provider = ComponentName(this, MemoWidgetProvider::class.java)
                    val callbackIntent = Intent(this, PinResultReceiver::class.java)
                    val pending = PendingIntent.getBroadcast(
                        this, 0, callbackIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    mgr.requestPinAppWidget(provider, null, pending)
                } else false
            } catch (_: Exception) {
                false
            }

            btnPin.isEnabled = false
            status.text = if (ok) {
                "已发起添加请求。\n\n· 若弹出系统确认框：点“添加”，随后自动打开“选择便签”页。\n\n· 部分 MIUI 不弹确认框、直接把小部件放到桌面：若看到显示“点击选择便签”的小部件，点它即可选择要显示的便签。\n\n· 若桌面无反应：请在系统设置的权限管理中，允许本应用“桌面快捷方式”权限后重试。"
            } else {
                "当前桌面不支持一键添加小部件。\n\n请手动添加：长按桌面空白处 → 选择“窗口小部件” → 找到“正闲拾笺”拖到桌面即可。"
            }
        }
    }

    /** pin 请求系统的结果回调接收器（无 UI，仅占位保证回调链完整） */
    class PinResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 结果无需处理：配置页在 widget 添加成功后由系统自动拉起
        }
    }
}
