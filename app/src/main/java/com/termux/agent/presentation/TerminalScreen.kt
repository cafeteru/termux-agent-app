package com.termux.agent.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
