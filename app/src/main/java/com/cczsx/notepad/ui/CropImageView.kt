package com.cczsx.notepad.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 照片裁剪视图：fit-center 展示位图，裁剪框外暗幕压暗，白色边框 + 四角蓝色圆形锚点。
 * - 拖角锚点调整裁剪区、框内拖动整体平移，均限制在图像范围内；
 * - 初始裁剪框为整图（打开即等同预览，不动手直接保存就是原图）；
 * - 旋转 90° 后裁剪框复位为整图。
 * 纯自绘零依赖，风格对照参考交互（黑底 + 蓝色锚点）。
 */
class CropImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : android.view.View(context, attrs) {

    private var bmp: Bitmap? = null
    /** 尚未按视图尺寸排布（setImage 后首次布局时 fit） */
    private var pendingFit = false

    /** 位图 fit-center 后在视图中的矩形 */
    private val imgRect = RectF()
    /** 裁剪框（视图坐标） */
    private val cropRect = RectF()

    private val density = resources.displayMetrics.density
    private val handleR = 11f * density       // 锚点绘制半径
    private val touchR = 26f * density         // 锚点触控热区半径
    private val minCrop = 40f * density       // 裁剪框最小边
    private val pad = 12f * density           // 图像四周留白

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val borderPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2.5f * density; isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = 0xFF2196F3.toInt(); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val handleRing = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2f * density; isAntiAlias = true
    }

    /** 显示位图（触发 fit + 裁剪框复位为整图） */
    fun setImageBitmap(b: Bitmap) {
        bmp = b
        pendingFit = true
        requestLayout()
        invalidate()
    }

    /** 顺时针旋转 90°：重建位图并复位裁剪框 */
    fun rotate90() {
        val b = bmp ?: return
        val rotated = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)
        if (rotated != b) b.recycle()
        bmp = rotated
        pendingFit = true
        fitIfNeeded()
        requestLayout()
        invalidate()
    }

    /** 取裁剪结果（无位图或区域无效时返回 null；未动过裁剪框时直接返回原图） */
    fun crop(): Bitmap? {
        val b = bmp ?: return null
        if (imgRect.isEmpty) return null
        if (abs(cropRect.left - imgRect.left) < 1f && abs(cropRect.top - imgRect.top) < 1f &&
            abs(cropRect.right - imgRect.right) < 1f && abs(cropRect.bottom - imgRect.bottom) < 1f
        ) return b
        val scale = imgRect.width() / b.width
        val x = ((cropRect.left - imgRect.left) / scale).toInt().coerceIn(0, b.width - 1)
        val y = ((cropRect.top - imgRect.top) / scale).toInt().coerceIn(0, b.height - 1)
        val w = (((cropRect.right - imgRect.left) / scale).toInt() - x).coerceAtLeast(1).coerceAtMost(b.width - x)
        val h = (((cropRect.bottom - imgRect.top) / scale).toInt() - y).coerceAtLeast(1).coerceAtMost(b.height - y)
        return Bitmap.createBitmap(b, x, y, w, h)
    }

    // ---------- 内部 ----------

    private fun fitIfNeeded() {
        val b = bmp ?: return
        if (width <= 0 || height <= 0 || !pendingFit) return
        pendingFit = false
        val vw = width - pad * 2
        val vh = height - pad * 2
        val s = min(vw / b.width, vh / b.height)
        val dw = b.width * s
        val dh = b.height * s
        imgRect.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        cropRect.set(imgRect)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, bt: Int) {
        super.onLayout(changed, l, t, r, bt)
        fitIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp ?: return
        canvas.drawBitmap(b, null, imgRect, bitmapPaint)
        // 暗幕：裁剪框外全屏压暗（框内保持原亮，参考效果）
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)
        // 白色边框 + 四角蓝色锚点（带白描边，深浅图上都清晰）
        canvas.drawRect(cropRect, borderPaint)
        cornerPoints().forEach { (cx, cy) ->
            canvas.drawCircle(cx, cy, handleR, handlePaint)
            canvas.drawCircle(cx, cy, handleR, handleRing)
        }
    }

    private fun cornerPoints(): List<Pair<Float, Float>> = listOf(
        cropRect.left to cropRect.top, cropRect.right to cropRect.top,
        cropRect.right to cropRect.bottom, cropRect.left to cropRect.bottom
    )

    private var mode = 0        // 0 无 1 拖角 2 拖框
    private var dragCorner = 0 // 0 左上 1 右上 2 右下 3 左下
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                mode = 0
                val corner = hitCorner(x, y)
                if (corner >= 0) {
                    mode = 1
                    dragCorner = corner
                } else if (cropRect.contains(x, y)) {
                    mode = 2
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    1 -> {
                        val l = cropRect.left
                        val t = cropRect.top
                        val r = cropRect.right
                        val b = cropRect.bottom
                        when (dragCorner) {
                            0 -> {
                                cropRect.left = x.coerceIn(imgRect.left, max(imgRect.left, r - minCrop))
                                cropRect.top = y.coerceIn(imgRect.top, max(imgRect.top, b - minCrop))
                            }
                            1 -> {
                                cropRect.right = x.coerceIn(min(imgRect.right, l + minCrop), imgRect.right)
                                cropRect.top = y.coerceIn(imgRect.top, max(imgRect.top, b - minCrop))
                            }
                            2 -> {
                                cropRect.right = x.coerceIn(min(imgRect.right, l + minCrop), imgRect.right)
                                cropRect.bottom = y.coerceIn(min(imgRect.bottom, t + minCrop), imgRect.bottom)
                            }
                            3 -> {
                                cropRect.left = x.coerceIn(imgRect.left, max(imgRect.left, r - minCrop))
                                cropRect.bottom = y.coerceIn(min(imgRect.bottom, t + minCrop), imgRect.bottom)
                            }
                        }
                        invalidate()
                    }
                    2 -> {
                        val w = cropRect.width()
                        val h = cropRect.height()
                        val nl = (cropRect.left + (x - lastX)).coerceIn(imgRect.left, max(imgRect.left, imgRect.right - w))
                        val nt = (cropRect.top + (y - lastY)).coerceIn(imgRect.top, max(imgRect.top, imgRect.bottom - h))
                        cropRect.offsetTo(nl, nt)
                        invalidate()
                    }
                }
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = 0
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun hitCorner(x: Float, y: Float): Int {
        cornerPoints().forEachIndexed { i, (cx, cy) ->
            if (abs(x - cx) <= touchR && abs(y - cy) <= touchR) return i
        }
        return -1
    }
}
