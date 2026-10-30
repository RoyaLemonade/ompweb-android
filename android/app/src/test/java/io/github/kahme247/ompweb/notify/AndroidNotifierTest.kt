package io.github.kahme247.ompweb.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.domain.CompletedAlertWindow
import io.github.kahme247.ompweb.domain.NotifyEvent
import io.github.kahme247.ompweb.domain.NotifyProject
import io.github.kahme247.ompweb.domain.NotifyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The system-facing half of the comfort round on a real [NotificationManager]:
 * channel vibration twins (a channel's vibration cannot be changed after
 * creation, so "Vibrate" switches between two channels), the project group and
 * its summary, and the silent result update after answering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AndroidNotifierTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = app
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    private var vibrate = true
    private var completedSound = false
    private var now = 1_000_000L
    private val notifier = AndroidNotifier(
        context,
        origin = { null },
        vibrate = { vibrate },
        completedSound = { completedSound },
        clock = { now },
    )

    private val project = NotifyProject(key = "c:/work/ompwebandroid", label = "ompwebandroid")

    @Before
    fun grantNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun input(session: String) = NotifyEvent(type = NotifyType.Input, sessionId = session)

    private fun posted(tag: String): Notification? =
        shadowOf(manager).activeNotifications.firstOrNull { it.tag == tag }?.notification

    private fun summary(): Notification? = posted("group:${project.key}")

    private fun summaryTitle(): String? =
        summary()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test
    fun `event channels vibrate and their quiet twins do not`() {
        assertTrue(notifier.post(input("s1"), project))

        val input = manager.getNotificationChannel("needs_input_v2")
        val quiet = manager.getNotificationChannel("needs_input_quiet")
        assertTrue("the vibrating twin must vibrate", input.shouldVibrate())
        assertTrue(
            "input is a double pulse",
            input.vibrationPattern.contentEquals(longArrayOf(0, 120, 120, 120)),
        )
        assertNotNull("the quiet twin must exist", quiet)
        assertFalse("the quiet twin must never vibrate", quiet.shouldVibrate())

        val completed = manager.getNotificationChannel("completed_v2")
        assertTrue(completed.vibrationPattern.contentEquals(longArrayOf(0, 120)))
        val silent = requireNotNull(manager.getNotificationChannel("completed_silent")) {
            "the fully silent twin of a finished chat must exist"
        }
        assertFalse("the silent twin never vibrates", silent.shouldVibrate())
        assertNull("the silent twin never plays a sound", silent.sound)
        val errors = manager.getNotificationChannel("errors_v2")
        assertTrue("an error is one long pulse", errors.vibrationPattern.contentEquals(longArrayOf(0, 600)))

        // The pre-twin ids are gone: otherwise the same event would exist in
        // two channels and the user could block the wrong one.
        assertNull(manager.getNotificationChannel("needs_input"))
        assertNull(manager.getNotificationChannel("errors"))
        assertNull(manager.getNotificationChannel("completed"))

        // Informational channels never vibrate and keep their single id.
        assertNotNull(manager.getNotificationChannel("model_switch"))
        assertNull(manager.getNotificationChannel("model_switch_v2"))
        assertNull(manager.getNotificationChannel("connection_status_v2"))
    }

    @Test
    fun `the setting switched off posts on the quiet twin`() {
        vibrate = false
        assertTrue(notifier.post(input("s1"), project))
        assertEquals("needs_input_quiet", posted("s1:input")?.channelId)
    }

    @Test
    fun `two sessions of one project share a group and a summary`() {
        assertTrue(notifier.post(input("s1"), project))
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s2"), project))
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s3"), project))

        assertEquals(project.key, posted("s1:input")?.group)
        assertEquals(project.key, posted("s2:completed")?.group)

        val summary = summary()
        assertNotNull("a group of children gets one summary", summary)
        assertEquals(
            "3 sessions in ompwebandroid",
            summary?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertTrue(
            "the summary is the group's own summary",
            summary!!.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
        assertEquals("needs_input_quiet", summary.channelId)
    }

    @Test
    fun `answering keeps the group and never vibrates`() {
        assertTrue(notifier.post(input("s1"), project))
        assertTrue(notifier.postResult("s1:input", "Answer sent", "needs_input", "Sent to the agent", "s1"))

        val result = posted("s1:input")
        assertEquals("needs_input_quiet", result?.channelId)
        assertEquals(project.key, result?.group)
        // One chat is one notification: a summary would only repeat it.
        assertNull("a single chat has no summary", summary())
    }

    @Test
    fun `a result after a finished chat stays silent`() {
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s1"), project))
        assertTrue(notifier.postResult("s1:completed", "Reply sent", "completed", "Sent to the agent", "s1"))

        assertEquals("completed_silent", posted("s1:completed")?.channelId)
    }

    @Test
    fun `a session without a project stays ungrouped and does not create a summary`() {
        assertTrue(notifier.post(input("s1"), null))
        assertNull(posted("s1:input")?.group)
        assertNull(summary())
    }

    @Test
    fun `cancelling the last child removes the summary`() {
        assertTrue(notifier.post(input("s1").copy(requestId = "req-1"), project))
        assertTrue(notifier.post(input("s2").copy(requestId = "req-2"), project))
        assertNotNull(summary())
        notifier.cancelIfMatches("s1:input", requestId = "req-1")
        assertNull("one remaining chat needs no summary", summary())
        notifier.cancelIfMatches("s2:input", requestId = "req-2")
        assertNull("an empty group must not leave an orphan summary", summary())
    }

    @Test
    fun `swiping a child away rebuilds the summary and drops it with the last child`() {
        assertTrue(notifier.post(input("s1"), project))
        assertTrue(notifier.post(input("s2"), project))
        assertEquals("2 sessions in ompwebandroid", summaryTitle())

        val firstDelete = requireNotNull(posted("s1:input")?.deleteIntent) {
            "a grouped child needs a delete intent"
        }
        val secondDelete = requireNotNull(posted("s2:input")?.deleteIntent)
        assertNotEquals("each child owns its delete intent", firstDelete, secondDelete)

        val first = shadowOf(firstDelete).savedIntent
        assertEquals(NotificationActionReceiver::class.java.name, first.component?.className)
        assertEquals(NotificationIntents.ACTION_DISMISS, first.action)
        assertEquals("s1:input", first.getStringExtra(NotificationIntents.EXTRA_TAG))
        assertEquals(project.key, first.getStringExtra(NotificationIntents.EXTRA_GROUP_KEY))

        // The system removes the swiped child; the broadcast carries its tag
        // because the active list can still show it while the receiver runs.
        manager.cancel("s1:input", 1)
        NotificationActionReceiver.dismiss(first, notifier)
        assertNull("one remaining chat needs no summary", summary())

        manager.cancel("s2:input", 1)
        NotificationActionReceiver.dismiss(shadowOf(secondDelete).savedIntent, notifier)
        assertNull("the last child takes the summary with it", summary())
    }

    @Test
    fun `a foreground visit repairs a summary a tap left stale`() {
        assertTrue(notifier.post(input("s1"), project))
        assertTrue(notifier.post(input("s2"), project))

        // A tap auto-cancels the child without firing its delete intent.
        manager.cancel("s1:input", 1)
        assertEquals("2 sessions in ompwebandroid", summaryTitle())

        notifier.refreshGroupSummaries()
        assertNull("a summary of one chat is dropped", summary())
    }

    @Test
    fun `each event type has its own small icon and accent color`() {
        val cases = listOf(
            Triple(NotifyType.Completed, R.drawable.ic_notify_completed, R.color.notify_accent_completed),
            Triple(NotifyType.Input, R.drawable.ic_notify_input, R.color.notify_accent_input),
            Triple(NotifyType.Error, R.drawable.ic_notify_error, R.color.notify_accent_error),
            Triple(NotifyType.ModelSwitch, R.drawable.ic_notify_model_switch, R.color.notify_accent_model_switch),
        )
        for ((type, icon, color) in cases) {
            val event = NotifyEvent(type = type, sessionId = "s-${type.wire}")
            assertTrue(notifier.post(event, null))
            val notification = requireNotNull(posted(event.tag)) { "no notification for ${type.wire}" }
            assertEquals(
                "small icon for ${type.wire}",
                icon,
                notification.smallIcon.resId,
            )
            assertEquals(
                "accent for ${type.wire}",
                ContextCompat.getColor(context, color),
                notification.color,
            )
        }
    }

    @Test
    fun `the group summary carries its own icon`() {
        assertTrue(notifier.post(input("s1"), project))
        assertTrue(notifier.post(input("s2"), project))
        val summary = requireNotNull(summary())
        assertEquals(R.drawable.ic_notify_summary, summary.smallIcon.resId)
    }

    @Test
    fun `opening a chat closes every notification of that session only`() {
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s1"), project))
        assertTrue(notifier.post(input("s1"), project))
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s2"), project))
        assertEquals("2 sessions in ompwebandroid", summaryTitle())

        notifier.cancelSession("s1")

        assertNull("the completed notification is gone", posted("s1:completed"))
        assertNull("the input notification is gone", posted("s1:input"))
        assertNotNull("another session keeps its notification", posted("s2:completed"))
        assertNull("one remaining chat needs no summary", summary())
    }

    @Test
    fun `opening a chat closes every type and the answered result`() {
        for (type in NotifyType.entries) {
            assertTrue(notifier.post(NotifyEvent(type = type, sessionId = "s1"), project))
        }
        assertTrue(notifier.postResult("s1:error", "Reply sent", "errors", "Sent to the agent", "s1"))
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s2"), project))

        notifier.cancelSession("s1")

        for (type in NotifyType.entries) {
            assertNull("${type.wire} is gone", posted("s1:${type.wire}"))
        }
        assertNotNull("another session keeps its notification", posted("s2:completed"))
    }

    @Test
    fun `a finished chat is silent by default`() {
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s1"), project))

        val completed = requireNotNull(posted("s1:completed"))
        assertEquals("completed_silent", completed.channelId)
        val channel = requireNotNull(manager.getNotificationChannel("completed_silent"))
        assertFalse("no vibration", channel.shouldVibrate())
        assertNull("no sound", channel.sound)
        assertEquals("still visible in the shade", NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("completed_silent").importance)
    }

    @Test
    fun `questions and errors stay loud`() {
        assertTrue(notifier.post(input("s1"), project))
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Error, sessionId = "s2"), project))

        assertEquals("needs_input_v2", posted("s1:input")?.channelId)
        assertEquals("errors_v2", posted("s2:error")?.channelId)
    }

    @Test
    fun `the finished-chat setting alerts once per burst and project`() {
        completedSound = true
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s1"), project))
        assertEquals("completed_v2", posted("s1:completed")?.channelId)

        // Same burst: the second chat of the project is posted silently.
        now += 5_000
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s2"), project))
        assertEquals("completed_silent", posted("s2:completed")?.channelId)

        // A later completion opens a new burst and alerts again.
        now += CompletedAlertWindow.DEFAULT_WINDOW_MILLIS + 1
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s3"), project))
        assertEquals("completed_v2", posted("s3:completed")?.channelId)
    }

    @Test
    fun `the vibrate switch quiets the finished-chat alert but keeps its sound`() {
        completedSound = true
        vibrate = false
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s1"), project))

        // "Sound/vibration for finished chats" decides whether a finished chat
        // alerts; the global "Vibrate" switch still decides whether that alert
        // pulses — the row promises "one short pulse when a task finishes".
        val posted = requireNotNull(posted("s1:completed"))
        assertEquals("completed_quiet", posted.channelId)
        assertFalse(
            "vibration off: the quiet twin must not pulse",
            requireNotNull(manager.getNotificationChannel("completed_quiet")).shouldVibrate(),
        )
    }

    @Test
    fun `a burst of finished chats collapses into one summary with their names`() {
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s1", sessionName = "Fix build"), project))
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s2", sessionName = "Write tests"), project))
        assertTrue(notifier.post(NotifyEvent(type = NotifyType.Completed, sessionId = "s3", sessionName = "Review"), project))

        val summary = requireNotNull(summary())
        assertEquals("3 chats finished", summary.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(
            "newest first, like the inbox lines",
            "Review, Write tests, Fix build",
            summary.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertEquals("completed_silent", summary.channelId)
        assertEquals(project.label, summary.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
    }
}
