package com.termux.agent.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.termux.agent.infrastructure.ssh.SshSessionManager
import com.termux.agent.infrastructure.terminal.AnsiTextParser

@Composable
internal fun PtyTerminalCard(
    ptyOutput: String,
    currentCommandLine: String = "",
    onSendRaw: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var interactiveValue by remember { mutableStateOf(TextFieldValue("")) }
    val interactiveFocusRequester = remember { FocusRequester() }
    var isInteractiveFocused by remember { mutableStateOf(false) }

    var ctrlArmed by rememberSaveable { mutableStateOf(false) }
    var altArmed by rememberSaveable { mutableStateOf(false) }
    val outputScrollState = rememberScrollState()
    val styledOutput = remember(ptyOutput) { AnsiTextParser.parse(ptyOutput) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val terminalInteractionSource = remember { MutableInteractionSource() }

    val focusInteractiveField = {
        runCatching { interactiveFocusRequester.requestFocus() }
        keyboardController?.show()
        isInteractiveFocused = true
        Unit
    }

    LaunchedEffect(ptyOutput, outputScrollState.maxValue) {
        outputScrollState.scrollTo(outputScrollState.maxValue)
    }

    LaunchedEffect(Unit) {
        focusInteractiveField()
    }

    LaunchedEffect(currentCommandLine) {
        if (currentCommandLine != interactiveValue.text) {
            interactiveValue = TextFieldValue(
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
                text = "Modo interactivo (en vivo)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(
                    interactionSource = terminalInteractionSource,
                    indication = null,
                    onClick = focusInteractiveField
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
                            onClick = focusInteractiveField
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
                focusInteractiveField()
            },
            onToggleAlt = {
                altArmed = !altArmed
                focusInteractiveField()
            },
            onEsc = {
                onSendRaw(SshSessionManager.applyModifiers("\u001B", ctrl = false, alt = altArmed))
                ctrlArmed = false
                altArmed = false
                focusInteractiveField()
            },
            onTab = {
                onSendRaw("\t")
                focusInteractiveField()
            },
            onArrowLeft = {
                onSendRaw(SshSessionManager.applyModifiers("\u001B[D", ctrl = false, alt = altArmed))
                ctrlArmed = false
                altArmed = false
                focusInteractiveField()
            },
            onArrowDown = {
                interactiveValue = TextFieldValue("")
                onSendRaw(SshSessionManager.applyModifiers("\u001B[B", ctrl = false, alt = altArmed))
                ctrlArmed = false
                altArmed = false
                focusInteractiveField()
            },
            onArrowUp = {
                interactiveValue = TextFieldValue("")
                onSendRaw(SshSessionManager.applyModifiers("\u001B[A", ctrl = false, alt = altArmed))
                ctrlArmed = false
                altArmed = false
                focusInteractiveField()
            },
            onArrowRight = {
                onSendRaw(SshSessionManager.applyModifiers("\u001B[C", ctrl = false, alt = altArmed))
                ctrlArmed = false
                altArmed = false
                focusInteractiveField()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )
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
    }
}

@Composable
internal fun ExtraKeysRow(
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
        ExtraKeyButton(onClick = onEsc, modifier = Modifier.weight(1f)) {
            Text(text = "ESC", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        ExtraKeyButton(onClick = onTab, modifier = Modifier.weight(1f)) {
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
        ExtraKeyButton(onClick = onArrowLeft, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Flecha izquierda"
            )
        }
        ExtraKeyButton(onClick = onArrowDown, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Flecha abajo"
            )
        }
        ExtraKeyButton(onClick = onArrowUp, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Flecha arriba"
            )
        }
        ExtraKeyButton(onClick = onArrowRight, modifier = Modifier.weight(1f)) {
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
