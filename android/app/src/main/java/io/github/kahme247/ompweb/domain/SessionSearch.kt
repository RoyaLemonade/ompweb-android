package io.github.kahme247.ompweb.domain

import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import java.util.Locale

/**
 * Sidebar search, ported from `components/SessionSidebar.tsx`
 * (`visibleProjectEntries`).
 *
 * A session matches on its stored title (`name`) or the **full** first user
 * message. Sessions without a stored title are therefore still findable, no
 * matter where in the first message the term appears. The path, cwd and id are
 * not searchable — the web does not match them either.
 */
fun sessionMatchesSearch(session: SessionSummary, query: String): Boolean {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return true
    return (session.name ?: "").lowercase(Locale.ROOT).contains(q) ||
        session.firstMessage.lowercase(Locale.ROOT).contains(q)
}

/**
 * A workspace with no matching sessions stays visible while its folder label
 * or alias matches the query (the web's "custom workspace name is findable"
 * clause).
 */
fun projectMatchesSearch(project: ManagedProject, query: String): Boolean {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return false
    return projectPathLabel(project.path).lowercase(Locale.ROOT).contains(q) ||
        (project.alias ?: "").lowercase(Locale.ROOT).contains(q)
}
