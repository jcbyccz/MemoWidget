package com.cczsx.notepad.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 轻量 HSV 颜色选择器（无第三方依赖），支持：
 * 1. 大色区选饱和度/明度 + 色带选色相
 * 2. 透明度（不透明度 0-100%）滑杆（allowAlpha=false 时隐藏，用于工具栏主题色等必须不透明的场景）
 * 3. RGB 数值输入（0-255）
 * 4. 十六进制颜色码输入（如 #00FF00）
 * 5. 棋盘格底 + 半透明实时预览
 * 基于 AlertDialog.Builder + setView 的标准实现。
 */
object ColorPickerDialog {

    fun show(context: Context, initialColor: Int, onPick: (Int) -> Unit, allowAlpha: Boolean = true) {
        val hsv = FloatArray(3).also { Color.colorToHSV(initialColor, it) }
        // 不允许透明时（如主题色），固定完全不透明
        var alpha = if (allowAlpha) Color.alpha(initialColor).coerceIn(0, 255) else 255

        val density = context.resources.displayMetrics.density
        fun d(v: Int) = (v * density).toInt()

        fun currentColor(): Int {
            val opaque = Color.HSVToColor(hsv)
            return Color.argb(alpha, Color.red(opaque), Color.green(opaque), Color.blue(opaque))
        }

        // ---------- 控件 ----------
        val svPanel = SVPanel(context, hsv)
        val hueStrip = HueStrip(context, hsv)
        val checkerTile = makeCheckerTile(context, d(12))
        val colorLayer = GradientDrawable().apply { cornerRadius = d(8).toFloat() }
        val preview = View(context).apply {
            background = LayerDrawable(arrayOf(checkerTile, colorLayer))
        }

        val alphaLabel = TextView(context).apply {
            setTextColor(Color.DKGRAY)
            textSize = 13f
        }
        val alphaSeek = SeekBar(context).apply { max = 255; progress = alpha }

        val rgbRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        lateinit var rField: EditText
        lateinit var gField: EditText
        lateinit var bField: EditText
        lateinit var hexField: EditText
        var hexUpdating = false

        fun refresh() {
            val c = currentColor()
            colorLayer.setColor(c)
            preview.invalidate()
            svPanel.invalidate()
            hueStrip.invalidate()
            alphaLabel.text = "不透明度 ${alpha * 100 / 255}%"
            val opaque = Color.HSVToColor(hsv)
            rField.setText(Color.red(opaque).toString())
            gField.setText(Color.green(opaque).toString())
            bField.setText(Color.blue(opaque).toString())
            // 同步十六进制显示（hexUpdating 防回写循环）
            if (!hexUpdating) {
                hexUpdating = true
                hexField.setText(
                    String.format("#%02X%02X%02X", Color.red(opaque), Color.green(opaque), Color.blue(opaque))
                )
                hexUpdating = false
            }
        }

        fun applyRgbFromFields() {
            val r = rField.text.toString().toIntOrNull() ?: return
            val g = gField.text.toString().toIntOrNull() ?: return
            val b = bField.text.toString().toIntOrNull() ?: return
            Color.RGBToHSV(
                r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), hsv
            )
            refresh()
        }

        fun makeField(label: String, initial: Int): EditText {
            rgbRow.addView(TextView(context).apply {
                text = label
                setTextColor(Color.DKGRAY)
                textSize = 13f
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = d(10) })
            val et = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(3))
                setEms(2)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_DONE
                setText(initial.toString())
            }
            et.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) applyRgbFromFields()
                false
            }
            et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyRgbFromFields() }
            rgbRow.addView(et, LinearLayout.LayoutParams(
                d(46), ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = d(2) })
            return et
        }

        // ---------- 布局 ----------
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20), d(8), d(20), d(4))
        }
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(40)
        ).apply { bottomMargin = d(12) })
        root.addView(svPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(170)
        ))
        root.addView(hueStrip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(32)
        ).apply { topMargin = d(12) })

        root.addView(alphaLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = d(10) })
        root.addView(alphaSeek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // 不允许透明（主题色等）：隐藏不透明度控件，避免“可调但无效”的困惑
        if (!allowAlpha) {
            alphaLabel.visibility = View.GONE
            alphaSeek.visibility = View.GONE
        }

        root.addView(TextView(context).apply {
            text = "RGB 数值（0-255）"
            setTextColor(Color.DKGRAY)
            textSize = 13f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = d(8) })

        val opaqueInit = Color.HSVToColor(hsv)
        rField = makeField("R", Color.red(opaqueInit))
        gField = makeField("G", Color.green(opaqueInit))
        bField = makeField("B", Color.blue(opaqueInit))
        root.addView(rgbRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = d(4) })

        // ---------- 十六进制颜色码输入（如 #00FF00） ----------
        root.addView(TextView(context).apply {
            text = "十六进制颜色码（如 #00FF00）"
            setTextColor(Color.DKGRAY)
            textSize = 13f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = d(10) })

        hexField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(
                InputFilter.LengthFilter(7),
                InputFilter { source, _, _, _, _, _ ->
                    val sb = StringBuilder()
                    for (ch in source) {
                        if (ch == '#' || ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F') sb.append(ch)
                    }
                    if (sb.length == source.length) null else sb.toString()
                }
            )
            setEms(6)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            hint = "#00FF00"
            setTextColor(Color.DKGRAY)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (hexUpdating) return
                    val raw = s?.toString()?.removePrefix("#") ?: return
                    if (raw.length != 6) return
                    val v = raw.toIntOrNull(16) ?: return
                    Color.RGBToHSV(Color.red(v), Color.green(v), Color.blue(v), hsv)
                    hexUpdating = true
                    refresh()
                    hexUpdating = false
                }
            })
        }
        root.addView(hexField, LinearLayout.LayoutParams(
            d(130), ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = d(4) })

        // ---------- 联动 ----------
        svPanel.onChanged = { refresh() }
        hueStrip.onChanged = { refresh() }
        alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                alpha = p.coerceIn(0, 255)
                refresh()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        refresh()

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("自定义颜色")
            .setView(scroll)
            .setPositiveButton("确定") { _, _ -> onPick(currentColor()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 生成棋盘格平铺图，用于半透明预览底纹 */
    private fun makeCheckerTile(context: Context, cell: Int): BitmapDrawable {
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = Color.WHITE
        c.drawPaint(p)
        p.color = Color.rgb(204, 204, 204)
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        c.drawRect(cell.toFloat(), cell.toFloat(), (cell * 2).toFloat(), (cell * 2).toFloat(), p)
        val d = BitmapDrawable(context.resources, bmp)
        d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        return d
    }

    /** 饱和度/明度取色区 */
    private class SVPanel(c: Context, private val hsv: FloatArray) : View(c) {
        var onChanged: (() -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val hue = hsv[0]
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.TRANSPARENT, Color.BLACK,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            val x = hsv[1] * w
            val y = (1f - hsv[2]) * h
            val r = 8f * resources.displayMetrics.density
            marker.shader = null
            marker.style = Paint.Style.STROKE
            marker.strokeWidth = 3f * resources.displayMetrics.density
            marker.color = if (hsv[2] > 0.5f && hsv[1] < 0.5f) Color.BLACK else Color.WHITE
            canvas.drawCircle(x, y, r, marker)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    hsv[1] = (e.x / width).coerceIn(0f, 1f)
                    hsv[2] = (1f - e.y / height).coerceIn(0f, 1f)
                    onChanged?.invoke()
                    if (e.action == MotionEvent.ACTION_UP) performClick()
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** 色相条 */
    private class HueStrip(c: Context, private val hsv: FloatArray) : View(c) {
        var onChanged: (() -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val colors = intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.RED
            )
            val positions = floatArrayOf(0f, 0.167f, 0.333f, 0.5f, 0.667f, 0.833f, 1f)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, 0f, w, 0f, colors, positions, Shader.TileMode.CLAMP)
            val r = h / 6f
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)

            val x = hsv[0] / 360f * w
            marker.shader = null
            marker.style = Paint.Style.STROKE
            marker.strokeWidth = 3f * resources.displayMetrics.density
            marker.color = Color.WHITE
            canvas.drawLine(x, 0f, x, h, marker)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    hsv[0] = (e.x / width).coerceIn(0f, 1f) * 360f
                    onChanged?.invoke()
                    if (e.action == MotionEvent.ACTION_UP) performClick()
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}