package com.termux.agent.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

@Composable
fun AppNavGraph(
    navController: NavHostController,
    themeMode: com.termux.agent.infrastructure.ssh.AppThemeMode,
    onThemeModeChange: (com.termux.agent.infrastructure.ssh.AppThemeMode) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.MAIN.route
    ) {
        composable(AppDestination.MAIN.route) {
            StatusScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                initialScreen = MainScreen.MAIN
            )
        }
        composable(AppDestination.SSH_CONFIG.route) {
            StatusScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                initialScreen = MainScreen.CONFIG
            )
        }
    }
}
