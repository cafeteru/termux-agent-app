package com.termux.agent

import android.app.Application
import android.content.pm.PackageInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
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
@Config(sdk = [34])
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
