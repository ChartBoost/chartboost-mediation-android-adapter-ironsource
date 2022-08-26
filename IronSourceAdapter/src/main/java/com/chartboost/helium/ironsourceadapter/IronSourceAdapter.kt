package com.chartboost.helium.ironsourceadapter

import android.app.Activity
import android.content.Context
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.LogController
import com.ironsource.mediationsdk.IronSource
import com.ironsource.mediationsdk.IronSource.AD_UNIT
import com.ironsource.mediationsdk.logger.IronSourceError
import com.ironsource.mediationsdk.sdk.ISDemandOnlyInterstitialListener
import com.ironsource.mediationsdk.sdk.ISDemandOnlyRewardedVideoListener
import com.ironsource.mediationsdk.utils.IronSourceUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Helium ironSource adapter.
 */
class IronSourceAdapter : PartnerAdapter {
    companion object {
        /**
         * Key for parsing the ironSource app key.
         */
        private const val APP_KEY_KEY = "app_key"

        /**
         * The tag used for log messages.
         */
        private val TAG = "[${IronSourceAdapter::class.java.simpleName}]"
    }

    /**
     * Lambda to be called for a successful ironSource ad show.
     */
    private var onShowSuccess: () -> Unit = {}

    /**
     * Lambda to be called for a failed ironSource ad show.
     */
    private var onShowFailure: () -> Unit = {}

    /**
     * Router that handles instance to singleton communication with ironSource.
     */
    private var router: IronSourceRouter = IronSourceRouter(this)

    /**
     * Indicate whether GDPR currently applies to the user.
     */
    private var gdprApplies = false

    /**
     * Get the ironSource SDK version.
     */
    override val partnerSdkVersion: String
        get() = IronSourceUtils.getSDKVersion()

    /**
     * Get the ironSource adapter version.
     *
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = BuildConfig.HELIUM_IRONSOURCE_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "ironsource"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "ironSource"

    /**
     * Initialize the ironSource SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize ironSource.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        return partnerConfiguration.credentials[APP_KEY_KEY]?.let { appKey ->
            IronSource.setMediationType("Helium $adapterVersion")
            // IronSource leaks this Activity via ContextProvider, but it only ever leaks one
            // Activity at a time, so this is probably okay.
            IronSource.initISDemandOnly(
                context,
                appKey,
                AD_UNIT.INTERSTITIAL,
                AD_UNIT.REWARDED_VIDEO
            )

            // This router is required to forward the singleton callbacks to the instance ones.
            IronSource.setISDemandOnlyInterstitialListener(router)
            IronSource.setISDemandOnlyRewardedVideoListener(router)

            Result.success(LogController.i("$TAG ironSource successfully initialized"))
        } ?: run {
            LogController.e("$TAG ironSource failed to initialize. Missing the app key.")
            Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
        }
    }

    /**
     * Save the current GDPR applicability state for later use.
     *
     * @param context The current [Context].
     * @param gdprApplies True if GDPR applies, false otherwise.
     */
    override fun setGdprApplies(context: Context, gdprApplies: Boolean) {
        this.gdprApplies = gdprApplies
    }

    /**
     * Notify ironSource of the user's GDPR consent status, if applicable.
     *
     * @param context The current [Context].
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(context: Context, gdprConsentStatus: GdprConsentStatus) {
        if (gdprApplies) {
            IronSource.setConsent(gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED)
        }
    }

    /**
     * Notify ironSource of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGivenCcpaConsent True if the user has given CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGivenCcpaConsent: Boolean,
        privacyString: String?
    ) {
        IronSource.setMetaData("do_not_sell", if (hasGivenCcpaConsent) "false" else "true")
    }

    /**
     * Notify ironSource of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        IronSource.setMetaData("is_child_directed", if (isSubjectToCoppa) "true" else "false")
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ) = emptyMap<String, String>()

    /**
     * Attempt to load an ironSource ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        return (context as? Activity)?.let { activity ->
            when (request.format) {
                AdFormat.INTERSTITIAL -> {
                    loadInterstitialAd(activity, request, partnerAdListener)
                }
                AdFormat.REWARDED -> {
                    loadRewardedAd(activity, request, partnerAdListener)
                }
                else -> {
                    LogController.e(
                        "$TAG ironSource is trying to load an unsupported ad format: " +
                                "${request.format}"
                    )
                    Result.failure(HeliumAdException(HeliumErrorCode.AD_FORMAT_NOT_SUPPORTED))
                }
            }
        } ?: run {
            LogController.e("$TAG ironSource failed to load an ad. Activity context is required.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }

    /**
     * Attempt to show the currently loaded ironSource ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        return when (partnerAd.request.format) {
            AdFormat.INTERSTITIAL -> showInterstitialAd(partnerAd)
            AdFormat.REWARDED -> showRewardedAd(partnerAd)
            else -> {
                LogController.e(
                    "$TAG ironSource is trying to show an unsupported ad format: " +
                            "${partnerAd.request.format}"
                )
                Result.failure(HeliumAdException(HeliumErrorCode.AD_FORMAT_NOT_SUPPORTED))
            }
        }
    }

    /**
     * Discard unnecessary ironSource ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the ironSource ad to be invalidated.
     *
     * @return Result.success(PartnerAd)
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        // There isn't a way to clear an ironSource ad, so this just returns success.

        return Result.success(partnerAd)
    }

    /**
     * Attempt to load an ironSource interstitial ad.
     *
     * @param activity The current [Activity].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     */
    private suspend fun loadInterstitialAd(
        activity: Activity,
        request: AdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val ironSourceInterstitialListener = object :
                ISDemandOnlyInterstitialListener {
                override fun onInterstitialAdReady(partnerPlacement: String) {
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                // This returns just the partner placement since we don't have
                                // access to the actual ad.
                                ad = partnerPlacement,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onInterstitialAdLoadFailed(
                    partnerPlacement: String,
                    ironSourceError: IronSourceError
                ) {
                    LogController.e(
                        "$TAG ironSource failed to load an interstitial ad for placement " +
                                "$partnerPlacement. Error code: ${ironSourceError.errorCode}"
                    )

                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }

                override fun onInterstitialAdOpened(partnerPlacement: String) {
                    // Show success lambda handled in the router
                }

                override fun onInterstitialAdClosed(partnerPlacement: String) {
                    listener.onPartnerAdDismissed(
                        PartnerAd(
                            ad = partnerPlacement,
                            details = emptyMap(),
                            request = request
                        ), null
                    )
                }

                override fun onInterstitialAdShowFailed(
                    partnerPlacement: String,
                    ironSourceError: IronSourceError
                ) {
                    LogController.e(
                        "$TAG ironSource failed to show an interstitial ad for placement " +
                                "$partnerPlacement. Error code: ${ironSourceError.errorCode}"
                    )
                    // Show failure lambda handled in the router
                }

                override fun onInterstitialAdClicked(partnerPlacement: String) {
                    listener.onPartnerAdClicked(
                        PartnerAd(
                            ad = partnerPlacement,
                            details = emptyMap(),
                            request = request
                        )
                    )
                }
            }

            router.subscribeInterstitialListener(
                request.partnerPlacement,
                ironSourceInterstitialListener
            )
            IronSource.loadISDemandOnlyInterstitial(activity, request.partnerPlacement)
        }
    }

    /**
     * Attempt to load an ironSource rewarded ad.
     *
     * @param activity The current [Activity].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     */
    private suspend fun loadRewardedAd(
        activity: Activity,
        request: AdLoadRequest,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val ironSourceRewardedVideoListener = object :
                ISDemandOnlyRewardedVideoListener {
                override fun onRewardedVideoAdLoadSuccess(partnerPlacement: String) {
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                // This returns just the partner placement since we don't have
                                // access to the actual ad.
                                ad = partnerPlacement,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onRewardedVideoAdLoadFailed(
                    partnerPlacement: String,
                    ironSourceError: IronSourceError
                ) {
                    LogController.e(
                        "$TAG ironSource failed to load a rewarded video ad for placement " +
                                "$partnerPlacement. Error code: ${ironSourceError.errorCode}"
                    )
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }

                override fun onRewardedVideoAdOpened(partnerPlacement: String) {
                    // Show success lambda handled in the router
                }

                override fun onRewardedVideoAdClosed(partnerPlacement: String) {
                    listener.onPartnerAdDismissed(
                        PartnerAd(
                            ad = partnerPlacement,
                            details = emptyMap(),
                            request = request
                        ), null
                    )
                }

                override fun onRewardedVideoAdShowFailed(
                    partnerPlacement: String,
                    ironSourceError: IronSourceError
                ) {
                    LogController.e(
                        "$TAG ironSource failed to show a rewarded video ad for placement " +
                                "$partnerPlacement. Error code: ${ironSourceError.errorCode}"
                    )
                    // Show failure lambda handled in the router
                }

                override fun onRewardedVideoAdClicked(partnerPlacement: String) {
                    listener.onPartnerAdClicked(
                        PartnerAd(
                            ad = partnerPlacement,
                            details = emptyMap(),
                            request = request
                        )
                    )
                }

                override fun onRewardedVideoAdRewarded(partnerPlacement: String) {
                    listener.onPartnerAdRewarded(
                        PartnerAd(
                            ad = partnerPlacement,
                            details = emptyMap(),
                            request = request
                        ),
                        IronSource.getRewardedVideoPlacementInfo(partnerPlacement)
                            ?.let { Reward(it.rewardAmount, it.rewardName) } ?: Reward(0, "")
                    )
                }
            }

            router.subscribeRewardedListener(
                request.partnerPlacement,
                ironSourceRewardedVideoListener
            )
            IronSource.loadISDemandOnlyRewardedVideo(activity, request.partnerPlacement)
        }
    }

    /**
     * Attempt to show an ironSource interstitial ad.
     *
     * @param partnerAd The [PartnerAd] object containing the ironSource ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return if (readyToShow(partnerAd.request.format, partnerAd.request.partnerPlacement)) {
            return suspendCancellableCoroutine { continuation ->
                onShowSuccess = {
                    continuation.resume(Result.success(partnerAd))
                }

                onShowFailure = {
                    LogController.e(
                        "$TAG Failed to show an ironSource interstitial ad for placement " +
                                partnerAd.request.partnerPlacement
                    )
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR)))
                }

                IronSource.showISDemandOnlyInterstitial(partnerAd.request.partnerPlacement)
            }
        } else {
            LogController.e(
                "$TAG ironSource is trying to show an interstitial ad that isn't ready: " +
                        partnerAd.request.partnerPlacement
            )
            Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
        }
    }

    /**
     * Attempt to show an ironSource rewarded ad.
     *
     * @param partnerAd The [PartnerAd] object containing the ironSource ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showRewardedAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return if (readyToShow(partnerAd.request.format, partnerAd.request.partnerPlacement)) {
            return suspendCancellableCoroutine { continuation ->
                onShowSuccess = {
                    continuation.resume(Result.success(partnerAd))
                }

                onShowFailure = {
                    LogController.e(
                        "$TAG Failed to show an ironSource rewarded ad for placement " +
                                partnerAd.request.partnerPlacement
                    )
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR)))
                }

                IronSource.showISDemandOnlyRewardedVideo(partnerAd.request.partnerPlacement)
            }
        } else {
            LogController.e(
                "$TAG ironSource is trying to show a rewarded ad that isn't ready: " +
                        partnerAd.request.partnerPlacement
            )
            Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
        }
    }

    /**
     * Determine if the currently loaded ironSource ad is ready to be shown.
     *
     * @param format The ad format to check.
     * @param placement The placement to check.
     *
     * @return True if the ad is ready to be shown, false otherwise.
     */
    private fun readyToShow(format: AdFormat, placement: String): Boolean {
        return when (format) {
            AdFormat.INTERSTITIAL -> return IronSource.isISDemandOnlyInterstitialReady(
                placement
            )
            AdFormat.REWARDED -> return IronSource.isISDemandOnlyRewardedVideoAvailable(
                placement
            )
            else -> false
        }
    }

    /**
     * Since ironSource has a singleton listener, Helium needs a router to sort the callbacks that
     * result from each load/show attempt.
     */
    private class IronSourceRouter(val adapter: IronSourceAdapter) :
        ISDemandOnlyInterstitialListener,
        ISDemandOnlyRewardedVideoListener {

        /**
         * Map of ironSource placements to interstitial listeners.
         */
        val interstitialListenersMap: MutableMap<String, ISDemandOnlyInterstitialListener> =
            mutableMapOf()

        /**
         * Map of ironSource placements to rewarded video listeners.
         */
        val rewardedListenersMap: MutableMap<String, ISDemandOnlyRewardedVideoListener> =
            mutableMapOf()

        /**
         * Adds an interstitial listener to this router. These are automatically removed on ad
         * load failure, show failure, and close.
         */
        fun subscribeInterstitialListener(
            partnerPlacement: String,
            listener: ISDemandOnlyInterstitialListener
        ) {
            interstitialListenersMap[partnerPlacement] = listener
        }

        /**
         * Adds a rewarded video listener to this router. These are automatically removed on ad
         * load failure, show failure, and close.
         */
        fun subscribeRewardedListener(
            partnerPlacement: String,
            listener: ISDemandOnlyRewardedVideoListener
        ) {
            rewardedListenersMap[partnerPlacement] = listener
        }

        override fun onInterstitialAdReady(partnerPlacement: String) {
            interstitialListenersMap[partnerPlacement]?.onInterstitialAdReady(partnerPlacement)
                ?: LogController.w("Lost ironSource listener on interstitial ad load success.")
        }

        override fun onInterstitialAdLoadFailed(
            partnerPlacement: String,
            ironSourceError: IronSourceError
        ) {
            interstitialListenersMap.remove(partnerPlacement)
                ?.onInterstitialAdLoadFailed(partnerPlacement, ironSourceError)
                ?: LogController.w("Lost ironSource listener on interstitial ad load failed with error $ironSourceError.")
        }

        override fun onInterstitialAdOpened(partnerPlacement: String) {
            interstitialListenersMap[partnerPlacement]?.onInterstitialAdOpened(partnerPlacement)
                ?: LogController.w("Lost ironSource listener on interstitial ad opened.")
            adapter.onShowSuccess()
        }

        override fun onInterstitialAdClosed(partnerPlacement: String) {
            interstitialListenersMap.remove(partnerPlacement)
                ?.onInterstitialAdClosed(partnerPlacement)
                ?: LogController.w("Lost ironSource listener on interstitial ad closed.")
        }

        override fun onInterstitialAdShowFailed(
            partnerPlacement: String,
            ironSourceError: IronSourceError
        ) {
            interstitialListenersMap.remove(partnerPlacement)
                ?.onInterstitialAdShowFailed(partnerPlacement, ironSourceError)
                ?: LogController.w("Lost ironSource listener on interstitial ad show failed with error $ironSourceError.")
            adapter.onShowFailure()
        }

        override fun onInterstitialAdClicked(partnerPlacement: String) {
            interstitialListenersMap[partnerPlacement]?.onInterstitialAdClicked(partnerPlacement)
                ?: LogController.w("Lost ironSource listener on ad clicked.")
        }

        override fun onRewardedVideoAdLoadSuccess(partnerPlacement: String) {
            rewardedListenersMap[partnerPlacement]?.onRewardedVideoAdLoadSuccess(partnerPlacement)
                ?: LogController.w("Lost ironSource listener on rewarded ad load success.")
        }

        override fun onRewardedVideoAdLoadFailed(
            partnerPlacement: String,
            ironSourceError: IronSourceError
        ) {
            rewardedListenersMap.remove(partnerPlacement)
                ?.onRewardedVideoAdLoadFailed(partnerPlacement, ironSourceError) ?: LogController.w(
                "Lost ironSource listener on rewarded ad load failed."
            )
        }

        override fun onRewardedVideoAdOpened(partnerPlacement: String) {
            rewardedListenersMap[partnerPlacement]?.onRewardedVideoAdOpened(partnerPlacement)
                ?: LogController.w("Lost ironSource listener on rewarded ad opened.")
            adapter.onShowSuccess()
        }

        override fun onRewardedVideoAdClosed(partnerPlacement: String) {
            rewardedListenersMap.remove(partnerPlacement)?.onRewardedVideoAdClosed(partnerPlacement)
                ?: LogController.w("Lost ironSource listener on rewarded ad closed.")
        }

        override fun onRewardedVideoAdShowFailed(
            partnerPlacement: String,
            ironSourceError: IronSourceError
        ) {
            rewardedListenersMap.remove(partnerPlacement)
                ?.onRewardedVideoAdShowFailed(partnerPlacement, ironSourceError) ?: LogController.w(
                "Lost ironSource listener on rewarded ad show failed."
            )
            adapter.onShowFailure()
        }

        override fun onRewardedVideoAdClicked(partnerPlacement: String) {
            rewardedListenersMap[partnerPlacement]?.onRewardedVideoAdClicked(partnerPlacement)
                ?: LogController.w("Lost ironSource listener on rewarded ad clicked.")
        }

        override fun onRewardedVideoAdRewarded(partnerPlacement: String) {
            rewardedListenersMap[partnerPlacement]?.onRewardedVideoAdRewarded(partnerPlacement)
                ?: LogController.w("Lost ironSource listener on rewarded ad rewarded.")
        }
    }
}
