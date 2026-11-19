package com.bili.drama.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bili.drama.core.BiliNodes
import com.bili.drama.core.DramaStateEngine
import com.bili.drama.core.EngineAction
import com.bili.drama.core.RectBounds
import com.bili.drama.core.RuleMatcher
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
    private var windowManager: WindowManager? = null
    private var isRunning = false
    private var lastActivityName = ""
    private var lastDramaContainerActivity = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0"
    private var lastAdActivityName = BiliNodes.ACTIVITY_REWARD_AD
    private var lastStatusLogged = ""
    private var currentForegroundPkg = ""
    private var lastExternalEventPkg = ""
    private var lastDramaTitle = ""
    private var lastFgCheckUptimeMs = 0L

    private val ignoredSystemOverlayPkgs = setOf(
        "com.android.systemui",
        "miui.systemui.plugin"
    )

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val delay = runSingleTick()
            if (isRunning) {
                handler.postDelayed(this, delay)
            }
        }
    }

    // One-shot post-transition settle check: catches warm app resumes after OEM zoom animations finish
    private val settleOverlayRunnable = Runnable {
        val detected = detectForegroundPackage()
        if (detected.isNotBlank()) {
            currentForegroundPkg = detected
            if (detected == BiliNodes.PKG_BILI) {
                lastExternalEventPkg = ""
            }
            updateOverlayForPackage(currentForegroundPkg)
        }
    }

    private fun scheduleOverlaySettleCheck() {
        handler.removeCallbacks(settleOverlayRunnable)
        handler.postDelayed(settleOverlayRunnable, 450L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        floating = FloatingController(
            context = this,
            windowManager = wm,
            onToggleClick = { toggleBatchRun() },
            onCloseClick = { dismissOverlayByUser() },
            onPositionSaved = { x, y -> AccPrefs.savePillPosition(this, x, y) }
        )
        val initialPkg = detectForegroundPackage()
        if (initialPkg.isNotBlank()) {
            currentForegroundPkg = initialPkg
        }
        updateOverlayForPackage(currentForegroundPkg)
        appendLog("无障碍服务已连接")
    }

    override fun onDestroy() {
        handler.removeCallbacks(settleOverlayRunnable)
        stopBatch("服务已关闭")
        floating?.hide()
        floating = null
        windowManager = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        stopBatch("无障碍服务被中断")
        floating?.hide()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val et = event.eventType
        if (et == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            val cls = event.className?.toString().orEmpty()
            if (pkg == BiliNodes.PKG_BILI && cls.contains("Activity")) {
                lastActivityName = cls
                lastExternalEventPkg = ""
                if (cls.contains("RewardAdActivity")) {
                    lastAdActivityName = cls
                }
            } else if (pkg == BiliNodes.PKG_BILI) {
                lastExternalEventPkg = ""
                if (!lastActivityName.startsWith("com.bilibili.")) {
                    lastActivityName = ""
                }
            } else if (pkg.isNotBlank() && pkg !in ignoredSystemOverlayPkgs && pkg != "com.bili.drama.accessibility") {
                lastActivityName = cls
                // Ignore transient OEM gesture stub / overlay windows (non-Activity views from launcher/system)
                val isLauncherOverlay = (pkg.endsWith(".home") || pkg.contains("launcher")) && !cls.contains("Activity")
                if (!isLauncherOverlay) {
                    lastExternalEventPkg = pkg
                }
            }

            val isSelfOverlayWindow = pkg == "com.bili.drama.accessibility" && !cls.contains("Activity")
            val isLauncherGestureStub = (pkg.endsWith(".home") || pkg.contains("launcher")) && !cls.contains("Activity")
            if (pkg.isNotBlank() && pkg !in ignoredSystemOverlayPkgs && !isSelfOverlayWindow && !isLauncherGestureStub) {
                currentForegroundPkg = pkg
            } else {
                val detected = detectForegroundPackage()
                if (detected.isNotBlank()) currentForegroundPkg = detected
            }
            updateOverlayForPackage(currentForegroundPkg)
            scheduleOverlaySettleCheck()
            return
        }

        if (et == AccessibilityEvent.TYPE_WINDOWS_CHANGED || et == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (et == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                scheduleOverlaySettleCheck()
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (et == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastFgCheckUptimeMs < 250L) {
                return
            }
            lastFgCheckUptimeMs = now
            val detected = detectForegroundPackage()
            if (detected.isNotBlank() && detected != currentForegroundPkg) {
                currentForegroundPkg = detected
                if (detected == BiliNodes.PKG_BILI) {
                    lastExternalEventPkg = ""
                }
                updateOverlayForPackage(currentForegroundPkg)
            }
        }
    }

    private fun detectForegroundPackage(): String {
        val winList = windows
        if (!winList.isNullOrEmpty()) {
            // Strictly consider TYPE_APPLICATION windows so OEM TYPE_SYSTEM overlays
            // (gesture stubs, edge sidebars, dynamic islands, status/nav bars) never spoof the foreground app.
            val appWindows = winList.filter { win ->
                if (win.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return@filter false
                }
                val pkg = win.root?.packageName?.toString().orEmpty()
                pkg.isNotBlank() && pkg !in ignoredSystemOverlayPkgs
            }
            val focusedApp = appWindows.firstOrNull { it.isFocused || it.isActive }
                ?: appWindows.maxByOrNull { it.layer }
            val winPkg = focusedApp?.root?.packageName?.toString().orEmpty()
            if (winPkg.isNotBlank()) return winPkg
        }
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        return if (activePkg.isNotBlank() && activePkg !in ignoredSystemOverlayPkgs) activePkg else ""
    }

    fun dismissOverlayByUser() {
        stopBatch("用户关闭悬浮窗")
        AccPrefs.setOverlayEnabled(this, false)
        floating?.hide()
    }

    fun shutdownSelf() {
        stopBatch("用户关闭无障碍服务")
        AccPrefs.setOverlayEnabled(this, false)
        floating?.hide()
        disableSelf()
    }

    fun refreshOverlayVisibility() {
        updateOverlayForPackage(currentForegroundPkg)
    }

    private fun updateOverlayForPackage(pkg: String) {
        if (!AccPrefs.isOverlayEnabled(this)) {
            floating?.hide()
            return
        }
        if (isRunning) {
            floating?.show(AccPrefs.loadPillPosition(this))
            floating?.setVisible(true)
            return
        }
        if (AccPrefs.isOnlyShowInBili(this)) {
            if (pkg == BiliNodes.PKG_BILI) {
                floating?.show(AccPrefs.loadPillPosition(this))
                floating?.setVisible(true)
            } else {
                // Keep a 1x1 invisible pass-through anchor while overlay is enabled so OEM app freezers
                // (HyperOS/ColorOS/OriginOS) do not freeze this service before the user returns to Bilibili.
                floating?.show(AccPrefs.loadPillPosition(this))
                floating?.setAutoHidden(true)
            }
        } else {
            floating?.show(AccPrefs.loadPillPosition(this))
            floating?.setVisible(true)
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
        floating?.show(AccPrefs.loadPillPosition(this))
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
        updateOverlayForPackage(currentForegroundPkg)
        appendLog("已停止: $reason")
    }

    @Suppress("DEPRECATION")
    private fun getRealScreenSize(): Pair<Int, Int> {
        val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val dm = DisplayMetrics()
        wm.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) {
            return dm.widthPixels to dm.heightPixels
        }
        val fallback = resources.displayMetrics
        return fallback.widthPixels to fallback.heightPixels
    }

    private fun runSingleTick(): Long {
        val (screenW, screenH) = getRealScreenSize()
        var scan = NodeScanner.capture(
            windows = windows ?: emptyList(),
            activeRoot = rootInActiveWindow,
            screenWidth = screenW,
            screenHeight = screenH,
            lastActivityName = lastActivityName,
            fallbackExternalPkg = lastExternalEventPkg
        )
        val snapPkg = scan.snapshot.packageName
        if (snapPkg.isNotBlank() &&
            snapPkg !in ignoredSystemOverlayPkgs &&
            snapPkg != "com.bili.drama.accessibility" &&
            snapPkg != BiliNodes.PKG_EXTERNAL_PROTECTED
        ) {
            currentForegroundPkg = snapPkg
            if (snapPkg == BiliNodes.PKG_BILI) {
                lastExternalEventPkg = ""
            }
        }
        if (RuleMatcher.hasShortDramaStructure(scan.snapshot)) {
            scan.snapshot.firstById(BiliNodes.ID_ALBUM_NAME_V2)?.displayLabel?.takeIf { it.isNotBlank() }?.let {
                lastDramaTitle = it
            }
            if (lastActivityName.startsWith(BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX)) {
                lastDramaContainerActivity = lastActivityName
            } else if (lastActivityName.contains("RewardAdActivity") || !lastActivityName.startsWith("com.bilibili.")) {
                lastActivityName = lastDramaContainerActivity
                scan = scan.copy(snapshot = scan.snapshot.copy(activityName = lastActivityName))
            }
        }

        val action = engine.step(scan.snapshot)
        return when (action) {
            is EngineAction.Wait -> {
                val shouldLog = action.status.contains("已锁集") ||
                    action.status.contains("切出B站") ||
                    action.status.contains("未能自动拉回") ||
                    action.status.contains("等待进入短剧")
                updateStatus(action.status, logIfChanged = shouldLog)
                action.delayMs
            }

            is EngineAction.ClickNode -> {
                updateStatus(action.reason, logIfChanged = true)
                performClick(action.node, scan.nodeRefs[action.node.key], screenW, screenH)
                380L
            }

            is EngineAction.SeekRatio -> {
                updateStatus(action.reason, logIfChanged = true)
                performSeek(action.seekBar, action.ratio, action.isSecondary, screenW, screenH)
                380L
            }

            is EngineAction.SwipeNextEpisode -> {
                updateStatus(action.reason, logIfChanged = true)
                performVerticalSwipe(scan, screenW, screenH, engine.currentSwipeAttempt)
                430L
            }

            is EngineAction.PressBack -> {
                updateStatus(action.reason, logIfChanged = true)
                performGlobalAction(GLOBAL_ACTION_BACK)
                520L
            }

            is EngineAction.BringBiliToFront -> {
                updateStatus(action.reason, logIfChanged = true)
                bringBilibiliToFront(action.attempt)
                950L
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
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 240L))
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

    /**
     * Multi-tier return strategy so separate-task external apps (e.g. Taobao / JD / Browser / Store)
     * and Android 13-16 devices reliably return directly to Bilibili's AppContainer / RewardAd task.
     */
    private fun bringBilibiliToFront(attempt: Int) {
        when (attempt) {
            1 -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            2, 5 -> {
                switchBackViaRecents(attempt)
            }
            3 -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({
                    if (!isRunning) return@postDelayed
                    val curPkg = rootInActiveWindow?.packageName?.toString().orEmpty()
                    if (curPkg != BiliNodes.PKG_BILI) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }, 350L)
            }
            else -> {
                launchBilibiliViaIntent()
            }
        }
    }

    private fun switchBackViaRecents(attempt: Int) {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            val (screenW, screenH) = getRealScreenSize()
            val recentsScan = NodeScanner.capture(
                windows = windows ?: emptyList(),
                activeRoot = rootInActiveWindow,
                screenWidth = screenW,
                screenHeight = screenH,
                lastActivityName = ""
            )
            if (recentsScan.snapshot.packageName == BiliNodes.PKG_BILI) {
                return@postDelayed
            }
            val keywords = buildList {
                if (lastDramaTitle.isNotBlank()) add(lastDramaTitle)
                add("哔哩哔哩")
                add("漫剧")
                add("短剧")
            }
            val forbiddenLabels = listOf("应用信息", "分屏", "小窗", "清理", "清除", "悬浮", "锁定")
            val minCardW = (screenW * 0.14f).toInt()
            val minCardH = (screenH * 0.22f).toInt()

            // Select genuine Recents task cards (large geometry), filtering out app-info icons & home-screen shortcuts
            val biliCards = recentsScan.snapshot.nodes.filter { node ->
                val lbl = node.displayLabel
                lbl.isNotBlank() &&
                    node.bounds.isOnScreen(screenW, screenH) &&
                    node.bounds.width >= minCardW &&
                    node.bounds.height >= minCardH &&
                    !node.viewId.contains("icon", ignoreCase = true) &&
                    !node.viewId.contains("clear", ignoreCase = true) &&
                    !node.viewId.contains("menu", ignoreCase = true) &&
                    forbiddenLabels.none { lbl.contains(it) } &&
                    keywords.any { lbl.contains(it, ignoreCase = true) }
            }.sortedWith(
                compareByDescending<UiNode> { node ->
                    lastDramaTitle.isNotBlank() && node.displayLabel.contains(lastDramaTitle, ignoreCase = true)
                }.thenByDescending { it.bounds.width * it.bounds.height }
            )

            // If multiple Bilibili tasks exist (e.g. main app + AppContainer0 mini-program), alternate cards on retry
            val chosenCard = when {
                biliCards.isEmpty() -> null
                attempt >= 5 && biliCards.size >= 2 -> biliCards[1]
                else -> biliCards[0]
            }

            if (chosenCard != null) {
                appendLog("通过最近任务卡片切回B站: ${chosenCard.displayLabel}")
                tapPoint(chosenCard.bounds.centerX.toFloat(), chosenCard.bounds.centerY.toFloat())
            } else if (attempt == 2) {
                // Standard AOSP Android 7-16 Alt-Tab: second GLOBAL_ACTION_RECENTS switches to the previous task
                appendLog("未识别到卡片文字，二次触发最近任务切回上一应用")
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            } else {
                appendLog("尝试点击最近任务左侧前一应用卡片")
                tapPoint(screenW * 0.16f, screenH * 0.48f)
            }
        }, 500L)
    }

    private fun launchBilibiliViaIntent() {
        val launch = packageManager.getLaunchIntentForPackage(BiliNodes.PKG_BILI)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        if (launch == null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        val launched = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val opts = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
                val pi = PendingIntent.getActivity(
                    this,
                    1001,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    opts
                )
                pi.send()
            } else {
                startActivity(launch)
            }
            true
        }.getOrElse {
            runCatching {
                startActivity(launch)
                true
            }.getOrDefault(false)
        }
        appendLog(if (launched) "通过Intent拉回B站前台" else "Intent拉回失败，尝试系统返回")
        if (!launched) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }
}
