package io.github.holego.bcam.monetization

/**
 * The handful of facts the ad rules depend on. Backed by SharedPreferences in the app
 * ([PrefsAdStore]) and by a plain object in the unit tests, which is the only reason this
 * is an interface.
 */
interface AdStore {
    /** Cached result of the last Play Billing check: the ad-free subscription is active. */
    var subscribed: Boolean

    /** Wall-clock time (epoch millis) until which a watched reward keeps ads off. */
    var adFreeUntilMs: Long

    /** How many times the app has been started. */
    var launchCount: Int

    /** How many recordings the user has stopped from inside the app. */
    var stopCount: Int

    /** When a full-screen ad (interstitial, rewarded, app open) was last shown. */
    var lastFullScreenMs: Long
}

/** What, if anything, to offer at the moment the user stops a recording. */
enum class BreakAd {
    NONE,
    INTERSTITIAL,

    /** Rewarded interstitial: shown only after the user agrees to it in a dialog. */
    REWARDED_OFFER,
}

/**
 * When ads may appear. Deliberately conservative: ads only ever show at natural pauses in
 * the setup app, never while recording and never when recording is started from the
 * widget (which does not open the app at all).
 *
 * Pure logic with an injectable clock, so it is covered by plain JVM unit tests.
 */
class AdPolicy(
    private val store: AdStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** True while the ad-free subscription is active (as last confirmed by Google Play). */
    val subscribed: Boolean get() = store.subscribed

    /** True when no ad of any kind may be loaded or shown right now. */
    fun adsSuppressed(): Boolean = store.subscribed || adFreeRemainingMs() > 0L

    /** Milliseconds of reward-based ad-free time left; 0 when none. */
    fun adFreeRemainingMs(): Long {
        val remaining = store.adFreeUntilMs - clock()
        // A reward is never longer than REWARD_AD_FREE_MS, so anything beyond that means
        // the system clock was moved backwards after the grant. Treat it as expired.
        return if (remaining in 1..REWARD_AD_FREE_MS) remaining else 0L
    }

    /** Called when the user earns a reward. Restarts the window rather than stacking it. */
    fun grantAdFree(durationMs: Long = REWARD_AD_FREE_MS) {
        store.adFreeUntilMs = clock() + durationMs
    }

    fun setSubscribed(active: Boolean) {
        store.subscribed = active
    }

    fun onAppStarted() {
        store.launchCount += 1
    }

    /**
     * App-open ads are for *returning* to the app, not for the first impression: never on
     * the first launch, and never right after another full-screen ad.
     */
    fun canShowAppOpen(): Boolean =
        !adsSuppressed() && store.launchCount >= 2 && cooldownElapsed()

    /**
     * Called when the user stops a recording from the app. Decides what to show, if
     * anything. Interstitials and reward offers alternate, and the very first recording
     * the user ever makes is left alone.
     */
    fun onUserStoppedRecording(): BreakAd {
        store.stopCount += 1
        return when {
            adsSuppressed() -> BreakAd.NONE
            store.stopCount < 2 -> BreakAd.NONE
            !cooldownElapsed() -> BreakAd.NONE
            store.stopCount % 2 == 0 -> BreakAd.INTERSTITIAL
            else -> BreakAd.REWARDED_OFFER
        }
    }

    /** Call when a full-screen ad has actually been put on screen. */
    fun recordFullScreenShown() {
        store.lastFullScreenMs = clock()
    }

    private fun cooldownElapsed(): Boolean =
        clock() - store.lastFullScreenMs >= FULL_SCREEN_COOLDOWN_MS

    companion object {
        const val FULL_SCREEN_COOLDOWN_MS = 3 * 60 * 1000L
        const val REWARD_AD_FREE_MS = 24 * 60 * 60 * 1000L
    }
}
