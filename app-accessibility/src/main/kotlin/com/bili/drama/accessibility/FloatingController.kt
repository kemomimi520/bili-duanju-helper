package com.bili.drama.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lightweight TYPE_ACCESSIBILITY_OVERLAY floating pill.
 * Does not require SYSTEM_ALERT_WINDOW and satisfies Android 15/16 visible-overlay BAL rules.
 */
class FloatingController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onToggleClick: () -> Unit
) {
    private var container: LinearLayout? = null
    private var actionBtn: TextView? = null
    private var statusText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (container != null) return
        val dp = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xDD1A1C22.toInt())
                cornerRadius = 22 * dp
                setStroke((1 * dp).toInt(), 0x55FB7299)
            }
        }

        val btn = TextView(context).apply {
            text = "▶ 刷广告"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xFFFB7299.toInt())
                cornerRadius = 14 * dp
            }
            setOnClickListener {
                android.util.Log.i("BiliDramaAcc", "Pill button clicked")
                onToggleClick()
            }
        }

        val status = TextView(context).apply {
            text = "待机中"
            textSize = 12f
            setTextColor(0xFFE5E7EB.toInt())
            setPadding((8 * dp).toInt(), 0, 0, 0)
            maxWidth = (180 * dp).toInt()
            maxLines = 1
        }

        root.addView(btn)
        root.addView(status)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * dp).toInt()
            y = (160 * dp).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        status.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        dragging = true
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        android.util.Log.i("BiliDramaAcc", "Pill status tapped")
                        onToggleClick()
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            windowManager.addView(root, params)
            container = root
            actionBtn = btn
            statusText = status
            layoutParams = params
        }
    }

    fun hide() {
        val view = container ?: return
        runCatching { windowManager.removeView(view) }
        container = null
        actionBtn = null
        statusText = null
        layoutParams = null
    }

    fun setVisible(visible: Boolean) {
        container?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun updateState(running: Boolean, message: String) {
        val btn = actionBtn ?: return
        val status = statusText ?: return
        btn.text = if (running) "⏹ 停止" else "▶ 刷广告"
        (btn.background as? GradientDrawable)?.setColor(
            if (running) 0xFFEF4444.toInt() else 0xFFFB7299.toInt()
        )
        status.text = message
    }
}
