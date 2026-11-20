package com.termux.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.DrawerValue
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatusScreen()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    private companion object {
        const val REQUEST_POST_NOTIFICATIONS = 100
    }
}

enum class AppTab(val title: String) {
    CHAT("Chat"),
    TERMINAL("Terminal")
}

enum class MainScreen {
    MAIN,
    CONFIG
}

@Composable
fun StatusScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(SshConfigStore.load(context)) }
    var portText by rememberSaveable { mutableStateOf(config.port.toString()) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var currentScreen by rememberSaveable { mutableStateOf(MainScreen.MAIN) }

    val sessionState by SshSessionManager.connectionState.collectAsState()
    val ptyOutput by SshSessionManager.ptyOutput.collectAsState()
    val currentCommandLine by SshSessionManager.currentCommandLine.collectAsState()
    val activeAdapter by SshSessionManager.activeAdapter.collectAsState()
    val agentState by SshSessionManager.agentState.collectAsState()
    val adapterEvent by SshSessionManager.adapterEvent.collectAsState()
    val chatMessages by SshSessionManager.chatMessages.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.CHAT) }
    var selectedWorkspacePath by rememberSaveable {
        mutableStateOf(SshConfigStore.loadWorkspacePath(context))
    }
    var showWorkspacePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (SshSessionManager.consumeInitialAutoConnect()) {
            AgentForegroundService.connect(context)
        }
    }

    BackHandler(enabled = drawerState.isOpen || currentScreen == MainScreen.CONFIG) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            currentScreen = MainScreen.MAIN
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet {
                DrawerOptionsMenu(
                    activeAgentId = activeAdapter.id,
                    currentScreen = currentScreen,
                    onSelectAgent = { id ->
                        SshSessionManager.setActiveAdapterById(id)
                        currentScreen = MainScreen.MAIN
                        selectedTab = AppTab.CHAT
                        scope.launch { drawerState.close() }
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
                            modifier = Modifier.weight(1f)
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

                        when (selectedTab) {
                            AppTab.CHAT -> {
                                ChatScreen(
                                    activeAdapter = activeAdapter,
                                    agentState = agentState,
                                    adapterEvent = adapterEvent,
                                    messages = chatMessages,
                                    selectedWorkspacePath = selectedWorkspacePath,
                                    onOpenWorkspacePicker = {
                                        showWorkspacePicker = true
                                    },
                                    onLaunchAgent = {
                                        SshSessionManager.launchActiveAgentAsync(selectedWorkspacePath)
                                    },
                                    onSendPrompt = { prompt ->
                                        SshSessionManager.sendPromptAsync(prompt)
                                    },
                                    onResolvePermission = { allow ->
                                        AgentForegroundService.resolvePermission(context, allow)
                                    },
                                    onClearChat = {
                                        SshSessionManager.clearChat()
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            AppTab.TERMINAL -> {
                                PtyTerminalCard(
                                    ptyOutput = ptyOutput,
                                    currentCommandLine = currentCommandLine,
                                    onSendCommand = { command ->
                                        AgentForegroundService.sendCommand(context, command)
                                    },
                                    onSendRaw = { raw ->
                                        AgentForegroundService.sendRaw(context, raw)
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
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledIconButton(
                                        onClick = { currentScreen = MainScreen.CONFIG }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Configurar SSH"
                                        )
                                    }
                                    OutlinedIconButton(
                                        onClick = { AgentForegroundService.connect(context) }
                                    ) {
                                        val isDisconnected = sessionState is ConnectionState.Disconnected
                                        Icon(
                                            imageVector = if (isDisconnected) {
                                                Icons.Default.PlayArrow
                                            } else {
                                                Icons.Default.Refresh
                                            },
                                            contentDescription = if (isDisconnected) "Conectar" else "Reintentar"
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
private fun DrawerOptionsMenu(
    activeAgentId: String,
    currentScreen: MainScreen,
    onSelectAgent: (String) -> Unit,
    onSelectConfig: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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

        DrawerOptionCard(
            title = "Antigravity",
            subtitle = "Google Antigravity CLI (agy)",
            icon = AntigravityIcon,
            isSelected = currentScreen == MainScreen.MAIN && activeAgentId.equals("agy", ignoreCase = true),
            testTag = "drawer_option_antigravity",
            onClick = { onSelectAgent("agy") }
        )

        DrawerOptionCard(
            title = "Copilot",
            subtitle = "GitHub Copilot CLI (copilot)",
            icon = CopilotIcon,
            isSelected = currentScreen == MainScreen.MAIN && activeAgentId.equals("copilot", ignoreCase = true),
            testTag = "drawer_option_copilot",
            onClick = { onSelectAgent("copilot") }
        )

        HorizontalDivider(modifier = Modifier.testTag("drawer_options_divider"))

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
private fun DrawerOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val iconTint = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics { selected = isSelected },
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isSelected) Icons.Default.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun SshConfigScreenContent(
    config: SshConfig,
    portText: String,
    passwordVisible: Boolean,
    feedbackMessage: String?,
    validationError: String?,
    sessionState: ConnectionState,
    onSelectAuthMethod: (AuthMethod) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onCopySetupCommand: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onRegenerateKey: () -> Unit,
    onSaveAndConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Target: ${config.host}:${portText.ifBlank { config.port.toString() }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        SshConfigMenuCard(
            config = config,
            portText = portText,
            passwordVisible = passwordVisible,
            feedbackMessage = feedbackMessage,
            onSelectAuthMethod = onSelectAuthMethod,
            onUsernameChange = onUsernameChange,
            onPortChange = onPortChange,
            onPasswordChange = onPasswordChange,
            onTogglePasswordVisibility = onTogglePasswordVisibility,
            onCopySetupCommand = onCopySetupCommand,
            onCopyPublicKey = onCopyPublicKey,
            onRegenerateKey = onRegenerateKey,
            modifier = Modifier.fillMaxWidth()
        )

        validationError?.let { error ->
            SelectionContainer {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }

        Button(
            onClick = onSaveAndConnect,
            enabled = sessionState !is ConnectionState.Checking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Guardar y Conectar")
        }

        if (sessionState !is ConnectionState.Disconnected) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Desconectar")
            }
        }
    }
}

@Composable
private fun SshConfigMenuCard(
    config: SshConfig,
    portText: String,
    passwordVisible: Boolean,
    feedbackMessage: String?,
    onSelectAuthMethod: (AuthMethod) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onCopySetupCommand: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onRegenerateKey: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Método de conexión SSH",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = config.authMethod == AuthMethod.PASSWORD,
                    onClick = { onSelectAuthMethod(AuthMethod.PASSWORD) },
                    label = { Text("Contraseña (passwd)") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = config.authMethod == AuthMethod.PUBLIC_KEY,
                    onClick = { onSelectAuthMethod(AuthMethod.PUBLIC_KEY) },
                    label = { Text("Clave SSH (key)") },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = config.username,
                    onValueChange = onUsernameChange,
                    label = { Text("Usuario") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = onPortChange,
                    label = { Text("Puerto") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.7f)
                )
            }

            if (config.authMethod == AuthMethod.PASSWORD) {
                PasswordAuthSection(
                    password = config.password,
                    passwordVisible = passwordVisible,
                    onPasswordChange = onPasswordChange,
                    onTogglePasswordVisibility = onTogglePasswordVisibility
                )
            } else {
                PublicKeyAuthSection(
                    publicKey = config.publicKeyAuthorized,
                    feedbackMessage = feedbackMessage,
                    onCopySetupCommand = onCopySetupCommand,
                    onCopyPublicKey = onCopyPublicKey,
                    onRegenerateKey = onRegenerateKey
                )
            }
        }
    }
}

@Composable
private fun PasswordAuthSection(
    password: String,
    passwordVisible: Boolean,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit
) {
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Contraseña de Termux") },
        singleLine = true,
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(
                onClick = onTogglePasswordVisibility,
                // Keep toggle semantics separate from OutlinedTextField's mergeDescendants=true.
                modifier = Modifier.semantics(mergeDescendants = true) {}
            ) {
                Icon(
                    imageVector = if (passwordVisible) VisibilityOffIcon else VisibilityIcon,
                    contentDescription = if (passwordVisible) {
                        "Ocultar contraseña"
                    } else {
                        "Mostrar contraseña"
                    }
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = "En Termux ejecuta 'passwd' para definir tu contraseña y 'sshd' para iniciar el servidor.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PublicKeyAuthSection(
    publicKey: String,
    feedbackMessage: String?,
    onCopySetupCommand: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onRegenerateKey: () -> Unit
) {
    Text(
        text = "Clave pública generada (authorized_keys):",
        style = MaterialTheme.typography.labelMedium
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface
    ) {
        SelectionContainer {
            Text(
                text = publicKey,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(10.dp)
            )
        }
    }
    Button(
        onClick = onCopySetupCommand,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "Copiar comando para Termux")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onCopyPublicKey,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = "Copiar clave")
        }
        OutlinedButton(
            onClick = onRegenerateKey,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = "Regenerar clave")
        }
    }
    if (feedbackMessage != null) {
        Text(
            text = feedbackMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConnectionStatusBanner(
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    val (message, color) = when (state) {
        ConnectionState.Checking -> {
            "Detectando Termux y conectando por SSH..." to MaterialTheme.colorScheme.onSurface
        }
        is ConnectionState.Connected -> {
            "Termux conectado (${state.host}:${state.port})\nServidor: ${state.serverBanner}" to MaterialTheme.colorScheme.primary
        }
        is ConnectionState.Error -> {
            "Error detectado: ${state.reason}" to MaterialTheme.colorScheme.error
        }
        ConnectionState.Disconnected -> {
            "Sesión SSH desconectada." to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    SelectionContainer(modifier = modifier) {
        Text(
            text = message,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun PtyTerminalCard(
    ptyOutput: String,
    currentCommandLine: String = "",
    onSendCommand: (String) -> Unit,
    onSendRaw: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isInteractiveMode by rememberSaveable { mutableStateOf(true) }
    var interactiveValue by remember { mutableStateOf(TextFieldValue("")) }
    val interactiveFocusRequester = remember { FocusRequester() }
    var isInteractiveFocused by remember { mutableStateOf(false) }

    var commandValue by remember { mutableStateOf(TextFieldValue("")) }
    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    var draftCommand by remember { mutableStateOf("") }
    var isCommandFocused by remember { mutableStateOf(false) }

    var ctrlArmed by rememberSaveable { mutableStateOf(false) }
    var altArmed by rememberSaveable { mutableStateOf(false) }
    val outputScrollState = rememberScrollState()
    val styledOutput = remember(ptyOutput) { AnsiTextParser.parse(ptyOutput) }
    val commandFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val terminalInteractionSource = remember { MutableInteractionSource() }

    val focusCommandField = {
        runCatching { commandFocusRequester.requestFocus() }
        keyboardController?.show()
        isCommandFocused = true
        Unit
    }

    val focusInteractiveField = {
        runCatching { interactiveFocusRequester.requestFocus() }
        keyboardController?.show()
        isInteractiveFocused = true
        Unit
    }

    val focusCurrentField = {
        if (isInteractiveMode) focusInteractiveField() else focusCommandField()
    }

    val submitCommand = {
        val text = commandValue.text
        onSendCommand(text)
        if (text.isNotBlank()) {
            if (commandHistory.isEmpty() || commandHistory.last() != text) {
                commandHistory.add(text)
            }
        }
        historyIndex = -1
        draftCommand = ""
        commandValue = TextFieldValue("")
    }

    val navigateHistoryUp = {
        if (commandHistory.isNotEmpty()) {
            if (historyIndex == -1) {
                draftCommand = commandValue.text
                historyIndex = commandHistory.size - 1
            } else if (historyIndex > 0) {
                historyIndex--
            }
            val historical = commandHistory[historyIndex]
            commandValue = TextFieldValue(historical, selection = TextRange(historical.length))
        } else {
            onSendRaw(SshSessionManager.applyModifiers("\u001B[A", ctrl = false, alt = altArmed))
            ctrlArmed = false
            altArmed = false
        }
        focusCommandField()
    }

    val navigateHistoryDown = {
        if (historyIndex != -1) {
            if (historyIndex < commandHistory.size - 1) {
                historyIndex++
                val historical = commandHistory[historyIndex]
                commandValue = TextFieldValue(historical, selection = TextRange(historical.length))
            } else {
                historyIndex = -1
                commandValue = TextFieldValue(draftCommand, selection = TextRange(draftCommand.length))
            }
        } else {
            onSendRaw(SshSessionManager.applyModifiers("\u001B[B", ctrl = false, alt = altArmed))
            ctrlArmed = false
            altArmed = false
        }
        focusCommandField()
    }

    val handleAutocomplete = {
        val text = commandValue.text
        if (text.isNotEmpty()) {
            val matches = commandHistory.filter { it.startsWith(text, ignoreCase = true) && it != text }
            if (matches.isNotEmpty()) {
                val currentIndex = matches.indexOf(text)
                val nextIndex = (currentIndex + 1) % matches.size
                val completed = matches[nextIndex]
                commandValue = TextFieldValue(completed, selection = TextRange(completed.length))
            } else {
                val lastSpace = text.lastIndexOf(' ')
                if (lastSpace >= 0) {
                    val prefix = text.substring(0, lastSpace + 1)
                    val lastWord = text.substring(lastSpace + 1)
                    if (lastWord.isNotEmpty()) {
                        val tokenMatches = commandHistory
                            .flatMap { it.split(' ') }
                            .filter { it.startsWith(lastWord, ignoreCase = true) && it != lastWord }
                            .distinct()
                        if (tokenMatches.isNotEmpty()) {
                            val completed = prefix + tokenMatches.first()
                            commandValue = TextFieldValue(completed, selection = TextRange(completed.length))
                        } else {
                            onSendRaw("\t")
                        }
                    } else {
                        onSendRaw("\t")
                    }
                } else {
                    onSendRaw("\t")
                }
            }
        } else {
            onSendRaw("\t")
        }
        focusCommandField()
    }

    val handleMoveLeft = {
        if (commandValue.text.isNotEmpty()) {
            val newPos = maxOf(0, commandValue.selection.start - 1)
            commandValue = commandValue.copy(selection = TextRange(newPos))
        } else {
            onSendRaw(SshSessionManager.applyModifiers("\u001B[D", ctrl = false, alt = altArmed))
            ctrlArmed = false
            altArmed = false
        }
        focusCommandField()
    }

    val handleMoveRight = {
        if (commandValue.text.isNotEmpty()) {
            val newPos = minOf(commandValue.text.length, commandValue.selection.end + 1)
            commandValue = commandValue.copy(selection = TextRange(newPos))
        } else {
            onSendRaw(SshSessionManager.applyModifiers("\u001B[C", ctrl = false, alt = altArmed))
            ctrlArmed = false
            altArmed = false
        }
        focusCommandField()
    }

    LaunchedEffect(ptyOutput, outputScrollState.maxValue) {
        outputScrollState.scrollTo(outputScrollState.maxValue)
    }

    LaunchedEffect(isInteractiveMode) {
        if (isInteractiveMode) {
            focusInteractiveField()
        } else {
            focusCommandField()
        }
    }

    LaunchedEffect(currentCommandLine) {
        if (currentCommandLine != interactiveValue.text) {
            interactiveValue = TextFieldValue(
                text = currentCommandLine,
                selection = TextRange(currentCommandLine.length)
            )
        }
        if (currentCommandLine != commandValue.text) {
            commandValue = TextFieldValue(
                text = currentCommandLine,
                selection = TextRange(currentCommandLine.length)
            )
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isInteractiveMode) "Modo interactivo (en vivo)" else "Modo búfer (comando)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = isInteractiveMode,
                    onClick = { isInteractiveMode = true },
                    label = { Text("Directo", style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = !isInteractiveMode,
                    onClick = { isInteractiveMode = false },
                    label = { Text("Búfer", style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(
                    interactionSource = terminalInteractionSource,
                    indication = null,
                    onClick = focusCurrentField
                ),
            shape = MaterialTheme.shapes.small,
            color = Color(0xFF121212)
        ) {
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = if (styledOutput.isEmpty()) {
                        AnnotatedString("(esperando salida de Termux...)")
                    } else {
                        styledOutput
                    },
                    color = Color(0xFFE0E0E0),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(outputScrollState)
                        .clickable(
                            interactionSource = terminalInteractionSource,
                            indication = null,
                            onClick = focusCurrentField
                        )
                        .padding(10.dp)
                )
            }
        }
        ExtraKeysRow(
            ctrlArmed = ctrlArmed,
            altArmed = altArmed,
            onToggleCtrl = {
                ctrlArmed = !ctrlArmed
                focusCurrentField()
            },
            onToggleAlt = {
                altArmed = !altArmed
                focusCurrentField()
            },
            onEsc = {
                onSendRaw(SshSessionManager.applyModifiers("\u001B", ctrl = false, alt = altArmed))
                ctrlArmed = false
                altArmed = false
                focusCurrentField()
            },
            onTab = {
                if (isInteractiveMode) {
                    onSendRaw("\t")
                    focusCurrentField()
                } else {
                    handleAutocomplete()
                }
            },
            onArrowLeft = {
                if (isInteractiveMode) {
                    onSendRaw(SshSessionManager.applyModifiers("\u001B[D", ctrl = false, alt = altArmed))
                    ctrlArmed = false
                    altArmed = false
                    focusCurrentField()
                } else {
                    handleMoveLeft()
                }
            },
            onArrowDown = {
                if (isInteractiveMode) {
                    interactiveValue = TextFieldValue("")
                    onSendRaw(SshSessionManager.applyModifiers("\u001B[B", ctrl = false, alt = altArmed))
                    ctrlArmed = false
                    altArmed = false
                    focusCurrentField()
                } else {
                    navigateHistoryDown()
                }
            },
            onArrowUp = {
                if (isInteractiveMode) {
                    interactiveValue = TextFieldValue("")
                    onSendRaw(SshSessionManager.applyModifiers("\u001B[A", ctrl = false, alt = altArmed))
                    ctrlArmed = false
                    altArmed = false
                    focusCurrentField()
                } else {
                    navigateHistoryUp()
                }
            },
            onArrowRight = {
                if (isInteractiveMode) {
                    onSendRaw(SshSessionManager.applyModifiers("\u001B[C", ctrl = false, alt = altArmed))
                    ctrlArmed = false
                    altArmed = false
                    focusCurrentField()
                } else {
                    handleMoveRight()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )
        if (isInteractiveMode) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clickable(onClick = focusInteractiveField),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                BasicTextField(
                    value = interactiveValue,
                    onValueChange = { updated ->
                        val oldText = interactiveValue.text
                        val newText = updated.text
                        if (newText.length > oldText.length) {
                            val added = newText.substring(oldText.length)
                            for (ch in added) {
                                if (ch == '\n' || ch == '\r') {
                                    onSendRaw("\r")
                                } else {
                                    val raw = SshSessionManager.applyModifiers(
                                        ch.toString(),
                                        ctrl = ctrlArmed,
                                        alt = altArmed
                                    )
                                    onSendRaw(raw)
                                    ctrlArmed = false
                                    altArmed = false
                                }
                            }
                        } else if (newText.length < oldText.length) {
                            val deletedCount = oldText.length - newText.length
                            repeat(deletedCount) {
                                onSendRaw(
                                    SshSessionManager.applyModifiers(
                                        "\u007F",
                                        ctrl = ctrlArmed,
                                        alt = altArmed
                                    )
                                )
                                ctrlArmed = false
                                altArmed = false
                            }
                        }

                        if (newText.contains('\n') || newText.contains('\r')) {
                            interactiveValue = TextFieldValue("")
                        } else {
                            interactiveValue = updated
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("interactive_terminal_input")
                        .focusRequester(interactiveFocusRequester)
                        .onFocusChanged { isInteractiveFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        onSendRaw("\r")
                                        interactiveValue = TextFieldValue("")
                                        true
                                    }
                                    Key.Tab -> {
                                        onSendRaw("\t")
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        interactiveValue = TextFieldValue("")
                                        onSendRaw(SshSessionManager.applyModifiers("\u001B[A", ctrl = false, alt = altArmed))
                                        ctrlArmed = false
                                        altArmed = false
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        interactiveValue = TextFieldValue("")
                                        onSendRaw(SshSessionManager.applyModifiers("\u001B[B", ctrl = false, alt = altArmed))
                                        ctrlArmed = false
                                        altArmed = false
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        onSendRaw(SshSessionManager.applyModifiers("\u001B[D", ctrl = false, alt = altArmed))
                                        ctrlArmed = false
                                        altArmed = false
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        onSendRaw(SshSessionManager.applyModifiers("\u001B[C", ctrl = false, alt = altArmed))
                                        ctrlArmed = false
                                        altArmed = false
                                        true
                                    }
                                    Key.Escape -> {
                                        onSendRaw(SshSessionManager.applyModifiers("\u001B", ctrl = false, alt = altArmed))
                                        ctrlArmed = false
                                        altArmed = false
                                        true
                                    }
                                    Key.Backspace -> {
                                        if (interactiveValue.text.isEmpty()) {
                                            onSendRaw(
                                                SshSessionManager.applyModifiers(
                                                    "\u007F",
                                                    ctrl = ctrlArmed,
                                                    alt = altArmed
                                                )
                                            )
                                            ctrlArmed = false
                                            altArmed = false
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.None
                    ),
                    keyboardActions = KeyboardActions(
                        onAny = {
                            onSendRaw("\r")
                            interactiveValue = TextFieldValue("")
                        }
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace
                    ),
                    decorationBox = { innerTextField ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = ">_",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                if (interactiveValue.text.isEmpty()) {
                                    Text(
                                        text = "Teclado directo (escribe en vivo a Termux)...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    }
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandValue,
                    onValueChange = { updated ->
                        if ((ctrlArmed || altArmed) && updated.text.length > commandValue.text.length) {
                            val ch = updated.text.last().toString()
                            onSendRaw(SshSessionManager.applyModifiers(ch, ctrl = ctrlArmed, alt = altArmed))
                            ctrlArmed = false
                            altArmed = false
                        } else if (updated.text.contains('\n') || updated.text.contains('\r')) {
                            commandValue = updated.copy(
                                text = updated.text.replace("\r", "").replace("\n", "")
                            )
                            submitCommand()
                        } else {
                            commandValue = updated
                        }
                    },
                    label = { Text("Comando") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { submitCommand() },
                        onDone = { submitCommand() },
                        onGo = { submitCommand() }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_input_field")
                        .focusRequester(commandFocusRequester)
                        .onFocusChanged { isCommandFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        submitCommand()
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        navigateHistoryUp()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        navigateHistoryDown()
                                        true
                                    }
                                    Key.Tab -> {
                                        handleAutocomplete()
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                )
                FilledIconButton(onClick = submitCommand) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Enviar comando"
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraKeysRow(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onArrowLeft: () -> Unit,
    onArrowDown: () -> Unit,
    onArrowUp: () -> Unit,
    onArrowRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExtraKeyButton(
            onClick = onEsc,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = "ESC", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        ExtraKeyButton(
            onClick = onTab,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = "TAB", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        ExtraKeyButton(
            active = ctrlArmed,
            onClick = onToggleCtrl,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = "CTRL", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        ExtraKeyButton(
            active = altArmed,
            onClick = onToggleAlt,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = "ALT", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        ExtraKeyButton(
            onClick = onArrowLeft,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Flecha izquierda"
            )
        }
        ExtraKeyButton(
            onClick = onArrowDown,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Flecha abajo"
            )
        }
        ExtraKeyButton(
            onClick = onArrowUp,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Flecha arriba"
            )
        }
        ExtraKeyButton(
            onClick = onArrowRight,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Flecha derecha"
            )
        }
    }
}

@Composable
private fun ExtraKeyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    content: @Composable () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = MaterialTheme.shapes.extraSmall,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (active) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    ) {
        content()
    }
}

private val VisibilityIcon: ImageVector by lazy {
    materialVectorIcon(
        name = "Visibility",
        pathData = "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"
    )
}

private val VisibilityOffIcon: ImageVector by lazy {
    materialVectorIcon(
        name = "VisibilityOff",
        pathData = "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z"
    )
}

private const val ANTIGRAVITY_PATH =
    "M14.0777 13.984 C14.945 14.6345 16.2458 14.2008 15.0533 13.0084 C11.476 9.53949 12.2349 0 7.79033 0 C3.34579 0 4.10461 9.53949 0.527295 13.0084 C-0.773543 14.3092 0.635692 14.6345 1.50293 13.984 C4.86344 11.7076 4.64663 7.69664 7.79033 7.69664 C10.934 7.69664 10.7172 11.7076 14.0777 13.984 Z"

private const val COPILOT_PATH =
    "M23.922 16.997 C23.061 18.492 18.063 22.02 12 22.02 S0.939 18.492 0.078 16.997 A0.6 0.6 0 0 1 0 16.741 v-2.869 a1 1 0 0 1 0.053 -0.22 c0.372 -0.935 1.347 -2.292 2.605 -2.656 c0.167 -0.429 0.414 -1.055 0.644 -1.517 a10 10 0 0 1 -0.052 -1.086 c0 -1.331 0.282 -2.499 1.132 -3.368 c0.397 -0.406 0.89 -0.717 1.474 -0.952 C7.255 2.937 9.248 1.98 11.978 1.98 s4.767 0.957 6.166 2.093 c0.584 0.235 1.077 0.546 1.474 0.952 c0.85 0.869 1.132 2.037 1.132 3.368 c0 0.368 -0.014 0.733 -0.052 1.086 c0.23 0.462 0.477 1.088 0.644 1.517 c1.258 0.364 2.233 1.721 2.605 2.656 a0.8 0.8 0 0 1 0.053 0.22 v2.869 a0.6 0.6 0 0 1 -0.078 0.256 m-11.75 -5.992 h-0.344 a4 4 0 0 1 -0.355 0.508 c-0.77 0.947 -1.918 1.492 -3.508 1.492 c-1.725 0 -2.989 -0.359 -3.782 -1.259 a2 2 0 0 1 -0.085 -0.104 L4 11.746 v6.585 c1.435 0.779 4.514 2.179 8 2.179 s6.565 -1.4 8 -2.179 v-6.585 l-0.098 -0.104 s-0.033 0.045 -0.085 0.104 c-0.793 0.9 -2.057 1.259 -3.782 1.259 c-1.59 0 -2.738 -0.545 -3.508 -1.492 a4 4 0 0 1 -0.355 -0.508 m2.328 3.25 c0.549 0 1 0.451 1 1 v2 c0 0.549 -0.451 1 -1 1 s-1 -0.451 -1 -1 v-2 c0 -0.549 0.451 -1 1 -1 m-5 0 c0.549 0 1 0.451 1 1 v2 c0 0.549 -0.451 1 -1 1 s-1 -0.451 -1 -1 v-2 c0 -0.549 0.451 -1 1 -1 m3.313 -6.185 c0.136 1.057 0.403 1.913 0.878 2.497 c0.442 0.544 1.134 0.938 2.344 0.938 c1.573 0 2.292 -0.337 2.657 -0.751 c0.384 -0.435 0.558 -1.15 0.558 -2.361 c0 -1.14 -0.243 -1.847 -0.705 -2.319 c-0.477 -0.488 -1.319 -0.862 -2.824 -1.025 c-1.487 -0.161 -2.192 0.138 -2.533 0.529 c-0.269 0.307 -0.437 0.808 -0.438 1.578 v0.021 q0 0.397 0.063 0.893 m-1.626 0 q0.063 -0.496 0.063 -0.894 v-0.02 c-0.001 -0.77 -0.169 -1.271 -0.438 -1.578 c-0.341 -0.391 -1.046 -0.69 -2.533 -0.529 c-1.505 0.163 -2.347 0.537 -2.824 1.025 c-0.462 0.472 -0.705 1.179 -0.705 2.319 c0 1.211 0.175 1.926 0.558 2.361 c0.365 0.414 1.084 0.751 2.657 0.751 c1.21 0 1.902 -0.394 2.344 -0.938 c0.475 -0.584 0.742 -1.44 0.878 -2.497 z"

internal val AntigravityIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Antigravity",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addGroup(
        translationX = 2.26f,
        translationY = 3.0f,
        scaleX = 1.25f,
        scaleY = 1.25f
    ).addPath(
        pathData = PathParser().parsePathString(ANTIGRAVITY_PATH).toNodes(),
        fill = SolidColor(Color.Black)
    ).clearGroup().build()
}

internal val CopilotIcon: ImageVector by lazy {
    materialVectorIcon(name = "Copilot", pathData = COPILOT_PATH)
}

internal fun agentIconForId(id: String): ImageVector =
    if (id.equals("copilot", ignoreCase = true)) CopilotIcon else AntigravityIcon

private fun materialVectorIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black)
    ).build()
