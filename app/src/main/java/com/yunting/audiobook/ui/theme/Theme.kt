package com.yunting.audiobook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============================================================
// 微信配色方案
// ============================================================

val WeChatGreen = Color(0xFF07C160)        // 微信主绿
val WeChatGreenPressed = Color(0xFF06AD56) // 按压态深绿
val WeChatGreenDark = Color(0xFF07C160)    // 深色模式仍用同一主绿

val LinkBlue = Color(0xFF576B95)           // 微信链接蓝

// 浅色
val WBgLight = Color(0xFFEDEDED)           // 页面背景（微信灰）
val WCardLight = Color(0xFFFFFFFF)         // 卡片/单元格白
val WTabLight = Color(0xFFF7F7F7)          // 底部导航栏
val WTextPrimary = Color(0xFF191919)       // 主文本
val WTextSecondary = Color(0xFF7F7F7F)     // 次要文本
val WDivider = Color(0xFFE5E5E5)           // 分割线

// 深色
val WBgDark = Color(0xFF111111)
val WCardDark = Color(0xFF1E1E1E)
val WTabDark = Color(0xFF191919)
val WTextDarkPrimary = Color(0xFFE6E6E6)
val WTextDarkSecondary = Color(0xFF8A8A8A)
val WDividerDark = Color(0xFF2C2C2C)

private val LightColors = lightColorScheme(
    primary = WeChatGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCF3E5),
    onPrimaryContainer = Color(0xFF00391A),
    secondary = LinkBlue,
    background = WBgLight,
    onBackground = WTextPrimary,
    surface = WCardLight,
    onSurface = WTextPrimary,
    surfaceVariant = WTabLight,
    onSurfaceVariant = WTextSecondary,
    surfaceContainer = WCardLight,
    surfaceContainerHigh = WCardLight,
    outlineVariant = WDivider
)

private val DarkColors = darkColorScheme(
    primary = WeChatGreenDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF00532A),
    onPrimaryContainer = Color(0xFF9BF2C2),
    secondary = Color(0xFF8FA7C7),
    background = WBgDark,
    onBackground = WTextDarkPrimary,
    surface = WCardDark,
    onSurface = WTextDarkPrimary,
    surfaceVariant = WTabDark,
    onSurfaceVariant = WTextDarkSecondary,
    surfaceContainer = WCardDark,
    surfaceContainerHigh = Color(0xFF262626),
    outlineVariant = WDividerDark
)

@Composable
fun CloudListenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
