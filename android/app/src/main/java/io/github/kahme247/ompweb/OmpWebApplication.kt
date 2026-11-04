package io.github.kahme247.ompweb

import android.app.Application
import io.github.kahme247.ompweb.notify.NotificationOpenToken

class OmpWebApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A notification posted later carries an open token; its secret must
        // already be on disk by then (see NotificationOpenToken.prepare).
        NotificationOpenToken.prepare(this)
    }
}
