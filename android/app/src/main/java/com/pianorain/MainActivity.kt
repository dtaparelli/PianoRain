package com.pianorain

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.pianorain.playback.PlaybackViewModel
import com.pianorain.data.LibraryViewModel
import com.pianorain.ui.PianoRainApp
import com.pianorain.ui.PianoRainTheme

class MainActivity : ComponentActivity() {
    private val playback: PlaybackViewModel by viewModels()
    private val library: LibraryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A killed process cannot resume microphone capture; discard orphaned takes.
        cacheDir.listFiles()?.filter {
            it.name.startsWith("piano-recording-") && it.extension == "m4a"
        }?.forEach { it.delete() }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { PianoRainTheme { PianoRainApp(playback, library) } }
    }
    override fun onStop() {
        if (!isChangingConfigurations) playback.leavePlayer()
        super.onStop()
    }
}
