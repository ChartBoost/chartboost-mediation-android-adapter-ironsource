/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.ironsourceadapter

import android.app.Activity
import android.content.Context
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USP_CONSENT_GRANTED
import com.chartboost.core.consent.*
import com.ironsource.mediationsdk.IronSource
import com.ironsource.mediationsdk.IronSource.AD_UNIT
import com.ironsource.mediationsdk.demandOnly.ISDemandOnlyInterstitialListener
import com.ironsource.mediationsdk.demandOnly.ISDemandOnlyRewardedVideoListener
import com.ironsource.mediationsdk.logger.IronSourceError
import com.ironsource.mediationsdk.logger.IronSourceError.AUCTION_ERROR_TIMED_OUT
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_BN_INSTANCE_LOAD_AUCTION_FAILED
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_BN_INSTANCE_LOAD_EMPTY_SERVER_DATA
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_BN_INSTANCE_LOAD_TIMEOUT
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_BN_LOAD_NO_CONFIG
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_BN_LOAD_NO_FILL
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_CODE_NO_ADS_TO_SHOW
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_DO_IS_LOAD_TIMED_OUT
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_DO_RV_LOAD_TIMED_OUT
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_IS_LOAD_NO_FILL
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_NO_INTERNET_CONNECTION
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_RV_INIT_FAILED_TIMEOUT
import com.ironsource.mediationsdk.logger.IronSourceError.ERROR_RV_LOAD_NO_FILL
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation ironSource adapter.
 */
class IronSourceAdapter : PartnerAdapter {
    companion object {
        /**
         * Key for parsing the ironSource app key.
         */
        private const val APP_KEY_KEY = "app_key"
    }

    /**
     * The ironSource adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = IronSourceAdapterConfiguration

    /**
     * Lambda to be called for a successful ironSource ad show.
     */
    private var onShowSuccess: () -> Unit = {}

    /**
     * Lambda to be called for a failed ironSource ad show.
     */
    private var onShowFailure: (error: IronSourceError) -> Unit = {}

    /**
     * Router that handles instance to singleton communication with ironSource.
     */
    private var router: IronSourceRouter = IronSourceRouter(this)

    /**
     * Initialize the ironSource SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize ironSource.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> {
        PartnerLogController.log(SETUP_STARTED)

        return Json.decodeFromJsonElement<String>(
            (partnerConfiguration.credentials as JsonObject).getValue(APP_KEY_KEY),
        ).trim()
            .takeIf { it.isNotEmpty() }?.let { appKey ->
                IronSource.setMediationType("Chartboost")
                // IronSource leaks this Activity via ContextProvider, but it only ever leaks one
                // Activity at a time, so this is probably okay.
                IronSource.initISDemandOnly(
                    context,
                    appKey,
                    AD_UNIT.INTERSTITIAL,
                    AD_UNIT.REWARDED_VIDEO,
                )

                // This router is required to forward the singleton callbacks to the instance ones.
                IronSource.setISDemandOnlyInterstitialListener(router)
                IronSource.setISDemandOnlyRewardedVideoListener(router)

                PartnerLogController.log(SETUP_SUCCEEDED)
                Result.success(emptyMap())
            } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Missing the app key.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials))
        }
    }

    /**
     * Notify ironSource of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        IronSource.setMetaData("is_child_directed", if (isUserUnderage) "true" else "false")
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return Result.success(emptyMap())
    }

    /**
     * Attempt to load an ironSource ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return (context as? Activity)?.let { activity ->
            when (request.format) {
                PartnerAdFormats.INTERSTITIAL -> {
                    loadInterstitialAd(activity, request, partnerAdListener)
                }
                PartnerAdFormats.REWARDED -> {
                    loadRewardedAd(activity, request, partnerAdListener)
                }
                else -> {
                    PartnerLogController.log(LOAD_FAILED)
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
                }
            }
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "Activity context is required.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.ActivityNotFound))
        }
    }

    /**
     * Attempt to show the currently loaded ironSource ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return when (partnerAd.request.format) {
            PartnerAdFormats.INTERSTITIAL -> showInterstitialAd(partnerAd)
            PartnerAdFormats.REWARDED -> showRewardedAd(partnerAd)
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
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
        PartnerLogController.log(INVALIDATE_STARTED)
        PartnerLogController.log(INVALIDATE_SUCCEEDED)
        return Result.success(partnerAd)
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>,
    ) {
        consents[ConsentKeys.GDPR_CONSENT_GIVEN]?.let {
            if (it == ConsentValues.DOES_NOT_APPLY) {
                return@let
            }
            PartnerLogController.log(
                when (it) {
                    ConsentValues.GRANTED -> GDPR_CONSENT_GRANTED
                    ConsentValues.DENIED -> GDPR_CONSENT_DENIED
                    else -> GDPR_CONSENT_UNKNOWN
                },
            )

            IronSource.setConsent(it == ConsentValues.GRANTED)
        }

        consents[ConsentKeys.USP]?.let {
            val hasGrantedUspConsent = ConsentManagementPlatform.getUspConsentFromUspString(it)
            PartnerLogController.log(
                if (hasGrantedUspConsent) {
                    USP_CONSENT_GRANTED
                } else {
                    USP_CONSENT_DENIED
                },
            )

            IronSource.setMetaData("do_not_sell", if (hasGrantedUspConsent) "false" else "true")
        }
    }

    /**
     * Attempt to load an ironSource interstitial ad.
     *
     * @param activity The current [Activity].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     */
    private suspend fun loadInterstitialAd(
        activity: Activity,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            val ironSourceInterstitialListener =
                object :
                    ISDemandOnlyInterstitialListener {
                    override fun onInterstitialAdReady(partnerPlacement: String) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(
                            Result.success(
                                PartnerAd(
                                    // This returns just the partner placement since we don't have
                                    // access to the actual ad.
                                    ad = partnerPlacement,
                                    details = emptyMap(),
                                    request = request,
                                ),
                            ),
                        )
                    }

                    override fun onInterstitialAdLoadFailed(
                        partnerPlacement: String,
                        ironSourceError: IronSourceError,
                    ) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            "Placement $partnerPlacement. Error code: ${ironSourceError.errorCode}",
                        )

                        resumeOnce(
                            Result.failure(
                                ChartboostMediationAdException(
                                    getChartboostMediationError(
                                        ironSourceError,
                                    ),
                                ),
                            ),
                        )
                    }

                    override fun onInterstitialAdOpened(partnerPlacement: String) {
                        // Show success lambda handled in the router
                        listener.onPartnerAdImpression(
                            PartnerAd(
                                ad = partnerPlacement,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }

                    override fun onInterstitialAdClosed(partnerPlacement: String) {
                        PartnerLogController.log(DID_DISMISS)
                        listener.onPartnerAdDismissed(
                            PartnerAd(
                                ad = partnerPlacement,
                                details = emptyMap(),
                                request = request,
                            ),
                            null,
                        )
                    }

                    override fun onInterstitialAdShowFailed(
                        partnerPlacement: String,
                        ironSourceError: IronSourceError,
                    ) {
                        PartnerLogController.log(
                            SHOW_FAILED,
                            "Placement $partnerPlacement. Error code: ${ironSourceError.errorCode}",
                        )
                        // Show failure lambda handled in the router
                    }

                    override fun onInterstitialAdClicked(partnerPlacement: String) {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(
                            PartnerAd(
                                ad = partnerPlacement,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }
                }

            if (router.containsInterstitialListener(request.partnerPlacement)) {
                PartnerLogController.log(
                    LOAD_FAILED,
                    "ironSource interstitial placement ${request.partnerPlacement} is already attached to another Chartboost placement.",
                )
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(ChartboostMediationError.LoadError.Aborted),
                    ),
                )
                return@suspendCancellableCoroutine
            }

            router.subscribeInterstitialListener(
                request.partnerPlacement,
                ironSourceInterstitialListener,
            )
            IronSource.loadISDemandOnlyInterstitial(activity, request.partnerPlacement)
        }
    }

    /**
     * Attempt to load an ironSource rewarded ad.
     *
     * @param activity The current [Activity].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     */
    private suspend fun loadRewardedAd(
        activity: Activity,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            val ironSourceRewardedVideoListener =
                object :
                    ISDemandOnlyRewardedVideoListener {
                    override fun onRewardedVideoAdLoadSuccess(partnerPlacement: String) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(
                            Result.success(
                                PartnerAd(
                                    // This returns just the partner placement since we don't have
                                    // access to the actual ad.
                                    ad = partnerPlacement,
                                    details = emptyMap(),
                                    request = request,
                                ),
                            ),
                        )
                    }

                    override fun onRewardedVideoAdLoadFailed(
                        partnerPlacement: String,
                        ironSourceError: IronSourceError,
                    ) {
                        PartnerLogController.log(
                            LOAD_FAILED,
                            "Placement $partnerPlacement. Error code: ${ironSourceError.errorCode}",
                        )
                        resumeOnce(
                            Result.failure(
                                ChartboostMediationAdException(
                                    getChartboostMediationError(
                                        ironSourceError,
                                    ),
                                ),
                            ),
                        )
                    }

                    override fun onRewardedVideoAdOpened(partnerPlacement: String) {
                        // Show success lambda handled in the router
                        listener.onPartnerAdImpression(
                            PartnerAd(
                                ad = partnerPlacement,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }

                    override fun onRewardedVideoAdClosed(partnerPlacement: String) {
                        PartnerLogController.log(DID_DISMISS)
                        listener.onPartnerAdDismissed(
                            PartnerAd(
                                ad = partnerPlacement,
                                details = emptyMap(),
                                request = request,
                            ),
                            null,
                        )
                    }

                    override fun onRewardedVideoAdShowFailed(
                        partnerPlacement: String,
                        ironSourceError: IronSourceError,
                    ) {
                        PartnerLogController.log(
                            SHOW_FAILED,
                            "Placement $partnerPlacement. Error code: ${ironSourceError.errorCode}",
                        )
                        // Show failure lambda handled in the router
                    }

                    override fun onRewardedVideoAdClicked(partnerPlacement: String) {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(
                            PartnerAd(
                                ad = partnerPlacement,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }

                    override fun onRewardedVideoAdRewarded(partnerPlacement: String) {
                        PartnerLogController.log(DID_REWARD)
                        listener.onPartnerAdRewarded(
                            PartnerAd(
                                ad = partnerPlacement,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }
                }

            if (router.containsRewardedListener(request.partnerPlacement)) {
                PartnerLogController.log(
                    LOAD_FAILED,
                    "ironSource rewarded placement ${request.partnerPlacement} is already attached to another Chartboost placement.",
                )
                resumeOnce(
                    Result.failure(
                        ChartboostMediationAdException(ChartboostMediationError.LoadError.Aborted),
                    ),
                )
                return@suspendCancellableCoroutine
            }

            router.subscribeRewardedListener(
                request.partnerPlacement,
                ironSourceRewardedVideoListener,
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
                fun resumeOnce(result: Result<PartnerAd>) {
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                onShowSuccess = {
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    resumeOnce(Result.success(partnerAd))
                }

                onShowFailure = {
                    PartnerLogController.log(
                        SHOW_FAILED,
                        "Placement ${partnerAd.request.partnerPlacement}",
                    )

                    resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(it))))
                }

                IronSource.showISDemandOnlyInterstitial(partnerAd.request.partnerPlacement)
            }
        } else {
            PartnerLogController.log(SHOW_FAILED, "Ad isn't ready.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
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
                fun resumeOnce(result: Result<PartnerAd>) {
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                onShowSuccess = {
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    resumeOnce(Result.success(partnerAd))
                }

                onShowFailure = {
                    PartnerLogController.log(
                        SHOW_FAILED,
                        "Placement ${partnerAd.request.partnerPlacement}",
                    )
                    resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(it))))
                }

                IronSource.showISDemandOnlyRewardedVideo(partnerAd.request.partnerPlacement)
            }
        } else {
            PartnerLogController.log(SHOW_FAILED, "Ad isn't ready.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
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
    private fun readyToShow(
        format: PartnerAdFormat,
        placement: String,
    ): Boolean {
        return when (format) {
            PartnerAdFormats.INTERSTITIAL -> return IronSource.isISDemandOnlyInterstitialReady(
                placement,
            )
            PartnerAdFormats.REWARDED -> return IronSource.isISDemandOnlyRewardedVideoAvailable(
                placement,
            )
            else -> false
        }
    }

    /**
     * Convert a given ironSource error code into a [ChartboostMediationError].
     *
     * @param error The ironSource error code.
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getChartboostMediationError(error: IronSourceError) =
        when (error.errorCode) {
            ERROR_CODE_NO_ADS_TO_SHOW, ERROR_BN_LOAD_NO_FILL, ERROR_RV_LOAD_NO_FILL, ERROR_IS_LOAD_NO_FILL -> ChartboostMediationError.LoadError.NoFill
            ERROR_NO_INTERNET_CONNECTION -> ChartboostMediationError.OtherError.NoConnectivity
            ERROR_BN_LOAD_NO_CONFIG -> ChartboostMediationError.LoadError.InvalidAdRequest
            ERROR_BN_INSTANCE_LOAD_AUCTION_FAILED -> ChartboostMediationError.LoadError.AuctionNoBid
            ERROR_BN_INSTANCE_LOAD_EMPTY_SERVER_DATA -> ChartboostMediationError.LoadError.InvalidBidResponse
            ERROR_RV_INIT_FAILED_TIMEOUT -> ChartboostMediationError.InitializationError.Timeout
            ERROR_DO_IS_LOAD_TIMED_OUT, ERROR_BN_INSTANCE_LOAD_TIMEOUT, ERROR_DO_RV_LOAD_TIMED_OUT -> ChartboostMediationError.LoadError.AdRequestTimeout
            AUCTION_ERROR_TIMED_OUT -> ChartboostMediationError.LoadError.AdRequestTimeout
            else -> ChartboostMediationError.OtherError.PartnerError
        }

    /**
     * Since ironSource has a singleton listener, Chartboost Mediation needs a router to sort the
     * callbacks that result from each load/show attempt.
     */
    private class IronSourceRouter(val adapter: IronSourceAdapter) :
        ISDemandOnlyInterstitialListener,
        ISDemandOnlyRewardedVideoListener {
        /**
         * Map of ironSource placements to interstitial listeners.
         */
        private val interstitialListenersMap: MutableMap<String, ISDemandOnlyInterstitialListener> =
            mutableMapOf()

        /**
         * Map of ironSource placements to rewarded video listeners.
         */
        private val rewardedListenersMap: MutableMap<String, ISDemandOnlyRewardedVideoListener> =
            mutableMapOf()

        /**
         * Checks to see if the router currently is listening for this ironSource placement.
         * @return True if the placement is already being listened to, false otherwise.
         */
        fun containsInterstitialListener(ironSourcePlacement: String): Boolean {
            return interstitialListenersMap.contains(ironSourcePlacement)
        }

        /**
         * Adds an interstitial listener to this router. These are automatically removed on ad
         * load failure, show failure, and close.
         */
        fun subscribeInterstitialListener(
            partnerPlacement: String,
            listener: ISDemandOnlyInterstitialListener,
        ) {
            interstitialListenersMap[partnerPlacement] = listener
        }

        /**
         * Checks to see if the router currently is listening for this ironSource placement.
         * @return True if the placement is already being listened to, false otherwise.
         */
        fun containsRewardedListener(ironSourcePlacement: String): Boolean {
            return rewardedListenersMap.contains(ironSourcePlacement)
        }

        /**
         * Adds a rewarded video listener to this router. These are automatically removed on ad
         * load failure, show failure, and close.
         */
        fun subscribeRewardedListener(
            partnerPlacement: String,
            listener: ISDemandOnlyRewardedVideoListener,
        ) {
            rewardedListenersMap[partnerPlacement] = listener
        }

        override fun onInterstitialAdReady(partnerPlacement: String) {
            interstitialListenersMap[partnerPlacement]?.onInterstitialAdReady(partnerPlacement)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on interstitial ad load success.",
                )
        }

        override fun onInterstitialAdLoadFailed(
            partnerPlacement: String,
            ironSourceError: IronSourceError,
        ) {
            interstitialListenersMap.remove(partnerPlacement)
                ?.onInterstitialAdLoadFailed(partnerPlacement, ironSourceError)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on interstitial ad load failed with error $ironSourceError.",
                )
        }

        override fun onInterstitialAdOpened(partnerPlacement: String) {
            interstitialListenersMap[partnerPlacement]?.onInterstitialAdOpened(partnerPlacement)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on interstitial ad opened.",
                )
            adapter.onShowSuccess()
        }

        override fun onInterstitialAdClosed(partnerPlacement: String) {
            interstitialListenersMap.remove(partnerPlacement)
                ?.onInterstitialAdClosed(partnerPlacement)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on interstitial ad closed.",
                )
        }

        override fun onInterstitialAdShowFailed(
            partnerPlacement: String,
            ironSourceError: IronSourceError,
        ) {
            interstitialListenersMap.remove(partnerPlacement)
                ?.onInterstitialAdShowFailed(partnerPlacement, ironSourceError)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on interstitial ad show failed with error $ironSourceError.",
                )
            adapter.onShowFailure(ironSourceError)
        }

        override fun onInterstitialAdClicked(partnerPlacement: String) {
            interstitialListenersMap[partnerPlacement]?.onInterstitialAdClicked(partnerPlacement)
                ?: PartnerLogController.log(CUSTOM, "Lost ironSource listener on ad clicked.")
        }

        override fun onRewardedVideoAdLoadSuccess(partnerPlacement: String) {
            rewardedListenersMap[partnerPlacement]?.onRewardedVideoAdLoadSuccess(partnerPlacement)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on rewarded ad load success.",
                )
        }

        override fun onRewardedVideoAdLoadFailed(
            partnerPlacement: String,
            ironSourceError: IronSourceError,
        ) {
            rewardedListenersMap.remove(partnerPlacement)
                ?.onRewardedVideoAdLoadFailed(partnerPlacement, ironSourceError)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on rewarded ad load failed.",
                )
        }

        override fun onRewardedVideoAdOpened(partnerPlacement: String) {
            rewardedListenersMap[partnerPlacement]?.onRewardedVideoAdOpened(partnerPlacement)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on rewarded ad opened.",
                )
            adapter.onShowSuccess()
        }

        override fun onRewardedVideoAdClosed(partnerPlacement: String) {
            rewardedListenersMap.remove(partnerPlacement)?.onRewardedVideoAdClosed(partnerPlacement)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on rewarded ad closed.",
                )
        }

        override fun onRewardedVideoAdShowFailed(
            partnerPlacement: String,
            ironSourceError: IronSourceError,
        ) {
            rewardedListenersMap.remove(partnerPlacement)
                ?.onRewardedVideoAdShowFailed(partnerPlacement, ironSourceError)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on rewarded ad show failed.",
                )
            adapter.onShowFailure(ironSourceError)
        }

        override fun onRewardedVideoAdClicked(partnerPlacement: String) {
            rewardedListenersMap[partnerPlacement]?.onRewardedVideoAdClicked(partnerPlacement)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on rewarded ad clicked.",
                )
        }

        override fun onRewardedVideoAdRewarded(partnerPlacement: String) {
            rewardedListenersMap[partnerPlacement]?.onRewardedVideoAdRewarded(partnerPlacement)
                ?: PartnerLogController.log(
                    CUSTOM,
                    "Lost ironSource listener on rewarded ad rewarded.",
                )
        }
    }
}
