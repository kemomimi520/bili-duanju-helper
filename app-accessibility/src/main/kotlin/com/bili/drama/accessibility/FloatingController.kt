package com.bili.drama.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Lightweight TYPE_ACCESSIBILITY_OVERLAY floating pill.
 * Draggable from anywhere on the pill, includes an explicit '✕' close button,
 * and remembers its last screen coordinates without requiring SYSTEM_ALERT_WINDOW.
 */
class FloatingController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onToggleClick: () -> Unit,
    private val onCloseClick: () -> Unit,
    private val onPositionSaved: (Int, Int) -> Unit
) {
    private var container: LinearLayout? = null
    private var actionBtn: TextView? = null
    private var statusText: TextView? = null
    private var closeBtn: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAutoHidden = false
    private var lastShownX = 0
    private var lastShownY = 0

    val isShowing: Boolean
        get() = container != null && !isAutoHidden

    @SuppressLint("ClickableViewAccessibility")
    fun show(savedPosition: Pair<Int, Int>? = null) {
        val existing = container
        if (existing != null) {
            if (isAutoHidden) {
                setAutoHidden(false, savedPosition)
            } else if (existing.visibility != View.VISIBLE) {
                existing.visibility = View.VISIBLE
            }
            return
        }
        val dp = context.resources.displayMetrics.density
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.coerceAtLeast(8)
        val (screenW, screenH) = getRealScreenSize()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
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
        }

        val status = TextView(context).apply {
            text = "待机中"
            textSize = 12f
            setTextColor(0xFFE5E7EB.toInt())
            setPadding((8 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            maxWidth = (165 * dp).toInt()
            maxLines = 1
        }

        val close = TextView(context).apply {
            text = "✕"
            textSize = 12f
            setTextColor(0xFFD1D5DB.toInt())
            setPadding((7 * dp).toInt(), (4 * dp).toInt(), (7 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = 12 * dp
            }
        }

        root.addView(btn)
        root.addView(status)
        root.addView(close)

        val defaultX = (16 * dp).toInt()
        val defaultY = (160 * dp).toInt()
        val initX = savedPosition?.first ?: defaultX
        val initY = savedPosition?.second ?: defaultY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX.coerceIn(0, (screenW - (120 * dp).toInt()).coerceAtLeast(0))
            y = initY.coerceIn(0, (screenH - (56 * dp).toInt()).coerceAtLeast(0))
        }

        fun createDragOrTapListener(onTap: () -> Unit): View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false

            return View.OnTouchListener { _, event ->
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
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            dragging = true
                        }
                        if (dragging) {
                            val (curW, curH) = getRealScreenSize()
                            val viewW = root.width.takeIf { it > 0 } ?: (180 * dp).toInt()
                            val viewH = root.height.takeIf { it > 0 } ?: (48 * dp).toInt()
                            params.x = (startX + dx).coerceIn(0, (curW - viewW).coerceAtLeast(0))
                            params.y = (startY + dy).coerceIn(0, (curH - viewH).coerceAtLeast(0))
                            runCatching { windowManager.updateViewLayout(root, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            onPositionSaved(params.x, params.y)
                        } else {
                            onTap()
                        }
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        false
                    }
                    else -> false
                }
            }
        }

        val toggleTouch = createDragOrTapListener {
            android.util.Log.i("BiliDramaAcc", "Pill toggled")
            onToggleClick()
        }
        val closeTouch = createDragOrTapListener {
            android.util.Log.i("BiliDramaAcc", "Pill closed by user")
            onCloseClick()
        }

        root.setOnTouchListener(toggleTouch)
        btn.setOnTouchListener(toggleTouch)
        status.setOnTouchListener(toggleTouch)
        close.setOnTouchListener(closeTouch)

        runCatching {
            windowManager.addView(root, params)
            container = root
            actionBtn = btn
            statusText = status
            closeBtn = close
            layoutParams = params
            isAutoHidden = false
            lastShownX = params.x
            lastShownY = params.y
        }.onFailure {
            android.util.Log.w("BiliDramaAcc", "Floating addView failed: ${it.message}", it)
        }
    }

    /**
     * Temporarily collapses the overlay to an invisible, non-touchable 1x1 pixel when outside Bilibili
     * while the user keeps the overlay switch enabled. This prevents aggressive OEM background freezers
     * (HyperOS SmartPower/Millet, ColorOS Hans, OriginOS Tombstone) from freezing the AccessibilityService
     * and missing the return to Bilibili. Explicit close ('✕') or disabling calls hide() to removeView completely.
     */
    fun setAutoHidden(hidden: Boolean, savedPosition: Pair<Int, Int>? = null) {
        val root = container ?: return
        val params = layoutParams ?: return
        if (hidden == isAutoHidden) return
        isAutoHidden = hidden
        if (hidden) {
            lastShownX = params.x
            lastShownY = params.y
            root.alpha = 0f
            for (i in 0 until root.childCount) {
                root.getChildAt(i).visibility = View.GONE
            }
            params.width = 1
            params.height = 1
            params.x = 0
            params.y = 0
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            for (i in 0 until root.childCount) {
                root.getChildAt(i).visibility = View.VISIBLE
            }
            root.alpha = 1f
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = savedPosition?.first ?: lastShownX
            params.y = savedPosition?.second ?: lastShownY
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    fun hide() {
        val view = container ?: return
        runCatching { windowManager.removeView(view) }
        container = null
        actionBtn = null
        statusText = null
        closeBtn = null
        layoutParams = null
        isAutoHidden = false
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

    @Suppress("DEPRECATION")
    private fun getRealScreenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val dm = DisplayMetrics()
        windowManager.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) {
            return dm.widthPixels to dm.heightPixels
        }
        val fallback = context.resources.displayMetrics
        return fallback.widthPixels to fallback.heightPixels
    }
}
