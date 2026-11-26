package com.autoagent.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.autoagent.app.util.L
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ok = Color(0xFF4CAF50)
val Bad = Color(0xFFE53935)
val Warn = Color(0xFFFFC857)

private val Colors = darkColorScheme(
    primary = Color(0xFF7FB8FF),
    onPrimary = Color(0xFF00213F),
    primaryContainer = Color(0xFF1B3A5C),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFB4C8E8),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF151A21),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF1F2630),
    onSurfaceVariant = Color(0xFFC2C7CF),
    surfaceContainer = Color(0xFF1A2029),
    error = Bad,
)

private val Type = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
)

/** Dark theme + right-to-left layout when the app language is Hebrew. Re-composes everything on language change. */
@Composable
fun AutoAgentTheme(content: @Composable () -> Unit) {
    val hebrew by L.hebrewFlow.collectAsState()
    CompositionLocalProvider(LocalLayoutDirection provides if (hebrew) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = Colors, typography = Type) {
            key(hebrew) { content() }
        }
    }
}
