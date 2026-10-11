package io.github.holego.bcam.monetization

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * The F-Droid / GitHub build has no ads and no purchases, so this is deliberately empty. It
 * exists so that MainActivity can call the same four methods in both flavors; the real
 * implementation, with the ad and billing SDKs behind it, lives in src/play.
 */
@Suppress("UNUSED_PARAMETER")
internal class MonetizationController(activity: AppCompatActivity) {

    fun attach(savedInstanceState: Bundle?) = Unit

    fun onLeavingForSystemScreen() = Unit

    fun onRecordingStoppedByUser() = Unit
}
