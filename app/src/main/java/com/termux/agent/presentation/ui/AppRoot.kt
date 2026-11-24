package com.termux.agent.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import com.termux.agent.infrastructure.ssh.AppThemeMode
import com.termux.agent.infrastructure.ssh.SshConfigStore
import com.termux.agent.presentation.ui.theme.TermuxTheme

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var themeMode by remember {
        mutableStateOf(SshConfigStore.loadThemeMode(context))
    }
    val navController = rememberNavController()

    TermuxTheme(darkTheme = themeMode == AppThemeMode.DARK) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            AppNavGraph(
                navController = navController,
                themeMode = themeMode,
                onThemeModeChange = { nextMode ->
                    themeMode = nextMode
                    SshConfigStore.saveThemeMode(context, nextMode)
                }
            )
        }
    }
}
