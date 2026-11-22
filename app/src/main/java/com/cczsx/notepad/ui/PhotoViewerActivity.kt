package com.cczsx.notepad.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.cczsx.notepad.R
import com.cczsx.notepad.data.PhotoStore

/**
 * 大图浏览页：编辑页缩略图点开的全屏浏览。
 * - 顶栏：左 ✕ 关闭、中 张数计数（1/3）、右 删除；
 * - 左右滑动整页切换（RecyclerView + PagerSnapHelper，零新增依赖）；
 * - 删除在页内即时生效，计数同步；全部删完自动退出；
 * - 退出时把剩余照片列表回传编辑页，由编辑页负责删文件与刷新缩略图。
 */
class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        /** 便签照片文件名列表 */
        const val EXTRA_PHOTOS = "photos"
        /** 打开时定位到第几张（0 起） */
        const val EXTRA_INDEX = "index"
        /** 回传：浏览（可能删除过）之后的剩余照片列表 */
        const val EXTRA_RESULT = "result"
    }

    private lateinit var recycler: RecyclerView
    private lateinit var counter: TextView
    private val photos = mutableListOf<String>()
    private val snap = PagerSnapHelper()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        photos.addAll(intent.getStringArrayListExtra(EXTRA_PHOTOS) ?: arrayListOf())
        if (photos.isEmpty()) {
            finish()
            return
        }

        recycler = findViewById(R.id.viewer_pager)
        counter = findViewById(R.id.tv_viewer_count)

        val lm = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        recycler.layoutManager = lm
        snap.attachToRecyclerView(recycler)
        recycler.adapter = PhotoAdapter()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) updateCounter()
            }
        })

        val idx = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, photos.size - 1)
        lm.scrollToPosition(idx)
        counter.text = "${idx + 1}/${photos.size}"

        findViewById<View>(R.id.btn_viewer_close).setOnClickListener { done() }
        findViewById<View>(R.id.btn_viewer_delete).setOnClickListener { deleteCurrent() }
    }

    /** 当前停留的照片下标（按 SnapHelper 的落点） */
    private fun snapPos(): Int {
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return 0
        return snap.findSnapView(lm)?.let { lm.getPosition(it) } ?: 0
    }

    private fun updateCounter() {
        if (photos.isNotEmpty()) counter.text = "${snapPos() + 1}/${photos.size}"
    }

    /** 删除当前照片：计数同步，删完退出，否则跳到相邻一张 */
    private fun deleteCurrent() {
        if (photos.isEmpty()) return
        val pos = snapPos().coerceIn(0, photos.size - 1)
        photos.removeAt(pos)
        recycler.adapter?.notifyItemRemoved(pos)
        if (photos.isEmpty()) {
            done()
            return
        }
        val next = pos.coerceAtMost(photos.size - 1)
        recycler.scrollToPosition(next)
        counter.text = "${next + 1}/${photos.size}"
        recycler.post { updateCounter() }
    }

    /** 退出并回传剩余列表（编辑页据此删文件、更新便签与缩略图） */
    private fun done() {
        setResult(RESULT_OK, android.content.Intent().putStringArrayListExtra(EXTRA_RESULT, java.util.ArrayList(photos)))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        done()
    }

    /** 每页一张 fit-center 大图，后台解码、绑定校验防串图 */
    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.Holder>() {

        inner class Holder(val iv: ImageView) : RecyclerView.ViewHolder(iv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val iv = ImageView(parent.context)
            iv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            return Holder(iv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val name = photos[position]
            holder.iv.tag = name
            holder.iv.setImageDrawable(null)
            val ctx = this@PhotoViewerActivity
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            Thread {
                val b = PhotoStore.loadBitmap(ctx, name, w, h, centerCrop = false)
                runOnUiThread {
                    if (holder.iv.tag == name) holder.iv.setImageBitmap(b)
                }
            }.start()
        }

        override fun getItemCount(): Int = photos.size
    }
}
