package com.cczsx.notepad.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cczsx.notepad.R
import com.cczsx.notepad.data.BackupManager

/**
 * 设置页：版本信息 + 主题色自定义（工具栏 / 状态栏 / 整体风格取色）
 * + 备份与恢复（导出 / 导入 / 从自动备份恢复）+ 自动备份间隔设置。
 */
class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val REQ_EXPORT = 5001
        const val REQ_IMPORT = 5002
        const val MIME = "application/json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemePrefs.apply(this)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // 运行时读取真实版本号（无需 BuildConfig）
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
        } catch (_: Exception) {
            "未知"
        }
        findViewById<TextView>(R.id.tv_version).text = "v$versionName"

        buildThemeSwatches()

        // 底部回收站入口：点击进入回收站页（原主界面底部入口移入此处）
        findViewById<android.view.View>(R.id.btn_recycle_entry).setOnClickListener {
            startActivity(android.content.Intent(this, RecycleBinActivity::class.java))
        }

        bindBackupUi()
    }

    override fun onResume() {
        super.onResume()
        // 回到设置页时刷新最近备份状态（自动备份在每次冷启动时可能已更新）
        updateIntervalLabel()
    }

    // ---------- 备份与恢复 ----------

    /** 导出 / 导入 / 从自动备份恢复 / 自动备份间隔 */
    private fun bindBackupUi() {
        BackupManager.init(this)

        // 导出：SAF 选保存位置，写入完整快照 JSON
        findViewById<android.view.View>(R.id.btn_backup_export).setOnClickListener {
            val stamp = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss", java.util.Locale.getDefault()
            ).format(java.util.Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = MIME
                putExtra(Intent.EXTRA_TITLE, "便签备份-$stamp.json")
            }
            startActivityForResult(intent, REQ_EXPORT)
        }

        // 导入：SAF 选备份文件，按 id 合并（同号覆盖、缺失新增）
        findViewById<android.view.View>(R.id.btn_backup_import).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME, "text/plain", "application/octet-stream"))
            }
            startActivityForResult(intent, REQ_IMPORT)
        }

        // 从自动备份恢复：取最近一次自动备份文件合并导入
        findViewById<android.view.View>(R.id.btn_backup_restore).setOnClickListener {
            when (val n = BackupManager.restoreLatestBackup()) {
                -1 -> Toast.makeText(this, "备份文件损坏，恢复失败", Toast.LENGTH_SHORT).show()
                0 -> Toast.makeText(this, "还没有自动备份", Toast.LENGTH_SHORT).show()
                else -> {
                    com.cczsx.notepad.widget.MemoWidgetProvider.refreshAll(this)
                    Toast.makeText(this, "已从最近一次自动备份恢复 $n 条", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 自动备份间隔：单选弹窗（1/3/7/30/999 天）
        updateIntervalLabel()
        findViewById<android.view.View>(R.id.btn_backup_interval).setOnClickListener { showIntervalDialog() }
    }

    private fun updateIntervalLabel() {
        findViewById<TextView>(R.id.tv_backup_interval).text =
            "每 ${BackupManager.intervalDays()} 天"
        // 最近备份状态：备份存在应用私有目录里，用户看不到文件，
        // 在此显示最近一次备份时间让自动备份可见可感知
        val last = BackupManager.latestBackupTime()
        findViewById<TextView>(R.id.tv_backup_status).text = if (last > 0) {
            val fmt = java.text.SimpleDateFormat("yyyy/M/d(E) HH:mm", java.util.Locale.CHINA)
            "最近备份：" + fmt.format(java.util.Date(last))
        } else {
            "最近备份：暂无（到达设定间隔后打开应用时生成）"
        }
    }

    private fun showIntervalDialog() {
        val intervals = BackupManager.INTERVALS
        val labels = intervals.map { "每 $it 天" }.toTypedArray()
        val current = BackupManager.intervalDays()
        val checked = intervals.indexOfFirst { it == current }.let { if (it < 0) 0 else it }
        AlertDialog.Builder(this)
            .setTitle("备份时间间隔")
            .setSingleChoiceItems(labels, checked) { d, which ->
                BackupManager.setIntervalDays(intervals[which])
                updateIntervalLabel()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_EXPORT -> exportTo(data.data)
            REQ_IMPORT -> importFrom(data.data)
        }
    }

    private fun exportTo(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(BackupManager.snapshotJson().toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException()
            Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "导出失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFrom(uri: Uri?) {
        if (uri == null) return
        try {
            val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException()
            when (val n = BackupManager.mergeImport(json)) {
                -1 -> Toast.makeText(this, "文件格式不正确，导入失败", Toast.LENGTH_SHORT).show()
                else -> {
                    com.cczsx.notepad.widget.MemoWidgetProvider.refreshAll(this)
                    Toast.makeText(this, "导入成功，共 $n 条", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "导入失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    /** 主题色选择行：[自定义✎][预设×6]，选中打勾，选择后立即生效 */
    private fun buildThemeSwatches() {
        val container = findViewById<LinearLayout>(R.id.swatch_theme)
        container.removeAllViews()
        val current = ThemePrefs.load(this)
        val density = resources.displayMetrics.density

        fun makeCircle(color: Int, selected: Boolean, label: String? = null, onClick: () -> Unit) {
            val size = (30 * density).toInt()
            val d = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((1 * density).toInt(), ColorStateList.valueOf(Color.DKGRAY))
            }
            val inner = android.view.View(this).apply { background = d }
            val wrap = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                }
                addView(inner, FrameLayout.LayoutParams(size, size))
                setOnClickListener { onClick() }
            }
            if (selected) {
                val badgeSize = (13 * density).toInt()
                wrap.addView(TextView(this).apply {
                    text = "✓"
                    textSize = 9f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xEE212121.toInt())
                    }
                }, FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END))
            }
            if (label != null) {
                // 带文字标签的色圈（用于“自定义”入口）：圆圈下方显示小字
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                col.addView(wrap)
                col.addView(TextView(this).apply {
                    text = label
                    textSize = 10f
                    setTextColor(0xFF757575.toInt())
                })
                container.addView(col)
            } else {
                container.addView(wrap)
            }
        }

        fun pick(color: Int) {
            // 主题色强制不透明（工具栏/状态栏不允许半透明）
            ThemePrefs.save(this, color or 0xFF000000.toInt())
            ThemePrefs.apply(this)
            buildThemeSwatches()
        }

        // 自定义：打开 HSV + RGB + 十六进制取色器（主题色必须不透明，隐藏透明度滑杆），
        // 圆圈下方标“自定义”让入口更直观
        val isPreset = ThemePrefs.PRESETS.contains(current)
        makeCircle(if (isPreset) Color.WHITE else current, selected = !isPreset, label = "自定义") {
            ColorPickerDialog.show(this, ThemePrefs.load(this), { picked ->
                pick(picked)
            }, allowAlpha = false)
        }
        // 预设色
        ThemePrefs.PRESETS.forEach { c ->
            makeCircle(c, selected = current == c) { pick(c) }
        }
    }
}
