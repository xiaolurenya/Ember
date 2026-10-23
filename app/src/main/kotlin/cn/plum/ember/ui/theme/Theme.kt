package cn.plum.ember.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ============================ 调色板 ============================
// Ember 主张「深色影院感」：炭黑为底，唯一的强调色是炭火橙，
// 不搞花哨渐变，让海报成为画面主角。
val Ember = Color(0xFFFF7A18)          // 炭火橙（强调色）
val EmberSoft = Color(0xFFFFA253)      // 聚焦/悬停高亮
val EmberDeep = Color(0xFFD24E00)      // 按压态
val EmberDim = Color(0x33331C08)       // 8% 橙色叠加层
val Ink = Color(0xFF12100E)            // App 背景
val InkSurface = Color(0xFF17140F)     // 卡片表面
val InkRaised = Color(0xFF1E1A15)      // 二级抬升表面
val InkBorder = Color(0xFF2A241C)      // 描边
val TextHi = Color(0xFFF5F1EC)         // 主文字
val TextMid = Color(0xFFB8AEA4)        // 次文字
val TextLow = Color(0xFF7D7268)        // 弱化文字
val Scrim = Color(0xCC000000)          // 图片压暗

private val EmberScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF1A0E04),
    primaryContainer = EmberDeep,
    onPrimaryContainer = TextHi,
    secondary = EmberSoft,
    onSecondary = Color(0xFF1A0E04),
    background = Ink,
    onBackground = TextHi,
    surface = InkSurface,
    onSurface = TextHi,
    surfaceVariant = InkRaised,
    onSurfaceVariant = TextMid,
    outline = InkBorder,
    outlineVariant = InkBorder,
    error = Color(0xFFFF6B6B)
)

// ============================ 字体 ============================
private val EmberTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun EmberTheme(content: @Composable () -> Unit) {
    // 整个 App 只有深色影院一种主题，这是产品定义而非偷懒——
    // 影音播放器切亮色会在弱光环境里刺眼。
    MaterialTheme(
        colorScheme = EmberScheme,
        typography = EmberTypography,
        content = content
    )
}
