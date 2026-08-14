package com.payabli.example.app.demo.qa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a capture names the customer it is for, or leaves the paypoint to invent one.
 *
 * A paypoint matches a customer on its number within that paypoint, so sending one attaches every payment from
 * this device to a single record. Sending none, with `forceCustomerCreation` set, writes a new customer per
 * payment instead: three captures from one device produced three customers with no number at all. The capture
 * form collects no customer number, so this is the only thing that decides it.
 *
 * Both are things a paypoint can want and the SDK cannot see which, so the sample offers both and defaults to
 * the one that keeps a run readable.
 *
 * Not persisted. It describes the paypoint being tested rather than a preference, so it resets with the app
 * instead of outliving a switch between environments.
 */
class DemoCustomerSetting(
    identity: QaIdentity,
) {
    private val _suppliesDemoCustomer = MutableStateFlow(true)

    val suppliesDemoCustomer: StateFlow<Boolean> = _suppliesDemoCustomer.asStateFlow()

    /** What the sample says it sends, for the screen that reads the configuration back. */
    val summary: String = "${identity.holderName} · ${identity.customerNumber}"

    fun setSuppliesDemoCustomer(supplies: Boolean) {
        _suppliesDemoCustomer.value = supplies
    }
}
