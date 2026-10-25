package srv.scrtvwr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/** Whether the browsing screens use light or dark colors. The viewers stay dark either way. */
enum class ThemeMode {
    System,
    Light,
    Dark,
}

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
