package com.pianorain.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.pianorain.data.LibraryViewModel
import com.pianorain.data.ImportedSong
import android.view.WindowManager
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import com.pianorain.BuildConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianorain.domain.Song
import com.pianorain.playback.PlaybackSpeed
import com.pianorain.playback.PlaybackState
import com.pianorain.playback.PlaybackViewModel
import com.pianorain.rendering.PianoRoll

@Composable
fun PianoRainApp(viewModel: PlaybackViewModel, library: LibraryViewModel) {
    val imports by library.rows.collectAsStateWithLifecycle()
    val starting by library.starting.collectAsStateWithLifecycle()
    val importError by library.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            library.add(uri)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var recording by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        recording = granted
        permissionDenied = !granted
    }
    var remove by remember { mutableStateOf<ImportedSong?>(null) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedSongId by rememberSaveable { mutableStateOf<String?>(null) }
    val allSongs = viewModel.songs + imports.filter { it.state == "ready" }.map(library::song)
    val song = allSongs.find { it.id == selectedSongId }
    LaunchedEffect(song?.id) { song?.let(viewModel::open) }
    val back = { viewModel.leavePlayer(); selectedSongId = null }
    BackHandler(enabled = song != null, onBack = back)
    Surface(Modifier.fillMaxSize(), color = Plum) {
        if (song != null) PlayerScreen(state, viewModel, back)
        else SongsScreen(allSongs, imports, starting, onAdd = { picker.launch(arrayOf("audio/*")) },
            onRecord = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recording = true
                else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onCancel = library::cancel, onRetry = library::retry, onRemove = { remove = it }) { selectedSongId = it.id }
    }
    if (recording) RecordingDialog(onClose = { recording = false }, onSave = { file, title ->
        library.addRecording(file, title)
        recording = false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    })
    if (permissionDenied) AlertDialog(onDismissRequest = { permissionDenied = false },
        title = { Text("Microphone access needed") },
        text = { Text("Allow microphone access to record piano audio. You can still add audio with From File.") },
        confirmButton = { TextButton(onClick = {
            permissionDenied = false
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")))
        }) { Text("Settings") } },
        dismissButton = { TextButton(onClick = { permissionDenied = false }) { Text("Close") } })
    if (importError != null) AlertDialog(onDismissRequest = library::clearError, title = { Text("Unable to import audio") },
        text = { Text(importError.orEmpty()) }, confirmButton = { TextButton(onClick = library::clearError) { Text("OK") } })
    remove?.let { row -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${row.title}?") },
        text = { Text("Remove the imported copy and generated notes from PianoRain? Your original file stays where it is.") },
        confirmButton = { TextButton(onClick = { library.remove(row); remove = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep") } }) }
}

@Composable
private fun SongsScreen(songs: List<Song>, imports: List<ImportedSong>, starting: Boolean,
    onAdd: () -> Unit, onRecord: () -> Unit, onCancel: (ImportedSong) -> Unit, onRetry: (ImportedSong) -> Unit,
    onRemove: (ImportedSong) -> Unit, onOpen: (Song) -> Unit) {
    var addMenu by remember { mutableStateOf(false) }
    var aboutVisible by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    if (aboutVisible) Dialog(onDismissRequest = { aboutVisible = false }) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Panel.copy(alpha = .92f),
            border = BorderStroke(1.dp, Lilac.copy(alpha = .18f)),
            shadowElevation = 16.dp
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Lilac.copy(alpha = .08f), Color.Transparent)))
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(Lilac.copy(alpha = .08f)),
                    contentAlignment = Alignment.Center
                ) { RainMark(Modifier.size(40.dp)) }
                Text("PianoRain", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                    color = Muted, textAlign = TextAlign.Center)
                TextButton(onClick = { uriHandler.openUri("https://github.com/dtaparelli/PianoRain") }) {
                    Text("GitHub", textDecoration = TextDecoration.Underline)
                }
                OutlinedButton(
                    onClick = { aboutVisible = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Lilac.copy(alpha = .24f))
                ) { Text("Close") }
            }
        }
    }
    val busy = starting || imports.any { it.busy }
    val activity = LocalActivity.current
    DisposableEffect(busy, activity) {
        if (busy) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = "About PianoRain") { aboutVisible = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RainMark(Modifier.size(24.dp))
                    Text("PianoRain", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Box {
                    TextButton(onClick = { addMenu = true }, enabled = !busy) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (starting) "Opening audio…" else "Add Song")
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Record") }, onClick = { addMenu = false; onRecord() },
                            leadingIcon = { Icon(Icons.Default.Mic, null) })
                        DropdownMenuItem(text = { Text("From File") }, onClick = { addMenu = false; onAdd() },
                            leadingIcon = { Icon(Icons.Default.AudioFile, null) })
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("${songs.size} ${if (songs.size == 1) "song" else "songs"}", color = Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        items(imports.filter { it.state != "ready" }, key = { "import-${it.id}" }) { row ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(row.title, fontWeight = FontWeight.SemiBold)
                    Text(if (row.busy) row.stage else row.error ?: row.stage, color = Muted, style = MaterialTheme.typography.bodyMedium)
                    if (row.busy) {
                        if (row.progress > 0f) LinearProgressIndicator(progress = { row.progress.coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        ImportCheckpoints(row.stage)
                        TextButton(onClick = { onCancel(row) }) { Text("Cancel") }
                    } else Row {
                        TextButton(onClick = { onRetry(row) }, enabled = !busy) { Text("Retry") }
                        TextButton(onClick = { onRemove(row) }) { Text("Remove") }
                    }
                }
            }
        }
        items(songs, key = { it.id }) { song ->
            Card(onClick = { onOpen(song) }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A284C)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Piano, null, tint = Lilac, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Default.CheckCircle, null, tint = Lilac, modifier = Modifier.size(12.dp))
                            Text("Ready · ${formatTime(song.durationUs)}", color = Lilac, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (song.imported) IconButton(onClick = { imports.find { it.id == song.id }?.let(onRemove) }) {
                        Icon(Icons.Default.DeleteOutline, "Remove ${song.title}", tint = Muted)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Open ${song.title}", tint = Rose, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(state: PlaybackState, viewModel: PlaybackViewModel, onBack: () -> Unit) {
    var speedPicker by remember { mutableStateOf(false) }
    BackHandler(enabled = speedPicker) { speedPicker = false }
    var controlsVisible by remember { mutableStateOf(true) }
    var touching by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val accessibility = LocalAccessibilityManager.current
    val hideDelay = accessibility?.calculateRecommendedTimeoutMillis(
        3_000L, containsIcons = true, containsText = true, containsControls = true
    ) ?: 3_000L
    LaunchedEffect(state.playing, state.scrubbing, speedPicker, touching, interaction, hideDelay) {
        if (!state.playing || state.scrubbing || speedPicker || touching) {
            controlsVisible = true
        } else {
            delay(hideDelay)
            controlsVisible = false
        }
    }
    val frameTime = remember { mutableLongStateOf(state.visualTimeUs) }
    LaunchedEffect(state.timeline) {
        while (true) { withFrameNanos { frameTime.longValue = viewModel.frameTimeUs() } }
    }
    val activity = checkNotNull(LocalActivity.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        fun immerse() {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        immerse()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) immerse() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Rotation replaces Player; only leaving it restores Songs' orientation and bars.
            if (!activity.isChangingConfigurations) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
    val keepAwake = state.playing || state.countingDown
    DisposableEffect(keepAwake) {
        if (keepAwake) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    Box(Modifier.fillMaxSize().background(Plum).pointerInput(Unit) {
        // Observe without consuming: visible controls still receive their clicks and drags.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touching = true
            controlsVisible = true
            interaction++
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                touching = false
                interaction++
            }
        }
    }) {
        val timeline = state.timeline
        when {
            state.error != null -> Column(Modifier.align(Alignment.Center).padding(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = Rose)
                Text(state.error, Modifier.padding(top = 12.dp), color = Ink)
                TextButton(onClick = { state.song?.let(viewModel::open) }) { Text("Try again") }
            }
            timeline == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Lilac)
            else -> PianoRoll(timeline, frameTime, Modifier.fillMaxSize())
        }
        // All chrome floats above the canvas, leaving the keyboard at the bottom edge.
        AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(180)), exit = fadeOut(tween(350))) {
            Row(Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = CircleShape, color = Panel.copy(alpha = .88f)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Songs") }
                }
                Column(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingTransport(state, viewModel, Modifier.widthIn(max = 620.dp).fillMaxWidth())
                    AnimatedVisibility(visible = speedPicker, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                        SpeedPicker(state.speed, onSelect = { viewModel.setSpeed(it); speedPicker = false },
                            onClose = { speedPicker = false }, enabled = timeline != null && state.error == null,
                            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth())
                    }
                }
                Surface(shape = RoundedCornerShape(24.dp), color = Panel.copy(alpha = .88f)) {
                    val speedPercent = (state.speed.multiplier * 100).toInt()
                    TextButton(onClick = { speedPicker = !speedPicker },
                        enabled = timeline != null && state.error == null,
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Playback speed, $speedPercent percent"
                            selected = speedPicker
                        }) {
                        Icon(Icons.Default.Speed, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$speedPercent%", maxLines = 1)
                    }
                }
            }
        }

    }
}

@Composable
private fun SpeedPicker(selectedSpeed: PlaybackSpeed, onSelect: (PlaybackSpeed) -> Unit, onClose: () -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel.copy(alpha = .94f), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaybackSpeed.entries.forEach { speed ->
                val active = selectedSpeed == speed
                TextButton(onClick = { onSelect(speed) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = active },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (active) Lilac else Color.Transparent,
                        contentColor = if (active) Color(0xFF241335) else Ink)) {
                    Text("${(speed.multiplier * 100).toInt()}%", maxLines = 1)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close playback speed") }
        }
    }
}

@Composable
private fun FloatingTransport(state: PlaybackState, viewModel: PlaybackViewModel, modifier: Modifier = Modifier) {
    val duration = state.song?.durationUs ?: 1L
    val enabled = state.timeline != null && !state.loading && state.error == null
    val shown = if (state.scrubbing) state.visualTimeUs else state.positionUs
    Surface(modifier, color = Panel.copy(alpha = .88f), shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.skip(-10_000_000) }, enabled = enabled && shown > 0) {
                Icon(Icons.Default.Replay10, "Back 10 seconds", modifier = Modifier.size(25.dp))
            }
            FilledIconButton(onClick = viewModel::toggle, enabled = enabled && (state.ready || state.playRequested),
                shape = CircleShape, modifier = Modifier.size(48.dp)) {
                Icon(if (state.playing || state.countingDown || state.playRequested) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.playing || state.countingDown || state.playRequested) "Pause" else "Play", modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { viewModel.skip(10_000_000) }, enabled = enabled && shown < duration) {
                Icon(Icons.Default.Forward10, "Forward 10 seconds", modifier = Modifier.size(25.dp))
            }
            Text(formatTime(shown), color = Ink, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
            Slider(value = shown.coerceIn(0, duration).toFloat(), onValueChange = { viewModel.previewSeek(it.toLong()) },
                onValueChangeFinished = viewModel::finishSeek, enabled = enabled, valueRange = 0f..duration.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Playback position" })
            Text(formatTime(duration), color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 10.dp))
        }
    }
}

@Composable
private fun RainMark(modifier: Modifier, decorative: Boolean = false) {
    Canvas(modifier) {
        val count = if (decorative) 13 else 3
        val step = size.width / (count + 1)
        for (i in 0 until count) {
            val length = size.height * (.25f + (i * 7 % 5) * .10f)
            val top = size.height * ((i * 3 % 4) * .10f)
            drawRoundRect(if (i % 3 == 0) Rose else Lilac.copy(alpha = .45f + (i % 3) * .2f),
                Offset(step * (i + 1) - step * .13f, top), Size(step * .26f, length), CornerRadius(3f))
        }
    }
}

private fun formatTime(us: Long): String {
    val seconds = (us.coerceAtLeast(0) / 1_000_000).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun ImportCheckpoints(stage: String) {
    val active = when (stage) {
        "Decoding audio", "Preparing piano audio" -> 1
        "Loading piano model", "Generating notes" -> 2
        "Saving piano notes" -> 3
        else -> 0
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Import", "Prepare", "Transcribe", "Save").forEachIndexed { index, label ->
            val complete = index < active
            val current = index == active
            val tint = when {
                complete -> Lilac
                current -> Rose
                else -> Muted.copy(alpha = 0.5f)
            }
            val status = when {
                complete -> "completed"
                current -> "in progress"
                else -> "upcoming"
            }
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = "$label: $status"
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (complete) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = tint,
                        modifier = Modifier.size(16.dp))
                } else {
                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (current) 10.dp else 6.dp).clip(CircleShape).background(tint))
                    }
                }
                Text(label, color = tint, style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
