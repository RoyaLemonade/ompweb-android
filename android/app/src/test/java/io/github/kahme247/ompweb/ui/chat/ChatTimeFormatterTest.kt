package io.github.kahme247.ompweb.ui.chat

import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap dialog has to carry the clock time too: a header older than the
 * current year shows the date only, so a dialog without the time would leave
 * those turns with no visible time at all.
 */
class ChatTimeFormatterTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val locale = Locale.US

    // The formatter is handed the zone it should render in; the test machine's
    // default zone must not leak into the expected strings.
    private fun format(pattern: String) = SimpleDateFormat(pattern, locale).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }

    private val formatter = ChatTimeFormatter(
        timeFormat = format("h:mm a"),
        sameYearFormat = format("d MMM"),
        anyYearFormat = format("d MMM yyyy"),
        fullFormat = format("MMMM d, yyyy"),
        zone = zone,
        todayLabel = "Today",
        yesterdayLabel = "Yesterday",
    )

    private fun stamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the tapped dialog carries the date and the time`() {
        val value = formatter.full(stamp(2026, 10, 26, 15, 43))

        assertEquals("October 26, 2026, 3:43 PM", value)
    }

    @Test
    fun `a header older than this year omits the time, the dialog does not`() {
        val old = stamp(2025, 3, 4, 9, 5)
        val now = stamp(2026, 10, 26, 12, 0)

        val header = formatter.header(old, now)

        assertEquals("4 Mar 2025", header)
        assertFalse(header.contains(":"))
        assertTrue(formatter.full(old).contains("9:05 AM"))
    }

    @Test
    fun `the action row stamp reads like the header for today, yesterday and this year`() {
        val now = stamp(2026, 10, 26, 12, 0)

        assertEquals("9:05 AM", formatter.rowStamp(stamp(2026, 10, 26, 9, 5), now))
        assertEquals("Yesterday 9:05 AM", formatter.rowStamp(stamp(2026, 10, 25, 9, 5), now))
        assertEquals("4 Mar, 9:05 AM", formatter.rowStamp(stamp(2026, 3, 4, 9, 5), now))
    }

    @Test
    fun `the action row of an earlier year keeps the clock time the header drops`() {
        val now = stamp(2026, 10, 26, 12, 0)

        assertEquals("4 Mar 2025, 9:05 AM", formatter.rowStamp(stamp(2025, 3, 4, 9, 5), now))
    }
}
