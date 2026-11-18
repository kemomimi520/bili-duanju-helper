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
    data class BringBiliToFront(val reason: String) : EngineAction()
    data class Stop(val reason: String) : EngineAction()
}

/**
 * Shared state machine for batch-unlocking all episodes of a short drama by watching/experiencing ads.
 * Driven by platform-independent UI snapshots from Android AccessibilityService.
 */
class DramaStateEngine(
    private var config: DramaConfig = DramaConfig()
) {
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
    private var rewardEarnedSinceLastUnlockClick: Boolean = false
    private var sameEpisodeUnlockAttempts: Int = 0
    private var sameEpisodeSwipeAttempts: Int = 0
    private var sameEpisodeUnpauseAttempts: Int = 0
    private var lastUnlockEpisodeNum: Int = -1
    private var adClosingAtMs: Long = 0L
    private var lockedDramaTotal: Int? = null
    private var dramaContainerActivity: String = ""

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
        rewardEarnedSinceLastUnlockClick = false
        sameEpisodeUnlockAttempts = 0
        sameEpisodeSwipeAttempts = 0
        sameEpisodeUnpauseAttempts = 0
        lastUnlockEpisodeNum = -1
        adClosingAtMs = 0L
        adsCompletedCount = 0
        lastEpisode = null
        lockedDramaTotal = null
        dramaContainerActivity = ""
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
            return handleOutsideBilibili(snapshot, now)
        }

        // 2. Inside Bilibili: Reward Ad screen
        if (RuleMatcher.isRewardAdScreen(snapshot)) {
            return handleRewardAdScreen(snapshot, now)
        }

        // 3. Inside Bilibili: Short-drama container screen
        if (RuleMatcher.isShortDramaScreen(snapshot)) {
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
            externalJumpTriggeredForAd

        // Always reject app store / APK installer redirects immediately
        if (pkg in BiliNodes.APP_STORE_AND_INSTALLER_PACKAGES) {
            if (now - lastActionAtMs > 800L) {
                lastActionAtMs = now
                return EngineAction.BringBiliToFront("拦截应用商店/安装器($pkg)，立即返回")
            }
            return EngineAction.Wait(450L, "正在退回B站...")
        }

        // Handle system "是否允许打开外部应用 / 下载安装" confirmation dialogs
        if (expectingAdTransition) {
            val shouldBlockDialog = RuleMatcher.isDownloadOrInstallPrompt(snapshot) ||
                (config.interceptExternalJumps && !externalJumpTriggeredForAd)
            if (shouldBlockDialog) {
                RuleMatcher.findSystemJumpDeny(snapshot)?.let { denyNode ->
                    if (now - lastActionAtMs > 800L) {
                        lastActionAtMs = now
                        return EngineAction.ClickNode(denyNode, "拦截自动跳转/下载弹窗: ${denyNode.displayLabel}")
                    }
                    return EngineAction.Wait(450L, "关闭自动跳转弹窗")
                }
            } else {
                RuleMatcher.findSystemJumpConfirm(snapshot)?.let { confirmNode ->
                    if (now - lastActionAtMs > 900L) {
                        lastActionAtMs = now
                        return EngineAction.ClickNode(confirmNode, "确认体验跳转弹窗: ${confirmNode.displayLabel}")
                    }
                    return EngineAction.Wait(500L, "等待系统跳转确认")
                }
            }
        }

        // If we intentionally triggered an external experience task, wait the configured stay seconds
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
            lastActionAtMs = now
            return EngineAction.BringBiliToFront("外部体验满 ${config.externalStaySeconds}s，返回B站领奖")
        }

        // Never fight user returning to Launcher / System / Plugin UI
        val isPausePkg = pkg in BiliNodes.NON_AD_PAUSE_PACKAGES ||
            pkg.contains("launcher") ||
            pkg.endsWith(".home")
        if (isPausePkg || (phase != EnginePhase.WATCHING_AD && phase != EnginePhase.WAITING_AD_START)) {
            return EngineAction.Wait(900L, "已切出B站($pkg)，暂停等待返回")
        }

        // Unsolicited automatic jump out during an ad (e.g. shake/auto-redirect): pull back immediately so timer resumes
        val pullInterval = if (config.interceptExternalJumps) 800L else 1200L
        if (now - lastActionAtMs > pullInterval) {
            lastActionAtMs = now
            return EngineAction.BringBiliToFront("拦截自动跳出($pkg)，立即拉回继续计时")
        }
        return EngineAction.Wait(450L, "正在返回B站继续广告计时...")
    }

    private fun handleRewardAdScreen(snapshot: UiSnapshot, now: Long): EngineAction {
        if (phase != EnginePhase.WATCHING_AD) {
            phase = EnginePhase.WATCHING_AD
            adEnteredAtMs = now
            adClosingAtMs = 0L
        }

        // Guard against infinite wait in a broken ad
        val adElapsedSec = (now - adEnteredAtMs) / 1000L
        if (adElapsedSec > config.adTimeoutSeconds && now - lastActionAtMs > 2000L) {
            lastActionAtMs = now
            val closeNode = snapshot.firstById(BiliNodes.ID_TV_CLOSE)
            return if (closeNode != null) {
                EngineAction.ClickNode(closeNode, "广告超时(${adElapsedSec}s)，尝试关闭")
            } else {
                EngineAction.PressBack("广告超时(${adElapsedSec}s)，尝试返回")
            }
        }

        return when (val adState = RuleMatcher.classifyAd(snapshot)) {
            is AdClassification.RetentionDialog -> {
                if (now - lastActionAtMs > 800L) {
                    lastActionAtMs = now
                    EngineAction.ClickNode(adState.continueNode, "拦截挽留弹窗: ${adState.continueNode.displayLabel}")
                } else {
                    EngineAction.Wait(500L, "处理挽留弹窗")
                }
            }

            is AdClassification.Rewarded -> {
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
                if (!externalJumpTriggeredForAd && now - lastActionAtMs > 800L) {
                    externalJumpTriggeredForAd = true
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
            if (bonusDismiss != null && curNum == lastUnlockEpisodeNum && sameEpisodeUnlockAttempts >= 2) {
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
        if (RuleMatcher.isEpisodeBuffering(snapshot) && elapsedInEp < 2400L) {
            return EngineAction.Wait(220L, "${lastEpisode ?: "当前集"} 等待缓冲...")
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
                return EngineAction.SeekRatio(
                    seekBar = seekBar,
                    ratio = config.primarySeekRatio,
                    isSecondary = false,
                    reason = "${lastEpisode ?: "当前集"} 快进至 ${(config.primarySeekRatio * 100).toInt()}% 检测广告"
                )
            }
            if (seekStage == 1) {
                if (elapsedInEp < config.postSeekWaitMs) {
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
        val backDelay = if (config.interceptExternalJumps && phase == EnginePhase.WATCHING_AD) 900L else 1800L
        if (now - lastActionAtMs > backDelay) {
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
