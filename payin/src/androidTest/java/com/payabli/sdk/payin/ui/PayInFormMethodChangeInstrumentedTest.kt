package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.BANK_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.CARD_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormatting
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.telemetry.PayInFormReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Who the host hears from when the instrument on screen changes.
 *
 * A payer's tap and a configuration that moves the method are one change to whoever reprices on it, so
 * both reach [PayInFormContent]'s `onMethodChanged` the same way. The form opens silently: a host that
 * supplied `startingMethod` already knows it, and a change that leaves the method alone tells nothing.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormMethodChangeInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Held here rather than inside the composition, which is where a host holds it. */
    private val draft = PayInFormDraft()

    private val told = mutableListOf<PayInMethodType>()

    private val both =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.Card,
            cardSections = listOf(PayInFormSection(fields = CARD_INSTRUMENT_FIELDS)),
            bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
        )

    @Test
    fun theFormOpensSilently() {
        show(both)

        assertToldNothing("a form opening told the host what its own startingMethod already said")
    }

    @Test
    fun aPayersTapTellsTheHostOnce() {
        show(both)
        tap(PayInMethodType.BankAccount)

        assertTold(PayInMethodType.BankAccount)
    }

    @Test
    fun aRetapOfTheChosenTabSaysNothing() {
        show(both)
        tap(PayInMethodType.BankAccount)
        told.clear()

        tap(PayInMethodType.BankAccount)

        assertToldNothing("a tap that changed nothing told the host")
    }

    @Test
    fun aConfigurationThatWithdrawsTheChosenMethodTellsTheHost() {
        val configuration = mutableStateOf(both)
        show { configuration.value }
        tap(PayInMethodType.BankAccount)
        told.clear()

        rule.runOnIdle { configuration.value = both.copy(allowedMethods = listOf(PayInMethodType.Card)) }
        rule.waitForIdle()

        assertTold(PayInMethodType.Card)
    }

    @Test
    fun aConfigurationThatKeepsTheChosenMethodSaysNothing() {
        val configuration = mutableStateOf(both)
        show { configuration.value }
        tap(PayInMethodType.BankAccount)
        told.clear()

        rule.runOnIdle { configuration.value = both.copy(formatting = PayInFormatting(masksAccountNumber = false)) }
        rule.waitForIdle()

        assertToldNothing("a restyle told the host the method changed")
    }

    @Test
    fun aRotationAloneSaysNothing() {
        val restorer = StateRestorationTester(rule)
        restorer.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = PayInSubmissionState.Idle,
                    draft = draft,
                    configuration = both,
                    reports = PayInFormReports.None,
                    submitText = PayInSubmitText.Capture,
                    onMethodChanged = { told += it },
                )
            }
        }
        tap(PayInMethodType.BankAccount)
        told.clear()

        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        assertToldNothing("a rotation told the host the method changed")
    }

    @Test
    fun aConfigurationChangedWhileTheFormWasAwayTellsTheHostOnReturn() {
        // A host takes the form off the screen and hands a new configuration over while it is gone,
        // which is how a method comes to move with no form composed to see it.
        val configuration = mutableStateOf(both)
        var formShown by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                if (formShown) {
                    PayInFormContent(
                        submission = PayInSubmissionState.Idle,
                        draft = draft,
                        configuration = configuration.value,
                        reports = PayInFormReports.None,
                        submitText = PayInSubmitText.Capture,
                        onMethodChanged = { told += it },
                    )
                }
            }
        }
        tap(PayInMethodType.BankAccount)
        told.clear()

        rule.runOnIdle { formShown = false }
        rule.runOnIdle { configuration.value = both.copy(allowedMethods = listOf(PayInMethodType.Card)) }
        rule.runOnIdle { formShown = true }
        rule.waitForIdle()

        assertTold(PayInMethodType.Card)
    }

    @Test
    fun twoFormsSharingTheDraftAnnounceAChangeOnce() {
        // One draft draws one form, but a screen can mount a second one over the same flow, and both
        // see the method move. The host is told what changed, not that two forms saw it.
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = PayInSubmissionState.Idle,
                    draft = draft,
                    configuration = both,
                    reports = PayInFormReports.None,
                    submitText = PayInSubmitText.Capture,
                    onMethodChanged = { told += it },
                )
                PayInFormContent(
                    submission = PayInSubmissionState.Idle,
                    draft = draft,
                    configuration = both,
                    reports = PayInFormReports.None,
                    submitText = PayInSubmitText.Capture,
                    onMethodChanged = { told += it },
                )
            }
        }
        rule.waitForIdle()

        // Both draw a selector, so the tap takes the first of the two tabs.
        rule
            .onAllNodes(hasClickAction() and hasText(string(R.string.payabli_payin_method_bank_account)))[0]
            .performClick()
        rule.waitForIdle()

        assertTold(PayInMethodType.BankAccount)
    }

    @Test
    fun aSecondDraftOpeningOnTheSameMethodStillTellsItsFirstChange() {
        // A host can hand the form a new flow in the same place, and the new flow's draft opens where
        // the old one stood. The new draft opens silently, and a change on it reaches the host.
        val swapped = mutableStateOf(PayInFormDraft())
        show(draft = { swapped.value })
        rule.waitForIdle()

        rule.runOnIdle { swapped.value = PayInFormDraft() }
        rule.waitForIdle()
        tap(PayInMethodType.BankAccount)

        assertTold(PayInMethodType.BankAccount)
    }

    private fun show(configuration: PayInFormConfiguration) = show { configuration }

    private fun show(
        draft: () -> PayInFormDraft,
        configuration: PayInFormConfiguration = both,
    ) {
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = PayInSubmissionState.Idle,
                    draft = draft(),
                    configuration = configuration,
                    reports = PayInFormReports.None,
                    submitText = PayInSubmitText.Capture,
                    onMethodChanged = { told += it },
                )
            }
        }
    }

    private fun show(configuration: () -> PayInFormConfiguration) {
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = PayInSubmissionState.Idle,
                    draft = draft,
                    configuration = configuration(),
                    reports = PayInFormReports.None,
                    submitText = PayInSubmitText.Capture,
                    onMethodChanged = { told += it },
                )
            }
        }
    }

    private fun tap(method: PayInMethodType) {
        val label =
            string(
                when (method) {
                    PayInMethodType.Card -> R.string.payabli_payin_method_card
                    PayInMethodType.BankAccount -> R.string.payabli_payin_method_bank_account
                },
            )
        rule.onNode(hasClickAction() and hasText(label)).performClick()
        rule.waitForIdle()
    }

    private fun assertTold(vararg methods: PayInMethodType) {
        assertEquals("the host was told ${told.joinToString()}", methods.toList(), told)
    }

    private fun assertToldNothing(because: String) {
        assertTrue(because, told.isEmpty())
    }

    private fun string(resource: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
