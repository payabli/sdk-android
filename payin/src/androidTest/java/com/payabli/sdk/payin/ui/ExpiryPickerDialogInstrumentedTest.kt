package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.payin.form.ExpiryValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The month and year picker, which is a composition and reachable no other way.
 *
 * `ExpiryChoicesTest` covers which values may be offered. None of this is that: that both columns are drawn,
 * that confirming reports the pair chosen, that changing the year drags an unavailable month with it, and
 * that a row is announced as a selectable rather than as a button. The coercion rule in particular has two
 * callers here — the opening state and the year change — and a unit test reaches neither.
 */
@RunWith(AndroidJUnit4::class)
class ExpiryPickerDialogInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** August 2026, so the current year offers 08..12 and every later year offers all twelve. */
    private val today = ExpiryValue(8, 2026)

    private var picked: ExpiryValue? = null
    private var dismissed = false

    @Test
    fun bothColumnsAreDrawn() {
        show(initial = null)

        rule.onNodeWithText("08").assertExists()
        rule.onNodeWithText("2026").assertExists()
    }

    /**
     * The months already gone are not offered, which is the rule the column is built from.
     *
     * Asserted at the top of the list rather than anywhere: the column holds four rows at a time, so a month
     * being absent from the semantics tree usually means it is merely scrolled out of view. At index 0 it
     * does mean absent, because an offered 07 would be the first row.
     */
    @Test
    fun theCurrentYearOffersNoMonthAlreadyGone() {
        show(initial = null)

        monthColumn().performScrollToIndex(0)
        rule.onNodeWithText("07").assertDoesNotExist()
        rule.onNodeWithText("08").assertExists()

        monthColumn().performScrollToNode(hasText("12"))
        rule.onNodeWithText("12").assertExists()
    }

    @Test
    fun confirmingReportsTheMonthAndYearChosen() {
        show(initial = null)

        rule.onNodeWithText("11").performClick()
        rule.onNodeWithText(confirmLabel()).performClick()

        assertEquals(ExpiryValue(11, 2026), picked)
    }

    @Test
    fun cancellingReportsNothingPicked() {
        show(initial = null)

        rule.onNodeWithText(cancelLabel()).performClick()

        assertTrue("cancel did not dismiss", dismissed)
        assertEquals(null, picked)
    }

    /**
     * The opening coercion: a value that has expired names a month this year no longer offers.
     *
     * 03/2026 opened in August lands on 08, the first month still available, rather than on a selection the
     * column does not contain.
     */
    @Test
    fun anExpiredValueOpensOnTheFirstMonthStillOffered() {
        show(initial = ExpiryValue(3, 2026))

        rule.onNodeWithText(confirmLabel()).performClick()

        assertEquals(ExpiryValue(8, 2026), picked)
    }

    /**
     * The other coercion, and the one a year change triggers.
     *
     * 01 is selectable in 2027. Going back to 2026 leaves a month that year does not offer, so it moves to
     * 08 rather than confirming a date already past.
     */
    @Test
    fun goingBackToThisYearDragsAnUnavailableMonthForward() {
        show(initial = null)

        pickYear("2027")
        monthColumn().performScrollToNode(hasText("01"))
        rule.onNodeWithText("01").performClick()
        pickYear("2026")

        rule.onNodeWithText(confirmLabel()).performClick()

        assertEquals(ExpiryValue(8, 2026), picked)
    }

    /** A year that offers every month keeps the month that was already chosen. */
    @Test
    fun aLaterYearKeepsTheMonthAlreadyChosen() {
        show(initial = null)

        rule.onNodeWithText("11").performClick()
        pickYear("2028")

        rule.onNodeWithText(confirmLabel()).performClick()

        assertEquals(ExpiryValue(11, 2028), picked)
    }

    /**
     * Announced as a selection rather than as an action.
     *
     * `selectable` with `Role.RadioButton` is what tells a screen reader which month is chosen; a bare
     * `clickable` exposes an action and no selected state, and the file says so at the modifier.
     */
    @Test
    fun theChosenMonthIsAnnouncedAsSelected() {
        show(initial = null)

        rule.onNodeWithText("08").assertIsSelected()
        rule.onNodeWithText("09").assertIsNotSelected()

        rule.onNodeWithText("09").performClick()

        rule.onNodeWithText("09").assertIsSelected()
        rule.onNodeWithText("08").assertIsNotSelected()
    }

    /**
     * And announced as a radio button, which the selected state alone does not carry.
     *
     * Asserted separately because `selectable` sets the selected state with or without a role: dropping the
     * role leaves every assertion above green while a screen reader stops saying what kind of control this
     * is.
     */
    @Test
    fun aMonthCarriesTheRoleAScreenReaderReads() {
        show(initial = null)

        rule
            .onNodeWithText("08")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    /** Android's minimum touch target, which a row sized to its own text would be under. */
    @Test
    fun aRowIsBigEnoughToTap() {
        show(initial = null)

        rule.onNodeWithText("08").assertHeightIsAtLeast(48.dp)
    }

    private fun show(initial: ExpiryValue?) {
        rule.setContent {
            MaterialTheme {
                ExpiryPickerDialog(
                    today = today,
                    initial = initial,
                    style = PayabliPayInFormDefaults.style(),
                    onPicked = { picked = it },
                    onDismiss = { dismissed = true },
                )
            }
        }
    }

    /**
     * The two lists, told apart by their order in the row: month first, then year.
     *
     * Both are bounded to four rows, so anything further down has to be scrolled to before it exists to
     * assert on at all. Indexing the scrollables is the handle used because neither column carries a test
     * tag today; a tag on each would be a steadier selector than position if this grows a third column.
     */
    private fun monthColumn() = rule.onAllNodes(hasScrollAction())[0]

    private fun yearColumn() = rule.onAllNodes(hasScrollAction())[1]

    private fun pickYear(year: String) {
        yearColumn().performScrollToNode(hasText(year))
        rule.onNodeWithText(year).performClick()
    }

    private fun confirmLabel() = string(com.payabli.sdk.payin.R.string.payabli_payin_confirm)

    private fun cancelLabel() = string(com.payabli.sdk.payin.R.string.payabli_payin_cancel)

    private fun string(id: Int) = rule.activity.getString(id)
}
