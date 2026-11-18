package com.bili.drama.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bili.drama.core.BiliNodes
import com.bili.drama.core.DramaStateEngine
import com.bili.drama.core.EngineAction
import com.bili.drama.core.EnginePhase
import com.bili.drama.core.RectBounds
import com.bili.drama.core.UiNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class DramaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: DramaAccessibilityService? = null
            private set

        val logs = CopyOnWriteArrayList<String>()

        fun appendLog(msg: String) {
            android.util.Log.i("BiliDramaAcc", msg)
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logs.add("[$ts] $msg")
            while (logs.size > 100) {
                logs.removeAt(0)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val engine = DramaStateEngine()
    private var floating: FloatingController? = null
    private var isRunning = false
    private var lastActivityName = ""
    private var lastDramaContainerActivity = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0"
    private var lastAdActivityName = BiliNodes.ACTIVITY_REWARD_AD
    private var lastStatusLogged = ""

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val delay = runSingleTick()
            if (isRunning) {
                handler.postDelayed(this, delay)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        floating = FloatingController(this, wm) { toggleBatchRun() }
        if (AccPrefs.isOverlayEnabled(this)) {
            floating?.show()
        }
        appendLog("无障碍服务已连接")
    }

    override fun onDestroy() {
        stopBatch("服务已关闭")
        floating?.hide()
        floating = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        stopBatch("无障碍服务被中断")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            val cls = event.className?.toString().orEmpty()
            if (pkg == BiliNodes.PKG_BILI && cls.contains("Activity")) {
                lastActivityName = cls
                if (cls.contains("RewardAdActivity")) {
                    lastAdActivityName = cls
                }
            } else if (pkg.isNotBlank() && pkg != BiliNodes.PKG_BILI) {
                lastActivityName = cls
            }

            if (AccPrefs.isOverlayEnabled(this)) {
                floating?.show()
                floating?.setVisible(true)
            }
        }
    }

    fun refreshOverlayVisibility() {
        if (AccPrefs.isOverlayEnabled(this)) {
            floating?.show()
            floating?.setVisible(true)
        } else if (!isRunning) {
            floating?.hide()
        }
    }

    fun toggleBatchRun() {
        if (isRunning) {
            stopBatch("用户手动停止")
        } else {
            startBatch()
        }
    }

    fun startBatch() {
        engine.updateConfig(AccPrefs.loadConfig(this))
        engine.start(System.currentTimeMillis())
        isRunning = true
        floating?.show()
        floating?.setVisible(true)
        floating?.updateState(true, "启动中...")
        appendLog("开始自动刷全集解锁")
        handler.removeCallbacks(loopRunnable)
        handler.post(loopRunnable)
    }

    fun stopBatch(reason: String) {
        isRunning = false
        handler.removeCallbacks(loopRunnable)
        engine.stop()
        floating?.updateState(false, reason)
        appendLog("已停止: $reason")
    }

    private fun runSingleTick(): Long {
        val dm = resources.displayMetrics
        var scan = NodeScanner.capture(
            windows = windows ?: emptyList(),
            activeRoot = rootInActiveWindow,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            lastActivityName = lastActivityName
        )
        if (com.bili.drama.core.RuleMatcher.hasShortDramaStructure(scan.snapshot)) {
            if (lastActivityName.startsWith(BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX)) {
                lastDramaContainerActivity = lastActivityName
            } else if (lastActivityName.contains("RewardAdActivity")) {
                lastActivityName = lastDramaContainerActivity
                scan = scan.copy(snapshot = scan.snapshot.copy(activityName = lastActivityName))
            }
        }

        val action = engine.step(scan.snapshot)
        return when (action) {
            is EngineAction.Wait -> {
                val shouldLog = action.status.contains("已锁集") || action.status.contains("切出B站")
                updateStatus(action.status, logIfChanged = shouldLog)
                action.delayMs
            }

            is EngineAction.ClickNode -> {
                updateStatus(action.reason, logIfChanged = true)
                performClick(action.node, scan.nodeRefs[action.node.key], dm.widthPixels, dm.heightPixels)
                380L
            }

            is EngineAction.SeekRatio -> {
                updateStatus(action.reason, logIfChanged = true)
                performSeek(action.seekBar, action.ratio, action.isSecondary, dm.widthPixels, dm.heightPixels)
                380L
            }

            is EngineAction.SwipeNextEpisode -> {
                updateStatus(action.reason, logIfChanged = true)
                performVerticalSwipe(scan, dm.widthPixels, dm.heightPixels, engine.currentSwipeAttempt)
                430L
            }

            is EngineAction.PressBack -> {
                updateStatus(action.reason, logIfChanged = true)
                performGlobalAction(GLOBAL_ACTION_BACK)
                520L
            }

            is EngineAction.BringBiliToFront -> {
                updateStatus(action.reason, logIfChanged = true)
                bringBilibiliToFront()
                600L
            }

            is EngineAction.Stop -> {
                stopBatch(action.reason)
                0L
            }
        }
    }

    private fun updateStatus(msg: String, logIfChanged: Boolean) {
        val epPrefix = engine.lastEpisode?.let { "${it.current}/${it.total ?: "?"} " }.orEmpty()
        floating?.updateState(isRunning, "$epPrefix$msg")
        if (logIfChanged && msg != lastStatusLogged) {
            lastStatusLogged = msg
            appendLog(msg)
        }
    }

    private fun performClick(uiNode: UiNode, accNode: AccessibilityNodeInfo?, screenW: Int, screenH: Int) {
        if (!uiNode.clickable && uiNode.displayLabel.isBlank() && uiNode.bounds.isValid) {
            tapPoint(uiNode.bounds.centerX.toFloat(), uiNode.bounds.centerY.toFloat())
            return
        }
        var clicked = false
        var cur: AccessibilityNodeInfo? = accNode
        var depth = 0
        val tmpRect = Rect()
        val maxContainerW = (screenW * 0.92f).toInt()
        val maxContainerH = (screenH * 0.35f).toInt()
        while (cur != null && depth < 4) {
            if (cur.isClickable) {
                cur.getBoundsInScreen(tmpRect)
                val isOversizedBackdrop = depth > 0 &&
                    (tmpRect.width() >= maxContainerW && tmpRect.height() >= maxContainerH)
                if (!isOversizedBackdrop) {
                    clicked = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) break
                }
            }
            cur = cur.parent
            depth += 1
        }
        if (!clicked && uiNode.bounds.isValid) {
            tapPoint(uiNode.bounds.centerX.toFloat(), uiNode.bounds.centerY.toFloat())
        }
    }

    private fun performSeek(
        seekBar: UiNode?,
        ratio: Float,
        isSecondary: Boolean,
        screenW: Int,
        screenH: Int
    ) {
        val bounds: RectBounds = seekBar?.bounds?.takeIf { it.isOnScreen(screenW, screenH) }
            ?: RectBounds(
                left = (screenW * 0.04f).toInt(),
                top = (screenH * 0.89f).toInt(),
                right = (screenW * 0.96f).toInt(),
                bottom = (screenH * 0.92f).toInt()
            )
        // SAStorySeekbar applies relative delta (endX - startX) from current thumb position:
        // Primary seek drags from near episode start (~5%) by ~72% to ~77%, triggering both preview & mid-roll locks in one drag.
        val deltaRatio = if (!isSecondary) {
            (ratio - 0.05f).coerceIn(0.45f, 0.74f)
        } else {
            (ratio - 0.50f).coerceIn(0.25f, 0.40f)
        }
        // Stay >=14% inside screen edges so Android Gesture Navigation (EdgeBackGestureHandler) never intercepts
        val safeLeft = maxOf((bounds.left + 36).toFloat(), screenW * 0.14f)
        val safeRight = minOf((bounds.right - 36).toFloat(), screenW * 0.86f)
        val startX = safeLeft.coerceAtMost(safeRight - 140f)
        val targetX = (startX + bounds.width * deltaRatio)
            .coerceIn(startX + 120f, safeRight)
        val y = bounds.centerY.toFloat().coerceIn(20f, (screenH - 20).toFloat())

        appendLog("快进进度条: ratio=$ratio delta=${"%.2f".format(deltaRatio)} x=${startX.toInt()}->${targetX.toInt()} y=${y.toInt()}")
        val steps = 6
        val path = Path().apply {
            moveTo(startX, y)
            for (i in 1..steps) {
                val curX = startX + (targetX - startX) * (i.toFloat() / steps)
                lineTo(curX, y)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 190L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performVerticalSwipe(
        scan: NodeScanner.ScanResult,
        screenW: Int,
        screenH: Int,
        attempt: Int
    ) {
        val isAdScreen = scan.snapshot.activityName.contains("RewardAdActivity")
        if (!isAdScreen && attempt <= 1) {
            val feedUiNode = scan.snapshot.firstLaidOutOnScreenById(BiliNodes.ID_FEED_VIDEOS)
            val feedAccNode = feedUiNode?.let { scan.nodeRefs[it.key] }
            if (feedAccNode != null) {
                var scrolled = feedAccNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (!scrolled) {
                    for (i in 0 until feedAccNode.childCount) {
                        val child = feedAccNode.getChild(i) ?: continue
                        if (child.isScrollable) {
                            scrolled = child.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            if (scrolled) break
                        }
                    }
                }
                if (scrolled) {
                    appendLog("ViewPager2翻页: ACTION_SCROLL_FORWARD 成功")
                    return
                }
            }
        }

        val x = screenW * 0.5f
        val startY = screenH * 0.74f
        val endY = screenH * 0.18f
        val segments = 10
        val path = Path().apply {
            moveTo(x, startY)
            for (i in 1..segments) {
                // Quadratic acceleration generates high terminal upward velocity in RecyclerView's VelocityTracker
                val t = i.toFloat() / segments
                val curY = startY + (endY - startY) * (t * t)
                lineTo(x, curY)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 210L))
            .build()
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    appendLog("上滑手势被系统取消，将自动重试")
                }
            },
            null
        )
    }

    private fun tapPoint(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun bringBilibiliToFront() {
        // External app was launched on top of Bilibili's task; GLOBAL_ACTION_BACK returns directly to the ad task.
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            val curPkg = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (curPkg != BiliNodes.PKG_BILI) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, 600L)
    }
}
