package com.payabli.example.app

import android.app.Application

/**
 * Owns the [AppContainer] for the life of the process.
 *
 * The Application owns it, so nothing keeps a Context alive past the process it belongs to and a
 * test can build a container of its own.
 */
class PayabliDemoApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
