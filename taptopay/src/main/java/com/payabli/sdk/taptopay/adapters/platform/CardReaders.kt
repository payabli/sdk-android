package com.payabli.sdk.taptopay.adapters.platform

import android.content.Context
import com.payabli.sdk.taptopay.adapters.FiservAndroidCardReader
import com.payabli.sdk.taptopay.provider.TapToPayProvider

/** Builds the shipping [TapToPayProvider] over the real card reader. */
internal object CardReaders {
    fun fiserv(context: Context): TapToPayProvider =
        FiservAndroidCardReader(
            gateway = FiservCardReaderGateway(context),
            eligibility = CardReaderEligibility(context),
        )
}
