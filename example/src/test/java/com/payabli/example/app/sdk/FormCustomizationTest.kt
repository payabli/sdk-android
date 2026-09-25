package com.payabli.example.app.sdk

import com.payabli.example.app.demo.simple.parseAmount
import com.payabli.example.app.demo.ui.customize.FormMethods
import com.payabli.example.app.demo.ui.customize.FormOperation
import com.payabli.example.app.demo.ui.customize.FormPreset
import com.payabli.example.app.demo.ui.customize.FormSettings
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class FormCustomizationTest {
    private fun configure(
        settings: FormSettings,
        operation: FormOperation = FormOperation.Capture,
    ): PayInFormConfiguration = FormCustomization.configuration(settings, operation, "$ 12.34")

    @Test
    fun `every combination of settings is a configuration the SDK accepts`() {
        // The SDK refuses a configuration it cannot submit, at construction. A switch that produced one would
        // crash the screen the moment it was flipped.
        var built = 0
        for (bits in 0 until (1 shl 10)) {
            fun bit(i: Int) = bits and (1 shl i) != 0
            for (methods in FormMethods.entries) {
                for (operation in FormOperation.entries) {
                    val settings =
                        FormSettings(
                            methods = methods,
                            labelsInside = bit(0),
                            hideLabels = bit(1),
                            customWording = bit(2),
                            customerSection = bit(3),
                            customerFirst = bit(4),
                            requireCustomerNumber = bit(5),
                            summary = bit(6),
                            groupCardNumber = bit(7),
                            dashExpirySeparator = bit(8),
                            maskAccountNumber = bit(9),
                        )
                    configure(settings, operation)
                    FormCustomization.labels(settings, operation)
                    built++
                }
            }
        }
        assertEquals(1024 * 3 * 2, built)
    }

    @Test
    fun `the methods switch decides what the form offers`() {
        assertEquals(listOf(PayInMethodType.Card), configure(FormSettings(methods = FormMethods.Card)).methodsOffered)
        assertEquals(
            listOf(PayInMethodType.BankAccount),
            configure(FormSettings(methods = FormMethods.Bank)).methodsOffered,
        )
        assertEquals(2, configure(FormSettings(methods = FormMethods.Both)).methodsOffered.size)
    }

    @Test
    fun `hidden labels leave a placeholder on every text field`() {
        val settings = FormSettings(hideLabels = true)
        val configuration = configure(settings)
        val labels = FormCustomization.labels(settings, FormOperation.Capture)

        val inputs = configuration.inputFieldsFor(PayInMethodType.Card)
        assertTrue(inputs.none { configuration.showsLabelFor(it) || configuration.showsFloatingLabelFor(it) })
        assertTrue(
            inputs.filter { it != PayInField.CardExpiration }.all { labels.placeholderFor(it) != null },
        )

        val shown = configure(FormSettings())
        assertTrue(shown.inputFieldsFor(PayInMethodType.Card).all { shown.showsLabelFor(it) })
    }

    @Test
    fun `labels inside is the floating layout`() {
        assertEquals(PayInLabelLayout.Placeholder, configure(FormSettings(labelsInside = true)).labelLayout)
        assertEquals(PayInLabelLayout.External, configure(FormSettings()).labelLayout)
    }

    @Test
    fun `customer first puts the customer section before the card`() {
        val sections = configure(FormSettings(customerFirst = true)).sectionsFor(PayInMethodType.Card)
        assertEquals(PayInField.FirstName, sections.first().fields.first())
        val default = configure(FormSettings()).sectionsFor(PayInMethodType.Card)
        assertEquals(PayInField.CardholderName, default.first().fields.first())
    }

    @Test
    fun `a required customer number is on the form and required`() {
        val configuration = configure(FormSettings(requireCustomerNumber = true))
        assertTrue(PayInField.CustomerNumber in configuration.inputFieldsFor(PayInMethodType.Card))
        assertTrue(configuration.isRequired(PayInField.CustomerNumber))
        assertFalse(PayInField.CustomerNumber in configure(FormSettings()).inputFieldsFor(PayInMethodType.Card))
    }

    @Test
    fun `tokenizing asks for the customer number the store route identifies a customer by`() {
        val configuration = configure(FormSettings(), FormOperation.Tokenize)
        assertTrue(PayInField.CustomerNumber in configuration.inputFieldsFor(PayInMethodType.Card))
    }

    @Test
    fun `the amount summary is shown only on a capture that asks for it`() {
        fun hasSummary(configuration: PayInFormConfiguration) =
            configuration.sectionsFor(PayInMethodType.Card).any { it.style == PayInSectionStyle.Summary }

        assertTrue(hasSummary(configure(FormSettings())))
        assertFalse(hasSummary(configure(FormSettings(summary = false))))
        assertFalse(hasSummary(configure(FormSettings(), FormOperation.Tokenize)))
    }

    @Test
    fun `formatting follows its three switches`() {
        val formatting =
            configure(FormSettings(groupCardNumber = false, dashExpirySeparator = true, maskAccountNumber = false))
                .formatting
        assertFalse(formatting.groupsCardNumber)
        assertEquals("-", formatting.expirySeparator)
        assertFalse(formatting.masksAccountNumber)
    }

    @Test
    fun `with the customer section off the app supplies the customer`() {
        val off = FormSettings(customerSection = false)
        val capture = FormCustomization.operation(off, FormOperation.Capture, BigDecimal.ONE, null)
        val store = FormCustomization.operation(off, FormOperation.Tokenize, BigDecimal.ONE, null)

        assertNotNull((capture as PayabliPayInOperation.Capture).options.customerData)
        assertNotNull((store as PayabliPayInOperation.StoreMethod).options.customerData?.customerNumber)
        assertFalse(PayInField.FirstName in configure(off).inputFieldsFor(PayInMethodType.Card))

        val on = FormCustomization.operation(FormSettings(), FormOperation.Capture, BigDecimal.ONE, null)
        assertNull((on as PayabliPayInOperation.Capture).options.customerData)
    }

    @Test
    fun `a capture carries the amount and the held retry key`() {
        val capture =
            FormCustomization.operation(FormSettings(), FormOperation.Capture, BigDecimal("5.00"), "held-key")
                as PayabliPayInOperation.Capture
        assertEquals(BigDecimal("5.00"), capture.options.paymentDetails.totalAmount)
        assertEquals("held-key", capture.options.idempotencyKey)
    }

    @Test
    fun `custom wording renames the form and its fields`() {
        val labels = FormCustomization.labels(FormSettings(customWording = true), FormOperation.Capture)
        assertEquals("Pay now", labels.submitButtonOrNull())
        assertNotNull(labels.labelFor(PayInField.CardNumber))
        assertNull(FormCustomization.labels(FormSettings(), FormOperation.Capture).submitButtonOrNull())
    }

    @Test
    fun `each preset changes several settings at once`() {
        val default = FormPreset.Default.settings
        FormPreset.entries.filter { it != FormPreset.Default }.forEach { preset ->
            val changed =
                listOf(
                    preset.settings.look != default.look,
                    preset.settings.methods != default.methods,
                    preset.settings.labelsInside != default.labelsInside,
                    preset.settings.hideLabels != default.hideLabels,
                    preset.settings.customWording != default.customWording,
                    preset.settings.customerSection != default.customerSection,
                    preset.settings.customerFirst != default.customerFirst,
                    preset.settings.summary != default.summary,
                    preset.settings.groupCardNumber != default.groupCardNumber,
                ).count { it }
            assertTrue("${preset.label} changes only $changed settings", changed >= 4)
        }
    }

    @Test
    fun `an amount is positive with at most two decimals`() {
        assertEquals(BigDecimal("12.34"), parseAmount(" 12.34 "))
        assertNull(parseAmount("0"))
        assertNull(parseAmount("-1"))
        assertNull(parseAmount("1.234"))
        assertNull(parseAmount("abc"))
    }
}
