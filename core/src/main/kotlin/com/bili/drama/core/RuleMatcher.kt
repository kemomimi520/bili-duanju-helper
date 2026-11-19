package com.bili.drama.core

sealed class AdClassification {
    data class RetentionDialog(val continueNode: UiNode) : AdClassification()
    data class Rewarded(val closeNode: UiNode?, val hasSwipeGuide: Boolean) : AdClassification()
    data class ExternalJumpTask(val actionNode: UiNode, val promptText: String) : AdClassification()
    data class Waiting(val statusText: String) : AdClassification()
}

/**
 * Pure rule matcher for Bilibili short-drama unlock gates and reward ads.
 * Operates strictly on view IDs and text nodes (zero OCR / screenshot).
 */
object RuleMatcher {

    fun hasShortDramaStructure(snapshot: UiSnapshot): Boolean {
        if (snapshot.packageName != BiliNodes.PKG_BILI) return false
        return snapshot.firstById(BiliNodes.ID_FEED_VIDEOS) != null ||
            snapshot.firstById(BiliNodes.ID_ALBUM_SELECT_TEXT) != null ||
            snapshot.firstById(BiliNodes.ID_ALBUM_SELECT_LAYOUT) != null ||
            snapshot.firstLaidOutOnScreenById(BiliNodes.ID_CTRL_SEEKBAR) != null ||
            hasVisibleLockOverlay(snapshot) ||
            (snapshot.activityName.contains("AppContainerActivity") &&
                snapshot.allTexts().any { it.contains("选集(") })
    }

    fun isShortDramaScreen(snapshot: UiSnapshot): Boolean =
        hasShortDramaStructure(snapshot)

    fun isRewardAdScreen(snapshot: UiSnapshot): Boolean {
        if (snapshot.packageName != BiliNodes.PKG_BILI) return false
        // Structural short-drama views beat a potentially stale RewardAdActivity name
        if (hasShortDramaStructure(snapshot)) return false
        if (snapshot.firstById(BiliNodes.ID_TV_COUNTDOWN) != null ||
            snapshot.firstById(BiliNodes.ID_TV_CLOSE) != null
        ) {
            return true
        }
        if (snapshot.activityName.contains("RewardAdActivity")) {
            return true
        }
        val compact = snapshot.allTexts().joinToString("")
        if (BiliNodes.REWARD_DONE_KEYWORDS.any { compact.contains(it) } ||
            BiliNodes.EXTERNAL_JUMP_PROMPT_KEYWORDS.any { compact.contains(it) } ||
            compact.contains("秒后可获得奖励") ||
            compact.contains("领取奖励")
        ) {
            return true
        }
        // Only treat generic retention keywords ("放弃"/"继续") as an ad dialog if NOT inside an ad mini-program container
        if (!snapshot.activityName.contains("AppContainerActivity") &&
            BiliNodes.RETENTION_AVOID_KEYWORDS.any { compact.contains(it) }
        ) {
            return true
        }
        return false
    }

    fun findSeekBar(snapshot: UiSnapshot): UiNode? {
        val minWidth = (snapshot.screenWidth / 3).coerceAtLeast(100)
        // 1. Prefer visibly-shown on-screen seekbar on the active episode card
        val visibleSeek = snapshot.nodes.firstOrNull {
            it.viewId == BiliNodes.ID_CTRL_SEEKBAR &&
                it.visible &&
                it.enabled &&
                it.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                it.bounds.width >= minWidth
        }
        if (visibleSeek != null) return visibleSeek

        // 2. Fallback: active PlayerControlWidget may be View.INVISIBLE when idle while keeping valid screen bounds
        return snapshot.nodes.firstOrNull {
            it.viewId == BiliNodes.ID_CTRL_SEEKBAR &&
                it.enabled &&
                it.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                it.bounds.width >= minWidth
        }
    }

    fun findLockOverlayButtons(snapshot: UiSnapshot): List<UiNode> =
        BiliNodes.UNLOCK_BUTTON_IDS.flatMap { id -> snapshot.allById(id) }

    fun isSafeUnlockLabel(label: String): Boolean {
        if (label.isBlank()) return false
        if (BiliNodes.UNLOCK_BLACKLIST_KEYWORDS.any { label.contains(it) }) return false
        return BiliNodes.UNLOCK_POSITIVE_KEYWORDS.any { label.contains(it) }
    }

    /**
     * Locates a safe ad-unlock button on the short-drama screen.
     * Strictly rejects any button containing paid/VIP/purchase keywords or descriptive non-button text.
     */
    fun findUnlockButton(snapshot: UiSnapshot): UiNode? {
        // 1. Prefer known native short-drama unlock view IDs first
        for (id in BiliNodes.UNLOCK_BUTTON_IDS) {
            val candidates = snapshot.allById(id)
            for (node in candidates) {
                if (isSafeUnlockLabel(node.displayLabel)) {
                    return node
                }
            }
        }

        // 2. Explicit WebView / H5 bonus unlock CTA buttons (e.g. "观看领奖励" on "额外赠送2集" modal)
        val bonusBtn = snapshot.nodes.firstOrNull { node ->
            val label = node.displayLabel
            node.visible &&
                node.enabled &&
                node.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                BiliNodes.BONUS_UNLOCK_BUTTON_KEYWORDS.any { label == it || label.contains(it) } &&
                BiliNodes.UNLOCK_BLACKLIST_KEYWORDS.none { label.contains(it) }
        }
        if (bonusBtn != null) return bonusBtn

        // 3. Fallback: visible button/TextView matching safe unlock keywords (excluding descriptive sentences)
        return snapshot.nodes.firstOrNull { node ->
            val label = node.displayLabel
            node.visible &&
                node.enabled &&
                node.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                (node.clickable || node.className.contains("Button") || node.className.contains("TextView")) &&
                isSafeUnlockLabel(label) &&
                BiliNodes.UNLOCK_NON_BUTTON_HINTS.none { label.contains(it) }
        }
    }

    fun findBonusDismissButton(snapshot: UiSnapshot): UiNode? =
        snapshot.nodes.firstOrNull { node ->
            val label = node.displayLabel
            node.visible &&
                node.enabled &&
                node.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                BiliNodes.BONUS_DISMISS_KEYWORDS.any { label == it || label.contains(it) }
        }

    /**
     * Finds the close button ('close' / '关闭' / '×') on WebView paid/VIP unlock bottom sheets
     * (e.g. "选择解锁方式" / "虚拟商品购买后不可退换") so paid sheets never block underlying ad-unlock buttons.
     */
    fun findPaidModalCloseButton(snapshot: UiSnapshot): UiNode? {
        val allTexts = snapshot.allTexts()
        val hasPaidModal = allTexts.any { txt ->
            BiliNodes.PAID_MODAL_INDICATORS.any { txt.contains(it) }
        }
        if (!hasPaidModal) return null
        return snapshot.nodes.firstOrNull { node ->
            val label = node.displayLabel.lowercase()
            node.visible &&
                node.enabled &&
                node.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                BiliNodes.PAID_MODAL_CLOSE_TEXTS.any { label == it.lowercase() }
        }
    }

    fun findPausedPlayButton(snapshot: UiSnapshot): UiNode? {
        if (hasVisibleLockOverlay(snapshot) ||
            findBonusDismissButton(snapshot) != null ||
            findPaidModalCloseButton(snapshot) != null
        ) {
            return null
        }
        return snapshot.firstById(BiliNodes.ID_IC_PLAY)
            ?: snapshot.firstById(BiliNodes.ID_LAYOUT_PAUSE)
    }

    /**
     * Finds the top-right close ('X') button on non-cancelable in-app dialogs such as "游戏实名认证"
     * inside GameContainerActivity0 so 10s game-experience ads can exit cleanly without PressBack getting blocked.
     */
    fun findInAppModalCloseButton(snapshot: UiSnapshot): UiNode? {
        val compact = snapshot.allTexts().joinToString("")
        if (!compact.contains("实名认证") && !compact.contains("防沉迷")) return null
        return snapshot.nodes.firstOrNull { node ->
            node.visible &&
                node.enabled &&
                node.clickable &&
                node.displayLabel.isBlank() &&
                node.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                node.bounds.width in 40..280 &&
                node.bounds.height in 40..280 &&
                node.bounds.left >= snapshot.screenWidth / 2 &&
                node.bounds.top <= (snapshot.screenHeight * 0.55f).toInt()
        }
    }

    /**
     * Checks whether a lock overlay is visibly blocking playback even if button text isn't matched yet.
     */
    fun hasVisibleLockOverlay(snapshot: UiSnapshot): Boolean =
        snapshot.firstById(BiliNodes.ID_LAYOUT_LOCK_PAGE) != null ||
            snapshot.firstById(BiliNodes.ID_LAYOUT_PREVIEW_LOCK_PAGE) != null ||
            snapshot.firstById(BiliNodes.ID_LAYOUT_PAUSE_POINT_LOCK) != null

    fun isEpisodeBuffering(snapshot: UiSnapshot): Boolean =
        snapshot.allTexts().any { it.startsWith("正在缓冲") }

    private val ACTIVE_COUNTDOWN_REGEX = Regex("\\d+\\s*[秒sS]")

    fun isActiveCountdownText(text: String): Boolean {
        if (text.isBlank()) return false
        if (BiliNodes.REWARD_DONE_KEYWORDS.any { text.contains(it) }) return false
        if (BiliNodes.EXTERNAL_JUMP_PROMPT_KEYWORDS.any { text.contains(it) }) return false
        if (!ACTIVE_COUNTDOWN_REGEX.containsMatchIn(text)) return false
        return text.contains("秒后") ||
            text.contains("s后", ignoreCase = true) ||
            text.contains("还剩") ||
            text.contains("剩余") ||
            text.contains("倒计时")
    }

    /**
     * Classifies the current state of RewardAdActivityWeb or an ad dialog.
     */
    fun classifyAd(snapshot: UiSnapshot): AdClassification {
        val visibleTexts = snapshot.allTexts()
        val joined = visibleTexts.joinToString(" | ")
        val compact = visibleTexts.joinToString("")

        // 1. Retention dialog ("继续观看领取奖励" vs "放弃机会")
        val hasAvoidText = visibleTexts.any { txt ->
            BiliNodes.RETENTION_AVOID_KEYWORDS.any { txt.contains(it) }
        }
        if (hasAvoidText) {
            val continueNode = findClickableByKeywords(snapshot, BiliNodes.RETENTION_CONTINUE_KEYWORDS)
            if (continueNode != null) {
                return AdClassification.RetentionDialog(continueNode)
            }
        }

        // 2. Reward completed check (prioritize tv_countdown text, then global text).
        // If an active numeric countdown ("8秒后可获得奖励", "5s后领取", "还剩3秒") is still displayed,
        // never falsely treat ad body text or bottom swipe guides as Rewarded.
        val countdownText = snapshot.firstById(BiliNodes.ID_TV_COUNTDOWN)?.displayLabel.orEmpty()
        val hasSwipeGuide = visibleTexts.any { txt ->
            BiliNodes.REWARD_SWIPE_GUIDE_KEYWORDS.any { txt.contains(it) }
        }
        val countdownExplicitlyDone = BiliNodes.REWARD_DONE_KEYWORDS.any { countdownText.contains(it) }
        val activeCountdownVisible = !countdownExplicitlyDone && (
            isActiveCountdownText(countdownText) ||
                visibleTexts.any { isActiveCountdownText(it) }
            )
        val rewardDone = countdownExplicitlyDone ||
            (!activeCountdownVisible && BiliNodes.REWARD_DONE_KEYWORDS.any { joined.contains(it) })
        if (rewardDone) {
            val closeNode = snapshot.firstById(BiliNodes.ID_TV_CLOSE)
                ?: findClickableByKeywords(snapshot, BiliNodes.CLOSE_BUTTON_TEXTS)
            return AdClassification.Rewarded(closeNode = closeNode, hasSwipeGuide = hasSwipeGuide)
        }

        // 3. External app experience jump task ("前往体验10秒获得奖励" / "去体验 10秒 即可获得奖励！！").
        // Exclude ID_TV_COUNTDOWN and active countdown labels ("8秒后可获得奖励") so normal video ads
        // with bottom CTA cards ("去看看" / "立即购买") are NEVER mistakenly clicked!
        val nonCountdownTexts = snapshot.nodes
            .filter { it.visible && it.viewId != BiliNodes.ID_TV_COUNTDOWN && it.displayLabel.isNotEmpty() }
            .map { it.displayLabel }
            .filter { !isActiveCountdownText(it) }
        val nonCountdownCompact = nonCountdownTexts.joinToString("")
        val jumpPrompt = nonCountdownTexts.firstOrNull { txt ->
            BiliNodes.EXTERNAL_JUMP_PROMPT_KEYWORDS.any { txt.contains(it) }
        } ?: BiliNodes.EXTERNAL_JUMP_PROMPT_KEYWORDS.firstOrNull { nonCountdownCompact.contains(it) }
        if (jumpPrompt != null) {
            val goNode = findExternalJumpActionNode(snapshot)
            if (goNode != null) {
                return AdClassification.ExternalJumpTask(actionNode = goNode, promptText = jumpPrompt)
            }
        }

        val status = countdownText.ifBlank {
            visibleTexts.firstOrNull { it.contains("秒") || it.contains("奖励") }.orEmpty()
        }
        return AdClassification.Waiting(status)
    }

    fun isJumpConfirmationDialog(snapshot: UiSnapshot): Boolean {
        if (snapshot.packageName in BiliNodes.SYSTEM_CONFIRM_PACKAGES) return true
        val compact = snapshot.allTexts().joinToString("")
        return BiliNodes.JUMP_CONFIRM_DIALOG_HINTS.any { compact.contains(it) }
    }

    fun findSystemJumpConfirm(snapshot: UiSnapshot): UiNode? {
        if (!isJumpConfirmationDialog(snapshot)) return null
        val valid = snapshot.nodes.filter {
            it.visible && it.enabled &&
                (it.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) || it.bounds.isValid)
        }
        return valid.firstOrNull { node ->
            BiliNodes.SYSTEM_ALLOW_BUTTON_TEXTS.any { kw -> node.displayLabel == kw }
        } ?: valid.firstOrNull { node ->
            BiliNodes.SYSTEM_ALLOW_BUTTON_TEXTS.any { kw -> node.displayLabel.startsWith(kw) }
        }
    }

    fun findSystemJumpDeny(snapshot: UiSnapshot): UiNode? {
        if (!isJumpConfirmationDialog(snapshot)) return null
        val valid = snapshot.nodes.filter {
            it.visible && it.enabled &&
                (it.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) || it.bounds.isValid)
        }
        return valid.firstOrNull { node ->
            BiliNodes.SYSTEM_DENY_BUTTON_TEXTS.any { kw -> node.displayLabel == kw }
        } ?: valid.firstOrNull { node ->
            BiliNodes.SYSTEM_DENY_BUTTON_TEXTS.any { kw -> node.displayLabel.startsWith(kw) }
        }
    }

    fun findReturnToBiliButton(snapshot: UiSnapshot): UiNode? =
        snapshot.nodes.firstOrNull { node ->
            val label = node.displayLabel
            node.visible &&
                node.enabled &&
                node.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                BiliNodes.RETURN_TO_BILI_TEXTS.any { label == it || label.contains(it) }
        }

    fun isDownloadOrInstallPrompt(snapshot: UiSnapshot): Boolean {
        val compact = snapshot.allTexts().joinToString("")
        return compact.contains("安装") || compact.contains("下载") || compact.contains("应用商店")
    }

    private fun findClickableByKeywords(snapshot: UiSnapshot, keywords: List<String>): UiNode? {
        for (kw in keywords) {
            val exact = snapshot.nodes.firstOrNull {
                it.visible && it.enabled && it.bounds.isValid && it.displayLabel == kw
            }
            if (exact != null) return exact
        }
        for (kw in keywords) {
            val partial = snapshot.nodes.firstOrNull {
                it.visible && it.enabled && it.bounds.isValid && it.displayLabel.contains(kw)
            }
            if (partial != null) return partial
        }
        return null
    }

    private fun findExternalJumpActionNode(snapshot: UiSnapshot): UiNode? {
        val jumpForbidden = setOf("放弃", "残忍离开", "取消", "关闭", "下载", "安装", "会员", "大会员", "充值")
        val candidates = snapshot.nodes.filter { node ->
            node.visible &&
                node.enabled &&
                node.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                node.viewId != BiliNodes.ID_TV_CLOSE &&
                node.viewId != BiliNodes.ID_TV_COUNTDOWN &&
                !BiliNodes.EXTERNAL_JUMP_PROMPT_KEYWORDS.any { node.displayLabel.contains(it) } &&
                BiliNodes.EXTERNAL_JUMP_ACTION_KEYWORDS.any { node.displayLabel.contains(it) } &&
                jumpForbidden.none { node.displayLabel.contains(it) }
        }
        val textMatch = candidates.firstOrNull { it.clickable } ?: candidates.firstOrNull()
        if (textMatch != null) return textMatch

        // Fallback for Jetpack Compose prompt dialogs (e.g. "前往获得激励！/去体验 10秒 即可获得奖励！！")
        // whose CTA pill button renders graphics/text in canvas without exposing Accessibility text.
        val modalPrompts = snapshot.nodes.filter { node ->
            node.visible &&
                node.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                node.viewId != BiliNodes.ID_TV_COUNTDOWN &&
                (node.displayLabel.contains("前往获得激励") ||
                    node.displayLabel.contains("可获得奖励") ||
                    node.displayLabel == "去体验")
        }
        if (modalPrompts.isEmpty()) return null

        val promptBottom = modalPrompts.maxOf { it.bounds.bottom }
        val minWidth = (snapshot.screenWidth * 0.45f).toInt()
        val minHeight = (snapshot.screenHeight * 0.03f).toInt()
        val maxHeight = (snapshot.screenHeight * 0.09f).toInt()
        val maxBottom = (snapshot.screenHeight * 0.82f).toInt()

        return snapshot.nodes
            .filter { node ->
                node.visible &&
                    node.enabled &&
                    node.displayLabel.isBlank() &&
                    node.viewId != BiliNodes.ID_TV_CLOSE &&
                    node.bounds.isOnScreen(snapshot.screenWidth, snapshot.screenHeight) &&
                    node.bounds.width >= minWidth &&
                    node.bounds.height in minHeight..maxHeight &&
                    node.bounds.top >= promptBottom &&
                    node.bounds.bottom <= maxBottom
            }
            .maxByOrNull { it.bounds.top }
    }
}
