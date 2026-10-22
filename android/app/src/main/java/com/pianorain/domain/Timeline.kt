package com.pianorain.domain

data class NoteEvent(val pitch: Int, val startUs: Long, val endUs: Long, val velocity: Int, val channel: Int = 0) {
    init { require(pitch in 0..127 && startUs >= 0 && endUs > startUs && velocity in 1..127 && channel in 0..15) }
}
data class PedalEvent(val timeUs: Long, val channel: Int, val value: Int)
data class NoteTimeline(val notes: List<NoteEvent>, val pedals: List<PedalEvent>) {
    fun clippedTo(durationUs: Long) = copy(notes = notes.filter { it.startUs < durationUs }.map { it.copy(endUs = minOf(it.endUs, durationUs)) },
        pedals = pedals.filter { it.timeUs <= durationUs })
}
data class Song(val id: String, val title: String, val subtitle: String, val durationUs: Long, val audioAsset: String, val midiAsset: String, val imported: Boolean = false)
