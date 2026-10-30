package io.github.kahme247.ompweb.chat

import io.github.kahme247.ompweb.domain.SubmitDuringRunBehavior
import io.github.kahme247.ompweb.net.ApiError
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which send failures belong in the outbox, and how a queued message is routed
 * once the connection is back. A failure that may have reached the server is
 * never queued (exactly-once); `session_slow`/`maybeStarted` keeps its existing
 * run-locked path.
 */
class OutboxLogicTest {

    @Test
    fun aRequestThatCannotHaveLeftTheDeviceIsOutboxable() {
        assertTrue(isOutboxableSendFailure(ApiError.Network(ConnectException("refused"))))
        assertTrue(isOutboxableSendFailure(ApiError.Network(UnknownHostException("omp.local"))))
        assertTrue(
            isOutboxableSendFailure(ApiError.Network(SocketTimeoutException("connect timed out"))),
        )
        assertTrue(
            "an IOException chain keeps the decisive cause",
            isOutboxableSendFailure(ApiError.Network(IOException(ConnectException("refused")))),
        )
    }

    @Test
    fun anAmbiguousOrRefusedSendIsNotQueued() {
        assertFalse(
            "a read timeout may have reached the server",
            isOutboxableSendFailure(ApiError.Network(SocketTimeoutException("read timed out"))),
        )
        assertFalse(isOutboxableSendFailure(ApiError.Network(EOFException())))
        assertFalse(isOutboxableSendFailure(ApiError.Network(IOException("broken pipe"))))
        assertFalse(
            "omp refused the frame",
            isOutboxableSendFailure(ApiError.ServerResponse(500, "agent_busy", "busy")),
        )
        assertFalse(isOutboxableSendFailure(ApiError.Http(400, "bad request")))
        assertFalse(isOutboxableSendFailure(ApiError.SessionRunningElsewhere(null)))
        assertFalse(
            "maybeStarted keeps the run locked instead of queueing",
            isOutboxableSendFailure(ApiError.SessionSlow("slow", maybeStarted = true)),
        )
        assertFalse(isOutboxableSendFailure(ApiError.SessionSlow("slow")))
        assertFalse(isOutboxableSendFailure(IllegalStateException("other")))
    }

    @Test
    fun deliveryFollowsTheLiveRunStateAndTheUsersPreference() {
        assertEquals(OutboxDelivery.PROMPT, outboxDelivery(busy = false, SubmitDuringRunBehavior.STEER))
        assertEquals(OutboxDelivery.PROMPT, outboxDelivery(busy = false, SubmitDuringRunBehavior.QUEUE))
        assertEquals(OutboxDelivery.STEER, outboxDelivery(busy = true, SubmitDuringRunBehavior.STEER))
        assertEquals(OutboxDelivery.FOLLOW_UP, outboxDelivery(busy = true, SubmitDuringRunBehavior.QUEUE))
    }
}
