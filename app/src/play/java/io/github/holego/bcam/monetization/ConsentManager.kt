package io.github.holego.bcam.monetization

import android.app.Activity
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Google's User Messaging Platform: the consent form that is mandatory for ads shown to
 * users in the EEA, the UK and Switzerland, and the "privacy options" entry point that has
 * to stay reachable afterwards so the user can change their mind.
 *
 * The form's text and the regions it applies to are configured in the AdMob console
 * (Privacy & messaging), not here. Until a message is published there, UMP reports that
 * no form is required.
 */
internal class ConsentManager(private val activity: Activity) {

    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)

    val privacyOptionsRequired: Boolean
        get() = info.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Refreshes the consent state and shows the form if one is needed. Called on every
     * launch, as Google asks. [onDone] receives whether ads may be requested.
     */
    fun gather(onDone: (canRequestAds: Boolean) -> Unit) {
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                if (activity.isFinishing || activity.isDestroyed) return@requestConsentInfoUpdate
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form: " + formError.errorCode + " " + formError.message)
                    }
                    onDone(info.canRequestAds())
                }
            },
            { formError ->
                Log.w(TAG, "Consent info update failed: " + formError.errorCode + " " + formError.message)
                // A previous session may already have collected consent.
                onDone(info.canRequestAds())
            },
        )
    }

    fun showPrivacyOptions(onDismissed: () -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                Log.w(TAG, "Privacy options form: " + formError.errorCode + " " + formError.message)
            }
            onDismissed()
        }
    }

    private companion object {
        const val TAG = "Monetization"
    }
}
