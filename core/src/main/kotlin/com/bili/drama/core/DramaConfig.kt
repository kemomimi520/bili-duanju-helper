package com.bili.drama.core

/**
 * Runtime configuration for batch episode ad-watching automation.
 */
data class DramaConfig(
    /** Whether to drag/tap the seekbar to trigger preview/pause-point locks immediately. */
    val seekCheckEnabled: Boolean = true,
    /** Primary seek position ratio (0.78 covers both previewSecond and mid-roll pausePoints in one drag). */
    val primarySeekRatio: Float = 0.78f,
    /** Optional second seek stage; false by default since single 0.78 seek triggers all earlier pausePoints. */
    val secondarySeekEnabled: Boolean = false,
    /** Secondary seek position ratio (0.88 = near end) when secondarySeekEnabled is true. */
    val secondarySeekRatio: Float = 0.88f,
    /** Wait time after entering a new episode before probing/seeking. */
    val episodeSettleMs: Long = 420L,
    /** Wait time after seeking before concluding an episode has no ad lock. */
    val postSeekWaitMs: Long = 900L,
    /** Settle time after closing a rewarded ad before re-probing or advancing. */
    val postAdSettleMs: Long = 600L,
    /** Fallback wait time on an episode when seekbar isn't available. */
    val fallbackNoSeekWaitMs: Long = 2200L,
    /** Seconds to stay in an external/in-app page for "前往体验10秒" ad tasks. */
    val externalStaySeconds: Int = 11,
    /** Max seconds to wait inside a single ad before retrying/backing out. */
    val adTimeoutSeconds: Int = 45,
    /** Automatically stop when reaching the final episode (e.g. 38/38). */
    val stopAtLastEpisode: Boolean = true,
    /** Max consecutive unlock attempts on the same episode before pausing for safety. */
    val maxSameEpisodeRetries: Int = 4,
    /** Intercept automatic external app / store jumps while keeping ad timers running. */
    val interceptExternalJumps: Boolean = true
)
