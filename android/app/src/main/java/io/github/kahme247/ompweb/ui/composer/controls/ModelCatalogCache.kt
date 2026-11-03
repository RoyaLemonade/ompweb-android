package io.github.kahme247.ompweb.ui.composer.controls

import io.github.kahme247.ompweb.net.ModelsResponse

/**
 * The `GET /api/models` catalog of one server and cwd, kept for a while.
 *
 * The catalog is ~310 KB for a real configuration and changes only when the
 * model configuration changes — but it was fetched on every chat open and
 * switch (about 9 MB a day). One entry per origin and cwd is kept for
 * [ttlMillis]; the controller drops everything on an explicit model-config
 * save, so a new model appears without waiting for the TTL.
 *
 * The origin is part of the key on purpose: a server switch (login/logout)
 * usually keeps the cwd string, and the catalog of the old installation names
 * providers and models the new one may not have. `AppContainer.clearLocalUserData`
 * additionally drops the table, so even the same origin under a new account
 * starts empty.
 *
 * Not thread-safe state shared across coroutines: callers on the main/UI
 * dispatcher only (the composer view models), guarded anyway.
 */
class ModelCatalogCache(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMillis: Long = TTL_MILLIS,
) {

    private val lock = Any()
    private val entries = HashMap<Key, Entry>()

    private data class Key(val origin: String, val cwd: String)

    private data class Entry(val catalog: ModelsResponse, val storedAt: Long)

    /** The cached catalog of [cwd] on [origin] while it is younger than the TTL. */
    fun get(origin: String, cwd: String): ModelsResponse? = synchronized(lock) {
        val key = Key(origin, cwd)
        val entry = entries[key] ?: return null
        val age = clock() - entry.storedAt
        if (age !in 0 until ttlMillis) {
            entries.remove(key)
            return null
        }
        entry.catalog
    }

    fun put(origin: String, cwd: String, catalog: ModelsResponse) = synchronized(lock) {
        entries[Key(origin, cwd)] = Entry(catalog, clock())
    }

    /** An explicit model-config change or a server/account change made every entry stale. */
    fun invalidate() = synchronized(lock) { entries.clear() }

    companion object {
        /** One hour: long enough to cover a day of chat switching, short enough
         * to pick up a model added by another client without a restart. */
        const val TTL_MILLIS: Long = 60L * 60L * 1000L
    }
}
