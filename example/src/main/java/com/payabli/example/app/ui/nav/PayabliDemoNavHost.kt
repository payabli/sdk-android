package com.payabli.example.app.ui.nav

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.payabli.example.app.ui.capture.CaptureResultScreen
import com.payabli.example.app.ui.capture.CaptureScreen
import com.payabli.example.app.ui.capture.CaptureViewModel
import com.payabli.example.app.ui.method.PaymentMethodSavedScreen
import com.payabli.example.app.ui.method.PaymentMethodScreen
import com.payabli.example.app.ui.method.PaymentMethodViewModel
import com.payabli.example.app.ui.payment.PaymentFlowActions
import com.payabli.example.app.ui.setup.SetupScreen
import com.payabli.example.app.ui.setup.SetupViewModel
import com.payabli.example.app.ui.taptopay.TapToPayActions
import com.payabli.example.app.ui.taptopay.TapToPayScreen
import com.payabli.example.app.ui.taptopay.TapToPayViewModel

/**
 * The whole navigation graph: four capability areas, each with its own back stack.
 *
 * [NavigationSuiteScaffold] makes the app a bottom bar on a phone and a navigation rail on a tablet
 * or an unfolded foldable, with no second layout to maintain.
 *
 * The `saveState`/`restoreState` pair on the navigation call is what keeps each area's stack alive:
 * push a result screen under Capture, look at Setup, come back, and the result is still there. That
 * is the behaviour the instrumented smoke test pins, because nothing else can.
 */
@Composable
fun PayabliDemoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected =
                    currentDestination?.hierarchy?.any { it.route == destination.graphRoute } == true
                item(
                    modifier = Modifier.testTag(destination.testTag),
                    selected = selected,
                    onClick = { navController.switchTo(destination) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = {
                        Text(
                            text = destination.navLabel,
                            // One line, centred. A label that wraps makes its item taller than the
                            // rest of the bar and left-aligns its text under a centred icon, which
                            // is what a long label did on a 720px screen.
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    },
                )
            }
        },
    ) {
        NavHost(navController = navController, startDestination = PaymentMethodGraph) {
            paymentMethodGraph(navController)
            captureGraph(navController)
            tapToPayGraph()
            setupGraph()
        }
    }
}

private fun NavGraphBuilder.paymentMethodGraph(navController: NavHostController) {
    navigation<PaymentMethodGraph>(startDestination = PaymentMethodHome) {
        composable<PaymentMethodHome> { entry ->
            val model =
                navController.graphViewModel<PaymentMethodGraph, PaymentMethodViewModel>(entry) {
                    PaymentMethodViewModel.from(it)
                }
            val state by model.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state.outcomeReady) {
                if (state.outcomeReady) {
                    model.outcomeShown()
                    navController.navigate(PaymentMethodSaved)
                }
            }
            PaymentMethodScreen(
                state = state,
                actions =
                    PaymentFlowActions(
                        onCheckToken = model::checkToken,
                        onOpenSheet = model::openSheet,
                        onDismissSheet = model::dismissSheet,
                        onSubmit = model::submit,
                    ),
            )
        }
        composable<PaymentMethodSaved> { entry ->
            // The same shape as the capture result below, and for the same reason. What was stored
            // lives in the graph-scoped view model, and the process can be killed while this screen
            // is on top; Navigation restores the destination and the model comes back empty, so the
            // screen announced "Payment method saved" for an outcome it could no longer establish.
            val model =
                navController.graphViewModel<PaymentMethodGraph, PaymentMethodViewModel>(entry) {
                    PaymentMethodViewModel.from(it)
                }
            val state by model.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state.storedMethod) {
                if (state.storedMethod == null) navController.popBackStack()
            }
            PaymentMethodSavedScreen(onDone = { navController.popBackStack() })
        }
    }
}

private fun NavGraphBuilder.captureGraph(navController: NavHostController) {
    navigation<CaptureGraph>(startDestination = CaptureHome) {
        composable<CaptureHome> { entry ->
            val model =
                navController.graphViewModel<CaptureGraph, CaptureViewModel>(entry) {
                    CaptureViewModel.from(it)
                }
            val state by model.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state.outcomeReady) {
                if (state.outcomeReady) {
                    model.outcomeShown()
                    navController.navigate(CaptureResult)
                }
            }
            CaptureScreen(
                state = state,
                actions =
                    PaymentFlowActions(
                        onCheckToken = model::checkToken,
                        onOpenSheet = model::openSheet,
                        onDismissSheet = model::dismissSheet,
                        onSubmit = model::submit,
                    ),
            )
        }
        composable<CaptureResult> { entry ->
            // The graph's entry, not this destination's, so the result the previous screen
            // produced is the one shown. Passing it as a navigation argument would mean
            // URL-encoding an arbitrary API response into a route.
            val model =
                navController.graphViewModel<CaptureGraph, CaptureViewModel>(entry) {
                    CaptureViewModel.from(it)
                }
            val state by model.uiState.collectAsStateWithLifecycle()
            // The result lives in the view model, and the process can be killed while this screen is
            // on top. Navigation restores the destination and the model comes back empty, which left
            // a screen reading "No payment yet" with its Done button below the early return, so
            // nothing on it went anywhere. Going back is the only honest answer: the payment
            // happened, and this screen has nothing to say about it any more.
            LaunchedEffect(state.lastResult) {
                if (state.lastResult == null) navController.popBackStack()
            }
            CaptureResultScreen(
                result = state.lastResult,
                onDone = { navController.popBackStack() },
            )
        }
    }
}

private fun NavGraphBuilder.tapToPayGraph() {
    navigation<TapToPayGraph>(startDestination = TapToPayHome) {
        composable<TapToPayHome> {
            val model = demoViewModel { TapToPayViewModel.from(it) }
            val state by model.uiState.collectAsStateWithLifecycle()
            TapToPayScreen(
                state = state,
                actions = remember(model) { TapToPayActions.from(model) },
            )
        }
    }
}

private fun NavGraphBuilder.setupGraph() {
    navigation<SetupGraph>(startDestination = SetupHome) {
        composable<SetupHome> {
            val model = demoViewModel { SetupViewModel.from(it) }
            val state by model.uiState.collectAsStateWithLifecycle()
            SetupScreen(
                state = state,
                onProbeToken = model::probeToken,
                onProbeHealth = model::probeHealth,
                onRecheck = model::recheck,
            )
        }
    }
}

/**
 * Moves to another capability area, keeping the one being left.
 *
 * `saveState` and `restoreState` together are what make each area remember where it was;
 * `launchSingleTop` stops tapping the current item stacking a second copy of it.
 */
private fun NavHostController.switchTo(destination: TopLevelDestination) {
    navigate(destination.graph) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
