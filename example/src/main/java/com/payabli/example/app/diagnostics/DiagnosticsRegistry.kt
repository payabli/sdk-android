package com.payabli.example.app.diagnostics

/**
 * The app's diagnostic logs, one per screen that makes requests.
 *
 * Separate stores, so a reader looking at the capture screen sees capture traffic and nothing else.
 * Held here so a test can build a fresh registry, which is what makes the bound on
 * [DiagnosticsStore] testable at all.
 */
class DiagnosticsRegistry {
    val paymentMethod: DiagnosticsStore = DiagnosticsStore()
    val capture: DiagnosticsStore = DiagnosticsStore()
}
