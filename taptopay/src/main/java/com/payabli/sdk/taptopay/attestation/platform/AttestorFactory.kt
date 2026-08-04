package com.payabli.sdk.taptopay.attestation.platform

import android.content.Context
import com.payabli.sdk.taptopay.attestation.AppAttestor
import com.payabli.sdk.taptopay.attestation.impl.ClassicAttestor
import com.payabli.sdk.taptopay.attestation.impl.StandardAttestor

/**
 * Builds the shipping [AppAttestor] over the real Play Integrity API.
 *
 * Here rather than beside the attestors for the same reason the storage factory sits apart from its store:
 * naming a platform gateway is what makes a file unreachable from a unit test, and this is the only other
 * place that does it. Keeping it out leaves both attestors testable on the JVM in full.
 *
 * **The two are not interchangeable and this does not choose between them.** A standard request is cheap
 * per call and needs a prepared provider and a cloud project number; a classic request needs neither but
 * reaches Google's servers every time and is rated for infrequent use. Which one a given flow wants is a
 * property of the flow, so the caller says.
 */
internal object AttestorFactory {
    /**
     * A standard-request attestor for [cloudProjectNumber].
     *
     * The number is required and is the project where the Play Integrity API is enabled. There is no
     * default and no discovery: a wrong one fails at request time with a code that names it, and guessing
     * would only move that failure somewhere less obvious.
     */
    fun standard(
        context: Context,
        cloudProjectNumber: Long,
    ): AppAttestor = StandardAttestor(PlayStandardIntegrityGateway(context), cloudProjectNumber)

    /**
     * A classic-request attestor.
     *
     * [cloudProjectNumber] is optional here because the platform makes it optional: an app whose Play
     * Console listing carries the linkage does not need to state it. Supply it where that linkage does not
     * exist.
     */
    fun classic(
        context: Context,
        cloudProjectNumber: Long? = null,
    ): AppAttestor = ClassicAttestor(PlayClassicIntegrityGateway(context), cloudProjectNumber)
}
