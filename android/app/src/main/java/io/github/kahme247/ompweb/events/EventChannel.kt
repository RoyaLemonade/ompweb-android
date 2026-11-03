package io.github.kahme247.ompweb.events

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The channels of `/api/events` the app subscribes to, mirroring
 * `EventStreamChannel` in `lib/event-stream-protocol.ts`.
 */
sealed interface EventChannel {

    /** Stable identity of the channel; two subscriptions with the same key share one server subscription. */
    val key: String

    fun toJson(): JsonObject

    /** Running-session ids plus session-list refresh hints. */
    data object Running : EventChannel {
        override val key: String = "running"

        override fun toJson(): JsonObject = buildJsonObject { put("kind", "running") }
    }

    /** One session's live frames. */
    data class Session(val sessionId: String) : EventChannel {
        override val key: String = "session:$sessionId"

        override fun toJson(): JsonObject = buildJsonObject {
            put("kind", "session")
            put("sessionId", sessionId)
        }
    }

    /**
     * Host-tool calls of sessions no tab watches, so those turns never block
     * (protocol §2.3 `host-tools`). Answered per call with `host_tool_result`.
     */
    data object HostTools : EventChannel {
        override val key: String = "host-tools"

        override fun toJson(): JsonObject = buildJsonObject { put("kind", "host-tools") }
    }

    /**
     * One file's changes (web `openFileChannel`): frames `connected`,
     * `change` and `error`. [sessionId] authorizes a file outside the
     * workspace through that session's roots.
     */
    data class File(val path: String, val sessionId: String?) : EventChannel {
        override val key: String = "file:$path|${sessionId.orEmpty()}"

        override fun toJson(): JsonObject = buildJsonObject {
            put("kind", "file")
            put("path", path)
            if (sessionId == null) put("sessionId", JsonNull) else put("sessionId", sessionId)
        }
    }

    /**
     * Server-routed notifications for one device identity (`clientId`), which
     * the notification service keeps open in the background.
     *
     * [deviceId] names the device the stream belongs to, exactly like the
     * `PUT /api/notifications/devices` identity. A stream without a live
     * presence entry is reachable but not visible, and the server routes such
     * a stream through *its own* device preferences (an in-page delivery only
     * while no push subscription covers the device) — without the id it could
     * only guess which device's prefs apply.
     */
    data class Notifications(val clientId: String, val deviceId: String) : EventChannel {
        override val key: String = "notifications:$clientId|$deviceId"

        override fun toJson(): JsonObject = buildJsonObject {
            put("kind", "notifications")
            put("clientId", clientId)
            put("deviceId", deviceId)
        }
    }
}
