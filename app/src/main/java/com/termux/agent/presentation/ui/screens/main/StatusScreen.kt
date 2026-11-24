package com.termux.agent.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.termux.agent.*
import com.termux.agent.application.config.AppConfig
import com.termux.agent.infrastructure.android.AgentForegroundService
import com.termux.agent.infrastructure.agent.AdapterRegistry
import com.termux.agent.infrastructure.ssh.*
import com.termux.agent.infrastructure.terminal.AnsiTextParser
import com.termux.agent.domain.model.AgentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTab(val title: String) {
    CHAT("Chat"),
    TERMINAL("Terminal")
}
enum class MainScreen {
    MAIN,
    CONFIG
}

@Composable
fun StatusScreen(
    themeMode: AppThemeMode = SshConfigStore.loadThemeMode(LocalContext.current),
    onThemeModeChange: (AppThemeMode) -> Unit = {},
    initialScreen: MainScreen = MainScreen.MAIN,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var showNotificationRationale by rememberSaveable { mutableStateOf(false) }
    var notificationPromptChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(notificationPromptChecked) {
        if (!notificationPromptChecked &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showNotificationRationale = true
        }
        notificationPromptChecked = true
    }
    val clipboard = LocalClipboardManager.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(SshConfigStore.load(context)) }
    var portText by rememberSaveable { mutableStateOf(config.port.toString()) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var currentScreen by rememberSaveable(initialScreen) { mutableStateOf(initialScreen) }

    val sessionState by SshSessionManager.connectionState.collectAsState()
    val ptyOutput by SshSessionManager.ptyOutput.collectAsState()
    val currentCommandLine by SshSessionManager.currentCommandLine.collectAsState()
    val activeAdapter by SshSessionManager.activeAdapter.collectAsState()
    val agentState by SshSessionManager.agentState.collectAsState()
    val adapterEvent by SshSessionManager.adapterEvent.collectAsState()
    val chatMessages by SshSessionManager.chatMessages.collectAsState()
    val agentSessions by SshSessionManager.agentSessions.collectAsState()
    val agentSessionsLoading by SshSessionManager.agentSessionsLoading.collectAsState()
    val agentSessionsError by SshSessionManager.agentSessionsError.collectAsState()
    val activeConversationId by SshSessionManager.activeConversationId.collectAsState()
    val activeConversationTitle by SshSessionManager.activeConversationTitle.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.CHAT) }
    var chatDraft by rememberSaveable { mutableStateOf("") }
    var selectedWorkspacePath by rememberSaveable {
        mutableStateOf(SshConfigStore.loadWorkspacePath(context))
    }
    var showWorkspacePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (SshSessionManager.consumeInitialAutoConnect()) {
            AgentForegroundService.connect(context)
        }
    }

    LaunchedEffect(drawerState.isOpen, sessionState, activeAdapter.id) {
        if (drawerState.isOpen &&
            sessionState is ConnectionState.Connected &&
            activeAdapter.supportsSessions
        ) {
            SshSessionManager.refreshAgentSessionsAsync()
        }

    }

    BackHandler(enabled = drawerState.isOpen || currentScreen == MainScreen.CONFIG) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            currentScreen = MainScreen.MAIN
        }
    }

    if (showNotificationRationale && activity != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNotificationRationale = false },
            title = { Text("Permitir notificaciones") },
            text = { Text("Las notificaciones avisan turnos completados y permisos pendientes.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showNotificationRationale = false
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        100
                    )
                }) { Text("Permitir") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showNotificationRationale = false
                }) { Text("Ahora no") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet {
                DrawerOptionsMenu(
                    activeAgentId = activeAdapter.id,
                    currentScreen = currentScreen,
                    isConnected = sessionState is ConnectionState.Connected,
                    selectedWorkspacePath = selectedWorkspacePath,
                    agentSessions = agentSessions,
                    agentSessionsLoading = agentSessionsLoading,
                    agentSessionsError = agentSessionsError,
                    activeConversationId = activeConversationId,
                    onSelectAgent = { id ->
                        if (agentState == AgentState.WORKING ||
                            agentState == AgentState.WAITING_PERMISSION
                        ) return@DrawerOptionsMenu
                        SshSessionManager.setActiveAdapterById(id)
                        currentScreen = MainScreen.MAIN
                        selectedTab = AppTab.CHAT
                        scope.launch { drawerState.close() }
                    },
                    onNewSession = {
                        if (agentState == AgentState.WORKING ||
                            agentState == AgentState.WAITING_PERMISSION
                        ) return@DrawerOptionsMenu
                        SshSessionManager.startNewSessionAsync(selectedWorkspacePath)
                        currentScreen = MainScreen.MAIN
                        selectedTab = AppTab.CHAT
                        scope.launch { drawerState.close() }
                    },
                    onSelectSession = { session ->
                        if (agentState == AgentState.WORKING ||
                            agentState == AgentState.WAITING_PERMISSION
                        ) return@DrawerOptionsMenu
                        if (session.workspacePath.isNotBlank()) {
                            selectedWorkspacePath = session.workspacePath
                            SshConfigStore.saveWorkspacePath(context, session.workspacePath)
                        }
                        SshSessionManager.openConversationAsync(session)
                        currentScreen = MainScreen.MAIN
                        selectedTab = AppTab.CHAT
                        scope.launch { drawerState.close() }
                    },
                    onRefreshSessions = {
                        SshSessionManager.refreshAgentSessionsAsync()
                    },
                    onSelectConfig = {
                        currentScreen = MainScreen.CONFIG
                        scope.launch { drawerState.close() }
                    },
                    onCloseDrawer = {
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            when (currentScreen) {
                MainScreen.CONFIG -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { currentScreen = MainScreen.MAIN }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Volver a la pantalla principal"
                            )
                        }
                        Text(
                            text = "Configuración SSH",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() }
                        )
                    }

                    HorizontalDivider()

                    SshConfigScreenContent(
                        config = config,
                        portText = portText,
                        passwordVisible = passwordVisible,
                        feedbackMessage = feedbackMessage,
                        validationError = validationError,
                        sessionState = sessionState,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        onSelectAuthMethod = { method ->
                            config = config.copy(authMethod = method)
                            feedbackMessage = null
                        },
                        onUsernameChange = { value ->
                            config = config.copy(username = value)
                        },
                        onPortChange = { value ->
                            val digits = value.filter(Char::isDigit).take(5)
                            portText = digits
                            validationError = null
                            digits.toIntOrNull()?.takeIf { it in 1..65535 }?.let { validPort ->
                                config = config.copy(port = validPort)
                            }
                        },
                        onPasswordChange = { value ->
                            config = config.copy(password = value)
                        },
                        onTogglePasswordVisibility = {
                            passwordVisible = !passwordVisible
                        },
                        onCopySetupCommand = {
                            SshConfigStore.save(context, config)
                            val cmd = SshKeyManager.buildAuthorizedKeysCommand(config.publicKeyAuthorized)
                            clipboard.setText(AnnotatedString(cmd))
                            feedbackMessage = "Comando copiado. Pégalo en Termux."
                        },
                        onCopyPublicKey = {
                            SshConfigStore.save(context, config)
                            clipboard.setText(AnnotatedString(config.publicKeyAuthorized))
                            feedbackMessage = "Clave pública copiada al portapapeles."
                        },
                        onRegenerateKey = {
                            val (newPriv, newPub) = SshKeyManager.generateKeyPair()
                            val updated = config.copy(
                                privateKeyPem = newPriv,
                                publicKeyAuthorized = newPub
                            )
                            config = updated
                            SshConfigStore.save(context, updated)
                            feedbackMessage = "Nueva clave SSH generada. Copia el comando en Termux."
                        },
                        onSaveAndConnect = {
                            val parsedPort = portText.trim().toIntOrNull()
                            if (parsedPort == null || parsedPort !in 1..65535) {
                                validationError = "Puerto SSH inválido. Introduce un valor entre 1 y 65535."
                                return@SshConfigScreenContent
                            }
                            validationError = null
                            val normalized = config.copy(
                                port = parsedPort,
                                username = config.username.trim().ifBlank { AppConfig.DEFAULT_SSH_USER }
                            )
                            config = normalized
                            SshConfigStore.save(context, normalized)
                            AgentForegroundService.connect(context)
                            currentScreen = MainScreen.MAIN
                        },
                        onDisconnect = {
                            AgentForegroundService.disconnect(context)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                MainScreen.MAIN -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Abrir menú"
                            )
                        }
                        Text(
                            text = "Termux Agent",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("main_screen_title")
                        )
                        if (sessionState !is ConnectionState.Disconnected) {
                            IconButton(
                                onClick = { AgentForegroundService.disconnect(context) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Desconectar SSH",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    if (sessionState is ConnectionState.Connected) {
                        TabRow(
                            selectedTabIndex = selectedTab.ordinal,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AppTab.values().forEach { tab ->
                                Tab(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    text = { Text(tab.title) }
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = agentIconForId(activeAdapter.id),
                                    contentDescription = activeAdapter.name,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activeConversationTitle?.takeIf { it.isNotBlank() }
                                            ?: "Nueva conversación",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = when (agentState) {
                                            AgentState.IDLE -> "Listo"
                                            AgentState.WORKING -> "Trabajando"
                                            AgentState.WAITING_PERMISSION -> "Esperando permiso"
                                            AgentState.COMPLETED -> "Completado"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = ChatBubbleIcon,
                                        contentDescription = "Abrir sesiones"
                                    )
                                }
                            }
                            HorizontalDivider()
                        }

                        when (selectedTab) {
                            AppTab.CHAT -> {
                                ChatScreen(
                                    activeAdapter = activeAdapter,
                                    agentState = agentState,
                                    adapterEvent = adapterEvent,
                                    messages = chatMessages,
                                    selectedWorkspacePath = selectedWorkspacePath,
                                    promptText = chatDraft,
                                    onPromptTextChange = { chatDraft = it },
                                    onOpenWorkspacePicker = {
                                        showWorkspacePicker = true
                                    },
                                    onLaunchAgent = {
                                        SshSessionManager.launchActiveAgentAsync(selectedWorkspacePath)
                                    },
                                    onSendPrompt = { prompt ->
                                        SshSessionManager.sendPromptAsync(
                                            prompt,
                                            selectedWorkspacePath
                                        ) { sent ->
                                            if (sent && chatDraft == prompt) chatDraft = ""
                                        }
                                    },
                                    onResolvePermission = { allow, optionIndex ->
                                        AgentForegroundService.resolvePermission(context, allow, optionIndex)
                                    },
                                    onClearChat = {
                                        SshSessionManager.clearChat()
                                    },
                                    activeConversationId = activeConversationId,
                                    activeConversationTitle = activeConversationTitle,
                                    onOpenSessions = {
                                        scope.launch { drawerState.open() }
                                    },
                                    onNewSession = {
                                        SshSessionManager.startNewSessionAsync(selectedWorkspacePath)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            AppTab.TERMINAL -> {
                                PtyTerminalCard(
                                    ptyOutput = ptyOutput,
                                    currentCommandLine = currentCommandLine,
                                    onSendRaw = { raw ->
                                        AgentForegroundService.sendRaw(context, raw)
                                    },
                                    onTerminalResize = { cols, rows, pixelWidth, pixelHeight ->
                                        AgentForegroundService.resizeTerminal(
                                            cols,
                                            rows,
                                            pixelWidth,
                                            pixelHeight
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ConnectionStatusBanner(state = sessionState)

                            if (sessionState is ConnectionState.Error || sessionState is ConnectionState.Disconnected) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Button(
                                        onClick = { AgentForegroundService.connect(context) },
                                        modifier = Modifier.fillMaxWidth(0.72f)
                                    ) {
                                        Icon(
                                            imageVector = if (sessionState is ConnectionState.Disconnected) {
                                                Icons.Default.PlayArrow
                                            } else {
                                                Icons.Default.Refresh
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(if (sessionState is ConnectionState.Disconnected) "Conectar" else "Reintentar")
                                    }
                                    TextButton(
                                        onClick = { currentScreen = MainScreen.CONFIG }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Configurar SSH")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showWorkspacePicker && sessionState is ConnectionState.Connected) {
        WorkspacePickerDialog(
            initialPath = selectedWorkspacePath,
            onListDirectories = { path ->
                withContext(Dispatchers.IO) {
                    SshSessionManager.listDirectories(path)
                }
            },
            onDismiss = { showWorkspacePicker = false },
            onSelectDirectory = { chosenPath ->
                selectedWorkspacePath = chosenPath
                SshConfigStore.saveWorkspacePath(context, chosenPath)
                showWorkspacePicker = false
            }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DrawerOptionsMenu(
    activeAgentId: String,
    currentScreen: MainScreen,
    isConnected: Boolean,
    selectedWorkspacePath: String,
    agentSessions: List<AgentSessionSummary>,
    agentSessionsLoading: Boolean,
    agentSessionsError: String?,
    activeConversationId: String?,
    onSelectAgent: (String) -> Unit,
    onNewSession: () -> Unit,
    onSelectSession: (AgentSessionSummary) -> Unit,
    onRefreshSessions: () -> Unit,
    onSelectConfig: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Menú de opciones",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    text = "Termux Agent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onCloseDrawer
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar menú"
                )
            }
        }

        HorizontalDivider()

        Text(
            text = "Agentes",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() }
        )

        val adapters = AdapterRegistry.availableAdapters()
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            adapters.forEachIndexed { index, adapter ->
                val selected = activeAgentId.equals(adapter.id, ignoreCase = true)
                SegmentedButton(
                    selected = selected,
                    onClick = { onSelectAgent(adapter.id) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = adapters.size
                    ),
                    icon = {
                        Icon(
                            imageVector = agentIconForId(adapter.id),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = {
                        Text(
                            text = if (adapter.id.equals("copilot", ignoreCase = true)) "Copilot" else "Antigravity",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.testTag(
                        if (adapter.id.equals("agy", ignoreCase = true)) {
                            "drawer_option_antigravity"
                        } else {
                            "drawer_option_copilot"
                        }
                    )
                )
            }
        }

        HorizontalDivider(modifier = Modifier.testTag("drawer_options_divider"))

        if (isConnected && AdapterRegistry.findAdapter(activeAgentId)?.supportsSessions == true) {
            AgentSessionsSection(
                activeAgentId = activeAgentId,
                selectedWorkspacePath = selectedWorkspacePath,
                sessions = agentSessions,
                isLoading = agentSessionsLoading,
                errorMessage = agentSessionsError,
                activeConversationId = activeConversationId,
                onNewSession = onNewSession,
                onSelectSession = onSelectSession,
                onRefresh = onRefreshSessions,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            HorizontalDivider()
        }

        DrawerOptionCard(
            title = "Configuración",
            subtitle = "Conexión SSH y ajustes de Termux",
            icon = Icons.Default.Settings,
            isSelected = currentScreen == MainScreen.CONFIG,
            testTag = "drawer_option_config",
            onClick = onSelectConfig
        )
    }
}

@Composable
private fun AgentSessionsSection(
    activeAgentId: String,
    selectedWorkspacePath: String,
    sessions: List<AgentSessionSummary>,
    isLoading: Boolean,
    errorMessage: String?,
    activeConversationId: String?,
    onNewSession: () -> Unit,
    onSelectSession: (AgentSessionSummary) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var filterByWorkspace by rememberSaveable { mutableStateOf(false) }
    val applyWorkspaceFilter = filterByWorkspace && selectedWorkspacePath.isNotBlank()
    val visibleSessions = remember(sessions, applyWorkspaceFilter, selectedWorkspacePath) {
        if (applyWorkspaceFilter) {
            sessions.filter { AgentSessionBrowser.matchesWorkspace(it.workspacePath, selectedWorkspacePath) }
        } else {
            sessions
        }
    }
    val groups = remember(visibleSessions, selectedWorkspacePath) {
        AgentSessionBrowser.groupSessionsByWorkspace(visibleSessions, selectedWorkspacePath)
    }
    val defaultExpanded = remember(groups, activeConversationId, selectedWorkspacePath) {
        AgentSessionBrowser.defaultExpandedWorkspaceKey(groups, activeConversationId, selectedWorkspacePath)
    }
    val expandedOverrides = remember(activeAgentId) { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Directorios y sesiones",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() }
            )
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag("drawer_refresh_sessions")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Actualizar sesiones",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        FilledTonalButton(
            onClick = onNewSession,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("drawer_new_session_button")
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Nueva conversación")
        }

        if (selectedWorkspacePath.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !filterByWorkspace,
                    onClick = { filterByWorkspace = false },
                    label = { Text("Todas") },
                    modifier = Modifier.testTag("drawer_sessions_filter_all")
                )
                FilterChip(
                    selected = filterByWorkspace,
                    onClick = { filterByWorkspace = true },
                    label = { Text("Este proyecto") },
                    modifier = Modifier.testTag("drawer_sessions_filter_workspace")
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                isLoading && sessions.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMessage != null && sessions.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        OutlinedButton(onClick = onRefresh) {
                            Text("Reintentar")
                        }
                    }
                }
                groups.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (applyWorkspaceFilter) {
                                "No hay sesiones en este proyecto."
                            } else {
                                "No hay sesiones guardadas."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (applyWorkspaceFilter && sessions.isNotEmpty()) {
                            OutlinedButton(onClick = { filterByWorkspace = false }) {
                                Text("Ver todas")
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("drawer_sessions_list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (group in groups) {
                            val isExpanded = applyWorkspaceFilter ||
                                (expandedOverrides[group.workspacePath] ?: (group.workspacePath == defaultExpanded))
                            val isSelectedWorkspace = selectedWorkspacePath.isNotBlank() &&
                                AgentSessionBrowser.matchesWorkspace(group.workspacePath, selectedWorkspacePath)

                            item(key = "ws:${group.workspacePath}") {
                                AgentWorkspaceGroupHeader(
                                    group = group,
                                    isExpanded = isExpanded,
                                    isSelectedWorkspace = isSelectedWorkspace,
                                    onToggleExpand = {
                                        expandedOverrides[group.workspacePath] = !isExpanded
                                    }
                                )
                            }

                            if (isExpanded) {
                                items(group.sessions, key = { "sess:${it.id}" }) { session ->
                                    AgentSessionRow(
                                        session = session,
                                        isSelected = session.id == activeConversationId,
                                        onClick = { onSelectSession(session) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentWorkspaceGroupHeader(
    group: AgentWorkspaceGroup,
    isExpanded: Boolean,
    isSelectedWorkspace: Boolean,
    onToggleExpand: () -> Unit
) {
    val containerColor = if (isSelectedWorkspace) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    }
    val subTextColor = if (isSelectedWorkspace) {
        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tagKey = group.workspacePath.ifEmpty { "none" }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(onClick = onToggleExpand)
                    .testTag("drawer_workspace_header_$tagKey")
                    .semantics(mergeDescendants = true) {}
                    .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) {
                        Icons.Default.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = null,
                    tint = subTextColor,
                    modifier = Modifier.size(18.dp)
                )
                Icon(
                    imageVector = FolderIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${group.displayName} (${group.sessions.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (group.workspacePath.isNotBlank()) {
                        Text(
                            text = tailEllipsisPath(group.workspacePath),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = subTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun tailEllipsisPath(path: String): String {
    val parts = path.trimEnd('/').split('/')
    return if (parts.size <= 2) path else "…/" + parts.takeLast(2).joinToString("/")
}

@Composable
private fun AgentSessionRow(
    session: AgentSessionSummary,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val metaParts = buildList {
        val formattedTime = AgentSessionBrowser.formatShortTimestamp(session.lastModifiedIso)
        if (formattedTime.isNotBlank()) {
            add(formattedTime)
        }
        if (session.isRunning) {
            add("En curso")
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .defaultMinSize(minHeight = 48.dp)
            .testTag("drawer_session_item_${session.id}")
            .semantics { selected = isSelected },
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = ChatBubbleIcon,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
