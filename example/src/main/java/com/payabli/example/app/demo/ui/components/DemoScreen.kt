package com.payabli.example.app.demo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.payabli.example.app.demo.ui.theme.Dimens

/**
 * The frame every screen in this app shares: a title bar that collapses as the content scrolls, and
 * a scrolling column beneath it.
 *
 * One place, so four screens cannot drift into four different paddings, and so the inset handling is
 * written once. The content is a plain `Column` in a `verticalScroll`: these screens are a few dozen
 * items with no recycling to gain, and a `LazyColumn` would cost the ability to nest a scrolling
 * block inside one.
 *
 * @param actions goes in the title bar's trailing slot; the Tap to pay screen puts its session chip
 *   there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = { actions() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    // Before verticalScroll, so the keyboard takes height off the viewport.
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
            content = content,
        )
    }
}
