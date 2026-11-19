package com.bili.drama.core

/**
 * Verified Bilibili (tv.danmaku.bili) package, activity, view ID, and text constants
 * observed on live device dumps and APK resource tables.
 */
object BiliNodes {
    const val PKG_BILI = "tv.danmaku.bili"
    // Synthetic non-pause package marker when Android/OEM privacy shield hides the foreground external app's Accessibility window
    const val PKG_EXTERNAL_PROTECTED = "external.protected.app"

    // Activities
    const val ACTIVITY_APP_CONTAINER_PREFIX = "com.bilibili.lib.fasthybrid.container.AppContainerActivity"
    const val ACTIVITY_REWARD_AD = "com.bilibili.ad.reward.activity.RewardAdActivityWeb"

    // Short-drama player & lock View IDs
    const val ID_TOOLBAR_TITLE = "tv.danmaku.bili:id/toolbar_title"
    const val ID_ALBUM_NAME_V2 = "tv.danmaku.bili:id/tv_album_name_v2"
    const val ID_ALBUM_SELECT_LAYOUT = "tv.danmaku.bili:id/album_select_episode_layout"
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
        "安装",
        "刷广告",
        "检测广告"
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

    // Reward ad state keywords (verified from Bilibili 9.19.0 resources).
    // Note: "上滑继续观看剧集/短剧" can appear on the bottom card BEFORE the countdown ends,
    // so it belongs ONLY in REWARD_SWIPE_GUIDE_KEYWORDS, never as a standalone reward-done trigger.
    val REWARD_DONE_KEYWORDS = listOf(
        "已获得奖励",
        "恭喜你已获得奖励",
        "你已获得奖励",
        "已解锁下",
        "已解锁充电时长",
        "游戏试玩已结束"
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

    // Prompts that EXPLICITLY require jumping/experiencing an ad to earn the reward.
    // Never include bare "可获得奖励" because normal video countdowns ("8秒后可获得奖励") contain it!
    val EXTERNAL_JUMP_PROMPT_KEYWORDS = listOf(
        "前往体验",
        "去体验",
        "继续体验",
        "前往获得激励",
        "点击广告即可获得奖励",
        "点击即可获得奖励",
        "体验10秒",
        "体验15秒",
        "浏览10秒",
        "浏览15秒",
        "尚未满足体验时长"
    )

    // Matches dynamic jump prompts like "前往10秒即可获得奖励", "前往9秒即可获得奖励",
    // "跳转15秒获得奖励", "10秒即可领取奖励", while strictly excluding countdowns like "8秒后可获得奖励".
    val EXTERNAL_JUMP_PROMPT_REGEX = Regex(
        "(?:前往|去|跳转|浏览|体验|试玩|点击).{0,8}\\d+\\s*[秒sS].{0,8}(?:获得|领取|解锁|奖励|激励)|\\d+\\s*[秒sS]即可(?:获得|领取|解锁)"
    )

    val EXTERNAL_JUMP_ACTION_KEYWORDS = listOf(
        "查看详情",
        "立即查看",
        "前往",
        "去体验",
        "立即体验",
        "马上体验",
        "继续体验",
        "去看看",
        "立即前往",
        "点击前往",
        "去逛逛",
        "立即购买",
        "立即抢购",
        "去下单",
        "领券购买",
        "立即打开",
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
        "com.miui.guardprovider",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.intentresolver",
        "com.google.android.intentresolver",
        "com.oplus.safecenter",
        "com.coloros.safecenter",
        "com.iqoo.secure",
        "com.vivo.permissionmanager",
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
        "com.samsung.android.permissioncontroller",
        "android"
    )

    val JUMP_CONFIRM_DIALOG_HINTS = listOf(
        "即将离开",
        "离开哔哩哔哩",
        "离开短剧",
        "想要打开",
        "允许打开",
        "是否允许",
        "打开其他应用",
        "打开第三方",
        "跳转至",
        "跳转到",
        "外部应用",
        "第三方应用",
        "应用商店",
        "下载并安装",
        "允许一次"
    )

    val SYSTEM_ALLOW_BUTTON_TEXTS = listOf(
        "允许",
        "打开",
        "立即打开",
        "始终允许",
        "仍要打开",
        "继续",
        "同意",
        "确定"
    )

    val SYSTEM_DENY_BUTTON_TEXTS = listOf(
        "取消",
        "拒绝",
        "不允许",
        "暂不",
        "禁止"
    )

    val APK_INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.miui.global.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    val APP_STORE_AND_INSTALLER_PACKAGES = setOf(
        "com.android.vending",
        "com.xiaomi.market",
        "com.huawei.appmarket",
        "com.hihonor.appmarket",
        "com.oppo.market",
        "com.heytap.market",
        "com.oplus.appdetail",
        "com.bbk.appstore",
        "com.vivo.appstore",
        "com.meizu.mstore",
        "com.sec.android.app.samsungapps",
        "com.tencent.android.qqdownloader",
        "com.qihoo.appstore",
        "com.baidu.appsearch"
    ) + APK_INSTALLER_PACKAGES

    // Launcher / system / plugin packages that indicate user intentionally left Bilibili (never fight Home)
    val NON_AD_PAUSE_PACKAGES = setOf(
        "com.miui.home",
        "com.miui.launcher",
        "com.lge.launcher3",
        "com.oppo.launcher",
        "com.oplus.launcher",
        "com.bbk.launcher2",
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3",
        "com.android.systemui",
        "com.android.settings",
        "com.bili.drama.accessibility"
    )

    val RETURN_TO_BILI_TEXTS = listOf(
        "返回哔哩哔哩",
        "回到哔哩哔哩",
        "返回B站",
        "回到B站"
    )
}
