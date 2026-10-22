package com.pianorain.data

import android.content.Context
import com.pianorain.domain.NoteTimeline
import com.pianorain.domain.Song
import com.pianorain.midi.MidiTimelineParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SongRepository(private val context: Context) {
    val songs = listOf(Song("goldberg-aria", "Goldberg Variations — Aria (excerpt)", "J. S. Bach · Kimiko Ishizaka", 26_500_000L,
        "songs/goldberg-aria/audio.wav", "songs/goldberg-aria/notes.mid"),
        Song("chopin-prelude20", "Prelude in C minor, Op. 28 No. 20 (excerpt)", "Frédéric Chopin · Ivan Ilic", 30_000_000L,
            "songs/chopin-prelude20/audio.wav", "songs/chopin-prelude20/notes.mid"))
    suspend fun timeline(song: Song): NoteTimeline = withContext(Dispatchers.IO) {
        (if (song.imported) TimelineJson.decode(org.json.JSONObject(java.io.File(context.filesDir,"songs/${song.id}/timeline.json").readText()))
        else context.assets.open(song.midiAsset).use { MidiTimelineParser().parse(it.readBytes()) }).clippedTo(song.durationUs).also {
            require(it.notes.isNotEmpty()) { "This song has no playable note data." }
            require(it.notes.all { n -> n.pitch in 21..108 }) { "This song contains notes outside the piano range." }
        }
    }
}
