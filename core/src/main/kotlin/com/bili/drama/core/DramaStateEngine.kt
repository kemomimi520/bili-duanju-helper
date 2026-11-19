package com.bili.drama.core

enum class EnginePhase {
    IDLE,
    PROBING_EPISODE,
    WAITING_AD_START,
    WATCHING_AD,
    EXTERNAL_APP_STAY,
    RETURNING_TO_BILI,
    POST_AD_SETTLE,
    STOPPED
}

sealed class EngineAction {
    data class Wait(val delayMs: Long, val status: String) : EngineAction()
    data class ClickNode(val node: UiNode, val reason: String) : EngineAction()
    data class SeekRatio(
        val seekBar: UiNode?,
        val ratio: Float,
        val isSecondary: Boolean = false,
        val reason: String
    ) : EngineAction()
    data class SwipeNextEpisode(val reason: String) : EngineAction()
    data class PressBack(val reason: String) : EngineAction()
    data class BringBiliToFront(val reason: String, val attempt: Int = 1) : EngineAction()
    data class Stop(val reason: String) : EngineAction()
}

/**
 * Shared state machine for batch-unlocking all episodes of a short drama by watching/experiencing ads.
 * Driven by platform-independent UI snapshots from Android AccessibilityService.
 */
class DramaStateEngine(
    private var config: DramaConfig = DramaConfig()
) {
    companion object {
        const val MAX_RETURN_ATTEMPTS = 8
    }

    var phase: EnginePhase = EnginePhase.IDLE
        private set

    var lastEpisode: EpisodeProgress? = null
        private set

    var adsCompletedCount: Int = 0
        private set

    val currentSwipeAttempt: Int
        get() = sameEpisodeSwipeAttempts

    private var episodeEnteredAtMs: Long = 0L
    private var seekStage: Int = 0 // 0=not seeked, 1=primary seek done, 2=secondary seek done
    private var lastActionAtMs: Long = 0L
    private var lastSwipeAtMs: Long = 0L
    private var adEnteredAtMs: Long = 0L
    private var externalEnteredAtMs: Long = 0L
    private var externalJumpTriggeredForAd: Boolean = false
    private var externalJumpClickCount: Int = 0
    private var rewardEarnedSinceLastUnlockClick: Boolean = false
    private var sameEpisodeUnlockAttempts: Int = 0
    private var sameEpisodeSwipeAttempts: Int = 0
    private var sameEpisodeUnpauseAttempts: Int = 0
    private var returnToBiliAttempts: Int = 0
    private var lastUnlockEpisodeNum: Int = -1
    private var adClosingAtMs: Long = 0L
    private var lockedDramaTotal: Int? = null
    private var dramaContainerActivity: String = ""
    private var lastBufferingAtMs: Long = 0L
    private var retentionCooldownUntilMs: Long = 0L
    private var unexpectedEnteredAtMs: Long = 0L

    fun updateConfig(newConfig: DramaConfig) {
        config = newConfig
    }

    fun start(nowMs: Long) {
        phase = EnginePhase.PROBING_EPISODE
        episodeEnteredAtMs = nowMs
        seekStage = 0
        lastActionAtMs = 0L
        lastSwipeAtMs = 0L
        adEnteredAtMs = 0L
        externalEnteredAtMs = 0L
        externalJumpTriggeredForAd = false
        externalJumpClickCount = 0
        rewardEarnedSinceLastUnlockClick = false
        sameEpisodeUnlockAttempts = 0
        sameEpisodeSwipeAttempts = 0
        sameEpisodeUnpauseAttempts = 0
        returnToBiliAttempts = 0
        lastUnlockEpisodeNum = -1
        adClosingAtMs = 0L
        adsCompletedCount = 0
        lastEpisode = null
        lockedDramaTotal = null
        dramaContainerActivity = ""
        lastBufferingAtMs = 0L
        retentionCooldownUntilMs = 0L
        unexpectedEnteredAtMs = 0L
    }

    fun stop() {
        phase = EnginePhase.IDLE
    }

    fun step(snapshot: UiSnapshot): EngineAction {
        if (phase == EnginePhase.IDLE || phase == EnginePhase.STOPPED) {
            return EngineAction.Wait(1000L, "待机中")
        }

        val now = snapshot.timestampMs

        // 1. Handle external app screen (outside Bilibili)
        if (snapshot.packageName != BiliNodes.PKG_BILI) {
            unexpectedEnteredAtMs = 0L
            return handleOutsideBilibili(snapshot, now)
        }

        // 4. Inside Bilibili: Reward Ad screen
        if (RuleMatcher.isRewardAdScreen(snapshot)) {
            unexpectedEnteredAtMs = 0L
            return handleRewardAdScreen(snapshot, now)
        }

        // 4.5 Inside Bilibili: Handle in-app "即将离开哔哩哔哩" dialog even before RewardAdActivity attaches
        if (RuleMatcher.isJumpConfirmationDialog(snapshot)) {
            unexpectedEnteredAtMs = 0L
            val shouldBlock = RuleMatcher.isDownloadOrInstallPrompt(snapshot) ||
                phase == EnginePhase.RETURNING_TO_BILI ||
                (config.interceptExternalJumps && !externalJumpTriggeredForAd)
            if (shouldBlock) {
                RuleMatcher.findSystemJumpDeny(snapshot)?.let { denyNode ->
                    if (now - lastActionAtMs > 700L) {
                        lastActionAtMs = now
                        return EngineAction.ClickNode(denyNode, "拒绝站内跳出弹窗: ${denyNode.displayLabel}")
                    }
                    return EngineAction.Wait(350L, "关闭跳出弹窗")
                }
            } else {
                RuleMatcher.findSystemJumpConfirm(snapshot)?.let { confirmNode ->
                    if (now - lastActionAtMs > 700L) {
                        lastActionAtMs = now
                        return EngineAction.ClickNode(confirmNode, "确认站内体验跳转: ${confirmNode.displayLabel}")
                    }
                    return EngineAction.Wait(350L, "确认跳转体验")
                }
            }
        }

        // 5. Inside Bilibili: Short-drama container screen
        if (RuleMatcher.isShortDramaScreen(snapshot)) {
            unexpectedEnteredAtMs = 0L
            return handleShortDramaScreen(snapshot, now)
        }

        // 4. Inside Bilibili but on an unexpected page or in-app experience mini-program (e.g. novel/game ad)
        return handleUnexpectedBiliPage(snapshot, now)
    }

    private fun handleOutsideBilibili(snapshot: UiSnapshot, now: Long): EngineAction {
        val pkg = snapshot.packageName
        val expectingAdTransition = phase == EnginePhase.WAITING_AD_START ||
            phase == EnginePhase.WATCHING_AD ||
            phase == EnginePhase.EXTERNAL_APP_STAY ||
            phase == EnginePhase.RETURNING_TO_BILI ||
            externalJumpTriggeredForAd

        // 1. Handle system / OEM "是否允许打开外部应用 / 下载安装" confirmation dialogs first
        if (expectingAdTransition && RuleMatcher.isJumpConfirmationDialog(snapshot)) {
            val shouldBlockDialog = RuleMatcher.isDownloadOrInstallPrompt(snapshot) ||
                phase == EnginePhase.RETURNING_TO_BILI ||
                (config.interceptExternalJumps && !externalJumpTriggeredForAd)
            if (shouldBlockDialog) {
                RuleMatcher.findSystemJumpDeny(snapshot)?.let { denyNode ->
                    if (now - lastActionAtMs > 700L) {
                        lastActionAtMs = now
                        return EngineAction.ClickNode(denyNode, "拦截自动跳转/下载弹窗: ${denyNode.displayLabel}")
                    }
                    return EngineAction.Wait(400L, "关闭自动跳转弹窗")
                }
            } else {
                RuleMatcher.findSystemJumpConfirm(snapshot)?.let { confirmNode ->
                    if (now - lastActionAtMs > 750L) {
                        lastActionAtMs = now
                        return EngineAction.ClickNode(confirmNode, "确认体验跳转弹窗: ${confirmNode.displayLabel}")
                    }
                    return EngineAction.Wait(450L, "等待系统跳转确认")
                }
            }
        }

        // 2. Always reject APK installers immediately; reject App Stores immediately when unsolicited.
        // During an intentional ExternalJumpTask (when target 3rd-party app isn't installed and Android opens Store),
        // passively wait out externalStaySeconds without clicking install/download, then pull back to claim reward.
        val isApkInstaller = pkg in BiliNodes.APK_INSTALLER_PACKAGES
        val isUnsolicitedStore = pkg in BiliNodes.APP_STORE_AND_INSTALLER_PACKAGES &&
            !externalJumpTriggeredForAd &&
            phase != EnginePhase.EXTERNAL_APP_STAY
        if (isApkInstaller || isUnsolicitedStore) {
            phase = EnginePhase.RETURNING_TO_BILI
            externalJumpTriggeredForAd = false
            if (now - lastActionAtMs > 850L) {
                returnToBiliAttempts = (returnToBiliAttempts + 1).coerceAtMost(MAX_RETURN_ATTEMPTS)
                lastActionAtMs = now
                return EngineAction.BringBiliToFront(
                    reason = "拦截应用商店/安装器($pkg)，立即返回(第${returnToBiliAttempts}次)",
                    attempt = returnToBiliAttempts
                )
            }
            return EngineAction.Wait(400L, "正在退回B站...")
        }

        // 3. If we intentionally triggered an external experience task, wait the configured stay seconds
        if (phase == EnginePhase.EXTERNAL_APP_STAY || externalJumpTriggeredForAd) {
            if (phase != EnginePhase.EXTERNAL_APP_STAY || externalEnteredAtMs == 0L) {
                phase = EnginePhase.EXTERNAL_APP_STAY
                externalEnteredAtMs = now
            }
            val elapsedMs = now - externalEnteredAtMs
            val targetMs = config.externalStaySeconds * 1000L
            if (elapsedMs < targetMs) {
                val remainSec = ((targetMs - elapsedMs + 999L) / 1000L).toInt()
                val stepWait = (targetMs - elapsedMs).coerceIn(200L, 450L)
                return EngineAction.Wait(stepWait, "外部软件体验中: 剩余 ${remainSec}s")
            }
            phase = EnginePhase.RETURNING_TO_BILI
            externalJumpTriggeredForAd = false
            returnToBiliAttempts = 1
            lastActionAtMs = now
            return EngineAction.BringBiliToFront(
                reason = "外部体验满 ${config.externalStaySeconds}s，返回B站领奖",
                attempt = 1
            )
        }

        // 4. Active multi-attempt escalation to return to Bilibili (Checked BEFORE pause-package guard
        //    so transient Back -> Launcher or multi-screen third-party apps never stall pull-back!)
        if (phase == EnginePhase.RETURNING_TO_BILI) {
            if (returnToBiliAttempts < MAX_RETURN_ATTEMPTS) {
                RuleMatcher.findReturnToBiliButton(snapshot)?.let { returnNode ->
                    if (now - lastActionAtMs > 800L) {
                        lastActionAtMs = now
                        return EngineAction.ClickNode(returnNode, "点击返回B站按钮: ${returnNode.displayLabel}")
                    }
                    return EngineAction.Wait(350L, "正在返回B站...")
                }
                if (now - lastActionAtMs > 2200L) {
                    returnToBiliAttempts += 1
                    lastActionAtMs = now
                    return EngineAction.BringBiliToFront(
                        reason = "正在拉回B站(第${returnToBiliAttempts}次尝试)",
                        attempt = returnToBiliAttempts
                    )
                }
                return EngineAction.Wait(350L, "正在返回B站...")
            }
            return EngineAction.Wait(1000L, "未能自动拉回B站($pkg)，请手动切回B站")
        }

        // 5. Never fight user returning to Launcher / System / Plugin UI when idle/not returning
        val isPausePkg = pkg in BiliNodes.NON_AD_PAUSE_PACKAGES ||
            pkg.contains("launcher") ||
            pkg.endsWith(".home")
        if (isPausePkg || (phase != EnginePhase.WATCHING_AD && phase != EnginePhase.WAITING_AD_START)) {
            return EngineAction.Wait(900L, "已切出B站($pkg)，暂停等待返回")
        }

        // 6. Unsolicited automatic jump out during an ad (e.g. shake/auto-redirect): pull back immediately so timer resumes
        val pullInterval = if (config.interceptExternalJumps) 800L else 1200L
        if (now - lastActionAtMs > pullInterval) {
            phase = EnginePhase.RETURNING_TO_BILI
            returnToBiliAttempts = 1
            lastActionAtMs = now
            return EngineAction.BringBiliToFront(
                reason = "拦截自动跳出($pkg)，立即拉回继续计时",
                attempt = 1
            )
        }
        return EngineAction.Wait(450L, "正在返回B站继续广告计时...")
    }

    private fun handleRewardAdScreen(snapshot: UiSnapshot, now: Long): EngineAction {
        returnToBiliAttempts = 0
        if (phase != EnginePhase.WATCHING_AD) {
            phase = EnginePhase.WATCHING_AD
            adEnteredAtMs = now
            adClosingAtMs = 0L
            retentionCooldownUntilMs = 0L
            externalJumpClickCount = 0
        }

        // Handle Bilibili's own in-app jump confirmation dialog ("即将离开哔哩哔哩")
        if (RuleMatcher.isJumpConfirmationDialog(snapshot)) {
            val shouldBlock = RuleMatcher.isDownloadOrInstallPrompt(snapshot) ||
                (config.interceptExternalJumps && !externalJumpTriggeredForAd)
            if (shouldBlock) {
                RuleMatcher.findSystemJumpDeny(snapshot)?.let { denyNode ->
                    if (now - lastActionAtMs > 700L) {
                        lastActionAtMs = now
                        return EngineAction.ClickNode(denyNode, "拒绝站内跳出弹窗: ${denyNode.displayLabel}")
                    }
                    return EngineAction.Wait(350L, "关闭跳出弹窗")
                }
            } else {
                RuleMatcher.findSystemJumpConfirm(snapshot)?.let { confirmNode ->
                    if (now - lastActionAtMs > 700L) {
                        lastActionAtMs = now
                        return EngineAction.ClickNode(confirmNode, "确认站内体验跳转: ${confirmNode.displayLabel}")
                    }
                    return EngineAction.Wait(350L, "确认跳转体验")
                }
            }
        }

        // Guard against infinite wait in a broken ad
        val adElapsedSec = (now - adEnteredAtMs) / 1000L
        if (adElapsedSec > config.adTimeoutSeconds && now - lastActionAtMs > 2000L) {
            lastActionAtMs = now
            val closeNode = RuleMatcher.findTimeoutDismissButton(snapshot)
            return if (closeNode != null) {
                EngineAction.ClickNode(closeNode, "广告超时(${adElapsedSec}s)，尝试关闭(${closeNode.displayLabel})")
            } else {
                EngineAction.PressBack("广告超时(${adElapsedSec}s)，尝试返回")
            }
        }

        return when (val adState = RuleMatcher.classifyAd(snapshot)) {
            is AdClassification.RetentionDialog -> {
                // Retention dialog proves the ad has NOT finished rewarding yet: roll back any premature close state
                if (adClosingAtMs > 0L) {
                    adsCompletedCount = (adsCompletedCount - 1).coerceAtLeast(0)
                    adClosingAtMs = 0L
                    rewardEarnedSinceLastUnlockClick = false
                }
                retentionCooldownUntilMs = now + 4000L
                if (now - lastActionAtMs > 800L) {
                    lastActionAtMs = now
                    EngineAction.ClickNode(adState.continueNode, "拦截挽留弹窗: ${adState.continueNode.displayLabel}")
                } else {
                    EngineAction.Wait(500L, "处理挽留弹窗")
                }
            }

            is AdClassification.Rewarded -> {
                val countdownLabel = snapshot.firstById(BiliNodes.ID_TV_COUNTDOWN)?.displayLabel.orEmpty()
                val explicitCountdownDone = BiliNodes.REWARD_DONE_KEYWORDS.any { countdownLabel.contains(it) }
                if (now < retentionCooldownUntilMs && !explicitCountdownDone) {
                    return EngineAction.Wait(350L, "继续观看广告等待奖励...")
                }
                if (adClosingAtMs == 0L) {
                    adsCompletedCount += 1
                    rewardEarnedSinceLastUnlockClick = true
                    adClosingAtMs = now
                }
                if (now - lastActionAtMs > 650L) {
                    lastActionAtMs = now
                    val close = adState.closeNode
                    if (close != null) {
                        EngineAction.ClickNode(close, "已获得奖励，关闭广告")
                    } else if (adState.hasSwipeGuide) {
                        EngineAction.SwipeNextEpisode("已获得奖励，上滑返回短剧")
                    } else {
                        EngineAction.PressBack("已获得奖励，返回短剧")
                    }
                } else {
                    EngineAction.Wait(250L, "关闭已达标广告...")
                }
            }

            is AdClassification.ExternalJumpTask -> {
                val canRetryClick = externalJumpTriggeredForAd &&
                    externalJumpClickCount < 3 &&
                    now - lastActionAtMs > 4500L
                if ((!externalJumpTriggeredForAd && now - lastActionAtMs > 800L) || canRetryClick) {
                    externalJumpTriggeredForAd = true
                    externalJumpClickCount += 1
                    lastActionAtMs = now
                    phase = EnginePhase.EXTERNAL_APP_STAY
                    externalEnteredAtMs = 0L
                    EngineAction.ClickNode(adState.actionNode, "触发外部体验任务: ${adState.actionNode.displayLabel}")
                } else {
                    EngineAction.Wait(350L, "等待外部跳转...")
                }
            }

            is AdClassification.Waiting -> {
                val label = adState.statusText.ifBlank { "等待广告奖励(${adElapsedSec}s)" }
                EngineAction.Wait(260L, label)
            }
        }
    }

    private fun handleShortDramaScreen(snapshot: UiSnapshot, now: Long): EngineAction {
        returnToBiliAttempts = 0
        val rawEp = EpisodeParser.parse(snapshot)

        // Lock onto the user's target drama total episode count on initial entry
        if (lockedDramaTotal == null && rawEp?.total != null &&
            phase != EnginePhase.EXTERNAL_APP_STAY && !externalJumpTriggeredForAd
        ) {
            lockedDramaTotal = rawEp.total
            if (snapshot.activityName.contains("AppContainerActivity")) {
                dramaContainerActivity = snapshot.activityName
            }
        }

        // Detect when an ad ("去看看") opened a DIFFERENT promoted short drama (e.g. 1/55 vs target 38)
        if (lockedDramaTotal != null && rawEp?.total != null && rawEp.total != lockedDramaTotal) {
            return handleUnexpectedBiliPage(snapshot, now, allowCapsuleClose = false)
        }

        // Preserve known drama total when select bar momentarily omits "(x/total)" during seeking
        val ep = if (rawEp != null && rawEp.total == null && lockedDramaTotal != null) {
            rawEp.copy(total = lockedDramaTotal)
        } else {
            rawEp
        }

        // Just returned from an ad -> settle briefly & reset ad flags
        if (phase == EnginePhase.WATCHING_AD || phase == EnginePhase.EXTERNAL_APP_STAY || phase == EnginePhase.RETURNING_TO_BILI) {
            phase = EnginePhase.POST_AD_SETTLE
            episodeEnteredAtMs = now
            lastActionAtMs = 0L
            externalJumpTriggeredForAd = false
            externalEnteredAtMs = 0L
            seekStage = 0
            sameEpisodeSwipeAttempts = 0
            sameEpisodeUnpauseAttempts = 0
            if (rewardEarnedSinceLastUnlockClick) {
                sameEpisodeUnlockAttempts = 0
                rewardEarnedSinceLastUnlockClick = false
            }
            return EngineAction.Wait(config.postAdSettleMs.coerceAtMost(350L), "广告结束，等待解锁状态刷新")
        }

        if (ep != null && lastEpisode?.current != ep.current) {
            // Moved to a new episode!
            lastEpisode = ep
            phase = EnginePhase.PROBING_EPISODE
            episodeEnteredAtMs = now
            lastActionAtMs = 0L
            seekStage = 0
            sameEpisodeUnlockAttempts = 0
            sameEpisodeSwipeAttempts = 0
            sameEpisodeUnpauseAttempts = 0
            externalJumpTriggeredForAd = false
            lastBufferingAtMs = 0L
        } else if (ep != null) {
            lastEpisode = ep
        }

        if (phase == EnginePhase.POST_AD_SETTLE) {
            if (now - episodeEnteredAtMs < config.postAdSettleMs) {
                return EngineAction.Wait(220L, "刷新解锁状态...")
            }
            phase = EnginePhase.PROBING_EPISODE
        }

        // 1. If waiting for ad to launch after clicking unlock, give Bilibili up to 3s to open RewardAdActivityWeb
        if (phase == EnginePhase.WAITING_AD_START) {
            if (now - lastActionAtMs < 3000L) {
                return EngineAction.Wait(250L, "等待广告拉起...")
            }
            // Didn't launch an ad within 3s (unlocked directly or no ad inventory), resume probing
            phase = EnginePhase.PROBING_EPISODE
            episodeEnteredAtMs = now
        }

        // 1.5 If a paid/VIP unlock bottom sheet ("选择解锁方式") is covering the player, close it first
        RuleMatcher.findPaidModalCloseButton(snapshot)?.let { paidClose ->
            if (now - lastActionAtMs > 750L) {
                lastActionAtMs = now
                episodeEnteredAtMs = now
                return EngineAction.ClickNode(paidClose, "关闭付费解锁弹窗，保留纯广告解锁")
            }
            return EngineAction.Wait(250L, "正在关闭付费弹窗...")
        }

        // 2. Check if an unlock button (or WebView bonus multi-episode CTA) is visible on screen
        val unlockBtn = RuleMatcher.findUnlockButton(snapshot)
        if (unlockBtn != null) {
            if (now - lastActionAtMs < 850L) {
                return EngineAction.Wait(250L, "准备点击解锁按钮")
            }
            val curNum = lastEpisode?.current ?: -1
            val bonusDismiss = RuleMatcher.findBonusDismissButton(snapshot)
            if (bonusDismiss != null &&
                (lastEpisode?.isLastEpisode == true || (curNum == lastUnlockEpisodeNum && sameEpisodeUnlockAttempts >= 2))
            ) {
                sameEpisodeUnlockAttempts = 0
                lastActionAtMs = now
                episodeEnteredAtMs = now
                return EngineAction.ClickNode(bonusDismiss, "额外奖励广告未拉起，关闭弹窗: ${bonusDismiss.displayLabel}")
            }
            if (curNum == lastUnlockEpisodeNum) {
                sameEpisodeUnlockAttempts += 1
            } else {
                lastUnlockEpisodeNum = curNum
                sameEpisodeUnlockAttempts = 1
            }
            if (sameEpisodeUnlockAttempts > config.maxSameEpisodeRetries) {
                phase = EnginePhase.STOPPED
                return EngineAction.Stop("同一集连续解锁失败 ${config.maxSameEpisodeRetries} 次，已自动暂停")
            }
            lastActionAtMs = now
            phase = EnginePhase.WAITING_AD_START
            externalJumpTriggeredForAd = false
            rewardEarnedSinceLastUnlockClick = false
            return EngineAction.ClickNode(unlockBtn, "点击广告解锁: ${unlockBtn.displayLabel}")
        }

        // 2.5 Never treat a visibly locked screen as unlocked/no-ad even if button labels aren't matched yet
        if (RuleMatcher.hasVisibleLockOverlay(snapshot)) {
            val labels = RuleMatcher.findLockOverlayButtons(snapshot)
                .map { it.displayLabel }
                .filter { it.isNotEmpty() }
                .joinToString(" | ")
            val detail = if (labels.isNotEmpty()) "[$labels]" else "等待按钮渲染"
            return EngineAction.Wait(250L, "${lastEpisode ?: "当前集"} 已锁集 $detail")
        }

        // 2.8 Unpause video if paused (while paused, seeking cannot trigger preview/pause-point lock callbacks)
        val pausedPlayBtn = RuleMatcher.findPausedPlayButton(snapshot)
        if (pausedPlayBtn != null && sameEpisodeUnpauseAttempts < 3) {
            if (now - lastActionAtMs > 700L) {
                sameEpisodeUnpauseAttempts += 1
                lastActionAtMs = now
                episodeEnteredAtMs = now
                return EngineAction.ClickNode(pausedPlayBtn, "${lastEpisode ?: "当前集"} 视频已暂停，恢复播放")
            }
            return EngineAction.Wait(250L, "${lastEpisode ?: "当前集"} 正在恢复播放...")
        }

        val elapsedInEp = now - episodeEnteredAtMs

        // 2.9 Wait briefly if player is visibly buffering so seekbar drag hits an active player (state >= 3)
        if (RuleMatcher.isEpisodeBuffering(snapshot) && elapsedInEp < 5200L) {
            lastBufferingAtMs = now
            return EngineAction.Wait(250L, "${lastEpisode ?: "当前集"} 等待缓冲...")
        }

        // 3. Fast single-stage seek probe (or optional 2-stage) to trigger preview/pause-point locks immediately
        val seekBar = RuleMatcher.findSeekBar(snapshot)
        if (config.seekCheckEnabled && seekBar != null) {
            if (seekStage == 0) {
                if (elapsedInEp < config.episodeSettleMs) {
                    return EngineAction.Wait(200L, "${lastEpisode ?: "当前集"} 准备检测...")
                }
                seekStage = 1
                episodeEnteredAtMs = now
                lastBufferingAtMs = 0L
                return EngineAction.SeekRatio(
                    seekBar = seekBar,
                    ratio = config.primarySeekRatio,
                    isSecondary = false,
                    reason = "${lastEpisode ?: "当前集"} 快进至 ${(config.primarySeekRatio * 100).toInt()}% 检测广告"
                )
            }
            if (seekStage == 1) {
                if (elapsedInEp < config.postSeekWaitMs ||
                    (lastBufferingAtMs > 0L && now - lastBufferingAtMs < config.postSeekWaitMs)
                ) {
                    return EngineAction.Wait(220L, "${lastEpisode ?: "当前集"} 检测锁集...")
                }
                if (config.secondarySeekEnabled) {
                    seekStage = 2
                    episodeEnteredAtMs = now
                    return EngineAction.SeekRatio(
                        seekBar = seekBar,
                        ratio = config.secondarySeekRatio,
                        isSecondary = true,
                        reason = "${lastEpisode ?: "当前集"} 快进至 ${(config.secondarySeekRatio * 100).toInt()}% 复检"
                    )
                }
                seekStage = 2
            } else if (seekStage == 2 && config.secondarySeekEnabled && elapsedInEp < config.postSeekWaitMs) {
                return EngineAction.Wait(220L, "${lastEpisode ?: "当前集"} 复检结尾锁...")
            }
        } else if (elapsedInEp < config.fallbackNoSeekWaitMs) {
            return EngineAction.Wait(300L, "${lastEpisode ?: "当前集"} 观察是否有广告...")
        }

        // 4. Episode has no lock -> check if we reached the final episode
        val curEp = lastEpisode
        if (config.stopAtLastEpisode && curEp != null && curEp.isLastEpisode) {
            phase = EnginePhase.STOPPED
            return EngineAction.Stop("已刷完最后一集 ($curEp)，共处理 $adsCompletedCount 个广告")
        }

        // 5. Swipe to next episode (NEVER reset seekStage on same episode if swipe snaps back!)
        if (now - lastSwipeAtMs > 1050L) {
            if (curEp != null) {
                sameEpisodeSwipeAttempts += 1
                if (sameEpisodeSwipeAttempts > 4) {
                    phase = EnginePhase.STOPPED
                    return EngineAction.Stop("$curEp 连续上滑未切集（已达末尾或需手动处理），共完成 $adsCompletedCount 个广告")
                }
            } else {
                seekStage = 0
                episodeEnteredAtMs = now
            }
            lastSwipeAtMs = now
            return EngineAction.SwipeNextEpisode("${curEp ?: "本集"} 无广告/已解锁，上滑下一集")
        }
        return EngineAction.Wait(250L, "切换下一集...")
    }

    private fun handleUnexpectedBiliPage(
        snapshot: UiSnapshot,
        now: Long,
        allowCapsuleClose: Boolean = true
    ): EngineAction {
        val canUseCapsule = allowCapsuleClose &&
            snapshot.activityName.isNotBlank() &&
            snapshot.activityName != dramaContainerActivity

        // An ad's "去体验10秒" opened an in-Bilibili mini-program (e.g. AppContainerActivity2 novel/game), promo drama, or WebView
        if (phase == EnginePhase.EXTERNAL_APP_STAY || externalJumpTriggeredForAd) {
            if (phase != EnginePhase.EXTERNAL_APP_STAY || externalEnteredAtMs == 0L) {
                phase = EnginePhase.EXTERNAL_APP_STAY
                externalEnteredAtMs = now
            }
            val elapsedMs = now - externalEnteredAtMs
            val targetMs = config.externalStaySeconds * 1000L
            if (elapsedMs < targetMs) {
                val remainSec = ((targetMs - elapsedMs + 999L) / 1000L).toInt()
                val stepWait = (targetMs - elapsedMs).coerceIn(200L, 450L)
                return EngineAction.Wait(stepWait, "站内广告体验中: 剩余 ${remainSec}s")
            }
            phase = EnginePhase.RETURNING_TO_BILI
            externalJumpTriggeredForAd = false
            if (now - lastActionAtMs > 750L) {
                lastActionAtMs = now
                RuleMatcher.findInAppModalCloseButton(snapshot)?.let { modalClose ->
                    return EngineAction.ClickNode(modalClose, "站内体验满 ${config.externalStaySeconds}s，关闭实名弹窗")
                }
                if (canUseCapsule) {
                    val capsuleClose = snapshot.firstById(BiliNodes.ID_MORE_RIGHT_BTN)
                    if (capsuleClose != null) {
                        return EngineAction.ClickNode(capsuleClose, "站内体验满 ${config.externalStaySeconds}s，关闭广告小程序")
                    }
                }
                return EngineAction.PressBack("站内体验满 ${config.externalStaySeconds}s，返回广告领奖")
            }
            return EngineAction.Wait(300L, "正在退出站内体验...")
        }

        // Before any drama episode or ad has been entered, wait calmly for the user/mini-program to finish loading SAStoryView
        if (phase == EnginePhase.PROBING_EPISODE &&
            lastEpisode == null &&
            adsCompletedCount == 0 &&
            lastUnlockEpisodeNum == -1
        ) {
            return EngineAction.Wait(800L, "等待进入短剧播放页...")
        }

        if (phase == EnginePhase.WAITING_AD_START && now - lastActionAtMs < 3500L) {
            return EngineAction.Wait(500L, "页面跳转中...")
        }

        // Wait calmly right after swiping episodes so transient ViewPager2/ad-loading frames never trigger Back
        if (lastSwipeAtMs > 0L && now - lastSwipeAtMs < 3500L) {
            return EngineAction.Wait(450L, "切集过渡加载中...")
        }

        val isAdLandingOrCallUp = snapshot.activityName.contains("AdWebActivity") ||
            snapshot.activityName.contains("AdCallUp") ||
            snapshot.activityName.contains("CallUpProxy")

        if (phase == EnginePhase.RETURNING_TO_BILI && isAdLandingOrCallUp) {
            if (now - lastActionAtMs > 550L) {
                lastActionAtMs = now
                RuleMatcher.findInAppModalCloseButton(snapshot)?.let { closeBtn ->
                    return EngineAction.ClickNode(closeBtn, "关闭广告落地页/中转页弹窗，返回广告领奖")
                }
                return EngineAction.PressBack("关闭广告落地页/中转页，返回广告领奖")
            }
            return EngineAction.Wait(350L, "正在关闭广告中转页...")
        }

        // If we returned to Bilibili's main activity after launcher fallback instead of AppContainer/RewardAd,
        // try switching back to the drama task window once before falling back to PressBack
        if (phase == EnginePhase.RETURNING_TO_BILI &&
            snapshot.activityName.contains("MainActivity")
        ) {
            if (dramaContainerActivity.isNotBlank() && returnToBiliAttempts < MAX_RETURN_ATTEMPTS) {
                if (now - lastActionAtMs > 2200L) {
                    returnToBiliAttempts += 1
                    lastActionAtMs = now
                    return EngineAction.BringBiliToFront(
                        reason = "尝试切回短剧任务窗口(第${returnToBiliAttempts}次)",
                        attempt = returnToBiliAttempts
                    )
                }
                return EngineAction.Wait(400L, "等待切回短剧窗口...")
            }
            // Never press Back on MainActivityV2 when returning, which pops right back into Taobao/external app
            return EngineAction.Wait(1000L, "已切回B站主界面，等待切回短剧窗口")
        }

        if (unexpectedEnteredAtMs == 0L) {
            unexpectedEnteredAtMs = now
        }

        val backDelay = if (config.interceptExternalJumps && phase == EnginePhase.WATCHING_AD) 900L else 1800L
        val onDramaContainer = snapshot.activityName.isNotBlank() && snapshot.activityName == dramaContainerActivity

        // Never press Back blindly inside the drama mini-program container on transient empty/loading frames
        // (doing so exits the mini-program and drops the user out to Bilibili home/launcher).
        if (onDramaContainer && phase != EnginePhase.RETURNING_TO_BILI) {
            RuleMatcher.findInAppModalCloseButton(snapshot)?.let { modalClose ->
                if (now - lastActionAtMs > 800L) {
                    lastActionAtMs = now
                    return EngineAction.ClickNode(modalClose, "关闭遮挡弹窗，恢复短剧画面")
                }
            }
            return EngineAction.Wait(500L, "等待短剧画面加载...")
        }

        if (now - unexpectedEnteredAtMs >= backDelay && now - lastActionAtMs > backDelay) {
            lastActionAtMs = now
            RuleMatcher.findInAppModalCloseButton(snapshot)?.let { modalClose ->
                return EngineAction.ClickNode(modalClose, "关闭实名阻塞弹窗，继续返回短剧")
            }
            if (canUseCapsule && dramaContainerActivity.isNotBlank()) {
                val capsuleClose = snapshot.firstById(BiliNodes.ID_MORE_RIGHT_BTN)
                if (capsuleClose != null) {
                    return EngineAction.ClickNode(capsuleClose, "关闭非短剧小程序，返回短剧")
                }
            }
            return EngineAction.PressBack("当前在非预期页面，尝试返回短剧/广告")
        }
        return EngineAction.Wait(600L, "等待页面恢复...")
    }
}
