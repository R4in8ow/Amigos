package io.nekohasekai.sagernet.utils

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.r4in8ow.amigos.BuildConfig
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs

object AmigosAds {

    private const val REAL_BANNER_ID = "ca-app-pub-3961408285604914/4138218760"
    private const val REAL_INTERSTITIAL_ID = "ca-app-pub-3961408285604914/4194234090"
    private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"

    private const val INTERSTITIAL_COOLDOWN_MS = 3 * 60 * 1000L

    val bannerAdUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_BANNER_ID else REAL_BANNER_ID

    private val interstitialAdUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_INTERSTITIAL_ID else REAL_INTERSTITIAL_ID

    fun isAdsEnabled(): Boolean = DataStore.amigosFreeMode

    fun init(context: Context) {
        MobileAds.initialize(context)
    }

    private var interstitialAd: InterstitialAd? = null

    fun preloadInterstitial(context: Context) {
        if (!isAdsEnabled()) return
        if (interstitialAd != null) return
        InterstitialAd.load(
            context,
            interstitialAdUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    Logs.w("AmigosAds: interstitial load failed: ${error.message}")
                }
            }
        )
    }

    fun showInterstitialIfDue(activity: Activity) {
        if (!isAdsEnabled()) return
        if (activity.isFinishing || activity.isDestroyed) return
        val now = System.currentTimeMillis()
        if (now - DataStore.amigosLastInterstitial < INTERSTITIAL_COOLDOWN_MS) return
        val ad = interstitialAd ?: return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                preloadInterstitial(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                Logs.w("AmigosAds: interstitial show failed: ${error.message}")
                preloadInterstitial(activity)
            }
        }
        DataStore.amigosLastInterstitial = now
        try {
            ad.show(activity)
        } catch (e: Exception) {
            Logs.w("AmigosAds: interstitial show threw: ${e.message}")
            interstitialAd = null
        }
    }
}
