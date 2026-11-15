package com.termux.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    var state by remember { mutableStateOf<ConnectionState>(ConnectionState.Checking) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryTrigger) {
        state = ConnectionState.Checking
        state = withContext(Dispatchers.IO) {
            TermuxConnectionChecker.checkConnection(
                isTermuxInstalled = {
                    TermuxConnectionChecker.isTermuxInstalled(context.packageManager)
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Termux Agent",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Target SSH: ${AppConfig.DEFAULT_SSH_HOST}:${AppConfig.DEFAULT_SSH_PORT}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        when (val current = state) {
            ConnectionState.Checking -> Text(
                text = "Detectando Termux y conectando...",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
            is ConnectionState.Connected -> Text(
                text = "Termux detectado y conectado (${current.host}:${current.port})\nServidor: ${current.serverBanner}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
            is ConnectionState.Error -> Text(
                text = "Error detectado: ${current.reason}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        Button(
            onClick = { retryTrigger++ },
            enabled = state !is ConnectionState.Checking,
            modifier = Modifier.padding(top = 20.dp)
        ) {
            Text(text = "Reintentar")
        }
    }
}
