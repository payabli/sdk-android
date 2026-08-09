package com.payabli.example.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.DemoEnvironment
import com.payabli.example.app.config.TokenHostDefaults
import com.payabli.example.app.config.TokenHostResolver
import com.payabli.example.app.payment.DemoForms
import com.payabli.example.app.payment.PaymentFormSummary
import com.payabli.example.app.preflight.CheckStatus
import com.payabli.example.app.preflight.DeviceFacts
import com.payabli.example.app.preflight.PreflightCheck
import com.payabli.example.app.preflight.Readiness
import com.payabli.example.app.ui.components.BorderedButton
import com.payabli.example.app.ui.components.DemoIcons
import com.payabli.example.app.ui.components.DemoScreen
import com.payabli.example.app.ui.components.DetailRow
import com.payabli.example.app.ui.components.PreviewSurface
import com.payabli.example.app.ui.components.ReadinessCard
import com.payabli.example.app.ui.components.SectionHeader
import com.payabli.example.app.ui.theme.Dimens

/**
 * Everything the SDK was configured with, read back.
 *
 * Read-only throughout, and that is the design. A session captures its configuration when it is
 * created, so a control here would appear to change something already decided. Each value says where
 * it came from, so a wrong one is fixed at the source.
 */
@Composable
fun SetupScreen(
    state: SetupUiState,
    onProbeToken: () -> Unit,
    onProbeHealth: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DemoScreen(title = "Setup", modifier = modifier) {
        Section(
            title = "Integration",
            note = "Set in example/secrets.properties and captured when the app started.",
        ) {
            DetailRow(
                label = "Entry point",
                value = state.configuration.entryPoint,
                problem = state.configuration.entryPointProblem,
            )
            DetailRow(label = "App ID", value = state.configuration.appId)
            DetailRow(
                label = "Environment",
                value = "${state.configuration.environment.label} · ${state.configuration.environment.host}",
            )
        }

        Section(
            title = "Token endpoint",
            note = "Resolved for this run. The app fetches its own token here; the SDK is not involved.",
        ) {
            DetailRow(label = "Server", value = state.tokenServer.baseUrl)
            // The routes, not just the host. "Check token" calls exchange-token deliberately, because
            // the access-token route serves a cached value and a token provider has to mint on every
            // call. A row that showed only the host would leave that decision invisible.
            DetailRow(label = "Token route", value = state.tokenServer.accessTokenUrl)
            DetailRow(label = "Health route", value = state.tokenServer.healthUrl)
            DetailRow(label = "Chosen because", value = state.tokenServer.explanation)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            ) {
                BorderedButton(
                    text = "Check token",
                    icon = DemoIcons.CheckToken,
                    onClick = onProbeToken,
                    enabled = !state.isProbing,
                    modifier = Modifier.weight(1f),
                )
                BorderedButton(
                    text = "Health",
                    icon = DemoIcons.CheckHealth,
                    onClick = onProbeHealth,
                    enabled = !state.isProbing,
                    modifier = Modifier.weight(1f),
                )
            }
            listOf(state.tokenProbeText, state.healthProbeText)
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }

        Section(title = "Card present", note = "Whether this device can take a contactless payment.") {
            ReadinessCard(
                readiness = state.readiness,
                problems = state.problems,
                onRecheck = onRecheck,
            )
        }

        Section(
            title = "Card not present",
            note = "What the payment form on the Payment method and Capture screens was configured with.",
        ) {
            // Read from the configuration and the rules, never written out here. A transcribed list
            // would agree with the form today and stop agreeing the first time a field moved.
            PaymentFormSummary.rows(state.formConfiguration).forEach { row ->
                DetailRow(label = row.label, value = row.value)
            }
        }

        Section(
            title = "Diagnostics",
            note = "Redacted request and response logging on the payment screens. Never a card number or a token.",
        ) {
            val value = if (state.configuration.diagnosticsEnabled) "On" else "Off"
            // Two rows for two logs, both driven by one build setting. Listing them separately says
            // which screens are affected; collapsing them to one row would not.
            DetailRow(label = "Payment method", value = value)
            DetailRow(label = "Capture", value = value)
            DetailRow(label = "Set by", value = "payabli.demo.diagnostics")
        }

        Section(title = "This build") {
            DetailRow(label = "Model", value = state.deviceFacts.model)
            DetailRow(label = "Android", value = "API ${state.deviceFacts.apiLevel}")
            // Stated either way. It is otherwise only visible as a readiness failure, so on a real
            // phone a reader is never told which kind of host they are on.
            DetailRow(
                label = "Host",
                value = if (state.deviceFacts.isEmulator) "Emulator" else "Physical device",
            )
            DetailRow(label = "Package", value = state.deviceFacts.packageName)
            // The value that says which build is installed, and what attestation binds a verdict to.
            // It is read for the readiness check already; showing it costs nothing and is what a
            // reader compares against the Play Console.
            DetailRow(
                label = "Signing certificate",
                value = state.deviceFacts.signingCertificateDigest ?: "",
                problem =
                    if (state.deviceFacts.signingCertificateDigest == null) {
                        "Reading it needs API 28 or newer."
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    note: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
        SectionHeader(title = title, note = note)
        content()
    }
}

@PreviewLightDark
@Composable
private fun SetupScreenPreview() {
    PreviewSurface {
        SetupScreen(
            state =
                SetupUiState(
                    configuration =
                        DemoConfiguration(
                            entryPoint = "",
                            appId = "com.payabli.example.app",
                            signingCertificate = "",
                            environment = DemoEnvironment.SANDBOX,
                            diagnosticsEnabled = true,
                        ),
                    // Resolved, not typed out. A preview showing an address the resolver does not
                    // choose is a preview of a screen that cannot happen.
                    tokenServer =
                        TokenHostResolver.resolve(
                            launchOverride = null,
                            buildSettingHost = "",
                            isEmulator = true,
                            defaults = TokenHostDefaults.fromBuildConfig(),
                        ),
                    formConfiguration = DemoForms.storePaymentMethod().configuration,
                    deviceFacts =
                        DeviceFacts(
                            isEmulator = true,
                            model = "Google sdk_gphone64_arm64",
                            apiLevel = 36,
                            hasNfcHardware = false,
                            isNfcEnabled = false,
                            playServicesInstalled = true,
                            playStoreInstalled = true,
                            packageName = "com.payabli.example.app",
                            signingCertificateDigest = "AB:CD",
                        ),
                    readiness = Readiness.NotAvailable,
                    problems =
                        listOf(
                            PreflightCheck(
                                "Emulator",
                                "Google sdk_gphone64_arm64. A contactless payment needs real hardware.",
                                CheckStatus.Fail,
                            ),
                        ),
                    tokenProbeText = "✓ Token endpoint returned a token",
                    healthProbeText = "✗ Local token server unreachable: Connection refused",
                ),
            onProbeToken = {},
            onProbeHealth = {},
            onRecheck = {},
        )
    }
}
