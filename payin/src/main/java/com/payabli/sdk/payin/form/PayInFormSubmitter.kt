package com.payabli.sdk.payin.form

/** The form a submission was made from, which is handed its outcome while it is drawn. */
internal interface PayInFormSubmitter {
    /** Whether the form is drawn, over this flow or another. */
    val onScreen: Boolean
}
