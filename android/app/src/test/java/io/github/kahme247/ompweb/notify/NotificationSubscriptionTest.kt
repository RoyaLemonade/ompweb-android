package io.github.kahme247.ompweb.notify

import io.github.kahme247.ompweb.events.EventChannel
import io.github.kahme247.ompweb.events.EventMux
import io.github.kahme247.ompweb.events.EventStream
import io.github.kahme247.ompweb.events.EventTransport
import io.github.kahme247.ompweb.events.SseFrame
import io.github.kahme247.ompweb.net.EventPostResult
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5': the `notifications` channel subscription names both halves of the
 * device identity. A stream without a live presence entry is reachable but not
 * visible, and the server routes it through the preferences of the device that
 * owns it — for which it needs the `deviceId`, not only the `clientId`.
 *
 * This pins the wire body the mux posts; `NotificationCenter` builds the
 * channel from the stored identity (`EventChannel.Notifications(clientId,
 * deviceId)`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSubscriptionTest {

    private class FakeTransport : EventTransport {
        val subscribes = mutableListOf<EventChannel>()
        private var stream: FakeStream? = null

        override suspend fun open(): EventStream = FakeStream().also { stream = it }

        fun send(event: String, data: String) {
            requireNotNull(stream) { "no stream opened yet" }.send(event, data)
        }

        override suspend fun subscribe(
            connectionId: String,
            id: String,
            channel: EventChannel,
        ): EventPostResult {
            subscribes += channel
            return EventPostResult.Ok
        }

        override suspend fun unsubscribe(connectionId: String, id: String) = Unit
    }

    private class FakeStream : EventStream {
        private val channel = Channel<SseFrame>(Channel.UNLIMITED)

        override val frames: Flow<SseFrame> = channel.receiveAsFlow()

        fun send(event: String, data: String) {
            channel.trySend(SseFrame(event, data))
        }

        override fun close() {
            channel.close()
        }
    }

    private val transport = FakeTransport()

    private fun TestScope.mux(): EventMux = EventMux(
        transport = transport,
        scope = backgroundScope,
        backoff = io.github.kahme247.ompweb.events.Backoff(
            baseMillis = 1,
            maxMillis = 4,
            jitterRatio = 0.0,
            random = Random(0),
        ),
        clock = { testScheduler.currentTime },
    )

    @Test
    fun `the notifications subscription carries the device id`() = runTest {
        val mux = mux()
        backgroundScope.launch {
            mux.subscribe(EventChannel.Notifications(clientId = "client-1", deviceId = "device-1")).collect {}
        }
        runCurrent()

        transport.send(EventMux.MUX_CONTROL_EVENT, """{"type":"hello","connectionId":"c1"}""")
        runCurrent()

        val channel = transport.subscribes.filterIsInstance<EventChannel.Notifications>().single()
        assertEquals("client-1", channel.clientId)
        assertEquals("device-1", channel.deviceId)

        val body: JsonObject = channel.toJson()
        assertEquals("notifications", body["kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals("client-1", body["clientId"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "the server routes this stream by device, so the id must be on the wire",
            "device-1",
            body["deviceId"]?.jsonPrimitive?.contentOrNull,
        )
        assertTrue(
            "two devices never share one subscription key",
            channel.key.contains("device-1"),
        )
    }
}
