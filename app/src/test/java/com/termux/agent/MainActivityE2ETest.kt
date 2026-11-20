package com.termux.agent

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h915dp")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MainActivityE2ETest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        SshSessionManager.disconnect()
        SshConfigStore.saveWorkspacePath(app, "")
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
                drainStartedServices()

                composeRule.onNodeWithContentDescription("Abrir menú").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Menú de opciones").assertIsDisplayed()
                composeRule.onNodeWithTag("drawer_option_config").performSemanticsAction(SemanticsActions.OnClick)
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Configuración SSH").assertIsDisplayed()
                composeRule.onNodeWithContentDescription("Volver a la pantalla principal").assertIsDisplayed()
                composeRule.onNodeWithContentDescription("Volver a la pantalla principal").performClick()

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

                composeRule.onNodeWithText("Terminal").performClick()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("termux@local:~$ ", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithText("Termux conectado", substring = true).assertDoesNotExist()

                composeRule.onNodeWithText("Búfer").performClick()
                composeRule.onNodeWithTag("command_input_field").performTextInput("whoami")
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

                drainStartedServices()
                composeRule.onNodeWithContentDescription("Desconectar SSH").performClick()
                pumpStartedServices()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
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

                composeRule.onNodeWithText("Terminal").performClick()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("termux@local:~$ ", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }

                composeRule.onNodeWithText("Búfer").performClick()
                composeRule.onNodeWithText("termux@local:~$ ", substring = true).performClick()
                composeRule.onNodeWithTag("command_input_field").assertIsFocused()

                composeRule.onNodeWithText("ESC").performClick()
                composeRule.onNodeWithText("TAB").performClick()
                composeRule.onNodeWithContentDescription("Flecha izquierda").performClick()
                composeRule.onNodeWithContentDescription("Flecha abajo").performClick()
                composeRule.onNodeWithContentDescription("Flecha arriba").performClick()
                composeRule.onNodeWithContentDescription("Flecha derecha").performClick()

                composeRule.onNodeWithText("CTRL").performClick()
                composeRule.onNodeWithTag("command_input_field").performTextInput("c")

                composeRule.onNodeWithText("ALT").performClick()
                composeRule.onNodeWithTag("command_input_field").performTextInput("f")

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    server.receivedRawString()
                        .contains("\u001B\t\u001B[D\u001B[B\u001B[A\u001B[C\u0003\u001Bf")
                }
            }
        }
    }

    @Test
    fun interactiveTerminalModeStreamsKeystrokesDirectlyToPty() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                drainStartedServices()

                val connectState = connectTestServer(server.port)
                assertTrue("Expected Connected but got $connectState", connectState is ConnectionState.Connected)

                composeRule.onNodeWithText("Terminal").performClick()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("termux@local:~$ ", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }

                // Direct mode is default
                composeRule.onNodeWithText("Modo interactivo (en vivo)").assertIsDisplayed()

                composeRule.onNodeWithTag("interactive_terminal_input").performTextInput("ls")
                composeRule.onNodeWithText("TAB").performClick()
                composeRule.onNodeWithContentDescription("Flecha arriba").performClick()
                composeRule.onNodeWithContentDescription("Flecha abajo").performClick()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    server.receivedRawString().contains("ls\t\u001B[A\u001B[B")
                }

                composeRule.onNodeWithText("Búfer").performClick()
                composeRule.onNodeWithText("Modo búfer (comando)").assertIsDisplayed()
            }
        }
    }

    @Test
    fun terminalCommandUpdateSyncsToInputField() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                drainStartedServices()

                val connectState = connectTestServer(server.port)
                assertTrue("Expected Connected but got $connectState", connectState is ConnectionState.Connected)

                composeRule.onNodeWithText("Terminal").performClick()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("termux@local:~$ ", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }

                // Simulate terminal tab completion or history updating the active line
                server.sendOutput("\rtermux@local:~$ cd Download/\u001B[K")

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("cd Download/")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }
        }
    }

    @Test
    fun directModeModifiersAndEnterSubmission() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                drainStartedServices()

                val connectState = connectTestServer(server.port)
                assertTrue("Expected Connected but got $connectState", connectState is ConnectionState.Connected)

                composeRule.onNodeWithText("Terminal").performClick()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("termux@local:~$ ", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }

                composeRule.onNodeWithText("Modo interactivo (en vivo)").assertIsDisplayed()

                composeRule.onNodeWithText("CTRL").performClick()
                composeRule.onNodeWithTag("interactive_terminal_input").performTextInput("c")

                composeRule.onNodeWithText("ALT").performClick()
                composeRule.onNodeWithTag("interactive_terminal_input").performTextInput("b")

                composeRule.onNodeWithTag("interactive_terminal_input").performTextInput("\n")

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    server.receivedRawString().contains("\u0003\u001Bb\r")
                }
            }
        }
    }

    @Test
    fun chatTabAgentSelectionAndPromptSubmission() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                drainStartedServices()

                val connectState = connectTestServer(server.port)
                assertTrue("Expected Connected but got $connectState", connectState is ConnectionState.Connected)

                // By default connected lands on Chat tab
                composeRule.onNodeWithText("Chat").assertIsDisplayed()
                composeRule.onNodeWithText("Terminal").assertIsDisplayed()

                // Agent selector chips were moved from ChatScreen into the navigation drawer
                composeRule.onNodeWithText("Antigravity (agy)").assertDoesNotExist()
                composeRule.onNodeWithText("Google Antigravity CLI").assertIsDisplayed()

                // Switch to Copilot via the navigation drawer
                composeRule.onNodeWithContentDescription("Abrir menú").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Antigravity").assertIsDisplayed()
                composeRule.onNodeWithText("Copilot").assertIsDisplayed()
                composeRule.onNodeWithTag("drawer_options_divider").assertIsDisplayed()
                composeRule.onNodeWithText("Configuración").assertIsDisplayed()
                composeRule.onNodeWithTag("drawer_option_copilot").performSemanticsAction(SemanticsActions.OnClick)
                composeRule.waitForIdle()

                assertEquals("copilot", SshSessionManager.activeAdapter.value.id)
                composeRule.onNodeWithText("GitHub Copilot CLI").assertIsDisplayed()

                // Submit prompt in Chat input field
                composeRule.onNodeWithTag("chat_input_field").performTextInput("Hello agent")
                composeRule.onNodeWithTag("chat_send_button").performClick()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("Hello agent")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithText("Hello agent").assertIsDisplayed()
                assertTrue(SshSessionManager.chatMessages.value.any { it.content == "Hello agent" })
            }
        }
    }

    @Test
    fun chatActionsOverflowMenuLaunchesAgentAndClearsMessages() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                drainStartedServices()

                val connectState = connectTestServer(server.port)
                assertTrue("Expected Connected but got $connectState", connectState is ConnectionState.Connected)

                composeRule.onNodeWithContentDescription("Lanzar agente en PTY").assertDoesNotExist()
                composeRule.onNodeWithContentDescription("Limpiar mensajes de chat").assertDoesNotExist()

                composeRule.onNodeWithContentDescription("Acciones del agente").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Iniciar agente").assertIsDisplayed()
                composeRule.onNodeWithText("Limpiar chat").assertIsDisplayed()

                composeRule.onNodeWithText("Iniciar agente").performSemanticsAction(SemanticsActions.OnClick)
                composeRule.waitForIdle()
                assertTrue(
                    composeRule.onAllNodesWithText("Iniciar agente")
                        .fetchSemanticsNodes()
                        .isEmpty()
                )
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    server.receivedCommands.contains("agy")
                }

                composeRule.onNodeWithTag("chat_input_field").performTextInput("Hola menú")
                composeRule.onNodeWithTag("chat_send_button").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("Hola menú")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                assertTrue(SshSessionManager.chatMessages.value.any { it.content == "Hola menú" })

                composeRule.onNodeWithContentDescription("Acciones del agente").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Limpiar chat").performSemanticsAction(SemanticsActions.OnClick)
                composeRule.waitForIdle()
                assertTrue(
                    composeRule.onAllNodesWithText("Limpiar chat")
                        .fetchSemanticsNodes()
                        .isEmpty()
                )
                assertTrue(SshSessionManager.chatMessages.value.isEmpty())
            }
        }
    }

    @Test
    fun workspacePickerNavigatesPersistsAndLaunchesAgentInSelectedDirectory() {
        FakeSshPtyServer(expectedUser = "termux", expectedPassword = "secret").use { server ->
            ActivityScenario.launch(MainActivity::class.java).use {
                drainStartedServices()

                val connectState = connectTestServer(server.port)
                assertTrue("Expected Connected but got $connectState", connectState is ConnectionState.Connected)

                composeRule.onNodeWithTag("workspace_selector_bar").performClick()
                composeRule.onNodeWithTag("workspace_picker_title")
                    .assertIsDisplayed()
                    .assertTextEquals("Seleccionar proyecto")

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("termux-agent-app")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithText(".hidden-repo").assertDoesNotExist()

                composeRule.onNodeWithTag("workspace_picker_toggle_hidden").performClick()
                composeRule.onNodeWithText(".hidden-repo").assertIsDisplayed()

                composeRule.onNodeWithText("termux-agent-app").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithText("app")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithText("/home/termux/termux-agent-app").assertIsDisplayed()

                composeRule.onNodeWithTag("workspace_picker_confirm").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    composeRule.onAllNodesWithTag("workspace_picker_title")
                        .fetchSemanticsNodes()
                        .isEmpty()
                }

                composeRule.onNodeWithText("Cambiar carpeta").assertIsDisplayed()
                assertEquals("/home/termux/termux-agent-app", SshConfigStore.loadWorkspacePath(app))

                composeRule.onNodeWithText("Iniciar Google Antigravity CLI en Termux").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    server.receivedCommands.contains("cd \"/home/termux/termux-agent-app\" && agy")
                }
            }
        }
    }

    @Test
    fun drawerNavigationBetweenOptionsMenuAndSshConfig() {
        ActivityScenario.launch(MainActivity::class.java).use {
            drainStartedServices()

            // 1. Open drawer -> should show Antigravity and Copilot above divider and Configuración
            composeRule.onNodeWithContentDescription("Abrir menú").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Menú de opciones").assertIsDisplayed()
            composeRule.onNodeWithText("Antigravity").assertIsDisplayed()
            composeRule.onNodeWithText("Copilot").assertIsDisplayed()
            composeRule.onNodeWithTag("drawer_options_divider").assertIsDisplayed()
            composeRule.onNodeWithText("Configuración").assertIsDisplayed()

            val antigravityTop = composeRule.onNodeWithTag("drawer_option_antigravity").getUnclippedBoundsInRoot().top
            val copilotTop = composeRule.onNodeWithTag("drawer_option_copilot").getUnclippedBoundsInRoot().top
            val dividerTop = composeRule.onNodeWithTag("drawer_options_divider").getUnclippedBoundsInRoot().top
            val configTop = composeRule.onNodeWithTag("drawer_option_config").getUnclippedBoundsInRoot().top
            assertTrue(antigravityTop < copilotTop)
            assertTrue(copilotTop < dividerTop)
            assertTrue(dividerTop < configTop)

            // Switch Copilot -> Antigravity from drawer
            composeRule.onNodeWithTag("drawer_option_copilot").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            assertEquals("copilot", SshSessionManager.activeAdapter.value.id)

            composeRule.onNodeWithContentDescription("Abrir menú").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("drawer_option_antigravity").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            assertEquals("agy", SshSessionManager.activeAdapter.value.id)

            composeRule.onNodeWithContentDescription("Abrir menú").performClick()
            composeRule.waitForIdle()

            // 2. Click "Configuración" -> drawer closes and main screen transitions to SSH config
            composeRule.onNodeWithText("Configuración").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Configuración SSH").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Volver a la pantalla principal").assertIsDisplayed()

            // 3. Click back arrow -> returns to main screen
            composeRule.onNodeWithContentDescription("Volver a la pantalla principal").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("main_screen_title")
                .assertIsDisplayed()
                .assertTextEquals("Termux Agent")

            // 4. Test gear button directly opens config screen
            composeRule.onNodeWithContentDescription("Configurar SSH").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Configuración SSH").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Volver a la pantalla principal").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("main_screen_title")
                .assertIsDisplayed()
                .assertTextEquals("Termux Agent")
        }
    }

    @Test
    fun drawerFormValidationAuthSwitchAndPersistence() {
        ActivityScenario.launch(MainActivity::class.java).use {
            drainStartedServices()

            composeRule.onNodeWithContentDescription("Abrir menú").performClick()
            // Robolectric LEGACY graphics routes touches inside the open drawer to its scrim, closing it;
            // drive drawer controls via semantics actions (NATIVE mode is unsupported on aarch64 Termux).
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Configuración").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Mostrar contraseña").performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithContentDescription("Ocultar contraseña").assertIsDisplayed()

            composeRule.onNodeWithText("Puerto").performScrollTo().performTextClearance()
            composeRule.onNodeWithText("Puerto").performTextInput("0")
            composeRule.onNodeWithText("Guardar y Conectar").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithText(
                "Puerto SSH inválido. Introduce un valor entre 1 y 65535."
            ).performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithText("Puerto").performScrollTo().performTextClearance()
            composeRule.onNodeWithText("Puerto").performTextInput("8022")
            composeRule.onNodeWithText("Clave SSH (key)").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithText("Regenerar clave").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
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
        composeRule.waitForIdle()
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
