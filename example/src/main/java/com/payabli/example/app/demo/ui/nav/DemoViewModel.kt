package com.payabli.example.app.demo.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.payabli.example.app.AppContainer
import com.payabli.example.app.PayabliDemoApplication

/**
 * Builds a screen's view model from the app container.
 *
 * Lifecycle's own `viewModelFactory { initializer { } }` builder: no reflection, no code generation,
 * no framework. The construction is an ordinary function call, written out at each call site, which
 * is the whole of the dependency injection in this app.
 *
 * @param viewModelStoreOwner pass the graph's back stack entry to share one instance across the
 *   screens of a graph, which is how the capture screen and its result screen see the same result.
 *   Left alone, the model is scoped to the destination that asked for it.
 */
@Composable
inline fun <reified VM : ViewModel> demoViewModel(
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as PayabliDemoApplication).container
    return viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        factory = viewModelFactory { initializer { create(container) } },
    )
}

/**
 * A view model shared by every screen in one navigation graph.
 *
 * This is how the capture form and its result screen see the same instance.
 *
 * The parent entry is remembered against [entry], so it is looked up once while this destination is
 * on screen. Looking it up on each recomposition throws during the exit transition: the destination
 * is still composing after the back stack has moved on, and `getBackStackEntry` reports the graph is
 * no longer there. That crash needed the screen to recompose mid-transition to show up, which made it
 * look like a bug in whatever triggered the recomposition.
 */
@Composable
inline fun <reified G : Any, reified VM : ViewModel> NavHostController.graphViewModel(
    entry: NavBackStackEntry,
    crossinline create: (AppContainer) -> VM,
): VM {
    val parent = remember(entry) { getBackStackEntry<G>() }
    return demoViewModel(parent, create)
}
