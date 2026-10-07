package com.iknalos.twostep.ui

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

/** Follows the phone's own colours on Android 12+ (Material You), a calm blue before that. */
@Composable
fun TwoStepTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9CC2FF), secondary = Color(0xFFB7C6DC))
        else -> lightColorScheme(primary = Color(0xFF1A5FB4), secondary = Color(0xFF4F5F75))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
