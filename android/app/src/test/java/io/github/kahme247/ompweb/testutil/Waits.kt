package io.github.kahme247.ompweb.testutil

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * How long a test waits for the condition it needs.
 *
 * A loaded CI runner can take seconds between a request and its answer, so the
 * old eight-second deadlines in each test class timed out although nothing was
 * wrong. This bound is a safety net for a broken test, not a check: a passing
 * test still returns on the first observation that satisfies it.
 */
const val WAIT_TIMEOUT_MS = 60_000L

/** How often [waitFor] looks again; short enough to be invisible next to the bound. */
private const val POLL_MS = 10L

/**
 * Waits until [condition] holds.
 *
 * [message] names what is awaited; [describe] adds the state at the moment of
 * the failure, so a genuine regression reads like the assertion it is. No
 * retries: the condition is observed, never run twice for a verdict.
 */
fun waitFor(
    message: String,
    timeoutMillis: Long = WAIT_TIMEOUT_MS,
    describe: () -> Any? = { null },
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(POLL_MS)
    }
    val detail = describe()?.let { "; $it" }.orEmpty()
    throw AssertionError("timed out after $timeoutMillis ms waiting for $message$detail")
}

/**
 * The deterministic form of [waitFor] for state a test can observe: the
 * condition is re-evaluated on every emission instead of being polled, and
 * [timeoutMillis] only ends a test whose state never arrives.
 *
 * Throws [AssertionError] (not a coroutine timeout) so a failure names the
 * awaited state like every other assertion in the suite.
 */
fun <T> StateFlow<T>.awaitValue(
    message: String,
    timeoutMillis: Long = WAIT_TIMEOUT_MS,
    predicate: (T) -> Boolean,
): T = runBlocking {
    try {
        withTimeout(timeoutMillis) { first(predicate) }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("timed out after $timeoutMillis ms waiting for $message; state=$value", timeout)
    }
}
