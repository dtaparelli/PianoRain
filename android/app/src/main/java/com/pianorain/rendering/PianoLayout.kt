package com.pianorain.rendering

import com.pianorain.domain.NoteEvent

data class PianoKey(val pitch: Int, val left: Float, val width: Float, val black: Boolean)
class PianoLayout(notes: List<NoteEvent>) {
    val low = ((notes.minOfOrNull { it.pitch } ?: 48) / 12 * 12).coerceAtLeast(21)
    val high = ((notes.maxOfOrNull { it.pitch } ?: 72) / 12 * 12 + 11).coerceAtMost(108)
    val keys: List<PianoKey>
    init {
        require(low <= high) { "No piano notes in this song" }
        val whiteCount = (low..high).count { !isBlack(it) }
        val whiteWidth = 1f / whiteCount
        var whiteIndex = 0
        keys = (low..high).map { pitch ->
            val black = isBlack(pitch)
            if (black) PianoKey(pitch, (whiteIndex - .31f) * whiteWidth, .62f * whiteWidth, true)
            else PianoKey(pitch, whiteIndex++ * whiteWidth, whiteWidth, false)
        }
    }
    companion object {
        fun isBlack(pitch: Int) = pitch % 12 in setOf(1, 3, 6, 8, 10)
        fun label(pitch: Int) = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[pitch % 12] + (pitch / 12 - 1)
    }
}

/** Prefix maximum releases include held notes whose onset is far before a seek target. */
class TimelineIndex(private val notes: List<NoteEvent>) {
    private val prefixEnd = LongArray(notes.size)
    init {
        var end = 0L
        notes.forEachIndexed { i, n -> require(i == 0 || n.startUs >= notes[i - 1].startUs); end = maxOf(end, n.endUs); prefixEnd[i] = end }
    }
    fun visible(timeUs: Long, lookaheadUs: Long): List<NoteEvent> {
        var lo = 0; var hi = notes.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (prefixEnd[mid] <= timeUs) lo = mid + 1 else hi = mid }
        val first = lo; hi = notes.size
        val future = timeUs + lookaheadUs
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (notes[mid].startUs <= future) lo = mid + 1 else hi = mid }
        return notes.subList(first, lo).filter { it.endUs > timeUs }
    }
}

data class TileBounds(val top: Float, val bottom: Float)
fun tileBounds(note: NoteEvent, timeUs: Long, hitY: Float, pixelsPerSecond: Float) = TileBounds(
    hitY - (note.endUs - timeUs) / 1_000_000f * pixelsPerSecond,
    hitY - (note.startUs - timeUs) / 1_000_000f * pixelsPerSecond
)
