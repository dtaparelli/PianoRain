package com.pianorain.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Plum = Color(0xFF151020)
val Panel = Color(0xFF241B33)
val Lilac = Color(0xFFC8ABFF)
val Rose = Color(0xFFEFA7C7)
val Ink = Color(0xFFF4EDF8)
val Muted = Color(0xFFC4B7D1)
val LowNote = Color(0xFFB99AFF)
val HighNote = Color(0xFFF0A8CB)

@Composable
fun PianoRainTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Lilac, onPrimary = Color(0xFF241335),
        secondary = Rose, background = Plum, surface = Plum, surfaceContainer = Panel,
        surfaceVariant = Panel, onSurface = Ink, onBackground = Ink,
        onSurfaceVariant = Muted, outline = Color(0xFF675478)), content = content
)
