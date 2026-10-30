package io.github.kahme247.ompweb.data

import io.github.kahme247.ompweb.domain.ComposerImage
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The offline outbox: order, persistence across a restart, exactly-once
 * (a claimed record is never sent again), origin isolation and the image files
 * it keeps in app storage.
 */
class OutboxStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_000L

    private fun store(root: File = File(temp.root, "outbox")) = OutboxStore(root, clock = { now++ })

    private val origin = "http://omp:30177"
    private val other = "http://other:30178"

    private fun image(text: String, mime: String = "image/png"): ComposerImage =
        ComposerImage(Base64.getEncoder().encodeToString(text.toByteArray()), mime)

    @Test
    fun queueKeepsTheSendOrderAndSurvivesARestart() {
        val root = File(temp.root, "outbox")
        val first = store(root)
        first.enqueue(origin, "s1", "/w", "one", emptyList())
        first.enqueue(origin, "s1", "/w", "two", listOf(image("png-bytes")))
        first.enqueue(origin, "s2", "/w", "three", emptyList())

        // A new instance over the same directory is a process restart.
        val restarted = store(root)
        val load = restarted.read(origin)
        assertEquals(listOf("one", "two", "three"), load.entries.map { it.text })
        assertEquals("the image rides along and is back as base64", 1, load.entries[1].images.size)
        assertEquals("png-bytes", String(Base64.getDecoder().decode(load.entries[1].images.single().data)))
        assertEquals("image/png", load.entries[1].images.single().mimeType)
    }

    @Test
    fun anotherOriginNeverSeesTheQueue() {
        val outbox = store()
        outbox.enqueue(origin, "s1", null, "mine", emptyList())

        assertTrue(outbox.read(other).entries.isEmpty())
        assertEquals("mine", outbox.read(origin).entries.single().text)
    }

    @Test
    fun aClaimedMessageIsNeverDeliveredTwiceAfterADeadProcess() {
        val root = File(temp.root, "outbox")
        val outbox = store(root)
        val entry = outbox.enqueue(origin, "s1", null, "send me", emptyList())!!
        assertEquals(OutboxClaim.CLAIMED, outbox.claim(origin, entry.id))

        // The POST may have reached the server; a refresh does not resurrect it.
        assertTrue("a claimed record is hidden, not shown as pending", outbox.read(origin).entries.isEmpty())
        assertEquals(
            "claiming again reports the record as gone",
            OutboxClaim.GONE,
            outbox.claim(origin, "missing"),
        )

        val restarted = store(root)
        val recovered = restarted.recover(origin)
        assertEquals("the dead process' claim is finalized", 1, recovered.uncertain)
        assertTrue("and never sent a second time", recovered.entries.isEmpty())
        assertEquals("the recovery runs empty afterwards", 0, restarted.recover(origin).uncertain)
    }

    @Test
    fun aReleasedMessageReturnsToItsPlaceInTheQueue() {
        val outbox = store()
        val first = outbox.enqueue(origin, "s1", null, "one", emptyList())!!
        outbox.enqueue(origin, "s1", null, "two", emptyList())

        outbox.claim(origin, first.id)
        outbox.release(origin, first.id)

        assertEquals(listOf("one", "two"), outbox.read(origin).entries.map { it.text })
        assertEquals("recovery has nothing to finalize", 0, outbox.recover(origin).uncertain)
    }

    @Test
    fun cancelDropsTheRecordAndItsImages() {
        val root = File(temp.root, "outbox")
        val outbox = store(root)
        val entry = outbox.enqueue(origin, "s1", null, "with image", listOf(image("bytes")))!!
        val images = File(root, outbox.origins().single()).resolve("images")
        assertEquals(1, images.listFiles()?.size)

        outbox.remove(origin, entry.id)

        assertTrue(outbox.read(origin).entries.isEmpty())
        assertEquals(0, images.listFiles()?.size ?: 0)
    }

    @Test
    fun anEmptyMessageIsNotQueued() {
        assertNull(store().enqueue(origin, "s1", null, "  ", emptyList()))
        val outbox = store()
        val withImage = outbox.enqueue(origin, "s1", null, "", listOf(image("only")))
        assertTrue("images alone are a valid message", withImage != null)
        assertEquals(1, withImage!!.images.size)
        assertEquals("", withImage.text)
    }

    @Test
    fun aBrokenQueueFileIsAnEmptyQueueInsteadOfACrash() {
        val outbox = store()
        outbox.enqueue(origin, "s1", null, "one", emptyList())
        val dir = File(File(temp.root, "outbox"), outbox.origins().single())
        File(dir, "queue.json").writeText("{not json")

        assertTrue(outbox.read(origin).entries.isEmpty())
        // The next enqueue rewrites a valid file.
        outbox.enqueue(origin, "s1", null, "two", emptyList())
        assertEquals(listOf("two"), outbox.read(origin).entries.map { it.text })
    }

    @Test
    fun clearAllWipesEveryOrigin() {
        val outbox = store()
        outbox.enqueue(origin, "s1", null, "one", emptyList())
        outbox.enqueue(other, "s2", null, "two", emptyList())

        outbox.clearAll()

        assertTrue(outbox.read(origin).entries.isEmpty())
        assertTrue(outbox.read(other).entries.isEmpty())
    }

    @Test
    fun aClaimThatCannotBeWrittenReportsFailureAndLeavesTheMessageQueued() {
        val root = File(temp.root, "outbox")
        val outbox = store(root)
        val entry = outbox.enqueue(origin, "s1", null, "send me", emptyList())!!
        val dir = File(root, outbox.origins().single())
        // The temporary file cannot be created, so the "on its way" mark fails.
        File(dir, "queue.json.tmp").mkdirs()

        assertEquals(OutboxClaim.FAILED, outbox.claim(origin, entry.id))

        File(dir, "queue.json.tmp").delete()
        assertEquals(
            "the message is still pending and goes out later",
            listOf("send me"),
            outbox.read(origin).entries.map { it.text },
        )
    }

    @Test
    fun aMessageThatCannotBeRemovedKeepsItsImages() {
        val root = File(temp.root, "outbox")
        val outbox = store(root)
        outbox.enqueue(origin, "s1", null, "keep", emptyList())
        val entry = outbox.enqueue(origin, "s1", null, "with image", listOf(image("bytes")))!!
        val dir = File(root, outbox.origins().single())
        val images = File(dir, "images")
        assertEquals(1, images.listFiles()?.size)
        File(dir, "queue.json.tmp").mkdirs()

        assertFalse("the record could not be removed", outbox.remove(origin, entry.id))

        File(dir, "queue.json.tmp").delete()
        assertEquals(
            "the record is still queued, so it still needs its attachment",
            listOf("keep", "with image"),
            outbox.read(origin).entries.map { it.text },
        )
        assertEquals("the image was not deleted behind the record", 1, images.listFiles()?.size)
    }

    @Test
    fun anUnrenderableRecordIsDroppedWithItsImages() {
        val root = File(temp.root, "outbox")
        val outbox = store(root)
        outbox.enqueue(origin, "s1", null, "keep", emptyList())
        val dir = File(root, outbox.origins().single())
        val images = File(dir, "images")
        images.mkdirs()
        val dead = File(images, "dead-0.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(dir, "queue.json").writeText(
            """{"entries":[""" +
                """{"id":"dead","sessionId":"","text":"","images":[{"file":"dead-0.bin","mimeType":"image/png"}]},""" +
                """{"id":"live","sessionId":"s1","text":"keep","images":[],"createdAt":1000}]}""",
        )

        assertEquals(listOf("keep"), outbox.read(origin).entries.map { it.text })
        assertFalse("a record that can never render is gone with its image", dead.exists())
        assertEquals(
            "the readable record survives the rewrite",
            listOf("keep"),
            store(root).read(origin).entries.map { it.text },
        )
    }

    @Test
    fun aQueueOlderThanAMonthIsDroppedAtStartup() {
        val root = File(temp.root, "outbox")
        val outbox = store(root)
        outbox.enqueue(origin, "s1", null, "old", listOf(image("old")))
        val dir = File(root, outbox.origins().single())
        val stored = File(dir, "images").listFiles()!!.single()

        now += OutboxStore.MAX_AGE_MILLIS + 1
        val recovered = store(root).recover(origin)

        assertTrue("a month-old undeliverable message is not resurrected", recovered.entries.isEmpty())
        assertEquals(0, recovered.uncertain)
        assertFalse("its image goes with it", stored.exists())
    }

    @Test
    fun queueWritesLeaveNoTemporaryFileBehind() {
        val root = File(temp.root, "outbox")
        val outbox = store(root)
        val first = outbox.enqueue(origin, "s1", null, "one", emptyList())!!
        val dir = File(root, outbox.origins().single())
        assertEquals(OutboxClaim.CLAIMED, outbox.claim(origin, first.id))
        assertTrue(outbox.release(origin, first.id))
        assertTrue(outbox.remove(origin, first.id))

        assertFalse("the moved file is not left behind", File(dir, "queue.json.tmp").exists())
    }
}
