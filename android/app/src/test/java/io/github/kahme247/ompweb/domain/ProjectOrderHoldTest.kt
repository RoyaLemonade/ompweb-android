package io.github.kahme247.ompweb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The freeze/apply decision behind the activity-ordered project list. */
class ProjectOrderHoldTest {

    private val settled = listOf("/proj/a", "/proj/b")
    private val reordered = listOf("/proj/b", "/proj/a")

    @Test
    fun `a quiet list adopts a new order immediately`() {
        val hold = ProjectOrderHold(settleMillis = 1_500)
        assertEquals(settled, hold.order(settled, nowMillis = 0))
        assertEquals(reordered, hold.order(reordered, nowMillis = 1))
    }

    @Test
    fun `the order stays frozen for the whole interaction`() {
        val hold = ProjectOrderHold(settleMillis = 1_500)
        assertEquals(settled, hold.order(settled, nowMillis = 0))

        hold.beginInteraction()
        assertTrue(hold.interacting)
        assertEquals(settled, hold.order(reordered, nowMillis = 100))
        assertEquals(settled, hold.order(reordered, nowMillis = 60_000))

        hold.endInteraction(nowMillis = 60_000)
        assertFalse(hold.interacting)
    }

    @Test
    fun `a new order is applied only after the settle window`() {
        val hold = ProjectOrderHold(settleMillis = 1_500)
        assertEquals(settled, hold.order(settled, nowMillis = 0))
        hold.beginInteraction()
        hold.endInteraction(nowMillis = 1_000)
        // A finger lifting is not the end of the freeze.
        assertEquals(settled, hold.order(reordered, nowMillis = 1_001))
        assertEquals(1400L, hold.settleDelayMillis(nowMillis = 1_100))
        assertEquals(settled, hold.order(reordered, nowMillis = 2_400))
        assertEquals(reordered, hold.order(reordered, nowMillis = 2_500))
    }

    @Test
    fun `settle delay counts down and disappears once the window is over`() {
        val hold = ProjectOrderHold(settleMillis = 1_500)
        hold.order(settled, nowMillis = 0)
        hold.beginInteraction()
        hold.endInteraction(nowMillis = 1_000)
        assertEquals(1_500L, hold.settleDelayMillis(nowMillis = 1_000))
        assertEquals(500L, hold.settleDelayMillis(nowMillis = 2_000))
        assertEquals(0L, hold.settleDelayMillis(nowMillis = 2_600))
        assertNull("nothing pending without an interaction", ProjectOrderHold().settleDelayMillis(0))
    }

    @Test
    fun `touching again during the window restarts it`() {
        val hold = ProjectOrderHold(settleMillis = 1_500)
        hold.order(settled, nowMillis = 0)
        hold.beginInteraction()
        hold.endInteraction(nowMillis = 1_000)
        hold.order(reordered, nowMillis = 2_000)

        hold.beginInteraction()
        assertEquals(settled, hold.order(reordered, nowMillis = 2_400))
        hold.endInteraction(nowMillis = 2_400)
        assertEquals(settled, hold.order(reordered, nowMillis = 3_800))
        assertEquals(reordered, hold.order(reordered, nowMillis = 3_900))
    }

    @Test
    fun `a frozen order survives a project leaving the list`() {
        val hold = ProjectOrderHold(settleMillis = 1_500)
        val three = listOf("/proj/a", "/proj/b", "/proj/c")
        assertEquals(three, hold.order(three, nowMillis = 0))
        hold.beginInteraction()
        val withoutC = three - "/proj/c"
        // The gate keeps the frozen *paths*; the screen drops unknown ones.
        assertEquals(three, hold.order(withoutC, nowMillis = 10))
        hold.endInteraction(nowMillis = 10)
        assertEquals(withoutC, hold.order(withoutC, nowMillis = 1_600))
    }
}
