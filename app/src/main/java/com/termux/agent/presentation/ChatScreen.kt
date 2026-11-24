package com.termux.agent.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.agent.application.port.AgentAdapter
import com.termux.agent.domain.model.AdapterEvent
import com.termux.agent.domain.model.AgentState
import com.termux.agent.domain.model.ChatMessage

@Composable
fun ChatScreen(
    activeAdapter: AgentAdapter,
    agentState: AgentState,
    adapterEvent: AdapterEvent?,
    messages: List<ChatMessage>,
    selectedWorkspacePath: String,
    promptText: String,
    onPromptTextChange: (String) -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    onLaunchAgent: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onResolvePermission: (allow: Boolean, optionIndex: Int?) -> Unit,
    onClearChat: () -> Unit,
    modifier: Modifier = Modifier,
    activeConversationId: String? = null,
    activeConversationTitle: String? = null,
    onOpenSessions: () -> Unit = {},
    onNewSession: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val supportsSessions = activeAdapter.supportsSessions
    val hasPermissionCard = agentState == AgentState.WAITING_PERMISSION &&
        adapterEvent is AdapterEvent.PermissionRequired

    LaunchedEffect(messages.size, hasPermissionCard) {
        val totalItems = messages.size + if (hasPermissionCard) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    val submitPrompt = {
        val text = promptText.trim()
        if (text.isNotEmpty() &&
            agentState != AgentState.WORKING &&
            agentState != AgentState.WAITING_PERMISSION
        ) {
            onSendPrompt(text)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (messages.isEmpty() && agentState != AgentState.WAITING_PERMISSION) {
                EmptyChatPlaceholder(
                    agentName = activeAdapter.name,
                    onPromptSelected = onPromptTextChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message ->
                        ChatMessageItem(message = message)
                    }

                    if (agentState == AgentState.WAITING_PERMISSION && adapterEvent is AdapterEvent.PermissionRequired) {
                        item {
                            PermissionRequestCard(
                                event = adapterEvent,
                                onSelectOption = onResolvePermission,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("permission_request_card")
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        ChatInputBar(
            promptText = promptText,
            agentName = activeAdapter.name,
            agentState = agentState,
            onTextChange = onPromptTextChange,
            onSubmit = submitPrompt,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        )
    }
}

internal val ChatBubbleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ChatBubbleOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = PathParser().parsePathString(
            "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zm0,14H6l-2,2V4h16v12z"
        ).toNodes(),
        fill = SolidColor(Color.Black)
    ).build()
}

@Composable
private fun ChatMessageItem(message: ChatMessage, modifier: Modifier = Modifier) {
    val isUser = message.sender.equals("user", ignoreCase = true)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            },
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isUser) "Tú" else "Agente",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    if (message.timestamp.isNotBlank()) {
                        Text(
                            text = message.timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                MarkdownText(
                    markdown = message.content,
                    textColor = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                if (message.toolCalls.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Herramientas ejecutadas:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    for (toolCall in message.toolCalls) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0x22888888),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                        ) {
                            Text(
                                text = toolCall,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder(
    agentName: String,
    onPromptSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = ">_",
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Conversación con $agentName",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Elige una acción rápida o escribe un prompt.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            SuggestionChip(
                onClick = { onPromptSelected("git status") },
                label = { Text("git status") }
            )
            SuggestionChip(
                onClick = { onPromptSelected("Explicar código") },
                label = { Text("Explicar código") }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            SuggestionChip(
                onClick = { onPromptSelected("Revisar error") },
                label = { Text("Revisar error") }
            )
            SuggestionChip(
                onClick = { onPromptSelected("Analizar cambios") },
                label = { Text("Analizar cambios") }
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    promptText: String,
    agentName: String,
    agentState: AgentState,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isWorking = agentState == AgentState.WORKING
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = promptText,
            onValueChange = onTextChange,
            shape = RoundedCornerShape(24.dp),
            placeholder = {
                Text(
                    text = if (isWorking) "$agentName está procesando..." else "Prompt para $agentName...",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_input_field")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { onTextChange(promptText + "/") }) {
                Text("/")
            }
            TextButton(onClick = { onTextChange(promptText + "@") }) {
                Text("@")
            }
            TextButton(
                onClick = {
                    val stdout = clipboard.getText()?.text
                    if (!stdout.isNullOrBlank()) {
                        onTextChange(
                            if (promptText.isBlank()) stdout else "$promptText\n$stdout"
                        )
                    }
                }
            ) {
                Text("Pegar stdout")
            }

            Spacer(modifier = Modifier.weight(1f))

            FilledIconButton(
                onClick = onSubmit,
                enabled = promptText.isNotBlank() &&
                    agentState != AgentState.WORKING &&
                    agentState != AgentState.WAITING_PERMISSION,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar mensaje a $agentName"
                )
            }
        }
    }
}
