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
        private val TAG = "[${this::class.java.simpleName}]"
    }

    /**
     * Indicate whether GDPR currently applies to the user.
     */
    private var gdprApplies = false

    /**
     * A map of Helium's listeners for the corresponding Helium placements.
     */
    private var listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * The currently active Helium placement. Determined by the current show call.
     */
    private var activeHeliumPlacement: String? = null

    /**
     * The currently active Helium [AdLoadRequest] instance. Determined by the current show call.
     */
    private var activeHeliumLoadRequest: AdLoadRequest? = null

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
        // Since more than 1 creatives could have been cached prior to showing one, we need to know
        // the currently active Helium placement and AdLoadRequest instance in order to look up the
        // correct Helium listener for ad event notification purposes.
        activeHeliumPlacement = partnerAd.request.heliumPlacement
        activeHeliumLoadRequest = partnerAd.request

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
        activeHeliumPlacement = null
        activeHeliumLoadRequest = null
        listeners.clear()

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
            IronSource.setISDemandOnlyInterstitialListener(object :
                ISDemandOnlyInterstitialListener {
                override fun onInterstitialAdReady(placementName: String) {
                    // Make sure the loaded ad is for the correct placement.
                    if (IronSource.isISDemandOnlyInterstitialReady(request.partnerPlacement)) {
                        listeners[request.heliumPlacement] = listener

                        continuation.resume(
                            Result.success(
                                PartnerAd(ad = null, details = emptyMap(), request = request)
                            )
                        )
                    } else {
                        LogController.e("$TAG ironSource loaded an interstitial ad but for a different placement.")
                        continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR)))
                    }
                }

                override fun onInterstitialAdLoadFailed(
                    placementName: String,
                    ironSourceError: IronSourceError
                ) {
                    LogController.e(
                        "$TAG ironSource failed to load an interstitial ad for placement " +
                                "$placementName. Error code: ${ironSourceError.errorCode}"
                    )

                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }

                override fun onInterstitialAdOpened(placementName: String) {
                }

                override fun onInterstitialAdClosed(placementName: String) {
                    val activeListener = listeners.remove(activeHeliumPlacement)
                    activeListener?.onPartnerAdDismissed(
                        PartnerAd(
                            ad = null,
                            details = emptyMap(),
                            request = activeHeliumLoadRequest ?: request
                        ), null
                    ) ?: LogController.e(
                        "$TAG Unable to fire onPartnerAdDismissed for ironSource " +
                                "adapter. Listener is null."
                    )
                }

                override fun onInterstitialAdShowFailed(
                    placementName: String,
                    ironSourceError: IronSourceError
                ) {
                    LogController.e(
                        "$TAG ironSource failed to show an interstitial ad for placement " +
                                "$placementName. Error code: ${ironSourceError.errorCode}"
                    )
                }

                override fun onInterstitialAdClicked(placementName: String) {
                    val activeListener = listeners[activeHeliumPlacement]
                    activeListener?.onPartnerAdClicked(
                        PartnerAd(
                            ad = null,
                            details = emptyMap(),
                            request = activeHeliumLoadRequest ?: request
                        )
                    ) ?: LogController.e(
                        "$TAG Unable to fire onPartnerAdClicked for ironSource " +
                                "adapter. Listener is null."
                    )
                }
            })

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
            IronSource.setISDemandOnlyRewardedVideoListener(object :
                ISDemandOnlyRewardedVideoListener {
                override fun onRewardedVideoAdLoadSuccess(placementName: String) {
                    // Make sure the loaded ad is for the correct placement.
                    if (IronSource.isISDemandOnlyRewardedVideoAvailable(request.partnerPlacement)) {
                        listeners[request.heliumPlacement] = listener

                        continuation.resume(
                            Result.success(
                                PartnerAd(
                                    ad = null,
                                    details = emptyMap(),
                                    request = request
                                )
                            )
                        )
                    } else {
                        LogController.e(
                            "$TAG ironSource loaded a rewarded ad but for a different placement. " +
                                    "Failing ad request."
                        )
                        continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR)))
                    }
                }

                override fun onRewardedVideoAdLoadFailed(
                    placementName: String,
                    ironSourceError: IronSourceError
                ) {
                    LogController.e(
                        "$TAG ironSource failed to load a rewarded video ad for placement " +
                                "$placementName. Error code: ${ironSourceError.errorCode}"
                    )
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }

                override fun onRewardedVideoAdOpened(placementName: String) {
                }

                override fun onRewardedVideoAdClosed(placementName: String) {
                    val activeListener = listeners.remove(activeHeliumPlacement)
                    activeListener?.onPartnerAdDismissed(
                        PartnerAd(
                            ad = null,
                            details = emptyMap(),
                            request = activeHeliumLoadRequest ?: request
                        ), null
                    ) ?: LogController.e(
                        "$TAG Unable to fire onPartnerAdDismissed for ironSource adapter. Listener is null."
                    )
                }

                override fun onRewardedVideoAdShowFailed(
                    placementName: String,
                    ironSourceError: IronSourceError
                ) {
                    LogController.e(
                        "$TAG ironSource failed to show a rewarded video ad for placement " +
                                "$placementName. Error code: ${ironSourceError.errorCode}"
                    )
                }

                override fun onRewardedVideoAdClicked(placementName: String) {
                    val activeListener = listeners[activeHeliumPlacement]
                    activeListener?.onPartnerAdClicked(
                        PartnerAd(
                            ad = null,
                            details = emptyMap(),
                            request = activeHeliumLoadRequest ?: request
                        )
                    ) ?: LogController.e(
                        "$TAG Unable to fire onPartnerAdClicked for ironSource adapter. Listener is null."
                    )
                }

                override fun onRewardedVideoAdRewarded(placementName: String) {
                    val activeListener = listeners[activeHeliumPlacement]
                    activeListener?.onPartnerAdRewarded(
                        PartnerAd(
                            ad = null,
                            details = emptyMap(),
                            request = activeHeliumLoadRequest ?: request
                        ), Reward(
                            IronSource.getRewardedVideoPlacementInfo(placementName).rewardAmount, ""
                        )
                    ) ?: LogController.e(
                        "$TAG Unable to fire onPartnerAdRewarded for ironSource adapter. Listener is null."
                    )
                }
            })

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
    private fun showInterstitialAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return if (readyToShow(partnerAd.request.format, partnerAd.request.partnerPlacement)) {
            IronSource.showISDemandOnlyInterstitial(partnerAd.request.partnerPlacement)
            Result.success(partnerAd)
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
    private fun showRewardedAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return if (readyToShow(partnerAd.request.format, partnerAd.request.partnerPlacement)) {
            IronSource.showISDemandOnlyRewardedVideo(partnerAd.request.partnerPlacement)
            Result.success(partnerAd)
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
}
