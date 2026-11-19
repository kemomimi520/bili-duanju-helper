package com.bili.drama.accessibility

import android.content.Context
import android.content.SharedPreferences
import com.bili.drama.core.DramaConfig

/**
 * Persists user-tunable parameters and live run logs for the Accessibility plugin.
 */
object AccPrefs {
    private const val PREF_NAME = "drama_acc_prefs"
    private const val KEY_SEEK_CHECK = "seek_check_enabled"
    private const val KEY_INTERCEPT_JUMP = "intercept_external_jumps"
    private const val KEY_EXTERNAL_STAY = "external_stay_seconds_v2"
    private const val KEY_AD_TIMEOUT = "ad_timeout_seconds"
    private const val KEY_POST_SEEK_WAIT = "post_seek_wait_ms_v2"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    private const val KEY_ONLY_IN_BILI = "only_show_in_bili"
    private const val KEY_PILL_X = "pill_pos_x"
    private const val KEY_PILL_Y = "pill_pos_y"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadConfig(context: Context): DramaConfig {
        val p = prefs(context)
        return DramaConfig(
            seekCheckEnabled = p.getBoolean(KEY_SEEK_CHECK, true),
            externalStaySeconds = p.getInt(KEY_EXTERNAL_STAY, 11),
            adTimeoutSeconds = p.getInt(KEY_AD_TIMEOUT, 45),
            postSeekWaitMs = p.getLong(KEY_POST_SEEK_WAIT, 900L),
            interceptExternalJumps = p.getBoolean(KEY_INTERCEPT_JUMP, true)
        )
    }

    fun saveConfig(
        context: Context,
        seekCheckEnabled: Boolean,
        interceptExternalJumps: Boolean,
        externalStaySeconds: Int,
        adTimeoutSeconds: Int,
        postSeekWaitMs: Long
    ) {
        prefs(context).edit()
            .putBoolean(KEY_SEEK_CHECK, seekCheckEnabled)
            .putBoolean(KEY_INTERCEPT_JUMP, interceptExternalJumps)
            .putInt(KEY_EXTERNAL_STAY, externalStaySeconds.coerceIn(5, 30))
            .putInt(KEY_AD_TIMEOUT, adTimeoutSeconds.coerceIn(15, 120))
            .putLong(KEY_POST_SEEK_WAIT, postSeekWaitMs.coerceIn(500L, 5000L))
            .apply()
    }

    fun isOverlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_ENABLED, true)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    fun isOnlyShowInBili(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONLY_IN_BILI, true)

    fun setOnlyShowInBili(context: Context, onlyInBili: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONLY_IN_BILI, onlyInBili).apply()
    }

    fun loadPillPosition(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        if (!p.contains(KEY_PILL_X) || !p.contains(KEY_PILL_Y)) return null
        return p.getInt(KEY_PILL_X, 0) to p.getInt(KEY_PILL_Y, 0)
    }

    fun savePillPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_PILL_X, x)
            .putInt(KEY_PILL_Y, y)
            .apply()
    }
}
