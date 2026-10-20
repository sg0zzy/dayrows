// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun DayRowsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) darkColorScheme(primary = Color(0xff9ecbea), secondary = Color(0xffb6c9d7))
    else lightColorScheme(primary = Color(0xff315d76), secondary = Color(0xff526574))
    MaterialTheme(colorScheme = colors, content = content)
}
