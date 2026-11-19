package com.bili.drama.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.bili.drama.core.EnginePhase
import com.bili.drama.core.RectBounds
import com.bili.drama.core.RuleMatcher
import com.bili.drama.core.UiNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class DramaAccessibilityService : AccessibilityService() {

    companion object {
        private const val NOTIF_CHANNEL_ID = "bili_drama_batch"
        private const val NOTIF_ID_BATCH = 2041

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
    private var lastAppletName = ""
    private var lastExternalPkgForReturn = ""
    private val triedRecentsCardCenters = mutableListOf<Pair<Int, Int>>()
    private var lastReturnCardCenter: Pair<Int, Int>? = null
    private var lastReturnCardTapAtMs = 0L
    private var lastFgCheckUptimeMs = 0L
    private var hadAppTask = false

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

    private fun hasOwnAppTask(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        return runCatching { am.appTasks.isNotEmpty() }.getOrDefault(false)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        floating?.hide()
        val initialPkg = detectForegroundPackage()
        if (initialPkg.isNotBlank()) {
            currentForegroundPkg = initialPkg
        }
        val hasTask = hasOwnAppTask()
        val activatedFromSettings = initialPkg == "com.android.settings" ||
            initialPkg == "com.miui.securitycenter" ||
            initialPkg == packageName
        when {
            hasTask -> {
                hadAppTask = true
                AccPrefs.setSessionActive(this, true)
            }
            activatedFromSettings -> {
                hadAppTask = false
                AccPrefs.setSessionActive(this, true)
            }
            else -> {
                hadAppTask = false
                AccPrefs.setSessionActive(this, false)
            }
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        floating = FloatingController(
            context = this,
            windowManager = wm,
            onToggleClick = { toggleBatchRun() },
            onCloseClick = { dismissOverlayByUser() },
            onPositionSaved = { x, y -> AccPrefs.savePillPosition(this, x, y) }
        )
        updateOverlayForPackage(currentForegroundPkg)
        appendLog("无障碍服务已连接")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val removedPkg = rootIntent?.component?.packageName
        if (removedPkg == null || removedPkg == packageName) {
            onAppSwipedAway()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(settleOverlayRunnable)
        stopBatch("服务已关闭")
        leaveBatchForeground()
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
        AccPrefs.setSessionActive(this, false)
        AccPrefs.setOverlayEnabled(this, false)
        floating?.hide()
    }

    fun onAppSwipedAway() {
        hadAppTask = false
        AccPrefs.setSessionActive(this, false)
        stopBatch("主程序已从后台关闭")
        floating?.hide()
    }

    fun onAppOpened() {
        hadAppTask = true
        AccPrefs.setSessionActive(this, true)
        refreshOverlayVisibility()
    }

    fun shutdownSelf() {
        hadAppTask = false
        stopBatch("用户关闭无障碍服务")
        AccPrefs.setSessionActive(this, false)
        AccPrefs.setOverlayEnabled(this, false)
        floating?.hide()
        disableSelf()
    }

    fun refreshOverlayVisibility() {
        updateOverlayForPackage(currentForegroundPkg)
    }

    private fun updateOverlayForPackage(pkg: String) {
        if (hadAppTask && !hasOwnAppTask()) {
            onAppSwipedAway()
            return
        }
        if (!hadAppTask && pkg == packageName && hasOwnAppTask()) {
            hadAppTask = true
        }
        if (!AccPrefs.isOverlayEnabled(this) || !AccPrefs.isSessionActive(this)) {
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
                // Keep a 1x1 invisible pass-through anchor only when overlay & session are active
                // so OEM app freezers do not freeze this service before the user returns to Bilibili.
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

    private fun enterBatchForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "短剧自动刷广告运行状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("短剧自动刷广告运行中")
            .setContentText("正在处理短剧广告，完成后自动结束前台保活")
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID_BATCH,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID_BATCH, notification)
            }
        }.onFailure {
            appendLog("前台保活跳过: ${it.message}")
        }
    }

    private fun leaveBatchForeground() {
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    fun startBatch() {
        enterBatchForeground()
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
        leaveBatchForeground()
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
        if (RuleMatcher.isRewardAdScreen(scan.snapshot)) {
            triedRecentsCardCenters.clear()
            lastReturnCardCenter = null
            lastReturnCardTapAtMs = 0L
        } else if (engine.phase == EnginePhase.RETURNING_TO_BILI &&
            lastReturnCardCenter != null &&
            android.os.SystemClock.uptimeMillis() - lastReturnCardTapAtMs > 900L &&
            (lastActivityName.contains("MainActivity") ||
                (snapPkg.isNotBlank() &&
                    snapPkg != BiliNodes.PKG_BILI &&
                    snapPkg !in BiliNodes.NON_AD_PAUSE_PACKAGES &&
                    !snapPkg.contains("launcher") &&
                    !snapPkg.endsWith(".home")))
        ) {
            lastReturnCardCenter?.let { center ->
                if (triedRecentsCardCenters.none { kotlin.math.abs(it.first - center.first) < 90 && kotlin.math.abs(it.second - center.second) < 90 }) {
                    triedRecentsCardCenters.add(center)
                }
            }
            lastReturnCardCenter = null
            lastReturnCardTapAtMs = 0L
        }
        if (RuleMatcher.hasShortDramaStructure(scan.snapshot)) {
            triedRecentsCardCenters.clear()
            lastReturnCardCenter = null
            lastReturnCardTapAtMs = 0L
            scan.snapshot.firstById(BiliNodes.ID_ALBUM_NAME_V2)?.displayLabel?.takeIf { it.isNotBlank() }?.let {
                lastDramaTitle = it
            }
            scan.snapshot.nodes.firstOrNull { node ->
                val lbl = node.displayLabel
                lbl.length in 2..16 &&
                    (node.viewId.contains("title", ignoreCase = true) ||
                        lbl.endsWith("漫剧") ||
                        lbl.endsWith("短剧") ||
                        lbl.endsWith("剧场"))
            }?.displayLabel?.let { lastAppletName = it }
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
                when (action.attempt) {
                    2, 4, 5, 7, 8 -> 2300L
                    1, 3 -> 1300L
                    else -> 1800L
                }
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
     * and Android 8-16 OEM ROMs reliably return directly to Bilibili's AppContainer / RewardAd task.
     */
    private fun bringBilibiliToFront(attempt: Int) {
        when (attempt) {
            1 -> performGlobalAction(GLOBAL_ACTION_BACK)
            2, 4, 7 -> switchBackViaRecents(fastAltTab = false)
            3 -> if (detectForegroundPackage() != BiliNodes.PKG_BILI) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            5, 8 -> switchBackViaRecents(fastAltTab = true)
            else -> launchBilibiliViaIntent()
        }
    }

    private data class RecentsTarget(
        val label: String,
        val cx: Int,
        val cy: Int,
        val area: Int,
        val matchesAppletOrDrama: Boolean,
        val looksLikeMainApp: Boolean
    )

    private fun switchBackViaRecents(fastAltTab: Boolean) {
        val leavingPkg = currentForegroundPkg.takeIf { it.isNotBlank() && it != BiliNodes.PKG_BILI }
            ?: lastExternalEventPkg
        if (leavingPkg.isNotBlank()) {
            lastExternalPkgForReturn = leavingPkg
        }

        if (fastAltTab) {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            handler.postDelayed({
                if (isRunning && detectForegroundPackage() != BiliNodes.PKG_BILI) {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            }, 180L)
            return
        }

        performGlobalAction(GLOBAL_ACTION_RECENTS)
        handler.postDelayed({
            if (!isRunning || detectForegroundPackage() == BiliNodes.PKG_BILI) return@postDelayed
            val (screenW, screenH) = getRealScreenSize()
            val recentsScan = NodeScanner.capture(
                windows = windows ?: emptyList(),
                activeRoot = rootInActiveWindow,
                screenWidth = screenW,
                screenHeight = screenH,
                lastActivityName = ""
            )
            if (recentsScan.snapshot.packageName == BiliNodes.PKG_BILI) return@postDelayed

            val selected = findBestRecentsTarget(recentsScan, screenW, screenH)
            if (selected != null) {
                lastReturnCardCenter = selected.cx to selected.cy
                lastReturnCardTapAtMs = android.os.SystemClock.uptimeMillis()
                appendLog("通过最近任务卡片切回B站: ${selected.label}")
                tapPoint(selected.cx.toFloat(), selected.cy.toFloat())
            } else {
                appendLog("尝试点击最近任务相邻卡片切回B站")
                tapPoint(screenW * 0.18f, screenH * 0.48f)
            }
        }, 750L)
    }

    private fun findBestRecentsTarget(
        recentsScan: NodeScanner.ScanResult,
        screenW: Int,
        screenH: Int
    ): RecentsTarget? {
        val keywords = buildList {
            if (lastAppletName.isNotBlank()) add(lastAppletName)
            if (lastDramaTitle.isNotBlank()) add(lastDramaTitle)
            add("哔哩哔哩")
            add("bilibili")
            add("漫剧")
            add("短剧")
        }
        val forbiddenLabels = listOf(
            "应用信息", "分屏", "小窗", "清理", "清除", "全部清除", "关闭全部", "悬浮", "锁定", "设置", "内存"
        )
        val tmpRect = Rect()
        val rawTargets = mutableListOf<RecentsTarget>()

        for (node in recentsScan.snapshot.nodes) {
            val lbl = node.displayLabel.trim()
            if (lbl.isBlank() ||
                !node.bounds.isOnScreen(screenW, screenH) ||
                node.bounds.centerY >= (screenH * 0.86f).toInt() ||
                node.viewId.contains("icon", ignoreCase = true) ||
                node.viewId.contains("clear", ignoreCase = true) ||
                node.viewId.contains("menu", ignoreCase = true) ||
                forbiddenLabels.any { lbl.contains(it) } ||
                keywords.none { lbl.contains(it, ignoreCase = true) }
            ) continue

            val matchesSpecific = (lastAppletName.isNotBlank() && lbl.contains(lastAppletName, ignoreCase = true)) ||
                (lastDramaTitle.isNotBlank() && lbl.contains(lastDramaTitle, ignoreCase = true))
            val looksLikeMain = lbl.equals("哔哩哔哩", ignoreCase = true) && !matchesSpecific

            // Pattern 1: Full card with contentDescription (HyperOS / AOSP)
            if (node.bounds.width >= (screenW * 0.14f).toInt() && node.bounds.height >= (screenH * 0.18f).toInt()) {
                rawTargets.add(
                    RecentsTarget(
                        label = lbl,
                        cx = node.bounds.centerX,
                        cy = node.bounds.centerY,
                        area = node.bounds.width * node.bounds.height,
                        matchesAppletOrDrama = matchesSpecific,
                        looksLikeMainApp = looksLikeMain
                    )
                )
            } else if (node.bounds.width >= (screenW * 0.08f).toInt() &&
                node.bounds.height >= 24 &&
                node.bounds.top < (screenH * 0.65f).toInt()
            ) {
                // Pattern 2: Header TextView above task thumbnail (ColorOS / OriginOS / OneUI / HarmonyOS)
                var cardCenter: Pair<Int, Int>? = null
                var cardArea = node.bounds.width * node.bounds.height
                var p = recentsScan.nodeRefs[node.key]?.parent
                var depth = 0
                while (p != null && depth < 3) {
                    p.getBoundsInScreen(tmpRect)
                    if (tmpRect.width() in (screenW * 0.18f).toInt()..(screenW * 0.85f).toInt() &&
                        tmpRect.height() in (screenH * 0.20f).toInt()..(screenH * 0.80f).toInt()
                    ) {
                        cardCenter = tmpRect.centerX() to tmpRect.centerY()
                        cardArea = tmpRect.width() * tmpRect.height()
                        break
                    }
                    p = p.parent
                    depth += 1
                }
                val (cx, cy) = cardCenter ?: (
                    node.bounds.centerX to (node.bounds.bottom + (screenH * 0.16f).toInt()).coerceAtMost((screenH * 0.72f).toInt())
                )
                rawTargets.add(
                    RecentsTarget(
                        label = lbl,
                        cx = cx,
                        cy = cy,
                        area = cardArea,
                        matchesAppletOrDrama = matchesSpecific,
                        looksLikeMainApp = looksLikeMain
                    )
                )
            }
        }

        val deduped = mutableListOf<RecentsTarget>()
        for (cand in rawTargets.sortedByDescending { it.area }) {
            if (deduped.none { kotlin.math.abs(it.cx - cand.cx) < 80 && kotlin.math.abs(it.cy - cand.cy) < 80 }) {
                deduped.add(cand)
            }
        }
        val untried = deduped.filterNot { cand ->
            triedRecentsCardCenters.any { kotlin.math.abs(it.first - cand.cx) < 90 && kotlin.math.abs(it.second - cand.cy) < 90 }
        }
        val pool = untried.ifEmpty { deduped }
        val keywordPick = pool.sortedWith(
            compareByDescending<RecentsTarget> { it.matchesAppletOrDrama }
                .thenBy { it.looksLikeMainApp }
                .thenByDescending { it.area }
        ).firstOrNull()
        if (keywordPick != null) return keywordPick

        // Pattern 3: Non-external large task-card geometry fallback (English ROM / masked thumbnails)
        val externalKeywords = listOf(
            "淘宝", "天猫", "拼多多", "京东", "美团", "应用商店", "小米商城", "应用市场", "短剧自动看广告", "无障碍"
        )
        val nonExtCards = recentsScan.snapshot.nodes.filter { node ->
            val lbl = node.displayLabel
            node.bounds.isOnScreen(screenW, screenH) &&
                node.bounds.width in (screenW * 0.24f).toInt()..(screenW * 0.85f).toInt() &&
                node.bounds.height in (screenH * 0.20f).toInt()..(screenH * 0.78f).toInt() &&
                node.bounds.centerY in (screenH * 0.12f).toInt()..(screenH * 0.82f).toInt() &&
                externalKeywords.none { lbl.contains(it, ignoreCase = true) }
        }.filterNot { node ->
            triedRecentsCardCenters.any {
                kotlin.math.abs(it.first - node.bounds.centerX) < 90 &&
                    kotlin.math.abs(it.second - node.bounds.centerY) < 90
            }
        }
        return nonExtCards.firstOrNull()?.let { card ->
            RecentsTarget(
                label = card.displayLabel.ifBlank { "相邻应用卡片" },
                cx = card.bounds.centerX,
                cy = card.bounds.centerY,
                area = card.bounds.width * card.bounds.height,
                matchesAppletOrDrama = false,
                looksLikeMainApp = false
            )
        }
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
