package com.payabli.example.app.sdk

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.payabli.example.app.demo.ui.customize.FormLook
import com.payabli.example.app.demo.ui.customize.FormMethods
import com.payabli.example.app.demo.ui.customize.FormOperation
import com.payabli.example.app.demo.ui.customize.FormSettings
import com.payabli.example.app.demo.ui.customize.FormStart
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormSpacing
import com.payabli.sdk.payin.form.PayInFormStyle
import com.payabli.sdk.payin.form.PayInFormStyleOverrides
import com.payabli.sdk.payin.form.PayInFormatting
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle
import com.payabli.sdk.payin.model.PayInCustomerData
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.ui.PayabliPayInFormDefaults
import java.math.BigDecimal

/** The Simple Capture screen's [FormSettings], written as the SDK's configuration, labels, style and operation. */
object FormCustomization {
    fun configuration(
        settings: FormSettings,
        operation: FormOperation,
    ): PayInFormConfiguration {
        val customer = customerSection(settings, operation)
        val requiresNumber = customer != null && settings.requireCustomerNumber
        val summary =
            PayInFormSection(
                title = if (settings.customWording) "Order total" else null,
                fields = listOf(PayInField.Amount),
                style = PayInSectionStyle.Summary,
            ).takeIf { settings.summary && operation == FormOperation.Capture }

        fun arranged(details: PayInFormSection) =
            listOfNotNull(
                customer.takeIf { settings.customerFirst },
                details,
                customer.takeUnless { settings.customerFirst },
                summary,
            )

        return PayInFormConfiguration(
            allowedMethods =
                when (settings.methods) {
                    FormMethods.Card -> listOf(PayInMethodType.Card)
                    FormMethods.Bank -> listOf(PayInMethodType.BankAccount)
                    FormMethods.Both -> listOf(PayInMethodType.Card, PayInMethodType.BankAccount)
                },
            defaultMethod =
                if (settings.startOn ==
                    FormStart.Bank
                ) {
                    PayInMethodType.BankAccount
                } else {
                    PayInMethodType.Card
                },
            cardSections = arranged(cardDetails(settings)),
            bankSections = arranged(bankDetails(settings)),
            requiredFields = setOfNotNull(PayInField.CustomerNumber.takeIf { requiresNumber }),
            labelLayout = if (settings.labelsInside) PayInLabelLayout.Placeholder else PayInLabelLayout.External,
            hiddenFieldLabels = if (settings.hideLabels) PayInField.entries.toSet() else emptySet(),
            formatting =
                PayInFormatting(
                    groupsCardNumber = settings.groupCardNumber,
                    expirySeparator = if (settings.dashExpirySeparator) "-" else "/",
                    masksAccountNumber = settings.maskAccountNumber,
                ),
        )
    }

    fun labels(
        settings: FormSettings,
        operation: FormOperation,
    ): PayInFormLabels =
        PayInFormLabels(
            title = if (settings.customWording) "Acme Checkout" else operation.label,
            subtitle = if (settings.customWording) "Secure payment, powered by Payabli" else null,
            submitButton =
                when {
                    !settings.customWording -> null
                    operation == FormOperation.Capture -> "Pay now"
                    else -> "Save for later"
                },
            fieldLabels = if (settings.customWording) BRAND_LABELS else emptyMap(),
            fieldPlaceholders = if (settings.hideLabels) PLACEHOLDERS else emptyMap(),
        )

    /** The operation the form submits. With the customer section off, the app supplies the customer. */
    fun operation(
        settings: FormSettings,
        operation: FormOperation,
        amount: BigDecimal,
        idempotencyKey: String?,
    ): PayabliPayInOperation {
        val supplied = DEMO_CUSTOMER.takeUnless { settings.customerSection }
        return when (operation) {
            FormOperation.Capture ->
                PayabliPayInOperation.Capture(
                    PayInTransactionOptions(
                        PayInPaymentDetails(totalAmount = amount),
                        customerData = supplied,
                        idempotencyKey = idempotencyKey,
                    ),
                )

            FormOperation.Tokenize ->
                PayabliPayInOperation.StoreMethod(
                    PayInStoreOptions(customerData = supplied, forceCustomerCreation = true),
                )
        }
    }

    /** Null for the app theme, which the form follows with nothing passed. */
    @Composable
    fun style(look: FormLook): PayInFormStyle? =
        when (look) {
            FormLook.Default -> null
            FormLook.Brand ->
                PayabliPayInFormDefaults.style(
                    PayInFormStyleOverrides(
                        title =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        sectionTitle =
                            MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Serif,
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        fieldShape = RoundedCornerShape(16.dp),
                        spacing = PayInFormSpacing(content = 24.dp, fieldGroup = 16.dp, section = 28.dp),
                        fieldColors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        brandMark = DpSize(36.dp, 24.dp),
                    ),
                )

            FormLook.Compact ->
                PayabliPayInFormDefaults.style(
                    PayInFormStyleOverrides(
                        title = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
                        label = MaterialTheme.typography.labelMedium,
                        fieldShape = RoundedCornerShape(2.dp),
                        spacing =
                            PayInFormSpacing(
                                content = 12.dp,
                                fieldGroup = 6.dp,
                                pairedField = 8.dp,
                                label = 4.dp,
                                section = 10.dp,
                                sectionTitle = 6.dp,
                            ),
                    ),
                )
        }

    private fun cardDetails(settings: FormSettings) =
        PayInFormConfiguration
            .defaultCardSections()
            .single()
            .copy(title = if (settings.customWording) "Your card" else null)

    private fun bankDetails(settings: FormSettings) =
        PayInFormConfiguration
            .defaultBankSections()
            .single()
            .copy(title = if (settings.customWording) "Your bank" else null)

    /** Storing a method always asks for a customer number. */
    private fun customerSection(
        settings: FormSettings,
        operation: FormOperation,
    ): PayInFormSection? =
        PayInFormSection(
            title = if (settings.customWording) "About you" else "Customer",
            fields =
                buildList {
                    add(PayInField.FirstName)
                    add(PayInField.LastName)
                    if (settings.requireCustomerNumber || operation == FormOperation.Tokenize) {
                        add(PayInField.CustomerNumber)
                    }
                    add(PayInField.BillingEmail)
                },
        ).takeIf { settings.customerSection }

    private val DEMO_CUSTOMER =
        PayInCustomerData(
            customerNumber = "demo-customer",
            firstName = "Demo",
            lastName = "Payer",
            billingEmail = "demo.payer@example.com",
        )

    private val BRAND_LABELS =
        mapOf(
            PayInField.CardholderName to "Name on card",
            PayInField.CardNumber to "Card",
            PayInField.CardExpiration to "Expires",
            PayInField.CardSecurityCode to "Security code",
            PayInField.CardPostalCode to "Billing ZIP",
            PayInField.AccountHolder to "Name on account",
            PayInField.RoutingNumber to "Bank routing",
            PayInField.AccountNumber to "Bank account",
            PayInField.FirstName to "Given name",
            PayInField.LastName to "Family name",
            PayInField.CustomerNumber to "Member ID",
            PayInField.BillingEmail to "Receipt email",
        )

    private val PLACEHOLDERS =
        mapOf(
            PayInField.CardholderName to "Name on card",
            PayInField.CardNumber to "Card number",
            PayInField.CardSecurityCode to "CVV",
            PayInField.CardPostalCode to "ZIP",
            PayInField.AccountHolder to "Account holder",
            PayInField.RoutingNumber to "Routing number",
            PayInField.AccountNumber to "Account number",
            PayInField.AccountType to "Account type",
            PayInField.FirstName to "First name",
            PayInField.LastName to "Last name",
            PayInField.CustomerNumber to "Customer number",
            PayInField.BillingEmail to "Email",
        )
}
