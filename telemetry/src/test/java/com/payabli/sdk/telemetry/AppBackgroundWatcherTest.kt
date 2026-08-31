package com.payabli.sdk.telemetry

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the process lifecycle says, and what this makes of it.
 *
 * Driven through a `LifecycleRegistry`, which is the same machinery `ProcessLifecycleOwner` publishes to.
 * `createUnsafe` is what makes it reachable off a device: the ordinary constructor asserts the main thread,
 * and there is not one on a JVM.
 *
 * The behaviours that matter here are the two the counted-activity version got wrong, and neither can be
 * shown by asking the observer directly — both are properties of the source it observes. A rotation does not
 * take the process below `STARTED`, and a late observer is brought up to the current state instead of
 * beginning at zero. So what these pin is the half that is this class's own: that it reports the app going
 * away, once, and reports nothing else.
 */
class AppBackgroundWatcherTest {
    private var backgrounded = 0
    private val watcher = AppBackgroundWatcher { backgrounded++ }

    private val owner =
        object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = registry
        }
    private val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(owner)

    @Test
    fun theAppGoingAwayIsReportedOnce() {
        registry.addObserver(watcher)

        registry.currentState = Lifecycle.State.RESUMED
        assertEquals("nothing should be reported while the app is up", 0, backgrounded)

        registry.currentState = Lifecycle.State.CREATED

        assertEquals(1, backgrounded)
    }

    /**
     * Coming back and going away again is two departures, not one.
     *
     * The flush has to happen on each, because each is a last moment before a process that may not run again.
     */
    @Test
    fun comingBackAndLeavingAgainIsReportedAgain() {
        registry.addObserver(watcher)

        repeat(3) {
            registry.currentState = Lifecycle.State.RESUMED
            registry.currentState = Lifecycle.State.CREATED
        }

        assertEquals(3, backgrounded)
    }

    /**
     * An observer added while the app is already up is not told it went away.
     *
     * This is the case that broke the counted version: the SDK starts after the host's first screen, so a
     * mechanism that assumed it saw the beginning reported a departure that never happened. Here the state is
     * synced on registration, and syncing forward is not a stop.
     */
    @Test
    fun registeringWhileTheAppIsAlreadyUpReportsNothing() {
        registry.currentState = Lifecycle.State.RESUMED

        registry.addObserver(watcher)

        assertEquals(0, backgrounded)
    }

    /**
     * Losing focus is not going away, and the two are one state apart.
     *
     * A dialog over the app, a permission prompt, a partially obscured window: paused, still visible, and
     * still holding a queue that has somewhere to go. Flushing on each would send a request every time a
     * system prompt appeared.
     *
     * This is the only test here that separates `onPause` from `onStop`. A full departure dispatches both,
     * so every other assertion in this class holds either way.
     */
    @Test
    fun theAppMerelyLosingFocusIsNotTheAppGoingAway() {
        registry.addObserver(watcher)
        registry.currentState = Lifecycle.State.RESUMED

        registry.currentState = Lifecycle.State.STARTED

        assertEquals(0, backgrounded)
    }

    /** Removed means removed: a session that ended does not keep reporting through the next one's channel. */
    @Test
    fun aRemovedWatcherHearsNothingFurther() {
        registry.addObserver(watcher)
        registry.currentState = Lifecycle.State.RESUMED

        registry.removeObserver(watcher)
        registry.currentState = Lifecycle.State.CREATED

        assertEquals(0, backgrounded)
    }

    /**
     * The process ending is not the app backgrounding, and only one of them is this class's business.
     *
     * `DESTROYED` passes through the same stop on its way down, so this asserts the count rather than that
     * nothing happened: one departure, not two.
     */
    @Test
    fun theProcessEndingReportsTheSingleDeparture() {
        registry.addObserver(watcher)
        registry.currentState = Lifecycle.State.RESUMED

        registry.currentState = Lifecycle.State.DESTROYED

        assertEquals(1, backgrounded)
    }
}
