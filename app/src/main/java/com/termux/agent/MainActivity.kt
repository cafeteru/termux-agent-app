package com.termux.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatusScreen()
                }
            }
        }
    }
}

@Composable
fun StatusScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var config by remember { mutableStateOf(SshConfigStore.load(context)) }
    var portText by remember { mutableStateOf(config.port.toString()) }
    var activeConfig by remember { mutableStateOf(config) }
    var passwordVisible by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<ConnectionState>(ConnectionState.Checking) }
    var connectTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(connectTrigger) {
        state = ConnectionState.Checking
        state = withContext(Dispatchers.IO) {
            TermuxConnectionChecker.checkConnectionWithAuth(
                isTermuxInstalled = {
                    TermuxConnectionChecker.isTermuxInstalled(context.packageManager)
                },
                config = activeConfig
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Termux Agent",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Target SSH: ${config.host}:${portText.ifBlank { config.port.toString() }}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
            }
        )

        ConnectionStatusBanner(state = state)

        Button(
            onClick = {
                val parsedPort = portText.trim().toIntOrNull()
                if (parsedPort == null || parsedPort !in 1..65535) {
                    state = ConnectionState.Error(
                        "Puerto SSH inválido. Introduce un valor entre 1 y 65535."
                    )
                    return@Button
                }
                val normalized = config.copy(
                    port = parsedPort,
                    username = config.username.trim().ifBlank { AppConfig.DEFAULT_SSH_USER }
                )
                config = normalized
                SshConfigStore.save(context, normalized)
                activeConfig = normalized
                connectTrigger++
            },
            enabled = state !is ConnectionState.Checking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Guardar y Conectar")
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
    onRegenerateKey: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
private fun ConnectionStatusBanner(state: ConnectionState) {
    when (state) {
        ConnectionState.Checking -> Text(
            text = "Detectando Termux y conectando por SSH...",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        is ConnectionState.Connected -> Text(
            text = "Termux conectado (${state.host}:${state.port})\nServidor: ${state.serverBanner}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        is ConnectionState.Error -> Text(
            text = "Error detectado: ${state.reason}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}
