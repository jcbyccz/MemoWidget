package com.cczsx.notepad.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cczsx.notepad.R
import com.cczsx.notepad.data.PhotoStore

/**
 * 照片裁剪页：便签照片选定后的编辑步骤——
 * 打开即整幅展示（初始裁剪框 = 整图，不动手直接保存即原图插入），
 * 可拖四角锚点调整裁剪区、拖框平移、底部按钮旋转 90°，右上保存插入。
 * 左上返回 = 放弃本次添加。
 */
class CropEditActivity : AppCompatActivity() {

    companion object {
        /** 待编辑照片的相册 URI（字符串） */
        const val EXTRA_URI = "uri"
        /** 落盘文件名前缀（如 "n100014_"），由编辑页传入 */
        const val EXTRA_PREFIX = "prefix"
        /** 结果回传：裁剪后落盘的照片文件名 */
        const val EXTRA_NAME = "name"
    }

    private val cropView by lazy { findViewById<CropImageView>(R.id.crop_view) }
    /** 保存进行中标记：防连续点击重复落盘 */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val uri = intent.getStringExtra(EXTRA_URI)
        val prefix = intent.getStringExtra(EXTRA_PREFIX) ?: ""
        findViewById<View>(R.id.btn_crop_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_crop_save).setOnClickListener { save(prefix) }
        findViewById<View>(R.id.btn_crop_rotate).setOnClickListener { cropView.rotate90() }

        if (uri == null) {
            finish()
            return
        }
        Thread {
            val b = PhotoStore.decodeUri(this, android.net.Uri.parse(uri))
            runOnUiThread {
                if (b == null) {
                    Toast.makeText(
                        this, "照片读取失败：${PhotoStore.lastError}".trimEnd('：'), Toast.LENGTH_LONG
                    ).show()
                    finish()
                } else {
                    cropView.setImageBitmap(b)
                }
            }
        }.start()
    }

    private fun save(prefix: String) {
        if (busy) return
        val b = cropView.crop()
        if (b == null) {
            Toast.makeText(this, "裁剪区域无效，请重新调整", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        Thread {
            val name = PhotoStore.saveBitmap(this, prefix, b)
            runOnUiThread {
                busy = false
                if (name == null) {
                    Toast.makeText(
                        this, "照片保存失败：${PhotoStore.lastError}".trimEnd('：'), Toast.LENGTH_LONG
                    ).show()
                } else {
                    setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_NAME, name))
                    finish()
                }
            }
        }.start()
    }
}
