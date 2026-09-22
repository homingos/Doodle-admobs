package com.doodlepop.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.VersionInfo
import com.google.android.gms.ads.mediation.Adapter
import com.google.android.gms.ads.mediation.InitializationCompleteCallback
import com.google.android.gms.ads.mediation.MediationAdLoadCallback
import com.google.android.gms.ads.mediation.MediationConfiguration
import com.google.android.gms.ads.mediation.MediationInterstitialAd
import com.google.android.gms.ads.mediation.MediationInterstitialAdCallback
import com.google.android.gms.ads.mediation.MediationInterstitialAdConfiguration
import org.json.JSONObject

/**
 * AdMob Custom Event adapter that renders Vertex's interactive HTML5 creative
 * as an interstitial impression.
 *
 * AdMob console wiring (one-time, in https://apps.admob.com/):
 *   Apps → Doodlepop → Ad units → <interstitial unit> → Mediation tab
 *     → Add custom event (or "Add ad source group" → custom event)
 *       - Class Name: com.doodlepop.app.VertexCustomEventInterstitial
 *       - Parameter (JSON): {"campaign":"5svwQBRlBwdG",
 *                            "workerBase":"https://vertex-player.appless.workers.dev",
 *                            "landing":"https://your-landing-page.example",
 *                            "duration":"00:00:30"}
 *     → Set CPM high enough to win the waterfall in test traffic.
 *
 * Runtime flow:
 *   1. App calls InterstitialAd.load(unitId) as usual.
 *   2. AdMob SDK reaches our custom event entry → instantiates this class by
 *      name and calls loadInterstitialAd().
 *   3. We parse the parameter JSON and immediately call back "ad loaded" (the
 *      actual creative load happens inside the Activity's WebView when the
 *      user is about to see it — no point pre-fetching).
 *   4. App calls show() → AdMob calls showAd(context) → we launch
 *      VertexInterstitialActivity.
 *   5. Activity dismisses → onDestroy fires onAdClosed via the static
 *      activeCallback.
 */
class VertexCustomEventInterstitial : Adapter(), MediationInterstitialAd {

    private var workerBase: String = "https://vertex-player.appless.workers.dev"
    private var campaign: String = ""
    private var landingUrl: String = ""
    private var duration: String = "00:00:30"

    private var loadCallback: MediationInterstitialAdCallback? = null

    override fun getVersionInfo(): VersionInfo = VersionInfo(1, 0, 0)
    override fun getSDKVersionInfo(): VersionInfo = VersionInfo(1, 0, 0)

    override fun initialize(
        context: Context,
        initializationCompleteCallback: InitializationCompleteCallback,
        mediationConfigurations: MutableList<MediationConfiguration>
    ) {
        initializationCompleteCallback.onInitializationSucceeded()
    }

    override fun loadInterstitialAd(
        mediationInterstitialAdConfiguration: MediationInterstitialAdConfiguration,
        callback: MediationAdLoadCallback<MediationInterstitialAd, MediationInterstitialAdCallback>
    ) {
        val parameter = mediationInterstitialAdConfiguration
            .serverParameters
            .getString("parameter")
            .orEmpty()

        try {
            val json = JSONObject(parameter)
            workerBase = json.optString("workerBase", workerBase)
            campaign = json.optString("campaign", "")
            landingUrl = json.optString("landing", "")
            duration = json.optString("duration", "00:00:30")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse parameter JSON '$parameter': $e")
        }

        Log.i(TAG, "loadInterstitialAd · workerBase=$workerBase campaign=$campaign")

        if (campaign.isEmpty()) {
            callback.onFailure(
                AdError(
                    1,
                    "Missing 'campaign' in custom-event parameter JSON",
                    "VertexCustomEventInterstitial"
                )
            )
            return
        }

        loadCallback = callback.onSuccess(this)
    }

    override fun showAd(context: Context) {
        Log.i(TAG, "showAd · launching VertexInterstitialActivity")

        activeCallback = loadCallback

        val intent = Intent(context, VertexInterstitialActivity::class.java).apply {
            putExtra(VertexInterstitialActivity.EXTRA_WORKER_BASE, workerBase)
            putExtra(VertexInterstitialActivity.EXTRA_CAMPAIGN, campaign)
            putExtra(VertexInterstitialActivity.EXTRA_LANDING, landingUrl)
            putExtra(VertexInterstitialActivity.EXTRA_DURATION, duration)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        loadCallback?.let {
            it.onAdOpened()
            it.reportAdImpression()
        }
    }

    companion object {
        private const val TAG = "VertexCustomEvent"

        /**
         * Shared with [VertexInterstitialActivity] so the Activity can fire
         * onAdClosed via the same callback the adapter received from AdMob.
         * Cleared in the Activity's onDestroy.
         */
        @JvmStatic
        var activeCallback: MediationInterstitialAdCallback? = null
    }
}
