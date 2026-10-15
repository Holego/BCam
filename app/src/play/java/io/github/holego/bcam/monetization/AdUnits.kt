package io.github.holego.bcam.monetization

import io.github.holego.bcam.BuildConfig

/**
 * AdMob ad unit ids.
 *
 * Debug builds get Google's public sample units, which always fill and never produce real
 * traffic. Release builds get the live units. Tapping your own live ads is an AdMob policy
 * violation and can get the account suspended - on a personal device, add it as a test
 * device in the AdMob console first.
 *
 * Ad unit ids are not secrets: every app ships them in plain text.
 */
internal object AdUnits {

    val banner: String =
        if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/9214589741"
        else "ca-app-pub-6234952902147314/7765454659"

    val interstitial: String =
        if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/1033173712"
        else "ca-app-pub-6234952902147314/1699748918"

    val rewardedInterstitial: String =
        if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/5354046379"
        else "ca-app-pub-6234952902147314/5602325743"

    val rewarded: String =
        if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/5224354917"
        else "ca-app-pub-6234952902147314/2106943022"

    val nativeAdvanced: String =
        if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/2247696110"
        else "ca-app-pub-6234952902147314/5906587234"

    val appOpen: String =
        if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/9257395921"
        else "ca-app-pub-6234952902147314/7894367035"
}
