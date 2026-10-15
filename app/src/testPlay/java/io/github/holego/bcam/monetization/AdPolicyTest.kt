package io.github.holego.bcam.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPolicyTest {

    private class FakeStore : AdStore {
        override var subscribed = false
        override var adFreeUntilMs = 0L
        override var launchCount = 0
        override var stopCount = 0
        override var lastFullScreenMs = 0L
    }

    private var now = 1_000_000_000_000L
    private val store = FakeStore()
    private val policy = AdPolicy(store) { now }

    private val hour = 60 * 60 * 1000L
    private val minute = 60 * 1000L

    @Test
    fun adsAreOnByDefault() {
        assertFalse(policy.adsSuppressed())
        assertEquals(0L, policy.adFreeRemainingMs())
    }

    @Test
    fun activeSubscriptionSuppressesAdsUntilItLapses() {
        policy.setSubscribed(true)
        assertTrue(policy.adsSuppressed())
        now += 1000 * hour
        assertTrue(policy.adsSuppressed())
        policy.setSubscribed(false)
        assertFalse(policy.adsSuppressed())
    }

    @Test
    fun rewardSuppressesAdsForTwentyFourHoursOnly() {
        policy.grantAdFree()
        assertTrue(policy.adsSuppressed())
        assertEquals(24 * hour, policy.adFreeRemainingMs())

        now += 24 * hour - 1
        assertTrue(policy.adsSuppressed())

        now += 1
        assertFalse(policy.adsSuppressed())
    }

    @Test
    fun watchingAgainRestartsTheWindowInsteadOfStacking() {
        policy.grantAdFree()
        now += 10 * hour
        policy.grantAdFree()
        assertEquals(24 * hour, policy.adFreeRemainingMs())
    }

    @Test
    fun clockMovedBackwardsDoesNotExtendAReward() {
        policy.grantAdFree()
        now -= 5 * 24 * hour
        assertFalse(policy.adsSuppressed())
    }

    @Test
    fun firstRecordingEverIsLeftAlone() {
        assertEquals(BreakAd.NONE, policy.onUserStoppedRecording())
        assertEquals(1, store.stopCount)
    }

    @Test
    fun breaksAlternateInterstitialAndRewardOffer() {
        assertEquals(BreakAd.NONE, policy.onUserStoppedRecording()) // 1st
        assertEquals(BreakAd.INTERSTITIAL, policy.onUserStoppedRecording()) // 2nd
        assertEquals(BreakAd.REWARDED_OFFER, policy.onUserStoppedRecording()) // 3rd
        assertEquals(BreakAd.INTERSTITIAL, policy.onUserStoppedRecording()) // 4th
    }

    @Test
    fun fullScreenCooldownBlocksBreaks() {
        policy.onUserStoppedRecording()
        assertEquals(BreakAd.INTERSTITIAL, policy.onUserStoppedRecording())
        policy.recordFullScreenShown()

        now += 2 * minute
        assertEquals(BreakAd.NONE, policy.onUserStoppedRecording())

        now += 2 * minute
        assertEquals(BreakAd.INTERSTITIAL, policy.onUserStoppedRecording())
    }

    @Test
    fun noBreaksWhileAdsAreOff() {
        policy.grantAdFree()
        repeat(6) { assertEquals(BreakAd.NONE, policy.onUserStoppedRecording()) }
        assertEquals(6, store.stopCount)
    }

    @Test
    fun appOpenNeedsASecondLaunchAndNoRecentAd() {
        policy.onAppStarted()
        assertFalse(policy.canShowAppOpen())

        policy.onAppStarted()
        assertTrue(policy.canShowAppOpen())

        policy.recordFullScreenShown()
        assertFalse(policy.canShowAppOpen())
        now += 3 * minute
        assertTrue(policy.canShowAppOpen())
    }

    @Test
    fun appOpenRespectsAdFreeState() {
        policy.onAppStarted()
        policy.onAppStarted()
        policy.setSubscribed(true)
        assertFalse(policy.canShowAppOpen())
    }
}
