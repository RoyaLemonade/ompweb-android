package io.github.kahme247.ompweb.net

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scan route may time out while the server is still working. Saying
 * "cannot reach the server" for that hides the one fact the user needs: the
 * request arrived and only the answer was late, so a retry is worth it.
 */
class ApiErrorTest {

    @Test
    fun `a read timeout reads as a timeout, not as an unreachable server`() {
        val message = ApiError.Network(SocketTimeoutException("read timed out")).userMessage()

        assertTrue(message, message.contains("did not answer in time"))
    }

    @Test
    fun `a refused connection still reads as unreachable`() {
        assertEquals("Cannot reach the server", ApiError.Network(IOException("connection refused")).userMessage())
    }
}
