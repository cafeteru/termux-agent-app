package com.termux.agent

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp")
class MainActivityE2ETest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        SshSessionManager.disconnect()
        Shadows.shadowOf(app.packageManager).installPackage(
            PackageInfo().apply { packageName = AppConfig.TERMUX_PACKAGE_NAME }
        )
    }

    @After
    fun tearDown() {
        SshSessionManager.disconnect()
    }

    @Test
    fun fullDrawerTerminalCommandAndDisconnectFlow() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                // Drain any cold-start auto-connect service intent queued by LaunchedEffect(Unit).
                while (Shadows.shadowOf(app).nextStartedService != null) {
                    // Intentionally drained before explicitly testing drawer and connection flow.
                }

                composeRule.onNodeWithContentDescription("Abrir configuración SSH").performClick()
                composeRule.onNodeWithText("Configuración SSH").assertIsDisplayed()
                composeRule.onNodeWithContentDescription("Cerrar configuración SSH").assertIsDisplayed()
                composeRule.onNodeWithContentDescription("Cerrar configuración SSH").performClick()

                val connectState = SshSessionManager.connect(
                    isTermuxInstalled = {
                        TermuxConnectionChecker.isTermuxInstalled(app.packageManager)
                    },
                    config = SshConfig(
                        host = "127.0.0.1",
                        port = server.port,
                        username = "termux",
                        authMethod = AuthMethod.PASSWORD,
                        password = "secret"
                    ),
                    timeoutMs = 5_000
                )
                assertTrue("Expected Connected but got $connectState", connectState is ConnectionState.Connected)

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("termux@local:~$ ", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithText("Termux conectado", substring = true).assertDoesNotExist()

                composeRule.onNodeWithText("Comando").performTextInput("whoami")
                composeRule.onNodeWithContentDescription("Enviar comando").performClick()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("ACK:whoami", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }

                // Empty submission should send "\r" to the PTY and receive ACK:.
                composeRule.onNodeWithContentDescription("Enviar comando").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("ACK:\n", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                assertTrue(server.receivedCommands.contains("whoami"))
                assertTrue(server.receivedCommands.contains(""))

                composeRule.onNodeWithContentDescription("Desconectar SSH").performClick()
                pumpStartedServices()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("Sesión SSH desconectada.")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithText("Sesión SSH desconectada.").assertIsDisplayed()
            }
        }
    }

    @Test
    fun extraKeysAndTerminalTapFocusCommandAndDeliverRawSequences() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                drainStartedServices()

                val connectState = connectTestServer(server.port)
                assertTrue("Expected Connected but got $connectState", connectState is ConnectionState.Connected)

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("termux@local:~$ ", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }

                composeRule.onNodeWithText("termux@local:~$ ", substring = true).performClick()
                composeRule.onNodeWithText("Comando").assertIsFocused()

                composeRule.onNodeWithText("ESC").performClick()
                composeRule.onNodeWithText("TAB").performClick()
                composeRule.onNodeWithContentDescription("Flecha izquierda").performClick()
                composeRule.onNodeWithContentDescription("Flecha abajo").performClick()
                composeRule.onNodeWithContentDescription("Flecha arriba").performClick()
                composeRule.onNodeWithContentDescription("Flecha derecha").performClick()

                composeRule.onNodeWithText("CTRL").performClick()
                composeRule.onNodeWithText("Comando").performTextInput("c")

                composeRule.onNodeWithText("ALT").performClick()
                composeRule.onNodeWithText("Comando").performTextInput("f")

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    server.receivedRawString()
                        .contains("\u001B\t\u001B[D\u001B[B\u001B[A\u001B[C\u0003\u001Bf")
                }
            }
        }
    }

    @Test
    fun drawerFormValidationAuthSwitchAndPersistence() {
        ActivityScenario.launch(MainActivity::class.java).use {
            drainStartedServices()

            composeRule.onNodeWithContentDescription("Abrir configuración SSH").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Mostrar contraseña")
                .performScrollTo()
                .performClick()
            composeRule.onNodeWithContentDescription("Ocultar contraseña")
                .performScrollTo()
                .assertIsDisplayed()

            composeRule.onNodeWithText("Puerto").performScrollTo().performTextClearance()
            composeRule.onNodeWithText("Puerto").performTextInput("0")
            composeRule.onNodeWithText("Guardar y Conectar").performScrollTo().performClick()
            composeRule.onNodeWithText(
                "Puerto SSH inválido. Introduce un valor entre 1 y 65535."
            ).performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithText("Puerto").performScrollTo().performTextClearance()
            composeRule.onNodeWithText("Puerto").performTextInput("8022")
            composeRule.onNodeWithText("Clave SSH (key)").performScrollTo().performClick()
            composeRule.onNodeWithText("Regenerar clave").performScrollTo().performClick()
            composeRule.onNodeWithText(
                "Nueva clave SSH generada. Copia el comando en Termux."
            ).performScrollTo().assertIsDisplayed()

            val persisted = SshConfigStore.load(app)
            assertEquals(AuthMethod.PUBLIC_KEY, persisted.authMethod)
            assertTrue(persisted.publicKeyAuthorized.startsWith("ecdsa-sha2-nistp256 "))
        }
    }

    @Test
    fun foregroundServiceNotificationPostsWithDisconnectActionAndCancelsOnDisconnect() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            SshConfigStore.save(
                app,
                SshConfig(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "termux",
                    authMethod = AuthMethod.PASSWORD,
                    password = "secret"
                )
            )
            val notificationManager = app.getSystemService(NotificationManager::class.java)
            val shadowNotificationManager = Shadows.shadowOf(notificationManager)

            val connectIntent = Intent(app, AgentForegroundService::class.java).apply {
                action = AgentForegroundService.ACTION_CONNECT
            }
            val controller = Robolectric.buildService(AgentForegroundService::class.java, connectIntent)
                .create()
                .startCommand(0, 1)

            try {
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    SshSessionManager.connectionState.value is ConnectionState.Connected &&
                        shadowNotificationManager.allNotifications.any { notification ->
                            notification.actions?.any { it.title == "Desconectar" } == true
                        }
                }

                val activeNotification = shadowNotificationManager.allNotifications.firstOrNull()
                assertNotNull(activeNotification)
                assertTrue(
                    activeNotification!!.actions.any { it.title == "Desconectar" }
                )

                val disconnectIntent = Intent(app, AgentForegroundService::class.java).apply {
                    action = AgentForegroundService.ACTION_DISCONNECT
                }
                controller.get().onStartCommand(disconnectIntent, 0, 2)

                assertTrue(shadowNotificationManager.allNotifications.isEmpty())
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    SshSessionManager.connectionState.value == ConnectionState.Disconnected
                }
            } finally {
                controller.destroy()
            }
        }
    }

    private fun connectTestServer(port: Int): ConnectionState = SshSessionManager.connect(
        isTermuxInstalled = { TermuxConnectionChecker.isTermuxInstalled(app.packageManager) },
        config = SshConfig(
            host = "127.0.0.1",
            port = port,
            username = "termux",
            authMethod = AuthMethod.PASSWORD,
            password = "secret"
        ),
        timeoutMs = 5_000
    )

    private fun drainStartedServices() {
        val shadowApp = Shadows.shadowOf(app)
        while (shadowApp.nextStartedService != null) {
            // Intentionally drained before explicitly testing UI and service actions.
        }
    }

    private fun pumpStartedServices() {
        val shadowApp = Shadows.shadowOf(app)
        while (true) {
            val intent = shadowApp.nextStartedService ?: break
            val controller = Robolectric.buildService(AgentForegroundService::class.java, intent)
            controller.create().startCommand(0, 1)
            controller.destroy()
        }
    }
}
