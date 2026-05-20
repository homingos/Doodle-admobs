package com.doodlepop.app

import android.app.Activity
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Wraps the Google User Messaging Platform (UMP) SDK.
 *
 * AdMob policy requires GDPR/CCPA consent before loading personalized ads in
 * many regions. Loading ads first and asking later is grounds for rejection
 * during AdMob account review.
 *
 * Flow: request consent info → if a form is required, show it → callback
 * fires with `canRequestAds = true` once the user has answered (or if no form
 * is needed in their region).
 */
class ConsentManager(private val activity: Activity) {

    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(activity)

    val canRequestAds: Boolean get() = consentInformation.canRequestAds()

    fun gatherConsent(onConsentResolved: (canRequestAds: Boolean) -> Unit) {
        val params = ConsentRequestParameters.Builder()
            // tagForUnderAgeOfConsent(false) is the default; left explicit for clarity.
            .setTagForUnderAgeOfConsent(false)
            .build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "consent form error: ${formError.message}")
                    }
                    // We always proceed — canRequestAds() reflects whatever the user
                    // chose (or that no form was required for their jurisdiction).
                    onConsentResolved(consentInformation.canRequestAds())
                }
            },
            { requestError ->
                Log.w(TAG, "consent info update failed: ${requestError.message}")
                // Network or config failure: fall back to whatever cached state says.
                onConsentResolved(consentInformation.canRequestAds())
            }
        )
    }

    companion object {
        private const val TAG = "ConsentManager"
    }
}
