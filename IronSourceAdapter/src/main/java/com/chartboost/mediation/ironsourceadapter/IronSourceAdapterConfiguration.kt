package com.chartboost.mediation.ironsourceadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.ironsource.mediationsdk.utils.IronSourceUtils

object IronSourceAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "ironsource"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "ironSource"

    /**
     * The version of the partner SDK.
     */
    override val partnerSdkVersion: String = IronSourceUtils.getSDKVersion()

    /**
     * The partner adapter version.
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
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_IRONSOURCE_ADAPTER_VERSION
}
