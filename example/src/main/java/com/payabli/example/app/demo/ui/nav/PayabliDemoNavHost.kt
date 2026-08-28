package com.payabli.example.app.demo.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.payabli.example.app.PayabliDemoApplication
import com.payabli.example.app.demo.net.checkToken
import com.payabli.example.app.demo.simple.SimpleCaptureScreen
import com.payabli.example.app.demo.simple.SimpleCaptureViewModel
import com.payabli.example.app.demo.ui.capture.CaptureResultScreen
import com.payabli.example.app.demo.ui.capture.CaptureScreen
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.demo.ui.method.PaymentMethodSavedScreen
import com.payabli.example.app.demo.ui.method.PaymentMethodScreen
import com.payabli.example.app.demo.ui.method.PaymentMethodViewModel
import com.payabli.example.app.demo.ui.payment.PaymentFlowActions
import com.payabli.example.app.demo.ui.setup.SetupScreen
import com.payabli.example.app.demo.ui.setup.SetupViewModel
import com.payabli.example.app.demo.ui.taptopay.TapToPayActions
import com.payabli.example.app.demo.ui.taptopay.TapToPayScreen
import com.payabli.example.app.demo.ui.taptopay.TapToPayViewModel
import java.math.BigDecimal

/**
 * The whole navigation graph: four capability areas, each with its own back stack.
 *
 * [NavigationSuiteScaffold] is the bottom bar on a phone and the navigation rail on a tablet or an
 * unfolded foldable, from one layout.
 *
 * `saveState`/`restoreState` keeps each area's stack alive: push a result screen under Capture, look
 * at Setup, come back, and the result is still there.
 */
@Composable
fun PayabliDemoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val container = (LocalContext.current.applicationContext as PayabliDemoApplication).container
    val simpleCaptureShown by container.simpleCapture.shown.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // The bar's height comes off the content above it, keyboard up or not, so hiding it while a payer types
    // gives that height to the form.
    val barState = rememberNavigationSuiteScaffoldState()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(keyboardVisible) { if (keyboardVisible) barState.hide() else barState.show() }

    NavigationSuiteScaffold(
        modifier = modifier,
        state = barState,
        navigationSuiteItems = {
            shownDestinations(simpleCaptureShown).forEach { destination ->
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
                        onCompleted = model::onCompleted,
                        onFailed = model::onFailed,
                        onStartOver = model::startOver,
                    ),
            )
        }
        composable<PaymentMethodSaved> { entry ->
            // The graph's entry, so what the form screen stored is what this one describes. Navigation
            // restores this destination after process death with the model empty, which the effect below
            // reads: "Payment method saved" needs something to name.
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
    navigation<SimpleCaptureGraph>(startDestination = SimpleCaptureHome) {
        composable<SimpleCaptureHome> { entry ->
            SimpleCaptureScreen(
                viewModel =
                    navController.graphViewModel<SimpleCaptureGraph, SimpleCaptureViewModel>(entry) {
                        SimpleCaptureViewModel(it.sessionSource, it.configuration.entryPoint)
                    },
                amount = SIMPLE_CAPTURE_AMOUNT,
            )
        }
    }

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
                        onCompleted = model::onCompleted,
                        onFailed = model::onFailed,
                        onStartOver = model::startOver,
                    ),
            )
        }
        composable<CaptureResult> { entry ->
            // The graph's entry, not this destination's, so the result the previous screen
            // produced is the one shown. A route carries no arbitrary API response.
            val model =
                navController.graphViewModel<CaptureGraph, CaptureViewModel>(entry) {
                    CaptureViewModel.from(it)
                }
            val state by model.uiState.collectAsStateWithLifecycle()
            // The result lives in the view model, and the process can be killed while this screen
            // is on top. Navigation restores the destination and the model comes back empty, which
            // left a screen reading "No payment yet" whose Done button sat below the early return
            // and did nothing.
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
                onSuppliesDemoCustomerChange = model::setSuppliesDemoCustomer,
                onShowSimpleCaptureChange = model::setShowSimpleCapture,
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

/** What the Simple Capture screen charges. Fixed, because that screen collects nothing but a card. */
private val SIMPLE_CAPTURE_AMOUNT: BigDecimal = BigDecimal("12.34")
