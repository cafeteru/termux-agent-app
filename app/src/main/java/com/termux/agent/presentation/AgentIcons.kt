package com.termux.agent.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private const val ANTIGRAVITY_PATH =
    "M14.0777 13.984 C14.945 14.6345 16.2458 14.2008 15.0533 13.0084 C11.476 9.53949 12.2349 0 7.79033 0 C3.34579 0 4.10461 9.53949 0.527295 13.0084 C-0.773543 14.3092 0.635692 14.6345 1.50293 13.984 C4.86344 11.7076 4.64663 7.69664 7.79033 7.69664 C10.934 7.69664 10.7172 11.7076 14.0777 13.984 Z"

private const val COPILOT_PATH =
    "M23.922 16.997 C23.061 18.492 18.063 22.02 12 22.02 S0.939 18.492 0.078 16.997 A0.6 0.6 0 0 1 0 16.741 v-2.869 a1 1 0 0 1 0.053 -0.22 c0.372 -0.935 1.347 -2.292 2.605 -2.656 c0.167 -0.429 0.414 -1.055 0.644 -1.517 a10 10 0 0 1 -0.052 -1.086 c0 -1.331 0.282 -2.499 1.132 -3.368 c0.397 -0.406 0.89 -0.717 1.474 -0.952 C7.255 2.937 9.248 1.98 11.978 1.98 s4.767 0.957 6.166 2.093 c0.584 0.235 1.077 0.546 1.474 0.952 c0.85 0.869 1.132 2.037 1.132 3.368 c0 0.368 -0.014 0.733 -0.052 1.086 c0.23 0.462 0.477 1.088 0.644 1.517 c1.258 0.364 2.233 1.721 2.605 2.656 a0.8 0.8 0 0 1 0.053 0.22 v2.869 a0.6 0.6 0 0 1 -0.078 0.256 m-11.75 -5.992 h-0.344 a4 4 0 0 1 -0.355 0.508 c-0.77 0.947 -1.918 1.492 -3.508 1.492 c-1.725 0 -2.989 -0.359 -3.782 -1.259 a2 2 0 0 1 -0.085 -0.104 L4 11.746 v6.585 c1.435 0.779 4.514 2.179 8 2.179 s6.565 -1.4 8 -2.179 v-6.585 l-0.098 -0.104 s-0.033 0.045 -0.085 0.104 c-0.793 0.9 -2.057 1.259 -3.782 1.259 c-1.59 0 -2.738 -0.545 -3.508 -1.492 a4 4 0 0 1 -0.355 -0.508 m2.328 3.25 c0.549 0 1 0.451 1 1 v2 c0 0.549 -0.451 1 -1 1 s-1 -0.451 -1 -1 v-2 c0 -0.549 0.451 -1 1 -1 m-5 0 c0.549 0 1 0.451 1 1 v2 c0 0.549 -0.451 1 -1 1 s-1 -0.451 -1 -1 v-2 c0 -0.549 0.451 -1 1 -1 m3.313 -6.185 c0.136 1.057 0.403 1.913 0.878 2.497 c0.442 0.544 1.134 0.938 2.344 0.938 c1.573 0 2.292 -0.337 2.657 -0.751 c0.384 -0.435 0.558 -1.15 0.558 -2.361 c0 -1.14 -0.243 -1.847 -0.705 -2.319 c-0.477 -0.488 -1.319 -0.862 -2.824 -1.025 c-1.487 -0.161 -2.192 0.138 -2.533 0.529 c-0.269 0.307 -0.437 0.808 -0.438 1.578 v0.021 q0 0.397 0.063 0.893 m-1.626 0 q0.063 -0.496 0.063 -0.894 v-0.02 c-0.001 -0.77 -0.169 -1.271 -0.438 -1.578 c-0.341 -0.391 -1.046 -0.69 -2.533 -0.529 c-1.505 0.163 -2.347 0.537 -2.824 1.025 c-0.462 0.472 -0.705 1.179 -0.705 2.319 c0 1.211 0.175 1.926 0.558 2.361 c0.365 0.414 1.084 0.751 2.657 0.751 c1.21 0 1.902 -0.394 2.344 -0.938 c0.475 -0.584 0.742 -1.44 0.878 -2.497 z"

internal val AntigravityIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Antigravity",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addGroup(
        translationX = 2.26f,
        translationY = 3.0f,
        scaleX = 1.25f,
        scaleY = 1.25f
    ).addPath(
        pathData = PathParser().parsePathString(ANTIGRAVITY_PATH).toNodes(),
        fill = SolidColor(Color.Black)
    ).clearGroup().build()
}

internal val CopilotIcon: ImageVector by lazy {
    materialVectorIcon(name = "Copilot", pathData = COPILOT_PATH)
}

internal fun agentIconForId(id: String): ImageVector =
    if (id.equals("copilot", ignoreCase = true)) CopilotIcon else AntigravityIcon

private fun materialVectorIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black)
    ).build()
