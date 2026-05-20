package com.doodlepop.app

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Loads an interstitial once and shows it on demand, then reloads.
 *
 * Why a manager instead of inline code: an InterstitialAd is single-shot —
 * after one show() the reference is dead and you have to load a fresh one.
 * Centralizing the load/show/reload cycle keeps the activity readable and
 * makes the cooldown rule (one ad per N seconds) easy to enforce.
 */
class InterstitialAdManager(
    private val context: Context,
    private val unitId: String,
    // 60s cooldown — AdMob policy discourages back-to-back interstitials and
    // users will rage-quit if every save triggers an ad.
    private val minIntervalMs: Long = 60_000L,
) {
    private var ad: InterstitialAd? = null
    private var loading = false
    private var lastShownAt = 0L

    fun preload() {
        if (loading || ad != null) return
        loading = true
        InterstitialAd.load(
            context,
            unitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: InterstitialAd) {
                    ad = loaded
                    loading = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    ad = null
                    loading = false
                    Log.w(TAG, "interstitial load failed: ${error.message}")
                }
            }
        )
    }

    /**
     * Show the ad if one is ready and cooldown has elapsed; otherwise run [onDone]
     * immediately. Either way [onDone] is invoked exactly once so the caller can
     * proceed with whatever post-ad action it had.
     */
    fun showIfReady(activity: Activity, onDone: () -> Unit) {
        val ready = ad
        val now = System.currentTimeMillis()
        val cooldownElapsed = (now - lastShownAt) >= minIntervalMs

        if (ready == null || !cooldownElapsed) {
            if (ready == null) preload()
            onDone()
            return
        }

        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                ad = null
                lastShownAt = System.currentTimeMillis()
                preload()
                onDone()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                ad = null
                preload()
                onDone()
            }
        }
        ready.show(activity)
    }

    companion object {
        private const val TAG = "InterstitialAd"
    }
}
