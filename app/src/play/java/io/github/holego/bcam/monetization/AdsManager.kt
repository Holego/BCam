package io.github.holego.bcam.monetization

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback

/**
 * Loads and shows the six AdMob formats. It decides nothing about *whether* an ad may
 * appear - that is [AdPolicy] and [MonetizationController]; this class only does what it
 * is told and keeps the loaded ads around.
 *
 * Must be used from the main thread. Every ad is loaded ahead of time and replaced after
 * it has been shown, so a "show" call never waits on the network: if nothing is ready it
 * returns false and the moment simply passes without an ad.
 */
internal class AdsManager(
    private val activity: Activity,
    private val policy: AdPolicy,
) {
    private val appContext: Context = activity.applicationContext

    private var banner: AdView? = null
    private var interstitial: InterstitialAd? = null
    private var rewardedInterstitial: RewardedInterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var appOpen: AppOpenAd? = null
    private var appOpenLoadedAtMs = 0L
    private var nativeAd: NativeAd? = null

    private var loadingInterstitial = false
    private var loadingRewardedInterstitial = false
    private var loadingRewarded = false
    private var loadingAppOpen = false
    private var loadingNative = false

    private var destroyed = false

    /** True while any full-screen ad is on screen. */
    var isShowingFullScreen = false
        private set

    val hasRewardedInterstitial: Boolean get() = rewardedInterstitial != null
    val hasRewarded: Boolean get() = rewarded != null

    // ------------------------------------------------------------------ loading

    /**
     * Starts every format that is not already loaded. Safe to call repeatedly. The native ad
     * stays owned by this class (and is destroyed with it); [onNativeReady] only binds it.
     */
    fun preload(onNativeReady: (NativeAd) -> Unit) {
        loadInterstitial()
        loadRewardedInterstitial()
        loadRewarded()
        loadAppOpen()
        loadNative(onNativeReady)
    }

    private fun request(): AdRequest = AdRequest.Builder().build()

    private fun loadInterstitial() {
        if (interstitial != null || loadingInterstitial || destroyed) return
        loadingInterstitial = true
        InterstitialAd.load(
            appContext,
            AdUnits.interstitial,
            request(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadingInterstitial = false
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingInterstitial = false
                    log("interstitial", error)
                }
            },
        )
    }

    private fun loadRewardedInterstitial() {
        if (rewardedInterstitial != null || loadingRewardedInterstitial || destroyed) return
        loadingRewardedInterstitial = true
        RewardedInterstitialAd.load(
            appContext,
            AdUnits.rewardedInterstitial,
            request(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    loadingRewardedInterstitial = false
                    rewardedInterstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingRewardedInterstitial = false
                    log("rewarded interstitial", error)
                }
            },
        )
    }

    private fun loadRewarded() {
        if (rewarded != null || loadingRewarded || destroyed) return
        loadingRewarded = true
        RewardedAd.load(
            appContext,
            AdUnits.rewarded,
            request(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loadingRewarded = false
                    rewarded = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingRewarded = false
                    log("rewarded", error)
                }
            },
        )
    }

    private fun loadAppOpen() {
        if (appOpen != null || loadingAppOpen || destroyed) return
        loadingAppOpen = true
        AppOpenAd.load(
            appContext,
            AdUnits.appOpen,
            request(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    loadingAppOpen = false
                    appOpen = ad
                    appOpenLoadedAtMs = SystemClock.elapsedRealtime()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingAppOpen = false
                    log("app open", error)
                }
            },
        )
    }

    private fun loadNative(onReady: (NativeAd) -> Unit) {
        if (nativeAd != null || loadingNative || destroyed) return
        loadingNative = true
        AdLoader.Builder(appContext, AdUnits.nativeAdvanced)
            .forNativeAd { ad ->
                loadingNative = false
                if (destroyed) {
                    ad.destroy()
                } else {
                    nativeAd = ad
                    onReady(ad)
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingNative = false
                    log("native", error)
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()
            .loadAd(request())
    }

    // ------------------------------------------------------------------ banner

    /** Adds an adaptive banner to [container]; the container stays hidden until it loads. */
    fun showBanner(container: FrameLayout) {
        if (banner != null || destroyed) return
        val metrics = activity.resources.displayMetrics
        val widthPx = if (container.width > 0) container.width else metrics.widthPixels
        val widthDp = (widthPx / metrics.density).toInt()

        val view = AdView(activity)
        view.adUnitId = AdUnits.banner
        view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
        view.adListener = object : AdListener() {
            override fun onAdLoaded() {
                container.visibility = View.VISIBLE
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                container.visibility = View.GONE
                log("banner", error)
            }
        }
        container.removeAllViews()
        container.addView(view)
        banner = view
        view.loadAd(request())
    }

    fun hideBanner(container: FrameLayout) {
        banner?.destroy()
        banner = null
        container.removeAllViews()
        container.visibility = View.GONE
    }

    fun pauseBanner() {
        banner?.pause()
    }

    fun resumeBanner() {
        banner?.resume()
    }

    // ------------------------------------------------------------------ native

    // ------------------------------------------------------------------ showing

    fun showInterstitial(): Boolean {
        val ad = interstitial
        if (ad == null) {
            loadInterstitial()
            return false
        }
        interstitial = null
        ad.fullScreenContentCallback = fullScreenCallback { loadInterstitial() }
        ad.show(activity)
        return true
    }

    /** [onReward] runs when the user has watched enough of the video to earn the reward. */
    fun showRewardedInterstitial(onReward: () -> Unit): Boolean {
        val ad = rewardedInterstitial
        if (ad == null) {
            loadRewardedInterstitial()
            return false
        }
        rewardedInterstitial = null
        ad.fullScreenContentCallback = fullScreenCallback { loadRewardedInterstitial() }
        ad.show(activity) { onReward() }
        return true
    }

    fun showRewarded(onReward: () -> Unit): Boolean {
        val ad = rewarded
        if (ad == null) {
            loadRewarded()
            return false
        }
        rewarded = null
        ad.fullScreenContentCallback = fullScreenCallback { loadRewarded() }
        ad.show(activity) { onReward() }
        return true
    }

    /** App-open ads expire four hours after loading; an expired one is dropped, not shown. */
    fun showAppOpen(): Boolean {
        val ad = appOpen
        if (ad == null) {
            loadAppOpen()
            return false
        }
        appOpen = null
        if (SystemClock.elapsedRealtime() - appOpenLoadedAtMs > APP_OPEN_MAX_AGE_MS) {
            loadAppOpen()
            return false
        }
        ad.fullScreenContentCallback = fullScreenCallback { loadAppOpen() }
        ad.show(activity)
        return true
    }

    private fun fullScreenCallback(afterwards: () -> Unit) = object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() {
            isShowingFullScreen = true
            policy.recordFullScreenShown()
        }

        override fun onAdDismissedFullScreenContent() {
            isShowingFullScreen = false
            afterwards()
        }

        override fun onAdFailedToShowFullScreenContent(error: AdError) {
            isShowingFullScreen = false
            Log.w(TAG, "Ad failed to show: " + error.code + " " + error.message)
            afterwards()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun destroy() {
        destroyed = true
        banner?.destroy()
        banner = null
        nativeAd?.destroy()
        nativeAd = null
        interstitial = null
        rewardedInterstitial = null
        rewarded = null
        appOpen = null
    }

    private fun log(format: String, error: LoadAdError) {
        Log.w(TAG, "Could not load " + format + " ad: " + error.code + " " + error.message)
    }

    companion object {
        private const val TAG = "Monetization"
        private const val APP_OPEN_MAX_AGE_MS = 4 * 60 * 60 * 1000L

        private val main = Handler(Looper.getMainLooper())
        private val waiting = mutableListOf<() -> Unit>()
        private var sdkState = SDK_IDLE

        private const val SDK_IDLE = 0
        private const val SDK_STARTING = 1
        private const val SDK_READY = 2

        /**
         * Initialises the Mobile Ads SDK once per process, off the main thread as Google
         * recommends, and runs [onReady] on the main thread when it is done. Call this only
         * after consent has been gathered and only when ads are actually allowed - a user
         * who has bought "remove ads" never causes the SDK to start.
         */
        fun ensureSdk(context: Context, onReady: () -> Unit) {
            when (sdkState) {
                SDK_READY -> onReady()
                SDK_STARTING -> waiting += onReady
                else -> {
                    sdkState = SDK_STARTING
                    waiting += onReady
                    val appContext = context.applicationContext
                    Thread {
                        MobileAds.initialize(appContext) {
                            main.post {
                                sdkState = SDK_READY
                                val callbacks = waiting.toList()
                                waiting.clear()
                                callbacks.forEach { it() }
                            }
                        }
                    }.start()
                }
            }
        }
    }
}
