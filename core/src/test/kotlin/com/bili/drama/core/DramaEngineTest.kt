package com.bili.drama.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DramaEngineTest {

    private fun node(
        id: String = "",
        text: String = "",
        bounds: RectBounds = RectBounds(100, 100, 400, 200),
        clickable: Boolean = true,
        visible: Boolean = true,
        cls: String = "android.widget.TextView"
    ) = UiNode(
        key = "${id}_$text",
        viewId = id,
        className = cls,
        text = text,
        contentDesc = "",
        bounds = bounds,
        clickable = clickable,
        visible = visible,
        enabled = true
    )

    @Test
    fun `parses episode progress from select text`() {
        val snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(36/38) · 逆袭")
            ),
            timestampMs = 1000L
        )
        val ep = EpisodeParser.parse(snap)
        assertEquals(36, ep?.current)
        assertEquals(38, ep?.total)
        assertEquals(false, ep?.isLastEpisode)
    }

    @Test
    fun `rejects paid unlock button and picks ad unlock button`() {
        val snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_TV_PREVIEW_LEFT_BUTTON, text = "开通会员畅看"),
                node(id = BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, text = "看广告免费解锁")
            ),
            timestampMs = 1000L
        )
        val picked = RuleMatcher.findUnlockButton(snap)
        assertEquals(BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, picked?.viewId)
    }

    @Test
    fun `never clicks download button or paid button`() {
        val snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_TV_PREVIEW_LEFT_BUTTON, text = "1.9元购买本集"),
                node(id = BiliNodes.ID_DOWNLOAD_LABEL, text = "立即下载")
            ),
            timestampMs = 1000L
        )
        assertNull(RuleMatcher.findUnlockButton(snap))
    }

    @Test
    fun `fast single-stage seek probes episode and swipes cleanly when episode has no ad`() {
        val engine = DramaStateEngine(
            DramaConfig(
                seekCheckEnabled = true,
                episodeSettleMs = 420L,
                postSeekWaitMs = 900L
            )
        )
        engine.start(1000L)

        fun shortSnap(ts: Long, ep: Int = 35, total: Int = 38, buffering: Boolean = false) = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = buildList {
                add(node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集($ep/$total)"))
                add(node(id = BiliNodes.ID_CTRL_SEEKBAR, bounds = RectBounds(45, 2839, 1418, 2952)))
                if (buffering) add(node(text = "正在缓冲…120KB/s", clickable = false))
            },
            timestampMs = ts
        )

        assertIs<EngineAction.Wait>(engine.step(shortSnap(1100L)))
        // Waits while player reports buffering before firing single seek
        assertIs<EngineAction.Wait>(engine.step(shortSnap(1500L, buffering = true)))
        val seek1 = assertIs<EngineAction.SeekRatio>(engine.step(shortSnap(1750L)))
        assertTrue(seek1.ratio in 0.72f..0.82f)
        assertEquals(false, seek1.isSecondary)

        assertIs<EngineAction.SwipeNextEpisode>(engine.step(shortSnap(2750L)))
    }

    @Test
    fun `handles split-text taobao experience dialog without countdown id`() {
        val engine = DramaStateEngine(DramaConfig(externalStaySeconds = 12))
        engine.start(1000L)

        // Exact layout observed on real RewardAdActivityWeb dialog
        val dialogSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "前往获得激励！", clickable = false),
                node(text = "去体验", clickable = false),
                node(text = "10秒", clickable = false),
                node(text = "即", clickable = false),
                node(text = "可获得奖励！！", clickable = false),
                node(text = "淘宝", clickable = false),
                node(id = "tv.danmaku.bili:id/tv_download_status", text = "查看详情", clickable = false)
            ),
            timestampMs = 2500L
        )
        val action = assertIs<EngineAction.ClickNode>(engine.step(dialogSnap))
        assertEquals("查看详情", action.node.text)
    }

    @Test
    fun `prefers structural short drama views over stale RewardAdActivity name`() {
        val engine = DramaStateEngine()
        engine.start(1000L)
        val snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(38/38)"),
                node(id = BiliNodes.ID_CTRL_SEEKBAR, bounds = RectBounds(45, 2839, 1418, 2952))
            ),
            timestampMs = 2500L
        )
        assertIs<EngineAction.Wait>(engine.step(snap))
        assertIs<EngineAction.SeekRatio>(engine.step(snap.copy(timestampMs = 3500L)))
    }

    @Test
    fun `handles external jump task and closes reward ad when rewarded`() {
        val engine = DramaStateEngine(DramaConfig(externalStaySeconds = 12))
        engine.start(1000L)

        val jumpAdSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "前往体验10秒获得奖励"),
                node(text = "前往淘宝")
            ),
            timestampMs = 2500L
        )
        val jumpAction = assertIs<EngineAction.ClickNode>(engine.step(jumpAdSnap))
        assertEquals("前往淘宝", jumpAction.node.text)

        val externalSnap = UiSnapshot(
            packageName = "com.taobao.taobao",
            activityName = "com.taobao.Main",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = emptyList(),
            timestampMs = 5000L
        )
        assertIs<EngineAction.Wait>(engine.step(externalSnap))

        // Must stay full 12s after actually entering external app (5000 + 12500 = 17500)
        val externalDoneSnap = externalSnap.copy(timestampMs = 17500L)
        assertIs<EngineAction.BringBiliToFront>(engine.step(externalDoneSnap))

        val rewardedSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_TV_COUNTDOWN, text = "已获得奖励"),
                node(id = BiliNodes.ID_TV_CLOSE, text = "关闭")
            ),
            timestampMs = 19000L
        )
        val closeAction = assertIs<EngineAction.ClickNode>(engine.step(rewardedSnap))
        assertEquals(BiliNodes.ID_TV_CLOSE, closeAction.node.viewId)
    }

    @Test
    fun `ignores off-screen ViewPager2 seekbars and picks active on-screen seekbar`() {
        val snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                // Off-screen card above (Card -2)
                node(
                    id = BiliNodes.ID_CTRL_SEEKBAR,
                    bounds = RectBounds(45, -3501, 1418, -3388),
                    visible = true
                ),
                // Active on-screen card (PlayerControlWidget temporarily INVISIBLE -> visible=false)
                node(
                    id = BiliNodes.ID_CTRL_SEEKBAR,
                    bounds = RectBounds(45, 2839, 1418, 2952),
                    visible = false
                ),
                // Off-screen card below (Card +1)
                node(
                    id = BiliNodes.ID_CTRL_SEEKBAR,
                    bounds = RectBounds(45, 5979, 1418, 6092),
                    visible = true
                )
            ),
            timestampMs = 1000L
        )
        val bar = RuleMatcher.findSeekBar(snap)
        assertEquals(2839, bar?.bounds?.top)
    }

    @Test
    fun `never swipes past visibly locked episode even if unlock label is not yet ready`() {
        val engine = DramaStateEngine(DramaConfig(episodeSettleMs = 400L, postSeekWaitMs = 500L))
        engine.start(1000L)

        val lockedSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "选集(4/38)"),
                node(id = BiliNodes.ID_LAYOUT_PREVIEW_LOCK_PAGE, bounds = RectBounds(0, 0, 1440, 2971)),
                node(id = BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, text = "解锁加载中...")
            ),
            timestampMs = 5000L
        )
        val action = assertIs<EngineAction.Wait>(engine.step(lockedSnap))
        assertTrue(action.status.contains("已锁集"))
    }

    @Test
    fun `pauses calmly instead of fighting user on launcher home`() {
        val engine = DramaStateEngine()
        engine.start(1000L)
        val homeSnap = UiSnapshot(
            packageName = "com.miui.home",
            activityName = "com.miui.home.launcher.Launcher",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = emptyList(),
            timestampMs = 2500L
        )
        val action = assertIs<EngineAction.Wait>(engine.step(homeSnap))
        assertTrue(action.status.contains("暂停"))
    }

    @Test
    fun `handles in-app novel ad mini-program (AppContainerActivity2) without misclassifying as short drama`() {
        val engine = DramaStateEngine(DramaConfig(externalStaySeconds = 12))
        engine.start(1000L)

        val rewardDialog = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "前往获得激励！"),
                node(text = "去体验 10秒 即可获得奖励！！"),
                node(text = "查看详情")
            ),
            timestampMs = 2000L
        )
        assertIs<EngineAction.ClickNode>(engine.step(rewardDialog))

        // Ad opens Bilibili novel mini-program (AppContainerActivity2) with capsule close button & exit dialog text
        val novelSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}2",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_MORE_RIGHT_BTN, bounds = RectBounds(1280, 199, 1392, 304)),
                node(text = "继续阅读"),
                node(text = "放弃")
            ),
            timestampMs = 4000L
        )
        // Must stay ~12s instead of clicking "继续阅读" or pretending episode unlocked
        val waitAction = assertIs<EngineAction.Wait>(engine.step(novelSnap))
        assertTrue(waitAction.status.contains("站内广告体验中"))

        // After 12s, must click capsule close button (more_right_btn)
        val doneAction = assertIs<EngineAction.ClickNode>(engine.step(novelSnap.copy(timestampMs = 17000L)))
        assertEquals(BiliNodes.ID_MORE_RIGHT_BTN, doneAction.node.viewId)
    }

    @Test
    fun `detects unlabeled Compose CTA pill inside reward experience dialog`() {
        val engine = DramaStateEngine()
        engine.start(1000L)

        // Exact ComposeView hierarchy observed on real RewardAdActivityWeb dialog
        val composeDialog = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "前往获得激励！", bounds = RectBounds(227, 949, 878, 1073), clickable = false),
                node(text = "去体验", bounds = RectBounds(227, 1188, 395, 1267), clickable = false),
                node(text = "10秒", bounds = RectBounds(410, 1178, 576, 1277), clickable = false),
                node(text = "即", bounds = RectBounds(591, 1188, 647, 1267), clickable = false),
                node(text = "可获得奖励！！", bounds = RectBounds(647, 1188, 1039, 1267), clickable = false),
                // Product card wrapper (height > maxHeight, skipped)
                node(text = "", bounds = RectBounds(265, 1341, 1130, 1836), clickable = false, cls = "android.view.View"),
                // Unlabeled pink CTA button [227, 1884, 1168, 2064]
                node(text = "", bounds = RectBounds(227, 1884, 1168, 2064), clickable = false, cls = "android.view.View"),
                // Close X button below (width = 180 < minWidth, skipped)
                node(text = "", bounds = RectBounds(631, 2255, 811, 2435), clickable = true, cls = "android.widget.ImageView")
            ),
            timestampMs = 2000L
        )
        val clickAction = assertIs<EngineAction.ClickNode>(engine.step(composeDialog))
        assertEquals(1884, clickAction.node.bounds.top)
        assertEquals(2064, clickAction.node.bounds.bottom)
    }

    @Test
    fun `stays 12s and presses back from promoted ad drama without clicking host capsule close`() {
        val engine = DramaStateEngine(DramaConfig(externalStaySeconds = 12))
        engine.start(1000L)

        // Initial target drama has 38 episodes
        val targetDramaSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(14/38)"),
                node(id = BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, text = "看广告免费解锁")
            ),
            timestampMs = 2500L
        )
        assertIs<EngineAction.ClickNode>(engine.step(targetDramaSnap))

        val adJumpSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "前往体验10秒获得奖励"),
                node(text = "去看看")
            ),
            timestampMs = 4000L
        )
        assertIs<EngineAction.ClickNode>(engine.step(adJumpSnap))

        // Ad opens promoted drama (1/55) inside the same AppContainerActivity0 (has more_right_btn!)
        val promoDramaSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(1/55)"),
                node(id = BiliNodes.ID_CTRL_SEEKBAR, bounds = RectBounds(45, 2839, 1418, 2952)),
                node(id = BiliNodes.ID_MORE_RIGHT_BTN, bounds = RectBounds(1280, 199, 1392, 304))
            ),
            timestampMs = 5500L
        )
        val stayWait = assertIs<EngineAction.Wait>(engine.step(promoDramaSnap))
        assertTrue(stayWait.status.contains("站内广告体验中"))

        // After 12s, must PressBack (NOT click more_right_btn which would kill AppContainerActivity0)
        assertIs<EngineAction.PressBack>(engine.step(promoDramaSnap.copy(timestampMs = 18000L)))
    }

    @Test
    fun `denies unsolicited system jump dialog and blocks app store redirects`() {
        val engine = DramaStateEngine(DramaConfig(interceptExternalJumps = true))
        engine.start(1000L)

        // Watching a normal countdown video ad (no explicit 10s jump task clicked)
        val videoAdSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_TV_COUNTDOWN, text = "8秒后可获得奖励")
            ),
            timestampMs = 2000L
        )
        assertIs<EngineAction.Wait>(engine.step(videoAdSnap))

        // Unsolicited shake/auto-jump pops system confirm dialog -> must click "取消"
        val sysDialogSnap = UiSnapshot(
            packageName = "android",
            activityName = "com.android.internal.app.ResolverActivity",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "想要打开第三方应用"),
                node(text = "取消"),
                node(text = "打开")
            ),
            timestampMs = 3000L
        )
        val deny = assertIs<EngineAction.ClickNode>(engine.step(sysDialogSnap))
        assertEquals("取消", deny.node.text)

        // App store redirect -> immediately BringBiliToFront
        val storeSnap = UiSnapshot(
            packageName = "com.android.vending",
            activityName = "com.google.android.finsky.activities.MainActivity",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = emptyList(),
            timestampMs = 4500L
        )
        assertIs<EngineAction.BringBiliToFront>(engine.step(storeSnap))
    }

    @Test
    fun `never loops dragging seekbar on same episode if vertical swipe snaps back`() {
        val engine = DramaStateEngine(
            DramaConfig(
                primarySeekRatio = 0.55f,
                secondarySeekEnabled = true,
                episodeSettleMs = 400L,
                postSeekWaitMs = 800L
            )
        )
        engine.start(1000L)

        fun ep8Snap(ts: Long) = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(8/38)"),
                node(id = BiliNodes.ID_CTRL_SEEKBAR, bounds = RectBounds(42, 2784, 1418, 2889))
            ),
            timestampMs = ts
        )

        assertIs<EngineAction.Wait>(engine.step(ep8Snap(1100L))) // initial settle on ep 8
        assertIs<EngineAction.SeekRatio>(engine.step(ep8Snap(1600L))) // 55%
        assertIs<EngineAction.SeekRatio>(engine.step(ep8Snap(2500L))) // 85%
        assertIs<EngineAction.SwipeNextEpisode>(engine.step(ep8Snap(3400L))) // Swipe #1

        // Suppose ViewPager2 snapped back to Episode 8: must NOT re-drag seekbar, must retry SwipeNextEpisode!
        assertIs<EngineAction.SwipeNextEpisode>(engine.step(ep8Snap(5000L))) // Swipe #2
        assertIs<EngineAction.SwipeNextEpisode>(engine.step(ep8Snap(6500L))) // Swipe #3
        assertIs<EngineAction.SwipeNextEpisode>(engine.step(ep8Snap(8000L))) // Swipe #4
        assertIs<EngineAction.Stop>(engine.step(ep8Snap(9500L)))
    }

    @Test
    fun `clicks WebView bonus CTA instead of subtitle and dismisses if no ad inventory`() {
        val engine = DramaStateEngine()
        engine.start(1000L)

        // Exact WebView bonus modal hierarchy observed on LM_V500N after unlocking Episode 10
        fun bonusSnap(ts: Long) = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 2974,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(10/38) · 逆袭", bounds = RectBounds(70, 2725, 1121, 2779), clickable = false),
                node(id = BiliNodes.ID_IC_PLAY, text = "", bounds = RectBounds(580, 1228, 860, 1498), clickable = false, cls = "android.widget.ImageView"),
                node(text = "已解锁1集", bounds = RectBounds(353, 1274, 1085, 1354), clickable = false),
                node(text = "再看一个激励视频，额外赠送", bounds = RectBounds(353, 1351, 1085, 1431), clickable = false),
                node(text = "2", bounds = RectBounds(644, 1456, 731, 1634), clickable = false),
                node(text = "集", bounds = RectBounds(735, 1529, 794, 1613), clickable = false),
                node(text = "观看领奖励", bounds = RectBounds(332, 1669, 1106, 1820), clickable = false),
                node(text = "放弃福利", bounds = RectBounds(633, 1858, 805, 1921), clickable = false)
            ),
            timestampMs = ts
        )

        // Must pick "观看领奖励" (NEVER "再看一个激励视频，额外赠送" or "放弃福利" or ic_play)
        val click1 = assertIs<EngineAction.ClickNode>(engine.step(bonusSnap(2500L)))
        assertEquals("观看领奖励", click1.node.text)

        // Simulate no ad inventory (3s pass, still on bonus modal): retry once, then dismiss cleanly via "放弃福利"
        assertIs<EngineAction.Wait>(engine.step(bonusSnap(4000L)))
        val click2 = assertIs<EngineAction.ClickNode>(engine.step(bonusSnap(6000L)))
        assertEquals("观看领奖励", click2.node.text)

        assertIs<EngineAction.Wait>(engine.step(bonusSnap(7500L)))
        val dismiss = assertIs<EngineAction.ClickNode>(engine.step(bonusSnap(9500L)))
        assertEquals("放弃福利", dismiss.node.text)
    }

    @Test
    fun `unpauses video before seeking when paused play icon is visible`() {
        val engine = DramaStateEngine(DramaConfig(episodeSettleMs = 400L))
        engine.start(1000L)

        val pausedSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 2974,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(10/38)"),
                node(id = BiliNodes.ID_IC_PLAY, bounds = RectBounds(580, 1228, 860, 1498), clickable = false, cls = "android.widget.ImageView"),
                node(id = BiliNodes.ID_CTRL_SEEKBAR, bounds = RectBounds(42, 2810, 1418, 2862))
            ),
            timestampMs = 2200L
        )
        val action = assertIs<EngineAction.ClickNode>(engine.step(pausedSnap))
        assertEquals(BiliNodes.ID_IC_PLAY, action.node.viewId)
    }

    @Test
    fun `closes non-cancelable game real-name verification modal after 12s experience`() {
        val engine = DramaStateEngine(DramaConfig(externalStaySeconds = 12))
        engine.start(1000L)

        val jumpAd = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 2974,
            nodes = listOf(
                node(text = "前往体验10秒获得奖励"),
                node(text = "查看详情")
            ),
            timestampMs = 2000L
        )
        assertIs<EngineAction.ClickNode>(engine.step(jumpAd))

        // Exact GameContainerActivity0 real-name modal observed on LM_V500N Episode 18
        val gameModal = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "com.bilibili.lib.fasthybrid.game.scenarios.game.ui.GameContainerActivity0",
            screenWidth = 1440,
            screenHeight = 2974,
            nodes = listOf(
                node(text = "", bounds = RectBounds(1061, 954, 1210, 1106), clickable = true, cls = "android.widget.Button"),
                node(text = "游戏实名认证", bounds = RectBounds(534, 1038, 907, 1130), clickable = false),
                node(text = "请输入真实姓名", bounds = RectBounds(356, 1719, 706, 1791), clickable = false),
                node(text = "去认证", bounds = RectBounds(639, 2053, 802, 2116), clickable = false)
            ),
            timestampMs = 3500L
        )
        assertIs<EngineAction.Wait>(engine.step(gameModal))

        val closeModal = assertIs<EngineAction.ClickNode>(engine.step(gameModal.copy(timestampMs = 16500L)))
        assertEquals(1061, closeModal.node.bounds.left)
        assertEquals(954, closeModal.node.bounds.top)
    }

    @Test
    fun `rejects ad-free paid button 免广告解锁 and picks 7秒后看广告解锁 on 秋秋漫剧`() {
        val snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}1",
            screenWidth = 1440,
            screenHeight = 2974,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(2/17) · 逆袭"),
                node(id = BiliNodes.ID_TV_PREVIEW_LEFT_BUTTON, text = "免广告解锁", bounds = RectBounds(42, 2682, 604, 2808)),
                node(id = BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, text = "7秒后看广告解锁", bounds = RectBounds(643, 2682, 1205, 2808))
            ),
            timestampMs = 2000L
        )
        val picked = RuleMatcher.findUnlockButton(snap)
        assertEquals(BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, picked?.viewId)
        assertEquals("7秒后看广告解锁", picked?.text)
    }

    @Test
    fun `dismisses 选择解锁方式 paid bottom sheet before clicking underlying ad unlock button`() {
        val engine = DramaStateEngine()
        engine.start(1000L)

        // Exact UI hierarchy captured on LM_V500N when 选择解锁方式 sheet covered Episode 2/17
        val paidSheetSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}1",
            screenWidth = 1440,
            screenHeight = 2974,
            nodes = listOf(
                node(text = "选择解锁方式", bounds = RectBounds(45, 2271, 1396, 2362), clickable = false),
                node(text = "close", bounds = RectBounds(1298, 2271, 1396, 2366), clickable = false),
                node(text = "漫币余额：0", bounds = RectBounds(45, 2425, 1396, 2495), clickable = false),
                node(text = "虚拟商品购买后不可退换", bounds = RectBounds(507, 2926, 934, 2974), clickable = false),
                node(id = BiliNodes.ID_IC_PLAY, bounds = RectBounds(580, 1228, 860, 1498), clickable = false),
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(2/17) · 逆袭"),
                node(id = BiliNodes.ID_TV_PREVIEW_LEFT_BUTTON, text = "免广告解锁", bounds = RectBounds(42, 2682, 604, 2808)),
                node(id = BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, text = "7秒后看广告解锁", bounds = RectBounds(643, 2682, 1205, 2808))
            ),
            timestampMs = 2500L
        )
        val closePaid = assertIs<EngineAction.ClickNode>(engine.step(paidSheetSnap))
        assertEquals("close", closePaid.node.text)

        // Once sheet is closed, engine clicks "7秒后看广告解锁"
        val cleanSnap = paidSheetSnap.copy(
            nodes = paidSheetSnap.nodes.drop(4),
            timestampMs = 4000L
        )
        val clickAd = assertIs<EngineAction.ClickNode>(engine.step(cleanSnap))
        assertEquals(BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, clickAd.node.viewId)
    }

    @Test
    fun `waits calmly without closing capsule while mini-program is initially loading SAStoryView`() {
        val engine = DramaStateEngine()
        engine.start(1000L)

        val loadingMiniAppSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}1",
            screenWidth = 1440,
            screenHeight = 2974,
            nodes = listOf(
                node(id = BiliNodes.ID_MORE_RIGHT_BTN, bounds = RectBounds(1290, 125, 1395, 223))
            ),
            timestampMs = 4000L
        )
        val waitAction = assertIs<EngineAction.Wait>(engine.step(loadingMiniAppSnap))
        assertTrue(waitAction.status.contains("等待进入短剧播放页"))
    }

    @Test
    fun `escalates multi-attempt pullback from external app and launcher without stalling`() {
        val engine = DramaStateEngine(DramaConfig(externalStaySeconds = 11))
        engine.start(1000L)

        // Start inside target short drama so dramaContainerActivity is captured
        val dramaSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(5/38)"),
                node(id = BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, text = "看广告免费解锁")
            ),
            timestampMs = 1500L
        )
        assertIs<EngineAction.ClickNode>(engine.step(dramaSnap))

        val jumpAdSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(
                node(text = "前往体验10秒获得奖励"),
                node(text = "查看详情")
            ),
            timestampMs = 3000L
        )
        assertIs<EngineAction.ClickNode>(engine.step(jumpAdSnap))

        val taobaoSnap = UiSnapshot(
            packageName = "com.taobao.taobao",
            activityName = "com.taobao.tao.TBMainActivity",
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = emptyList(),
            timestampMs = 5000L
        )
        assertIs<EngineAction.Wait>(engine.step(taobaoSnap))

        // After 11s external stay: attempt 1 -> Back
        val a1 = assertIs<EngineAction.BringBiliToFront>(engine.step(taobaoSnap.copy(timestampMs = 16500L)))
        assertEquals(1, a1.attempt)

        // Back landed on OPPO Launcher instead of Bilibili -> must STILL escalate to attempt 2 (Recents), NOT pause!
        val launcherSnap = taobaoSnap.copy(packageName = "com.oppo.launcher", timestampMs = 18000L)
        val a2 = assertIs<EngineAction.BringBiliToFront>(engine.step(launcherSnap))
        assertEquals(2, a2.attempt)

        // Continue escalating up to MAX_RETURN_ATTEMPTS (6) if needed
        val a3 = assertIs<EngineAction.BringBiliToFront>(engine.step(taobaoSnap.copy(timestampMs = 19500L)))
        assertEquals(3, a3.attempt)
        val a4 = assertIs<EngineAction.BringBiliToFront>(engine.step(taobaoSnap.copy(timestampMs = 21000L)))
        assertEquals(4, a4.attempt)
        val a5 = assertIs<EngineAction.BringBiliToFront>(engine.step(taobaoSnap.copy(timestampMs = 22500L)))
        assertEquals(5, a5.attempt)
        val a6 = assertIs<EngineAction.BringBiliToFront>(engine.step(taobaoSnap.copy(timestampMs = 24000L)))
        assertEquals(6, a6.attempt)

        // After attempt 6 fails, pauses calmly without giving up, and resumes once user/system returns to Bilibili
        val manualPrompt = assertIs<EngineAction.Wait>(engine.step(taobaoSnap.copy(timestampMs = 25500L)))
        assertTrue(manualPrompt.status.contains("请手动切回B站"))

        val rewardedSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(
                node(id = BiliNodes.ID_TV_COUNTDOWN, text = "已获得奖励"),
                node(id = BiliNodes.ID_TV_CLOSE, text = "关闭")
            ),
            timestampMs = 27000L
        )
        val closeAction = assertIs<EngineAction.ClickNode>(engine.step(rewardedSnap))
        assertEquals(BiliNodes.ID_TV_CLOSE, closeAction.node.viewId)
    }

    @Test
    fun `handles cross-OEM and in-app Bilibili jump confirmation dialogs`() {
        val engine = DramaStateEngine(DramaConfig(interceptExternalJumps = true))
        engine.start(1000L)

        // Enter watching ad state
        val adSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(node(id = BiliNodes.ID_TV_COUNTDOWN, text = "12秒后可获得奖励")),
            timestampMs = 2000L
        )
        assertIs<EngineAction.Wait>(engine.step(adSnap))

        // Unsolicited OPPO safecenter / Android 14 intentresolver popup -> must deny ("取消")
        val oplusDialog = UiSnapshot(
            packageName = "com.oplus.safecenter",
            activityName = "com.oplus.safecenter.ConfirmDialog",
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(
                node(text = "哔哩哔哩想要打开拼多多"),
                node(text = "取消"),
                node(text = "允许")
            ),
            timestampMs = 3200L
        )
        val denyOplus = assertIs<EngineAction.ClickNode>(engine.step(oplusDialog))
        assertEquals("取消", denyOplus.node.text)

        // Bilibili's own in-app jump confirmation during an intentional 10s experience task -> must confirm ("立即打开")
        val jumpTaskSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(node(text = "前往体验10秒获得奖励"), node(text = "查看详情")),
            timestampMs = 4500L
        )
        assertIs<EngineAction.ClickNode>(engine.step(jumpTaskSnap))

        val inAppLeaveDialog = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(
                node(text = "即将离开哔哩哔哩，跳转至第三方应用"),
                node(text = "取消"),
                node(text = "立即打开")
            ),
            timestampMs = 5800L
        )
        val confirmLeave = assertIs<EngineAction.ClickNode>(engine.step(inAppLeaveDialog))
        assertEquals("立即打开", confirmLeave.node.text)
    }

    @Test
    fun `switches back to drama task if Intent lands on Bilibili MainActivity during return`() {
        val engine = DramaStateEngine(DramaConfig(externalStaySeconds = 11))
        engine.start(1000L)

        val dramaSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(5/38)"),
                node(id = BiliNodes.ID_TV_PREVIEW_RIGHT_BUTTON, text = "看广告免费解锁")
            ),
            timestampMs = 1500L
        )
        assertIs<EngineAction.ClickNode>(engine.step(dramaSnap))

        val jumpAdSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = listOf(node(text = "前往体验10秒获得奖励"), node(text = "查看详情")),
            timestampMs = 3000L
        )
        assertIs<EngineAction.ClickNode>(engine.step(jumpAdSnap))

        val extSnap = UiSnapshot("com.taobao.taobao", "TBMainActivity", 1440, 3120, emptyList(), 5000L)
        assertIs<EngineAction.Wait>(engine.step(extSnap))
        assertIs<EngineAction.BringBiliToFront>(engine.step(extSnap.copy(timestampMs = 16500L)))

        // Landing on Bilibili MainActivity must trigger task switch via BringBiliToFront instead of PressBack out of Bilibili
        val biliMainSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "tv.danmaku.bili.MainActivityV2",
            screenWidth = 1440,
            screenHeight = 3120,
            nodes = emptyList(),
            timestampMs = 18000L
        )
        val switchAction = assertIs<EngineAction.BringBiliToFront>(engine.step(biliMainSnap))
        assertEquals(2, switchAction.attempt)
    }

    @Test
    fun `does not prematurely close ad when swipe guide appears during active countdown`() {
        val snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_TV_COUNTDOWN, text = "8秒后可获得奖励"),
                node(id = BiliNodes.ID_TV_CLOSE, text = "关闭"),
                node(text = "上滑继续观看剧集")
            ),
            timestampMs = 3000L
        )
        val result = RuleMatcher.classifyAd(snap)
        assertIs<AdClassification.Waiting>(result)
    }

    @Test
    fun `closes ad via swipe or close button only after countdown flips to 已获得奖励`() {
        val snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_TV_COUNTDOWN, text = "已获得奖励"),
                node(id = BiliNodes.ID_TV_CLOSE, text = "关闭"),
                node(text = "上滑继续观看剧集")
            ),
            timestampMs = 15000L
        )
        val result = assertIs<AdClassification.Rewarded>(RuleMatcher.classifyAd(snap))
        assertEquals(BiliNodes.ID_TV_CLOSE, result.closeNode?.viewId)
        assertTrue(result.hasSwipeGuide)
    }

    @Test
    fun `retention dialog imposes cooldown and prevents infinite close loop`() {
        val engine = DramaStateEngine()
        engine.start(1000L)

        // Suppose body text falsely matched REWARD_DONE_KEYWORDS without tv_countdown present
        val fakeRewardSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "已解锁下集"),
                node(id = BiliNodes.ID_TV_CLOSE, text = "关闭")
            ),
            timestampMs = 2000L
        )
        assertIs<EngineAction.ClickNode>(engine.step(fakeRewardSnap))
        assertEquals(1, engine.adsCompletedCount)

        // Bilibili pops retention dialog -> must click continue AND roll back false reward count
        val retentionSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "放弃机会"),
                node(text = "继续观看领取奖励")
            ),
            timestampMs = 3000L
        )
        val clickContinue = assertIs<EngineAction.ClickNode>(engine.step(retentionSnap))
        assertEquals("继续观看领取奖励", clickContinue.node.text)
        assertEquals(0, engine.adsCompletedCount)

        // 1s later, same body text without explicit tv_countdown reward -> MUST wait during cooldown
        val afterContinue = assertIs<EngineAction.Wait>(engine.step(fakeRewardSnap.copy(timestampMs = 4000L)))
        assertTrue(afterContinue.status.contains("继续观看"))

        // Once tv_countdown explicitly shows 已获得奖励 -> closes immediately even during cooldown
        val trueRewardSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_TV_COUNTDOWN, text = "已获得奖励"),
                node(id = BiliNodes.ID_TV_CLOSE, text = "关闭")
            ),
            timestampMs = 4500L
        )
        val closeAction = assertIs<EngineAction.ClickNode>(engine.step(trueRewardSnap))
        assertEquals(BiliNodes.ID_TV_CLOSE, closeAction.node.viewId)
    }

    @Test
    fun `pulls back immediately from privacy-protected external app during ad`() {
        val engine = DramaStateEngine(DramaConfig(interceptExternalJumps = true))
        engine.start(1000L)

        val adSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(node(id = BiliNodes.ID_TV_COUNTDOWN, text = "10秒后可获得奖励")),
            timestampMs = 2000L
        )
        assertIs<EngineAction.Wait>(engine.step(adSnap))

        // Privacy-protected external app (e.g. HyperOS/ColorOS/OriginOS hides Accessibility window)
        val protectedSnap = UiSnapshot(
            packageName = BiliNodes.PKG_EXTERNAL_PROTECTED,
            activityName = "",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = emptyList(),
            timestampMs = 3500L
        )
        val pullAction = assertIs<EngineAction.BringBiliToFront>(engine.step(protectedSnap))
        assertEquals(1, pullAction.attempt)
    }

    @Test
    fun `does not click ad CTA button on normal video countdown ad with 8秒后可获得奖励`() {
        val snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_TV_COUNTDOWN, text = "8秒后可获得奖励"),
                node(id = BiliNodes.ID_TV_CLOSE, text = "关闭"),
                node(text = "【热播漫剧】一口气连看300集的漫剧..."),
                node(text = "去看看"),
                node(text = "立即购买")
            ),
            timestampMs = 3000L
        )
        val result = assertIs<AdClassification.Waiting>(RuleMatcher.classifyAd(snap))
        assertEquals("8秒后可获得奖励", result.statusText)
    }

    @Test
    fun `recognizes live 前往10秒即可获得奖励 modal with 立即购买 and 放弃机会 and enters external stay`() {
        val engine = DramaStateEngine(DramaConfig(externalStaySeconds = 11))
        engine.start(1000L)

        // Exact UI hierarchy captured live on Redmi K80 Pro Episode 25
        val jumpModalSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "前往10秒即可获得奖励", bounds = RectBounds(253, 1400, 1189, 1540), clickable = false),
                node(text = "", bounds = RectBounds(288, 1623, 1153, 1811), clickable = true),
                node(text = "立即购买", bounds = RectBounds(601, 1677, 841, 1758), clickable = false),
                node(text = "放弃机会", bounds = RectBounds(288, 1819, 1153, 1999), clickable = true)
            ),
            timestampMs = 2000L
        )
        val clickJump = assertIs<EngineAction.ClickNode>(engine.step(jumpModalSnap))
        assertEquals("立即购买", clickJump.node.text)
        assertEquals(EnginePhase.EXTERNAL_APP_STAY, engine.phase)

        // Lands in Taobao -> waits 11s -> pulls back to Bilibili
        val taobaoSnap = UiSnapshot("com.taobao.taobao", "TBMainActivity", 1440, 3200, emptyList(), 3000L)
        val stayWait = assertIs<EngineAction.Wait>(engine.step(taobaoSnap))
        assertTrue(stayWait.status.contains("外部软件体验中"))

        val returnAct = assertIs<EngineAction.BringBiliToFront>(engine.step(taobaoSnap.copy(timestampMs = 14500L)))
        assertEquals(1, returnAct.attempt)
    }

    @Test
    fun `passively stays 11s on store detail page when intentional ExternalJumpTask lands on app store`() {
        val engine = DramaStateEngine(DramaConfig(externalStaySeconds = 11))
        engine.start(1000L)

        val jumpModalSnap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = BiliNodes.ACTIVITY_REWARD_AD,
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(text = "前往10秒即可获得奖励", clickable = false),
                node(text = "立即购买", clickable = false),
                node(text = "放弃机会", clickable = true)
            ),
            timestampMs = 2000L
        )
        assertIs<EngineAction.ClickNode>(engine.step(jumpModalSnap))

        // Target app not installed -> Android opened App Store -> passively stay 11s without clicking install
        val storeSnap = UiSnapshot("com.xiaomi.market", "MarketDetailActivity", 1440, 3200, emptyList(), 3000L)
        val stayWait = assertIs<EngineAction.Wait>(engine.step(storeSnap))
        assertTrue(stayWait.status.contains("外部软件体验中"))

        // After 11s -> pulls back cleanly
        val backAct = assertIs<EngineAction.BringBiliToFront>(engine.step(storeSnap.copy(timestampMs = 14500L)))
        assertEquals(1, backAct.attempt)
    }

    @Test
    fun `never presses Back on transient empty frame right after swiping episode or inside drama container`() {
        val engine = DramaStateEngine(DramaConfig(episodeSettleMs = 400L, postSeekWaitMs = 900L))
        engine.start(1000L)

        val ep8Snap = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = listOf(
                node(id = BiliNodes.ID_ALBUM_SELECT_TEXT, text = "全集 · 选集(8/38)"),
                node(id = BiliNodes.ID_CTRL_SEEKBAR, bounds = RectBounds(45, 2839, 1418, 2952))
            ),
            timestampMs = 1500L
        )
        assertIs<EngineAction.Wait>(engine.step(ep8Snap))
        assertIs<EngineAction.SeekRatio>(engine.step(ep8Snap.copy(timestampMs = 2000L)))
        assertIs<EngineAction.SwipeNextEpisode>(engine.step(ep8Snap.copy(timestampMs = 3100L)))

        // 450ms later, ViewPager2 transition / next episode load yields a transient empty frame
        val emptySameContainer = UiSnapshot(
            packageName = BiliNodes.PKG_BILI,
            activityName = "${BiliNodes.ACTIVITY_APP_CONTAINER_PREFIX}0",
            screenWidth = 1440,
            screenHeight = 3200,
            nodes = emptyList(),
            timestampMs = 3550L
        )
        assertIs<EngineAction.Wait>(engine.step(emptySameContainer))
        // Even 5s later while still inside AppContainerActivity0, never press Back to exit the drama mini-program
        assertIs<EngineAction.Wait>(engine.step(emptySameContainer.copy(timestampMs = 8550L)))
    }
}
