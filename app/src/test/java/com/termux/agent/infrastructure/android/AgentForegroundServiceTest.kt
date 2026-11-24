package com.termux.agent.infrastructure.android

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.termux.agent.application.port.AgentAdapter
import com.termux.agent.domain.model.AdapterEvent
import com.termux.agent.domain.model.AgentState
import com.termux.agent.infrastructure.ssh.SshSessionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentForegroundServiceTest {
    private lateinit var app: Application
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        notificationManager =
            app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        SshSessionManager.disconnect()
    }

    @After
    fun tearDown() {
        SshSessionManager.disconnect()
    }

    @Test
    fun ensuresBothNotificationChannelsCreatedOnStart() {
        val serviceController = Robolectric.buildService(AgentForegroundService::class.java)
        serviceController.create()

        val sessionChannel =
            notificationManager.getNotificationChannel(AgentForegroundService.CHANNEL_ID)
        val alertsChannel =
            notificationManager.getNotificationChannel(AgentForegroundService.CHANNEL_ALERTS_ID)

        assertNotNull("Session channel should exist", sessionChannel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, sessionChannel.importance)

        assertNotNull("Alerts channel should exist", alertsChannel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alertsChannel.importance)

        serviceController.destroy()
    }

    @Test
    fun permissionRequiredEventPostsInteractiveNotificationWithAllowAndDeny() {
        val serviceController = Robolectric.buildService(AgentForegroundService::class.java)
        serviceController.create()

        val intent = Intent(app, AgentForegroundService::class.java).apply {
            action = "START_OBSERVING"
        }
        serviceController.get().onStartCommand(intent, 0, 1)

        val shadowManager = Shadows.shadowOf(notificationManager)

        val mockAdapter = object : AgentAdapter {
            override val id: String = "test_agent"
            override val name: String = "Test Agent"
            override var currentState: AgentState = AgentState.WAITING_PERMISSION

            override fun launchCommand(workspacePath: String): String = "test"
            override fun onScreenUpdated(screenLines: List<String>, idleMs: Long): AdapterEvent? =
                null

            override fun sendPrompt(ptyOutput: OutputStream, text: String) {}
            override fun resolvePermission(
                ptyOutput: OutputStream,
                allow: Boolean,
                optionIndex: Int?
            ) {
                currentState = AgentState.WORKING
            }

            override fun reset() {
                currentState = AgentState.IDLE
            }
        }
        SshSessionManager.setActiveAdapter(mockAdapter)

        val permissionEvent = AdapterEvent.PermissionRequired(
            id = "perm_1",
            tool = "run_command",
            command = "rm -rf /tmp/test"
        )
        SshSessionManager.emitAdapterEventForTesting(permissionEvent)

        val notification =
            shadowManager.getNotification(AgentForegroundService.NOTIFICATION_ID_PERMISSION)
        assertNotNull("Permission notification should be posted", notification)

        val title =
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text =
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()

        assertTrue("Title should mention agent name", title?.contains("Test Agent") == true)
        assertTrue("Text should contain command", text?.contains("rm -rf /tmp/test") == true)

        assertNotNull("Actions should be present", notification.actions)
        assertEquals(2, notification.actions.size)
        assertEquals("Permitir", notification.actions[0].title.toString())
        assertEquals("Denegar", notification.actions[1].title.toString())

        serviceController.destroy()
    }

    @Test
    fun turnCompletedEventPostsCompletionNotification() {
        val serviceController = Robolectric.buildService(AgentForegroundService::class.java)
        serviceController.create()

        val intent = Intent(app, AgentForegroundService::class.java).apply {
            action = "START_OBSERVING"
        }
        serviceController.get().onStartCommand(intent, 0, 1)

        val shadowManager = Shadows.shadowOf(notificationManager)

        val turnCompletedEvent =
            AdapterEvent.TurnCompleted(summary = "Build succeeded with 0 errors.")
        SshSessionManager.emitAdapterEventForTesting(turnCompletedEvent)

        val notification =
            shadowManager.getNotification(AgentForegroundService.NOTIFICATION_ID_COMPLETED)
        assertNotNull("Completion notification should be posted", notification)

        val title =
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text =
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()

        assertTrue(
            "Title should mention task completion",
            title?.contains("Tarea completada") == true
        )
        assertTrue("Text should contain summary", text?.contains("Build succeeded") == true)

        serviceController.destroy()
    }

    @Test
    fun resolvePermissionActionDispatchesAllowAndCancelsNotification() {
        val serviceController = Robolectric.buildService(AgentForegroundService::class.java)
        serviceController.create()

        val startIntent = Intent(app, AgentForegroundService::class.java).apply {
            action = "START_OBSERVING"
        }
        serviceController.get().onStartCommand(startIntent, 0, 1)

        val shadowManager = Shadows.shadowOf(notificationManager)

        val testAdapter = object : AgentAdapter {
            override val id: String = "mock"
            override val name: String = "Mock Agent"
            override var currentState: AgentState = AgentState.WAITING_PERMISSION
            override fun launchCommand(workspacePath: String): String = "mock"
            override fun onScreenUpdated(screenLines: List<String>, idleMs: Long): AdapterEvent? =
                null

            override fun sendPrompt(ptyOutput: OutputStream, text: String) {}
            override fun resolvePermission(
                ptyOutput: OutputStream,
                allow: Boolean,
                optionIndex: Int?
            ) {
                currentState = AgentState.WORKING
            }

            override fun reset() {
                currentState = AgentState.IDLE
            }
        }
        SshSessionManager.setActiveAdapter(testAdapter)

        // Post permission notification
        val permissionEvent = AdapterEvent.PermissionRequired(
            id = "perm_1",
            tool = "run_command",
            command = "git push"
        )
        SshSessionManager.emitAdapterEventForTesting(permissionEvent)
        assertNotNull(shadowManager.getNotification(AgentForegroundService.NOTIFICATION_ID_PERMISSION))

        // Trigger resolve permission action
        val permIntent = Intent(app, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_RESOLVE_PERMISSION
            putExtra(AgentForegroundService.EXTRA_ALLOW, true)
        }
        serviceController.get().onStartCommand(permIntent, 0, 1)

        assertNull(shadowManager.getNotification(AgentForegroundService.NOTIFICATION_ID_PERMISSION))

        serviceController.destroy()
    }

    @Test
    fun disconnectActionCancelsAllNotifications() {
        val serviceController = Robolectric.buildService(AgentForegroundService::class.java)
        serviceController.create()

        val shadowManager = Shadows.shadowOf(notificationManager)

        val disconnectIntent = Intent(app, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_DISCONNECT
        }
        serviceController.get().onStartCommand(disconnectIntent, 0, 1)

        assertNull(shadowManager.getNotification(AgentForegroundService.NOTIFICATION_ID))
        assertNull(shadowManager.getNotification(AgentForegroundService.NOTIFICATION_ID_PERMISSION))
        assertNull(shadowManager.getNotification(AgentForegroundService.NOTIFICATION_ID_COMPLETED))

        serviceController.destroy()
    }
}
