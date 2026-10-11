package io.github.holego.bcam.monetization

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import io.github.holego.bcam.R

/**
 * Everything ad- and purchase-related on the main screen, behind four calls from
 * MainActivity. The foss flavor has a class with the same name whose methods do nothing.
 *
 * Where ads appear - and where they never do:
 *  - banner: bottom of the setup screen; native card: below the settings;
 *  - interstitial / reward offer: right after the user stops a recording *in the app*;
 *  - app-open: when the user comes back to the app after a while away;
 *  - rewarded: only when the user taps "watch a video".
 * Never while recording, never from the widget or notification, and nothing is shown at all
 * once "remove ads" is bought or a reward is active.
 */
internal class MonetizationController(private val activity: AppCompatActivity) :
    DefaultLifecycleObserver {

    private val policy = AdPolicy(PrefsAdStore(activity))
    private val ads = AdsManager(activity, policy)
    private val consent = ConsentManager(activity)
    private val billing = BillingManager(
        activity,
        onEntitlement = ::onEntitlement,
        onProductReady = ::render,
        onMessage = ::toast,
    )
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var bannerContainer: FrameLayout
    private lateinit var nativeContainer: FrameLayout
    private lateinit var section: View

    private var entitlementKnown = false
    private var consentInFlight = false
    private var consentAttempted = false
    private var adsStarted = false
    private var suppressNextAppOpen = false
    private var destroyed = false

    // ------------------------------------------------------------------ MainActivity API

    /** Call once from onCreate, after the layout is inflated. */
    fun attach(savedInstanceState: Bundle?) {
        // Not on rotation / recreation: those are not new launches.
        if (savedInstanceState == null) policy.onAppStarted()

        bannerContainer = activity.findViewById(R.id.ad_banner_container)
        nativeContainer = activity.findViewById(R.id.ad_native_container)
        val sectionContainer = activity.findViewById<FrameLayout>(R.id.monetization_container)
        section = LayoutInflater.from(activity)
            .inflate(R.layout.section_monetization, sectionContainer, false)
        sectionContainer.addView(section)
        sectionContainer.visibility = View.VISIBLE

        section.findViewById<Button>(R.id.ads_remove_button).setOnClickListener { onRemoveAdsClicked() }
        section.findViewById<Button>(R.id.ads_reward_button).setOnClickListener { onRewardClicked() }
        section.findViewById<View>(R.id.ads_privacy_options).setOnClickListener {
            suppressNextAppOpen = true
            consent.showPrivacyOptions { render() }
        }

        activity.lifecycle.addObserver(this)
        billing.start()
        // Do not hold ads hostage to a Play Store that never answers: after a few seconds
        // carry on with the cached entitlement.
        handler.postDelayed({
            if (!entitlementKnown) {
                entitlementKnown = true
                startAdsIfAllowed()
            }
        }, BILLING_TIMEOUT_MS)
        render()
    }

    /**
     * Call before sending the user to a screen outside the app (folder picker, About...).
     * Coming back from it is not "returning to the app" and must not trigger an app-open ad.
     */
    fun onLeavingForSystemScreen() {
        suppressNextAppOpen = true
    }

    /** Call when the user stopped a recording with the in-app button. */
    fun onRecordingStoppedByUser() {
        when (policy.onUserStoppedRecording()) {
            BreakAd.NONE -> Unit
            BreakAd.INTERSTITIAL -> if (adsStarted) ads.showInterstitial()
            BreakAd.REWARDED_OFFER -> if (adsStarted && ads.hasRewardedInterstitial) showRewardOffer()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart(owner: LifecycleOwner) {
        render()
        startAdsIfAllowed()
        ads.resumeBanner()
        maybeShowAppOpen()
        suppressNextAppOpen = false
    }

    override fun onStop(owner: LifecycleOwner) {
        lastStopElapsedMs = SystemClock.elapsedRealtime()
        ads.pauseBanner()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        billing.end()
        ads.destroy()
    }

    // ------------------------------------------------------------------ entitlement

    private fun onEntitlement(owned: Boolean?) {
        if (destroyed) return
        // null: Play could not be reached - keep whatever was cached.
        if (owned != null) policy.setAdsRemoved(owned)
        entitlementKnown = true
        render()
        startAdsIfAllowed()
    }

    // ------------------------------------------------------------------ starting ads

    private fun startAdsIfAllowed() {
        if (destroyed || adsStarted || consentInFlight || consentAttempted || !entitlementKnown) return
        if (policy.adsSuppressed()) return
        consentInFlight = true
        consent.gather { canRequestAds ->
            consentInFlight = false
            // One attempt per screen instance: a user who declined is not asked again on
            // every return to the app. The "Ad privacy settings" row is how they change it.
            consentAttempted = true
            if (destroyed || !canRequestAds || policy.adsSuppressed()) {
                render()
                return@gather
            }
            AdsManager.ensureSdk(activity) {
                if (destroyed || policy.adsSuppressed()) return@ensureSdk
                adsStarted = true
                ads.showBanner(bannerContainer)
                ads.preload(::bindNative)
                render()
            }
        }
    }

    private fun maybeShowAppOpen() {
        val awayMs = SystemClock.elapsedRealtime() - lastStopElapsedMs
        if (lastStopElapsedMs == 0L || awayMs < APP_OPEN_MIN_AWAY_MS) return
        if (!adsStarted || suppressNextAppOpen || ads.isShowingFullScreen) return
        if (policy.canShowAppOpen()) ads.showAppOpen()
    }

    // ------------------------------------------------------------------ actions

    private fun onRemoveAdsClicked() {
        suppressNextAppOpen = true
        if (!billing.launchPurchase(activity)) toast(R.string.purchase_unavailable)
    }

    private fun onRewardClicked() {
        val shown = ads.showRewarded { grantReward() }
        if (!shown) toast(R.string.ads_video_not_ready)
    }

    private fun showRewardOffer() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.reward_offer_title)
            .setMessage(R.string.reward_offer_message)
            .setPositiveButton(R.string.reward_offer_watch) { _, _ ->
                if (!ads.showRewardedInterstitial { grantReward() }) toast(R.string.ads_video_not_ready)
            }
            .setNegativeButton(R.string.reward_offer_skip, null)
            .show()
    }

    private fun grantReward() {
        policy.grantAdFree()
        toast(R.string.reward_granted)
        render()
    }

    // ------------------------------------------------------------------ UI

    private fun render() {
        if (destroyed) return
        val removed = policy.adsRemoved
        val rewardLeftMs = policy.adFreeRemainingMs()
        val suppressed = policy.adsSuppressed()

        if (suppressed) {
            ads.hideBanner(bannerContainer)
            nativeContainer.removeAllViews()
            nativeContainer.visibility = View.GONE
        }

        val status = section.findViewById<TextView>(R.id.ads_status)
        val removeButton = section.findViewById<Button>(R.id.ads_remove_button)
        val rewardButton = section.findViewById<Button>(R.id.ads_reward_button)

        status.text = when {
            removed -> activity.getString(R.string.ads_status_removed)
            rewardLeftMs > 0L -> {
                val minutes = (rewardLeftMs / 60_000L).toInt()
                activity.getString(R.string.ads_status_reward, minutes / 60, minutes % 60)
            }
            else -> activity.getString(R.string.ads_status_on)
        }

        removeButton.visibility = if (removed) View.GONE else View.VISIBLE
        val price = billing.formattedPrice
        removeButton.text = if (price != null) {
            activity.getString(R.string.ads_remove_button, price)
        } else {
            activity.getString(R.string.ads_remove_button_noprice)
        }

        // Only offered when there is actually an ad to watch.
        rewardButton.visibility =
            if (!suppressed && adsStarted && ads.hasRewarded) View.VISIBLE else View.GONE

        section.findViewById<View>(R.id.ads_privacy_options).visibility =
            if (consent.privacyOptionsRequired) View.VISIBLE else View.GONE
    }

    /** Called when a native ad has loaded; shows it as a labelled card under the settings. */
    private fun bindNative(ad: NativeAd) {
        if (destroyed || policy.adsSuppressed()) return
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.ad_native, nativeContainer, false) as NativeAdView

        val headline = view.findViewById<TextView>(R.id.ad_headline)
        headline.text = ad.headline
        view.headlineView = headline

        val body = view.findViewById<TextView>(R.id.ad_body)
        if (ad.body != null) {
            body.text = ad.body
            body.visibility = View.VISIBLE
        } else {
            body.visibility = View.GONE
        }
        view.bodyView = body

        val advertiser = view.findViewById<TextView>(R.id.ad_advertiser)
        if (ad.advertiser != null) {
            advertiser.text = ad.advertiser
            advertiser.visibility = View.VISIBLE
        } else {
            advertiser.visibility = View.GONE
        }
        view.advertiserView = advertiser

        val icon = view.findViewById<ImageView>(R.id.ad_icon)
        val iconDrawable = ad.icon?.drawable
        if (iconDrawable != null) {
            icon.setImageDrawable(iconDrawable)
            icon.visibility = View.VISIBLE
        } else {
            icon.visibility = View.GONE
        }
        view.iconView = icon

        val media = view.findViewById<MediaView>(R.id.ad_media)
        view.mediaView = media

        val cta = view.findViewById<Button>(R.id.ad_cta)
        if (ad.callToAction != null) {
            cta.text = ad.callToAction
            cta.visibility = View.VISIBLE
        } else {
            cta.visibility = View.GONE
        }
        view.callToActionView = cta

        view.setNativeAd(ad)

        nativeContainer.removeAllViews()
        nativeContainer.addView(view)
        nativeContainer.visibility = View.VISIBLE
    }

    private fun toast(messageRes: Int) {
        if (!destroyed) Toast.makeText(activity, messageRes, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val BILLING_TIMEOUT_MS = 4_000L

        /** Quick hops (permission dialogs, other apps for a moment) are not "coming back". */
        const val APP_OPEN_MIN_AWAY_MS = 20_000L

        /** Process-wide, so it survives the Activity being recreated. */
        @Volatile
        var lastStopElapsedMs = 0L
    }
}
