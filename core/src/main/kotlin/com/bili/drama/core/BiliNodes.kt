package com.bili.drama.core

/**
 * Verified Bilibili (tv.danmaku.bili) package, activity, view ID, and text constants
 * observed on live device dumps and APK resource tables.
 */
object BiliNodes {
    const val PKG_BILI = "tv.danmaku.bili"

    // Activities
    const val ACTIVITY_APP_CONTAINER_PREFIX = "com.bilibili.lib.fasthybrid.container.AppContainerActivity"
    const val ACTIVITY_REWARD_AD = "com.bilibili.ad.reward.activity.RewardAdActivityWeb"

    // Short-drama player & lock View IDs
    const val ID_TOOLBAR_TITLE = "tv.danmaku.bili:id/toolbar_title"
    const val ID_ALBUM_SELECT_TEXT = "tv.danmaku.bili:id/tv_album_select_text_v2"
    const val ID_FEED_VIDEOS = "tv.danmaku.bili:id/feed_videos"
    const val ID_CTRL_SEEKBAR = "tv.danmaku.bili:id/ctrl_seekbar"
    const val ID_LAYOUT_PAUSE = "tv.danmaku.bili:id/layout_pause"
    const val ID_IC_PLAY = "tv.danmaku.bili:id/ic_play"
    const val ID_LAYOUT_LOCK_PAGE = "tv.danmaku.bili:id/layout_lock_page"
    const val ID_BTN_UNLOCK = "tv.danmaku.bili:id/btn_unlock"
    const val ID_LAYOUT_PREVIEW_LOCK_PAGE = "tv.danmaku.bili:id/layout_preview_lock_page"
    const val ID_TV_PREVIEW_LOCK_DESC = "tv.danmaku.bili:id/tv_preview_lock_desc"
    const val ID_TV_PREVIEW_LEFT_BUTTON = "tv.danmaku.bili:id/tv_preview_left_button"
    const val ID_TV_PREVIEW_RIGHT_BUTTON = "tv.danmaku.bili:id/tv_preview_right_button"
    const val ID_LAYOUT_PAUSE_POINT_LOCK = "tv.danmaku.bili:id/layout_pause_point_lock"
    const val ID_BTN_PAUSE_POINT_BT = "tv.danmaku.bili:id/btn_pause_point_bt"

    // Reward ad View IDs
    const val ID_TV_COUNTDOWN = "tv.danmaku.bili:id/tv_countdown"
    const val ID_TV_CLOSE = "tv.danmaku.bili:id/tv_close"
    const val ID_COUNT_DOWN_ON_EXIT = "tv.danmaku.bili:id/count_down_on_exit"
    const val ID_DOWNLOAD_LABEL = "tv.danmaku.bili:id/download_label"
    // Native FastHybrid capsule close ("X") button used to exit ad mini-programs (e.g. novel/game ads)
    const val ID_MORE_RIGHT_BTN = "tv.danmaku.bili:id/more_right_btn"

    val UNLOCK_BUTTON_IDS = listOf(
        ID_BTN_UNLOCK,
        ID_BTN_PAUSE_POINT_BT,
        ID_TV_PREVIEW_RIGHT_BUTTON,
        ID_TV_PREVIEW_LEFT_BUTTON
    )

    // Safety filters for unlock buttons (must NEVER tap paid/VIP buttons)
    val UNLOCK_POSITIVE_KEYWORDS = listOf(
        "观看领奖励",
        "看视频领奖励",
        "看广告领奖励",
        "看广告",
        "广告解锁",
        "免费解锁",
        "免费看",
        "看视频解锁",
        "观看广告",
        "看激励视频",
        "激励视频",
        "免费领取",
        "广告",
        "解锁本集",
        "解锁剧集"
    )

    // WebView bonus multi-episode unlock CTA buttons ("再看一个激励视频，额外赠送 2 集" -> "观看领奖励")
    val BONUS_UNLOCK_BUTTON_KEYWORDS = listOf(
        "观看领奖励",
        "看视频领奖励",
        "看广告领奖励",
        "免费领福利"
    )

    val BONUS_DISMISS_KEYWORDS = listOf(
        "放弃福利",
        "暂不需要",
        "不用了"
    )

    // Descriptive sentence fragments that must never be mistaken for fallback buttons
    val UNLOCK_NON_BUTTON_HINTS = listOf(
        "，",
        "。",
        "！",
        "？",
        "额外赠送",
        "已解锁",
        "再看一个"
    )

    val UNLOCK_BLACKLIST_KEYWORDS = listOf(
        "免广告",
        "免广",
        "去广告",
        "跳广告",
        "免看广告",
        "无限看",
        "畅看",
        "购买",
        "开通",
        "付费",
        "充值",
        "会员",
        "大会员",
        "VIP",
        "vip",
        "¥",
        "￥",
        "元",
        "电池",
        "B币",
        "漫币",
        "漫豆",
        "放弃福利",
        "放弃",
        "残忍离开",
        "取消",
        "下载",
        "安装"
    )

    val PAID_MODAL_INDICATORS = listOf(
        "选择解锁方式",
        "虚拟商品购买后不可退换",
        "解锁每集消耗漫币"
    )

    val PAID_MODAL_CLOSE_TEXTS = listOf(
        "close",
        "关闭",
        "×"
    )

    // Reward ad state keywords (verified from Bilibili 9.19.0 resources)
    val REWARD_DONE_KEYWORDS = listOf(
        "已获得奖励",
        "恭喜你已获得奖励",
        "你已获得奖励",
        "已解锁下",
        "已解锁充电时长",
        "游戏试玩已结束",
        "上滑继续观看短剧",
        "上滑继续观看剧集"
    )

    val REWARD_SWIPE_GUIDE_KEYWORDS = listOf(
        "上滑继续观看短剧",
        "上滑继续观看剧集",
        "广告结束后上滑继续观看短剧",
        "上滑重试"
    )

    val RETENTION_CONTINUE_KEYWORDS = listOf(
        "继续观看领取奖励",
        "继续游玩领取奖励",
        "继续拼图领取奖励",
        "继续观看",
        "继续"
    )

    val RETENTION_AVOID_KEYWORDS = listOf(
        "放弃机会",
        "放弃",
        "残忍离开"
    )

    val EXTERNAL_JUMP_PROMPT_KEYWORDS = listOf(
        "前往体验",
        "去体验",
        "即可获得奖励",
        "可获得奖励",
        "前往获得激励",
        "点击广告即可获得奖励",
        "尚未满足体验时长"
    )

    val EXTERNAL_JUMP_ACTION_KEYWORDS = listOf(
        "查看详情",
        "立即查看",
        "前往",
        "去体验",
        "立即体验",
        "去看看",
        "立即前往",
        "点击前往",
        "去逛逛",
        "立即购买",
        "立即抢购",
        "了解详情",
        "立即领取"
    )

    val CLOSE_BUTTON_TEXTS = listOf(
        "关闭",
        "关闭广告",
        "跳过",
        "跳过广告",
        "已获得奖励，立即返回"
    )

    val SYSTEM_CONFIRM_PACKAGES = setOf(
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "android"
    )

    val SYSTEM_ALLOW_BUTTON_TEXTS = listOf(
        "允许",
        "打开",
        "继续",
        "确定"
    )

    val SYSTEM_DENY_BUTTON_TEXTS = listOf(
        "取消",
        "拒绝",
        "不允许",
        "暂不",
        "禁止"
    )

    val APP_STORE_AND_INSTALLER_PACKAGES = setOf(
        "com.android.vending",
        "com.xiaomi.market",
        "com.huawei.appmarket",
        "com.oppo.market",
        "com.bbk.appstore",
        "com.meizu.mstore",
        "com.sec.android.app.samsungapps",
        "com.tencent.android.qqdownloader",
        "com.qihoo.appstore",
        "com.baidu.appsearch",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller"
    )

    // Launcher / system / plugin packages that indicate user intentionally left Bilibili (never fight Home)
    val NON_AD_PAUSE_PACKAGES = setOf(
        "com.miui.home",
        "com.miui.launcher",
        "com.lge.launcher3",
        "com.android.launcher",
        "com.android.launcher3",
        "com.android.systemui",
        "com.android.settings",
        "com.bili.drama.accessibility"
    )
}
