package com.pianorain.playback

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.pianorain.data.SongRepository
import com.pianorain.domain.NoteTimeline
import com.pianorain.domain.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One owner survives rotation. Every player call and frame position read stays on the main thread. */
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SongRepository(application)
    val songs = repository.songs
    private val preferences = application.getSharedPreferences("playback", 0)
    private val _state = MutableStateFlow(PlaybackState())
    val state = _state.asStateFlow()
    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private var countdownJob: Job? = null
    private var countdownPausedUs = -3_000_000L
    private var countdownStartNs = 0L
    private var countdownFromUs = -3_000_000L
    private var previewUs: Long? = null
    private var resumeAfterScrub = false
    private var lastSaveMs = 0L

    init {
        viewModelScope.launch { while (true) { delay(100); refresh() } }
    }

    fun open(song: Song) {
        if (_state.value.song?.id == song.id && player != null && _state.value.error == null) return
        loadJob?.cancel(); cancelCountdown(); player?.release(); player = null
        _state.value = PlaybackState(song = song, loading = true)
        loadJob = viewModelScope.launch {
            try {
                val timeline = repository.timeline(song)
                val saved = preferences.getLong("${song.id}.position", 0).coerceIn(0, song.durationUs / 1000)
                countdownPausedUs = if (saved == 0L) -3_000_000 else 0
                val savedSpeed = PlaybackSpeed.entries.firstOrNull {
                    it.multiplier == preferences.getFloat("${song.id}.speed", 1f)
                } ?: PlaybackSpeed.NORMAL
                val newPlayer = ExoPlayer.Builder(getApplication()).build()
                player = newPlayer
                newPlayer.setPlaybackSpeed(savedSpeed.multiplier)
                newPlayer.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                newPlayer.setHandleAudioBecomingNoisy(true)
                newPlayer.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { refresh() }
                    override fun onPlayerError(error: PlaybackException) {
                        cancelCountdown()
                        _state.value = _state.value.copy(error = "Audio playback failed. Return to Songs and try again.", playing = false)
                    }
                })
                newPlayer.setMediaItem(MediaItem.fromUri(if (song.imported) android.net.Uri.fromFile(java.io.File(song.audioAsset)) else android.net.Uri.parse("asset:///${song.audioAsset}")))
                newPlayer.seekTo(saved)
                _state.value = PlaybackState(song = song, timeline = timeline, positionUs = saved * 1000,
                    ready = false, speed = savedSpeed, visualTimeUs = if (saved == 0L) countdownPausedUs else saved * 1000)
                newPlayer.prepare()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(loading = false, error = e.message ?: "Unable to load this song.") }
        }
    }

    fun setSpeed(speed: PlaybackSpeed) {
        val p = player ?: return
        // Media3 changes tempo without changing pitch. The roll reads its source position.
        p.setPlaybackSpeed(speed.multiplier)
        _state.value = _state.value.copy(speed = speed)
        _state.value.song?.let { preferences.edit().putFloat("${it.id}.speed", speed.multiplier).apply() }
        refresh()
    }

    fun toggle() {
        val p = player ?: return
        if (_state.value.error != null || p.playbackState == Player.STATE_IDLE) return
        if (countdownJob != null || p.playWhenReady) { pause(); return }
        if (p.playbackState == Player.STATE_ENDED) { p.seekTo(0); countdownPausedUs = -3_000_000 }
        if (countdownPausedUs < 0 && p.currentPosition == 0L) startCountdown() else p.play()
        refresh()
    }

    private fun startCountdown() {
        countdownFromUs = countdownPausedUs
        countdownStartNs = SystemClock.elapsedRealtimeNanos()
        _state.value = _state.value.copy(countingDown = true, playing = false)
        countdownJob = viewModelScope.launch {
            while (currentCountdownUs() < 0) delay(10)
            countdownPausedUs = 0; countdownJob = null
            _state.value = _state.value.copy(countingDown = false)
            player?.play(); refresh()
        }
    }
    private fun currentCountdownUs() = (countdownFromUs + (SystemClock.elapsedRealtimeNanos() - countdownStartNs) / 1000).coerceAtMost(0)
    private fun cancelCountdown() { countdownJob?.cancel(); countdownJob = null }

    fun pause() {
        if (countdownJob != null) countdownPausedUs = currentCountdownUs()
        cancelCountdown(); player?.pause()
        _state.value = _state.value.copy(countingDown = false)
        refresh(); savePosition()
    }
    fun leavePlayer() {
        previewUs = null; resumeAfterScrub = false
        _state.value = _state.value.copy(scrubbing = false)
        pause()
    }
    fun stop() {
        cancelCountdown(); previewUs = null; resumeAfterScrub = false
        countdownPausedUs = -3_000_000
        player?.pause(); player?.seekTo(0)
        _state.value = _state.value.copy(countingDown = false, scrubbing = false)
        refresh(); savePosition()
    }
    fun skip(deltaUs: Long) {
        val wasCounting = countdownJob != null
        seek((_state.value.positionUs + deltaUs).coerceIn(0, _state.value.song?.durationUs ?: 0))
        if (wasCounting) player?.play()
    }
    fun seek(timeUs: Long) {
        cancelCountdown(); countdownPausedUs = 0
        val target = timeUs.coerceIn(0, _state.value.song?.durationUs ?: 0)
        player?.seekTo(target / 1000)
        _state.value = _state.value.copy(positionUs = target, visualTimeUs = target, countingDown = false)
        savePosition()
    }
    fun previewSeek(timeUs: Long) {
        if (previewUs == null) {
            resumeAfterScrub = player?.playWhenReady == true || countdownJob != null
            pause()
        }
        previewUs = timeUs
        _state.value = _state.value.copy(visualTimeUs = timeUs, scrubbing = true, countingDown = false)
    }
    fun finishSeek() {
        val target = previewUs ?: return
        previewUs = null
        seek(target)
        _state.value = _state.value.copy(scrubbing = false)
        if (resumeAfterScrub) player?.play()
        resumeAfterScrub = false
        refresh()
    }

    /** Used from a frame callback: no separate musical timer or per-note animation. */
    fun frameTimeUs(): Long {
        previewUs?.let { return it }
        if (countdownJob != null) return currentCountdownUs()
        val p = player ?: return _state.value.visualTimeUs
        if (p.playbackState == Player.STATE_ENDED) return _state.value.song?.durationUs ?: (p.currentPosition * 1000)
        if (p.currentPosition == 0L && countdownPausedUs < 0) return countdownPausedUs
        return (p.currentPosition * 1000).coerceAtMost(_state.value.song?.durationUs ?: Long.MAX_VALUE)
    }
    private fun refresh() {
        val p = player ?: return
        _state.value = _state.value.copy(positionUs = if (p.playbackState == Player.STATE_ENDED) _state.value.song?.durationUs ?: (p.currentPosition * 1000) else (p.currentPosition * 1000).coerceAtMost(_state.value.song?.durationUs ?: Long.MAX_VALUE),
            visualTimeUs = frameTimeUs(), playing = p.isPlaying, playRequested = p.playWhenReady, ready = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED,
            buffering = p.playbackState == Player.STATE_BUFFERING, countingDown = countdownJob != null)
        if (SystemClock.elapsedRealtime() - lastSaveMs > 1000) { savePosition(); lastSaveMs = SystemClock.elapsedRealtime() }
    }
    private fun savePosition() {
        _state.value.song?.let { preferences.edit().putLong("${it.id}.position", _state.value.positionUs / 1000).apply() }
    }
    override fun onCleared() { savePosition(); cancelCountdown(); player?.release(); player = null; super.onCleared() }
}

data class PlaybackState(
    val song: Song? = null, val timeline: NoteTimeline? = null, val loading: Boolean = false,
    val ready: Boolean = false, val playing: Boolean = false, val playRequested: Boolean = false, val countingDown: Boolean = false,
    val buffering: Boolean = false, val scrubbing: Boolean = false,
    val speed: PlaybackSpeed = PlaybackSpeed.NORMAL,
    val positionUs: Long = 0, val visualTimeUs: Long = -3_000_000, val error: String? = null
)

enum class PlaybackSpeed(val multiplier: Float, val label: String) {
    QUARTER(.25f, "25%"), HALF(.5f, "50%"), THREE_QUARTERS(.75f, "75%"), NORMAL(1f, "100% (Normal)")
}
