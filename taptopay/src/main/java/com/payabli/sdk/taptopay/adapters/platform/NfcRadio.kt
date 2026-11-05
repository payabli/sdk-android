package com.payabli.sdk.taptopay.adapters.platform

import android.content.Context
import android.nfc.NfcAdapter

/**
 * Whether this handset's contactless radio is switched on, read when a charge asks rather than held
 * anywhere: the switch is changed in Settings at any time. A null adapter answers false, and a
 * handset with no radio at all is refused by [CardReaderEligibility], not here.
 */
internal fun nfcEnabled(context: Context): Boolean =
    NfcAdapter.getDefaultAdapter(context.applicationContext)?.isEnabled == true
