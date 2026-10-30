package io.github.kahme247.ompweb.chat

import io.github.kahme247.ompweb.domain.SubmitDuringRunBehavior
import io.github.kahme247.ompweb.net.ApiError
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** How a queued message is delivered once the connection is back. */
enum class OutboxDelivery { PROMPT, STEER, FOLLOW_UP }

/**
 * The rendered form of one outbox entry in the open chat: the pending bubble
 * shows the text, the number of kept images and the "waiting" state; the
 * payload itself stays in the store until delivery.
 */
data class OutboxBubbleUi(
    val id: String,
    val text: String,
    val imageCount: Int,
)

/**
 * The delivery of a queued message follows the same rule as a submit made
 * right now (web `omp-web:submit-during-run`): an idle session gets a prompt,
 * a running one is steered or queued per the user's preference.
 */
fun outboxDelivery(busy: Boolean, behavior: SubmitDuringRunBehavior): OutboxDelivery = when {
    !busy -> OutboxDelivery.PROMPT
    behavior == SubmitDuringRunBehavior.STEER -> OutboxDelivery.STEER
    else -> OutboxDelivery.FOLLOW_UP
}

/**
 * A send failed without an answer and the request provably never left the
 * device: the message goes into the outbox. Everything else stays on the
 * existing path — a 4xx/omp refusal is an answer, and `session_slow` with
 * `maybeStarted` means the frame may already run (retrying would send it
 * twice, so it is never queued).
 *
 * Only a failure that happened *before* the request could be written counts as
 * "never left": a refused connection, an unresolvable host, no route and a
 * failed TLS handshake. A read/call timeout or an EOF may have reached the
 * server, so it is not queued (and a queued delivery that hits one is not
 * retried) — exactly-once outranks automatic retry.
 */
fun isOutboxableSendFailure(error: Throwable): Boolean {
    val network = error as? ApiError.Network ?: return false
    return neverLeftDevice(network.cause)
}

/** `true` when a cause in the chain proves nothing was written. */
internal fun neverLeftDevice(cause: Throwable?): Boolean {
    var current = cause
    var depth = 0
    while (current != null && depth++ < MAX_CAUSE_DEPTH) {
        when (current) {
            is ConnectException,
            is UnknownHostException,
            is NoRouteToHostException,
            is PortUnreachableException,
            is SSLHandshakeException,
            -> return true
            is SocketTimeoutException ->
                if (current.message?.contains("connect", ignoreCase = true) == true) return true
        }
        current = current.cause
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8
