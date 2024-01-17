/*
 * Copyright 2022-2023 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.ironsourceadapter

import android.app.Activity
import android.content.Context
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.ironsource.mediationsdk.IronSource
import com.ironsource.mediationsdk.IronSource.AD_UNIT
import com.ironsource.mediationsdk.demandOnly.ISDemandOnlyInterstitialListener
import com.ironsource.mediationsdk.demandOnly.ISDemandOnlyRewardedVideoListener
import com.ironsource.mediationsdk.logger.IronSourceError
import com.ironsource.mediationsdk.logger.IronSourceError.*
import com.ironsource.mediationsdk.utils.IronSourceUtils
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
     * Get the ironSource SDK version.
     */
    override val partnerSdkVersion: String
        get() = IronSourceUtils.getSDKVersion()

    /**
     * Get the ironSource adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_IRONSOURCE_ADAPTER_VERSION

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
        partnerConfiguration: PartnerConfiguration,
    ): Result<Unit> {
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

                Result.success(PartnerLogController.log(SETUP_SUCCEEDED))
            } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Missing the app key.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS))
        }
    }

    /**
     * Notify the ironSource SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus,
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            },
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            },
        )

        if (applies == true) {
            IronSource.setConsent(gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED)
        }
    }

    /**
     * Notify ironSource of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String,
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) {
                CCPA_CONSENT_GRANTED
            } else {
                CCPA_CONSENT_DENIED
            },
        )

        IronSource.setMetaData("do_not_sell", if (hasGrantedCcpaConsent) "false" else "true")
    }

    /**
     * Notify ironSource of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(
        context: Context,
        isSubjectToCoppa: Boolean,
    ) {
        PartnerLogController.log(
            if (isSubjectToCoppa) {
                COPPA_SUBJECT
            } else {
                COPPA_NOT_SUBJECT
            },
        )

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
        request: PreBidRequest,
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return emptyMap()
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
                AdFormat.INTERSTITIAL -> {
                    loadInterstitialAd(activity, request, partnerAdListener)
                }
                AdFormat.REWARDED -> {
                    loadRewardedAd(activity, request, partnerAdListener)
                }
                else -> {
                    PartnerLogController.log(LOAD_FAILED)
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
                }
            }
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "Activity context is required.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_ACTIVITY_NOT_FOUND))
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
    override suspend fun show(
        context: Context,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return when (partnerAd.request.format) {
            AdFormat.INTERSTITIAL -> showInterstitialAd(partnerAd)
            AdFormat.REWARDED -> showRewardedAd(partnerAd)
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
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
                        ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_ABORTED),
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
                        ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_ABORTED),
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
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
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
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
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
        format: AdFormat,
        placement: String,
    ): Boolean {
        return when (format) {
            AdFormat.INTERSTITIAL -> return IronSource.isISDemandOnlyInterstitialReady(
                placement,
            )
            AdFormat.REWARDED -> return IronSource.isISDemandOnlyRewardedVideoAvailable(
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
            ERROR_CODE_NO_ADS_TO_SHOW, ERROR_BN_LOAD_NO_FILL, ERROR_RV_LOAD_NO_FILL, ERROR_IS_LOAD_NO_FILL -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
            ERROR_NO_INTERNET_CONNECTION -> ChartboostMediationError.CM_NO_CONNECTIVITY
            ERROR_BN_LOAD_NO_CONFIG -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_AD_REQUEST
            ERROR_BN_INSTANCE_LOAD_AUCTION_FAILED -> ChartboostMediationError.CM_LOAD_FAILURE_AUCTION_NO_BID
            ERROR_BN_INSTANCE_LOAD_EMPTY_SERVER_DATA -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_BID_RESPONSE
            ERROR_RV_INIT_FAILED_TIMEOUT -> ChartboostMediationError.CM_INITIALIZATION_FAILURE_TIMEOUT
            ERROR_DO_IS_LOAD_TIMED_OUT, ERROR_BN_INSTANCE_LOAD_TIMEOUT, ERROR_DO_RV_LOAD_TIMED_OUT -> ChartboostMediationError.CM_LOAD_FAILURE_TIMEOUT
            AUCTION_ERROR_TIMED_OUT -> ChartboostMediationError.CM_LOAD_FAILURE_AUCTION_TIMEOUT
            else -> ChartboostMediationError.CM_PARTNER_ERROR
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
