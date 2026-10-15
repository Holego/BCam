package io.github.holego.bcam.monetization

import android.content.Context

/**
 * [AdStore] on SharedPreferences. Kept apart from the recording settings so that nothing
 * about ads touches the foss build's storage.
 *
 * [subscribed] is only a cache of the last Play Billing answer: the source of truth is
 * Google Play, which is re-queried on every start (see [BillingManager]). It flips back to
 * false by itself when a subscription lapses or is refunded.
 */
internal class PrefsAdStore(context: Context) : AdStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var subscribed: Boolean
        get() = prefs.getBoolean(KEY_SUBSCRIBED, false)
        set(value) = prefs.edit().putBoolean(KEY_SUBSCRIBED, value).apply()

    override var adFreeUntilMs: Long
        get() = prefs.getLong(KEY_AD_FREE_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_AD_FREE_UNTIL, value).apply()

    override var launchCount: Int
        get() = prefs.getInt(KEY_LAUNCH_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAUNCH_COUNT, value).apply()

    override var stopCount: Int
        get() = prefs.getInt(KEY_STOP_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_STOP_COUNT, value).apply()

    override var lastFullScreenMs: Long
        get() = prefs.getLong(KEY_LAST_FULL_SCREEN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_FULL_SCREEN, value).apply()

    private companion object {
        const val PREFS_NAME = "monetization"
        const val KEY_SUBSCRIBED = "subscribed"
        const val KEY_AD_FREE_UNTIL = "ad_free_until_ms"
        const val KEY_LAUNCH_COUNT = "launch_count"
        const val KEY_STOP_COUNT = "stop_count"
        const val KEY_LAST_FULL_SCREEN = "last_full_screen_ms"
    }
}
