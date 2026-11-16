package com.termux.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    val sessionState by SshSessionManager.connectionState.collectAsState()
    val ptyOutput by SshSessionManager.ptyOutput.collectAsState()

    LaunchedEffect(Unit) {
        if (SshSessionManager.consumeInitialAutoConnect()) {
            AgentForegroundService.connect(context)
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Configuración SSH",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() }
                    )
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
                        modifier = Modifier.fillMaxWidth()
                    )

                    validationError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }

                    Button(
                        onClick = {
                            val parsedPort = portText.trim().toIntOrNull()
                            if (parsedPort == null || parsedPort !in 1..65535) {
                                validationError = "Puerto SSH inválido. Introduce un valor entre 1 y 65535."
                                return@Button
                            }
                            validationError = null
                            val normalized = config.copy(
                                port = parsedPort,
                                username = config.username.trim().ifBlank { AppConfig.DEFAULT_SSH_USER }
                            )
                            config = normalized
                            SshConfigStore.save(context, normalized)
                            AgentForegroundService.connect(context)
                            scope.launch { drawerState.close() }
                        },
                        enabled = sessionState !is ConnectionState.Checking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Guardar y Conectar")
                    }

                    if (sessionState !is ConnectionState.Disconnected) {
                        OutlinedButton(
                            onClick = {
                                AgentForegroundService.disconnect(context)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "Desconectar")
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { scope.launch { drawerState.open() } }
                ) {
                    Text(text = "☰ SSH")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Termux Agent",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "${config.host}:${config.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (sessionState !is ConnectionState.Disconnected) {
                    TextButton(
                        onClick = { AgentForegroundService.disconnect(context) }
                    ) {
                        Text(
                            text = "Desconectar",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            HorizontalDivider()

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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Configurar SSH")
                        }
                        OutlinedButton(
                            onClick = { AgentForegroundService.connect(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (sessionState is ConnectionState.Disconnected) {
                                    "Conectar"
                                } else {
                                    "Reintentar"
                                }
                            )
                        }
                    }
                }

                if (sessionState is ConnectionState.Connected) {
                    PtyTerminalCard(
                        ptyOutput = ptyOutput,
                        onSendCommand = { command ->
                            AgentForegroundService.sendCommand(context, command)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
            TextButton(onClick = onTogglePasswordVisibility) {
                Text(text = if (passwordVisible) "Ocultar" else "Mostrar")
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
        Text(
            text = publicKey,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(10.dp)
        )
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
    Text(
        text = message,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun PtyTerminalCard(
    ptyOutput: String,
    onSendCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var commandText by rememberSaveable { mutableStateOf("") }
    val outputScrollState = rememberScrollState()
    val submitCommand = {
        onSendCommand(commandText)
        commandText = ""
    }

    LaunchedEffect(ptyOutput) {
        outputScrollState.animateScrollTo(outputScrollState.maxValue)
    }

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
                text = "Consola PTY SSH",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 260.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = ptyOutput.ifBlank { "(esperando salida de Termux...)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(outputScrollState)
                        .padding(10.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    label = { Text("Comando") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitCommand() }),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = submitCommand) {
                    Text(text = "Enviar")
                }
            }
        }
    }
}
