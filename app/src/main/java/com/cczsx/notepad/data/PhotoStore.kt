package com.cczsx.notepad.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.media.ExifInterface
import android.util.LruCache
import java.io.File

/**
 * 照片存储：所有照片（便签照片、小部件背景照片）统一保存在应用私有目录 files/photos/。
 * - 导入：从相册 URI 降采样（最长边 ≤1600px）+ EXIF 旋转矫正，存为 JPEG；
 * - 读取：按目标尺寸降采样，可选 centerCrop，内置 LruCache 避免列表/预览反复解码；
 * - 备份：文件以 base64 内嵌进备份 JSON，导入时落盘还原。
 */
object PhotoStore {

    /** 导入照片的最长边（px）：兼顾清晰度与备份体积 */
    private const val MAX_EDGE = 1600

    /** 最近一次导入/读取的结果（成功为 "ok 宽x高"，失败为原因）：设备禁 logcat 时靠它区分失败环节 */
    @Volatile var lastError: String? = null

    /** 解码缓存：key = "name@w×h"，按张数限流（照片多为缩略用途，12 张足够覆盖常见界面） */
    private val cache = object : LruCache<String, Bitmap>(12) {}

    fun dir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun exists(context: Context, name: String): Boolean = name.isNotBlank() && file(context, name).exists()

    /**
     * 从相册 URI 导入一张照片，成功返回文件名（失败返回 null）。
     * prefix 用于区分用途（如便签照片 / 背景照片），文件名 = prefix + 时间戳.jpg
     */
    fun importFrom(context: Context, uri: android.net.Uri, prefix: String): String? {
        val bmp = decodeUri(context, uri) ?: return null
        return saveBitmap(context, prefix, bmp)
    }

    /**
     * 解码相册 URI 为位图：按边界降采样 + EXIF 旋转矫正 + 最长边压到 MAX_EDGE 内。
     * 相册直读与裁剪页共用；失败置 lastError 并返回 null。
     */
    fun decodeUri(context: Context, uri: android.net.Uri): Bitmap? {
        lastError = null
        return try {
            val resolver = context.contentResolver
            // 1. 读边界算采样
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val ins = resolver.openInputStream(uri)
            if (ins == null) {
                lastError = "无法打开图片流"
                return null
            }
            ins.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                lastError = "无法解析图片尺寸(${bounds.outMimeType})"
                return null
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
            // 2. 按采样解码
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (raw == null) {
                lastError = "位图解码失败(${bounds.outMimeType})"
                return null
            }
            // 3. EXIF 旋转矫正（拍照方向不一致的常见问题）
            val rotated = rotateIfNeeded(context, uri, raw)
            // 4. 仍超限则整体缩到 MAX_EDGE 内
            val result = scaleDown(rotated, MAX_EDGE)
            if (result !== rotated) rotated.recycle()
            if (rotated !== raw) raw.recycle()
            result
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    /** 把位图存为应用私有照片文件，返回文件名（失败置 lastError 并返回 null） */
    fun saveBitmap(context: Context, prefix: String, bmp: Bitmap): String? {
        lastError = null
        return try {
            val name = "${prefix}${System.currentTimeMillis()}.jpg"
            val out = file(context, name)
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            name
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    /** 删除照片文件（文件不存在时静默） */
    fun delete(context: Context, name: String) {
        if (name.isBlank()) return
        val f = file(context, name)
        if (f.exists()) f.delete()
        // 同名各级缓存一并失效
        cache.snapshot().keys.filter { it.startsWith("$name@") }.forEach { cache.remove(it) }
    }

    /**
     * 读取照片位图：按目标尺寸降采样；centerCrop = true 时裁成 targetW×targetH 满铺。
     * 小部件画布、预览、缩略图都走这里，命中缓存不再解码。
     */
    fun loadBitmap(context: Context, name: String, targetW: Int, targetH: Int, centerCrop: Boolean): Bitmap? {
        if (name.isBlank()) { lastError = "空文件名"; return null }
        val key = "$name@${targetW}x${targetH}c$centerCrop"
        cache.get(key)?.let { lastError = "缓存命中"; return it }
        val f = file(context, name)
        if (!f.exists()) { lastError = "文件不存在:$name"; return null }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                lastError = "尺寸解析失败:$name"
                return null
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(f.absolutePath, opts)
            if (bmp == null) { lastError = "解码失败:$name"; return null }
            if (centerCrop && (bmp.width != targetW || bmp.height != targetH)) {
                try {
                    bmp = centerCropTo(bmp, targetW, targetH)
                } catch (e: Exception) {
                    lastError = "裁剪异常:${e.javaClass.simpleName}"
                    return null
                }
            } else if (!centerCrop) {
                // 非裁剪用途（编辑区照片、大图浏览）：整体等比压到目标框内，避免大图整图进缓存
                bmp = fitDown(bmp, targetW, targetH)
            }
            lastError = "ok ${bmp.width}x${bmp.height}"
            cache.put(key, bmp)
            bmp
        } catch (e: Exception) {
            lastError = "读取异常:${e.javaClass.simpleName}:${e.message}"
            null
        }
    }

    /** 读取照片原始字节（备份内嵌用） */
    fun readBytes(context: Context, name: String): ByteArray? {
        val f = file(context, name)
        if (!f.exists()) return null
        return try { f.readBytes() } catch (_: Exception) { null }
    }

    /** 备份还原：把字节写成照片文件（已存在则覆盖） */
    fun writeBytes(context: Context, name: String, bytes: ByteArray): Boolean {
        return try {
            file(context, name).writeBytes(bytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------- 内部 ----------

    private fun rotateIfNeeded(context: Context, uri: android.net.Uri, bmp: Bitmap): Bitmap {
        return try {
            val deg = context.contentResolver.openInputStream(uri)?.use { ins ->
                when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            if (deg == 0f) return bmp
            val m = Matrix().apply { postRotate(deg) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (_: Exception) {
            bmp
        }
    }

    private fun scaleDown(bmp: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true
        )
    }

    /** 等比缩小到目标框内（宽 ≤ maxW 且高 ≤ maxH；本身在框内则原样返回） */
    private fun fitDown(bmp: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val s = minOf(
            maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height, 1f
        )
        if (s >= 1f) return bmp
        return Bitmap.createScaledBitmap(
            bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true
        )
    }

    /** 居中裁剪到 targetW×targetH（放大或缩小，short 边对齐 centerCrop） */
    private fun centerCropTo(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val outW = targetW.coerceAtLeast(1)
        val outH = targetH.coerceAtLeast(1)
        val scale = maxOf(outW.toFloat() / src.width, outH.toFloat() / src.height)
        val drawW = (src.width * scale).toInt().coerceAtLeast(outW)
        val drawH = (src.height * scale).toInt().coerceAtLeast(outH)
        val scaled = if (drawW == src.width && drawH == src.height) src
        else Bitmap.createScaledBitmap(src, drawW, drawH, true)
        val x = ((drawW - outW) / 2f).toInt()
        val y = ((drawH - outH) / 2f).toInt()
        return Bitmap.createBitmap(scaled, x, y, outW, outH)
    }

    /** 供画布绘制的圆角裁剪路径（小部件背景用） */
    fun roundedClipPath(left: Float, top: Float, right: Float, bottom: Float, radius: Float): Path =
        Path().apply { addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW) }

    /** 画布绘制辅助：把位图按目标区域 centerCrop 画上去（等比缩放 + 居中裁剪） */
    fun drawCenterCrop(canvas: Canvas, bmp: Bitmap, left: Float, top: Float, right: Float, bottom: Float, paint: android.graphics.Paint) {
        val w = (right - left).coerceAtLeast(1f)
        val h = (bottom - top).coerceAtLeast(1f)
        val scale = maxOf(w / bmp.width, h / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dx = left + (w - dw) / 2f
        val dy = top + (h - dh) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(bmp, matrix, paint)
    }
}
