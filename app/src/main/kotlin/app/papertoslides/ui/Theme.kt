package app.papertoslides.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF2754C5), onPrimary = Color.White, primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF575E71), tertiary = Color(0xFF8A5100), tertiaryContainer = Color(0xFFFFDCBE),
    surface = Color(0xFFFBF8FF), background = Color(0xFFFBF8FF), error = Color(0xFFB3261E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF002A77), primaryContainer = Color(0xFF003EA8), onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFBFC6DC), tertiary = Color(0xFFFFB870), tertiaryContainer = Color(0xFF693C00),
    surface = Color(0xFF121318), background = Color(0xFF121318), error = Color(0xFFF2B8B5),
)

/** Light and dark follow the system; Material You colours on Android 12+. */
@Composable
fun PaperTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
