package com.termux.agent.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.termux.agent.domain.model.AdapterEvent

private val DENY_OPTION_REGEX = Regex("""(?i)^(?:no\b|cancel|deny|reject|denegar|cancelar)""")

@Composable
internal fun PermissionRequestCard(
    event: AdapterEvent.PermissionRequired,
    onSelectOption: (allow: Boolean, optionIndex: Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Confirmación requerida",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            Text(
                text = "Herramienta: ${event.tool}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (event.command.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E1E1E),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = event.command,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE0E0E0)
                        ),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            if (event.options.size > 2) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    event.options.forEachIndexed { index, option ->
                        val isDeny = DENY_OPTION_REGEX.containsMatchIn(option.trim())
                        val buttonModifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("permission_option_$index")
                        val buttonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        val labelContent: @Composable () -> Unit = {
                            Text(
                                text = "${index + 1}. $option",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start
                            )
                        }

                        when {
                            isDeny -> OutlinedButton(
                                onClick = { onSelectOption(false, index) },
                                modifier = buttonModifier,
                                contentPadding = buttonPadding,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                labelContent()
                            }
                            index == 0 -> Button(
                                onClick = { onSelectOption(true, index) },
                                modifier = buttonModifier,
                                contentPadding = buttonPadding
                            ) {
                                labelContent()
                            }
                            else -> FilledTonalButton(
                                onClick = { onSelectOption(true, index) },
                                modifier = buttonModifier,
                                contentPadding = buttonPadding
                            ) {
                                labelContent()
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSelectOption(true, null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Permitir")
                    }

                    OutlinedButton(
                        onClick = { onSelectOption(false, null) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Denegar")
                    }
                }
            }
        }
    }
}
