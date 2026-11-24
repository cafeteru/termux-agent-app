package com.termux.agent.presentation

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.agent.infrastructure.ssh.SshSessionManager
import com.termux.agent.infrastructure.terminal.AnsiTextParser
import kotlin.math.max

private data class PtyDimensions(
    val cols: Int,
    val rows: Int,
    val pixelWidth: Int,
    val pixelHeight: Int
)

@Composable
internal fun PtyTerminalCard(
    ptyOutput: String,
    currentCommandLine: String = "",
    onSendRaw: (String) -> Unit,
    onTerminalResize: (cols: Int, rows: Int, pixelWidth: Int, pixelHeight: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    var ctrlArmed by rememberSaveable { mutableStateOf(false) }
    var altArmed by rememberSaveable { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val textMeasurer = rememberTextMeasurer()
    val charSize = remember(textMeasurer) {
        textMeasurer.measure(
            text = "M",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 0.sp
            )
        ).size
    }
    var terminalSize by remember { mutableStateOf(IntSize.Zero) }
    var lastResize by remember { mutableStateOf<PtyDimensions?>(null) }
    val outputScrollState = rememberScrollState()
    val styledOutput = remember(ptyOutput) { AnsiTextParser.parse(ptyOutput) }

    LaunchedEffect(ptyOutput, outputScrollState.maxValue) {
        outputScrollState.scrollTo(outputScrollState.maxValue)
    }

    LaunchedEffect(Unit) {
        runCatching { inputFocusRequester.requestFocus() }
        keyboardController?.show()
    }

    LaunchedEffect(currentCommandLine) {
        if (currentCommandLine.isNotEmpty() && currentCommandLine != inputValue.text) {
            inputValue = TextFieldValue(
                text = currentCommandLine,
                selection = TextRange(currentCommandLine.length)
            )
        }
    }

    LaunchedEffect(terminalSize, imeBottom, charSize) {
        if (terminalSize == IntSize.Zero) return@LaunchedEffect
        val usableHeight = max(1, terminalSize.height - imeBottom)
        val charWidth = max(1, charSize.width)
        val charHeight = max(1, charSize.height)
        val cols = max(20, terminalSize.width / charWidth)
        val rows = max(5, usableHeight / charHeight)
        val dimensions = PtyDimensions(cols, rows, terminalSize.width, usableHeight)
        if (dimensions != lastResize) {
            lastResize = dimensions
            onTerminalResize(cols, rows, terminalSize.width, usableHeight)
        }
    }

    fun sendWithModifiers(text: String) {
        onSendRaw(SshSessionManager.applyModifiers(text, ctrl = ctrlArmed, alt = altArmed))
        ctrlArmed = false
        altArmed = false
    }

    fun focusTerminal() {
        runCatching { inputFocusRequester.requestFocus() }
        keyboardController?.show()
    }

    Column(modifier = modifier.imePadding()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { terminalSize = it },
            shape = MaterialTheme.shapes.small,
            color = Color(0xFF0D1117)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = if (styledOutput.isEmpty()) {
                            AnnotatedString("")
                        } else {
                            styledOutput
                        },
                        color = Color(0xFFE6EDF3),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            letterSpacing = 0.sp
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(outputScrollState)
                            .padding(8.dp)
                            .testTag("terminal_canvas")
                    )
                }

                BasicTextField(
                    value = inputValue,
                    onValueChange = { updated ->
                        val oldText = inputValue.text
                        val newText = updated.text
                        if (newText.length > oldText.length) {
                            val added = newText.substring(oldText.length)
                            val newlineIndex = added.indexOfFirst { it == '\n' || it == '\r' }
                            val printable = if (newlineIndex >= 0) {
                                added.substring(0, newlineIndex)
                            } else {
                                added
                            }
                            if (printable.isNotEmpty()) sendWithModifiers(printable)
                            if (newlineIndex >= 0) onSendRaw("\r")
                            inputValue = TextFieldValue("")
                        } else if (newText.length < oldText.length) {
                            repeat(oldText.length - newText.length) {
                                sendWithModifiers("\u007F")
                            }
                            inputValue = TextFieldValue("")
                        } else {
                            inputValue = updated
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("interactive_terminal_input")
                        .focusRequester(inputFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    onSendRaw("\r")
                                    inputValue = TextFieldValue("")
                                    ctrlArmed = false
                                    altArmed = false
                                    true
                                }
                                Key.Tab -> {
                                    sendWithModifiers("\t")
                                    true
                                }
                                Key.DirectionUp -> {
                                    sendWithModifiers("\u001B[A")
                                    true
                                }
                                Key.DirectionDown -> {
                                    sendWithModifiers("\u001B[B")
                                    true
                                }
                                Key.DirectionLeft -> {
                                    sendWithModifiers("\u001B[D")
                                    true
                                }
                                Key.DirectionRight -> {
                                    sendWithModifiers("\u001B[C")
                                    true
                                }
                                Key.Escape -> {
                                    sendWithModifiers("\u001B")
                                    true
                                }
                                Key.Backspace -> {
                                    sendWithModifiers("\u007F")
                                    true
                                }
                                else -> false
                            }
                        },
                    textStyle = TextStyle(
                        color = Color.Transparent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        letterSpacing = 0.sp
                    ),
                    cursorBrush = SolidColor(Color.Transparent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.None
                    ),
                    decorationBox = { it() }
                )
            }
        }

        ExtraKeysRow(
            ctrlArmed = ctrlArmed,
            altArmed = altArmed,
            onToggleCtrl = {
                ctrlArmed = !ctrlArmed
                focusTerminal()
            },
            onToggleAlt = {
                altArmed = !altArmed
                focusTerminal()
            },
            onEsc = {
                sendWithModifiers("\u001B")
                focusTerminal()
            },
            onTab = {
                sendWithModifiers("\t")
                focusTerminal()
            },
            onArrowLeft = {
                sendWithModifiers("\u001B[D")
                focusTerminal()
            },
            onArrowDown = {
                sendWithModifiers("\u001B[B")
                focusTerminal()
            },
            onArrowUp = {
                sendWithModifiers("\u001B[A")
                focusTerminal()
            },
            onArrowRight = {
                sendWithModifiers("\u001B[C")
                focusTerminal()
            },
            onSlash = {
                sendWithModifiers("/")
                focusTerminal()
            },
            onHyphen = {
                sendWithModifiers("-")
                focusTerminal()
            },
            onTilde = {
                sendWithModifiers("~")
                focusTerminal()
            },
            onCtrlC = {
                onSendRaw("\u0003")
                ctrlArmed = false
                altArmed = false
                focusTerminal()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(top = 2.dp)
        )
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
    onSlash: () -> Unit,
    onHyphen: () -> Unit,
    onTilde: () -> Unit,
    onCtrlC: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExtraKeyButton(onClick = onEsc, modifier = Modifier.weight(1f)) { Text("ESC", maxLines = 1) }
        ExtraKeyButton(onClick = onTab, modifier = Modifier.weight(1f)) { Text("TAB", maxLines = 1) }
        ExtraKeyButton(active = ctrlArmed, onClick = onToggleCtrl, modifier = Modifier.weight(1f)) { Text("CTRL", maxLines = 1) }
        ExtraKeyButton(active = altArmed, onClick = onToggleAlt, modifier = Modifier.weight(1f)) { Text("ALT", maxLines = 1) }
        ExtraKeyButton(onClick = onArrowLeft, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Flecha izquierda")
        }
        ExtraKeyButton(onClick = onArrowDown, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.KeyboardArrowDown, "Flecha abajo")
        }
        ExtraKeyButton(onClick = onArrowUp, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.KeyboardArrowUp, "Flecha arriba")
        }
        ExtraKeyButton(onClick = onArrowRight, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Flecha derecha")
        }
        ExtraKeyButton(onClick = onSlash, modifier = Modifier.weight(1f)) { Text("/", maxLines = 1) }
        ExtraKeyButton(onClick = onHyphen, modifier = Modifier.weight(1f)) { Text("-", maxLines = 1) }
        ExtraKeyButton(onClick = onTilde, modifier = Modifier.weight(1f)) { Text("~", maxLines = 1) }
        ExtraKeyButton(onClick = onCtrlC, modifier = Modifier.weight(1.2f)) { Text("C-c", maxLines = 1) }
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
        modifier = modifier.height(34.dp),
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
