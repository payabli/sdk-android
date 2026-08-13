package com.payabli.example.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TapAndPlay
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every icon the app uses, in one place, so a screen never names one directly.
 *
 * `material-icons-core` is 49 icons and covers six of the sixteen below, missing three of the
 * navigation bar's own. Hence the extended set on the dependency list.
 */
object DemoIcons {
    // Navigation.
    val PaymentMethod: ImageVector get() = Icons.Filled.CreditCard
    val Capture: ImageVector get() = Icons.Filled.Paid
    val TapToPay: ImageVector get() = Icons.Filled.Contactless
    val Setup: ImageVector get() = Icons.Filled.Settings

    // Actions.
    val OpenSheet: ImageVector get() = Icons.Filled.VerticalAlignBottom
    val Prefill: ImageVector get() = Icons.Filled.AutoFixHigh
    val CheckToken: ImageVector get() = Icons.Filled.VpnKey
    val CheckHealth: ImageVector get() = Icons.Filled.MonitorHeart
    val Charge: ImageVector get() = Icons.Filled.TapAndPlay
    val Activate: ImageVector get() = Icons.Filled.GppGood
    val Reinitialize: ImageVector get() = Icons.Filled.Refresh
    val StartOver: ImageVector get() = Icons.Filled.RestartAlt

    // Status.
    // Steps. Pass and Fail are reused at either end, keeping the readiness card's vocabulary.
    val Current: ImageVector get() = Icons.Filled.PlayCircle
    val Working: ImageVector get() = Icons.Filled.HourglassTop
    val Waiting: ImageVector get() = Icons.Filled.RadioButtonUnchecked
    val NotNeeded: ImageVector get() = Icons.Filled.RemoveCircleOutline

    val Pass: ImageVector get() = Icons.Filled.CheckCircle
    val Warn: ImageVector get() = Icons.Filled.Warning
    val Fail: ImageVector get() = Icons.Filled.Cancel
    val Unknown: ImageVector get() = Icons.AutoMirrored.Filled.HelpOutline

    /**
     * The overall verdict. A different shape from [Fail], which the readiness card shows alongside it.
     */
    val NotAvailable: ImageVector get() = Icons.Filled.Dangerous
}
