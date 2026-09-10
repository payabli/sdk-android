package com.payabli.sdk.taptopay.attestation.platform

import com.payabli.sdk.core.config.PayabliEnvironment

/**
 * The Google Cloud project a classic integrity request is made against, by environment.
 *
 * **It is Payabli's project and never an integrator's**, which is why it is resolved here rather than taken
 * as a parameter. The platform documents `setCloudProjectNumber` for SDKs as well as for apps distributed
 * outside Play, so an SDK naming its own project is the intended arrangement rather than a workaround.
 *
 * **Held here rather than fetched, and that is the part that is temporary.** The service is to return this
 * on the challenge call the SDK already makes, and the classic attestor already takes the number per
 * request, so this table is what stands in until that field exists. A value written by hand is a value that
 * drifts, so it comes out the moment there is one on the wire.
 *
 * Keyed on the environment's name because a build can add environments the SDK was not compiled with, and
 * matching by name lets one of those resolve rather than reaching a branch that cannot exist.
 */
internal object CloudProject {
    /**
     * The project for [environment], or null where none exists.
     *
     * Null is not an error and not a default: the platform makes the number optional for a classic request
     * by an app whose Play Console listing carries the linkage, so an app distributed through Play still
     * attests without one. A sideloaded build does not, which is why a missing entry is worth knowing about
     * rather than silently passing null.
     *
     * **Sandbox has no project.** Two exist, covering production and qa, and sandbox is a shipped
     * environment with nothing to name. Until one is created a sideloaded build cannot attest there, and
     * this is the one place that says so.
     */
    fun forEnvironment(environment: PayabliEnvironment): Long? =
        when (environment.name.lowercase()) {
            "production" -> PRODUCTION
            "qa" -> QA
            else -> null
        }

    private const val PRODUCTION = 984132363097L

    private const val QA = 736636912167L
}
