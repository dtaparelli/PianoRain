package com.pianorain.transcription

// Adapted from Qiuqiang Kong's piano_transcription_inference 0.0.6 (MIT declaration).
// Kotlin/source-time/clipping changes: PianoRain. See assets/licenses/MODEL.txt.

import com.pianorain.domain.NoteEvent
import com.pianorain.domain.NoteTimeline
import com.pianorain.domain.PedalEvent
import kotlin.math.roundToLong

/** Reference regression/peak and key-release rules from Kong's piano_transcription_inference 0.0.6.
 * Pure Kotlin. Pedal is independent of key duration; source timestamps never include countdown.
 */
object NoteExtractor {
    interface Frames { fun column(name: String, pitch: Int = 0): FloatArray }
    private data class Peaks(val active: BooleanArray, val shift: FloatArray)
    private fun peaks(x: FloatArray, threshold: Float, neighbour: Int): Peaks {
        val active = BooleanArray(x.size); val shift = FloatArray(x.size)
        for (n in neighbour until x.size - neighbour) {
            if (x[n] <= threshold) continue
            if ((0 until neighbour).any { x[n-it] < x[n-it-1] || x[n+it] < x[n+it+1] }) continue
            val denominator = if (x[n-1] > x[n+1]) x[n]-x[n+1] else x[n]-x[n-1]
            // A perfectly flat plateau has no finite sub-frame estimate.
            if (denominator <= 0f) continue
            active[n] = true
            shift[n] = ((x[n+1]-x[n-1])/denominator/2f).coerceIn(-.5f, .5f)
        }
        return Peaks(active, shift)
    }
    fun extract(source: Frames, durationUs: Long, checkCancelled: () -> Unit = {}): NoteTimeline {
        val notes = mutableListOf<NoteEvent>(); val pedals = mutableListOf<PedalEvent>()
        for (pitch in 0 until 88) {
            checkCancelled()
            val frame = source.column("frame_output", pitch)
            val onset = peaks(source.column("reg_onset_output", pitch), .3f, 2)
            val offset = peaks(source.column("reg_offset_output", pitch), .3f, 4)
            val velocity = source.column("velocity_output", pitch)
            require(listOf(onset.active.size, offset.active.size, velocity.size).all { it == frame.size })
            var begin = -1; var disappear = -1; var release = -1
            fun emit(end: Int, shift: Float) {
                if (begin < 0) return
                // Float seconds mirror reference float32 event serialization, before integer microseconds.
                val startUs = ((begin + onset.shift[begin].toDouble()) / 100).toFloat().toDouble().times(1e6).roundToLong().coerceAtLeast(0)
                val endUs = ((end + shift.toDouble()) / 100).toFloat().toDouble().times(1e6).roundToLong().coerceAtMost(durationUs)
                if (startUs < durationUs && endUs > startUs) notes += NoteEvent(pitch + 21, startUs, endUs, (velocity[begin] * 128).toInt().coerceIn(1,127))
            }
            for (i in frame.indices) {
                if (onset.active[i]) {
                    if (begin >= 0) emit((i-1).coerceAtLeast(0), 0f)
                    begin = i; disappear = -1; release = -1
                }
                if (begin >= 0 && i > begin) {
                    if (frame[i] <= .1f && disappear < 0) disappear = i
                    if (offset.active[i] && release < 0) release = i
                    if (disappear >= 0) {
                        val end = if (release >= 0 && release-begin > disappear-release) release else disappear
                        emit(end, offset.shift[end]); begin = -1; disappear = -1; release = -1
                    }
                    if (begin >= 0 && (i-begin >= 600 || i == frame.lastIndex)) {
                        emit(i, offset.shift[i]); begin = -1; disappear = -1; release = -1
                    }
                }
            }
        }
        checkCancelled()
        val frame = source.column("pedal_frame_output")
        val offset = peaks(source.column("reg_pedal_offset_output"), .2f, 4)
        var begin = -1; var disappear = -1; var release = -1
        fun emit(end: Int) {
            val startUs = (begin / 100.0).toFloat().toDouble().times(1e6).roundToLong()
            val endUs = ((end + offset.shift[end].toDouble()) / 100).toFloat().toDouble().times(1e6).roundToLong().coerceAtMost(durationUs)
            if (startUs < durationUs && endUs > startUs) {
                pedals += PedalEvent(startUs, 0, 127); pedals += PedalEvent(endUs, 0, 0)
            }
        }
        for (i in 1 until frame.size) {
            if (frame[i] >= .5f && frame[i] > frame[i-1] && begin < 0) begin = i
            if (begin >= 0 && i > begin) {
                if (frame[i] <= .5f && disappear < 0) disappear = i
                if (offset.active[i] && release < 0) release = i
                if (release >= 0) { emit(release); begin = -1; disappear = -1; release = -1 }
                if (disappear >= 0 && i-disappear >= 10) { emit(disappear); begin = -1; disappear = -1; release = -1 }
            }
        }
        // Retain a held pedal at EOF, but bound it to the real audio, never the padded tail.
        if (begin >= 0 && begin * 10_000L < durationUs) {
            pedals += PedalEvent(begin * 10_000L, 0, 127); pedals += PedalEvent(durationUs, 0, 0)
        }
        return NoteTimeline(notes.sortedWith(compareBy({ it.startUs }, { it.pitch })), pedals.sortedBy { it.timeUs })
    }
}
