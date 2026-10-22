package com.pianorain.midi

import com.pianorain.domain.NoteEvent
import com.pianorain.domain.NoteTimeline
import com.pianorain.domain.PedalEvent
import java.util.ArrayDeque

/** Standard MIDI File type 0/1, PPQ timing. Times are relative to the source, never the countdown. */
class MidiTimelineParser {
    private data class Event(val tick: Long, val order: Int, val status: Int, val a: Int, val b: Int)
    private data class Start(val timeUs: Long, val velocity: Int)

    fun parse(bytes: ByteArray): NoteTimeline {
        require(bytes.size <= 16 * 1024 * 1024) { "MIDI file is too large" }
        val input = Reader(bytes)
        require(input.tag() == "MThd") { "Invalid MIDI header" }
        val headerSize = input.u32().toInt()
        require(headerSize in 6..1024) { "Invalid MIDI header length" }
        val format = input.u16(); val tracks = input.u16(); val ppq = input.u16()
        require(format in 0..1 && tracks in 1..1024 && (format != 0 || tracks == 1)) { "Only MIDI type 0 and 1 are supported" }
        require(ppq in 1..0x7fff) { "SMPTE MIDI timing is not supported" }
        input.skip(headerSize - 6)
        val events = mutableListOf<Event>()
        var order = 0; var endTick = 0L
        repeat(tracks) {
            require(input.tag() == "MTrk") { "Missing MIDI track" }
            val length = input.u32()
            require(length <= input.remaining) { "Truncated MIDI track" }
            val track = Reader(input.take(length.toInt()))
            var tick = 0L; var running = 0; var ended = false
            while (track.remaining > 0 && !ended) {
                tick = Math.addExact(tick, track.variable().toLong())
                var status = track.peek()
                if (status >= 0x80) { track.u8(); if (status < 0xf0) running = status }
                else { require(running != 0) { "Invalid running status" }; status = running }
                when (status) {
                    0xff -> {
                        running = 0
                        val type = track.u8(); val size = track.variable(); val data = track.take(size)
                        if (type == 0x51) {
                            require(size == 3) { "Invalid tempo" }
                            val tempo = ((data[0].toInt() and 255) shl 16) or ((data[1].toInt() and 255) shl 8) or (data[2].toInt() and 255)
                            require(tempo > 0) { "Invalid zero tempo" }
                            events += Event(tick, order++, 0x100, tempo, 0)
                        }
                        if (type == 0x2f) { require(size == 0); ended = true }
                    }
                    0xf0, 0xf7 -> { running = 0; track.skip(track.variable()) }
                    in 0x80..0xef -> {
                        val kind = status and 0xf0
                        val a = track.data(); val b = if (kind == 0xc0 || kind == 0xd0) 0 else track.data()
                        if (kind == 0x80 || kind == 0x90 || (kind == 0xb0 && a == 64)) events += Event(tick, order++, status, a, b)
                    }
                    else -> error("Unsupported MIDI system event")
                }
            }
            require(ended) { "MIDI track has no end marker" }
            endTick = maxOf(endTick, tick)
        }
        events.sortWith(compareBy<Event> { it.tick }.thenBy { it.order })
        val notes = mutableListOf<NoteEvent>(); val pedals = mutableListOf<PedalEvent>()
        val active = mutableMapOf<Int, ArrayDeque<Start>>()
        var tempo = 500_000L; var tick = 0L; var us = 0L; var remainder = 0L
        fun advance(next: Long) {
            val numerator = Math.addExact(Math.multiplyExact(next - tick, tempo), remainder)
            us = Math.addExact(us, numerator / ppq); remainder = numerator % ppq; tick = next
            require(us <= 86_400_000_000L) { "MIDI is longer than 24 hours" }
        }
        for (event in events) {
            advance(event.tick)
            if (event.status == 0x100) { tempo = event.a.toLong(); continue }
            val channel = event.status and 15; val kind = event.status and 0xf0; val key = channel * 128 + event.a
            when {
                kind == 0x90 && event.b > 0 -> active.getOrPut(key) { ArrayDeque() }.addLast(Start(us, event.b))
                kind == 0x80 || kind == 0x90 -> active[key]?.pollFirst()?.let {
                    notes += NoteEvent(event.a, it.timeUs, maxOf(us, it.timeUs + 1), it.velocity, channel)
                }
                kind == 0xb0 -> pedals += PedalEvent(us, channel, event.b)
            }
        }
        advance(endTick)
        // A missing release is bounded by the file end; the repository also clips to the audio end.
        active.forEach { (key, starts) -> starts.forEach { notes += NoteEvent(key % 128, it.timeUs, maxOf(us, it.timeUs + 1), it.velocity, key / 128) } }
        return NoteTimeline(notes.sortedWith(compareBy<NoteEvent> { it.startUs }.thenBy { it.pitch }), pedals)
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0
        val remaining get() = bytes.size - position
        fun peek(): Int { require(remaining > 0) { "Truncated MIDI data" }; return bytes[position].toInt() and 255 }
        fun u8(): Int = peek().also { position++ }
        fun data(): Int = u8().also { require(it < 128) { "Invalid MIDI data byte" } }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun tag() = String(take(4), Charsets.US_ASCII)
        fun take(count: Int): ByteArray { require(count >= 0 && count <= remaining) { "Truncated MIDI data" }; return bytes.copyOfRange(position, position + count).also { position += count } }
        fun skip(count: Int) { require(count >= 0 && count <= remaining) { "Truncated MIDI data" }; position += count }
        fun variable(): Int {
            var value = 0
            repeat(4) { val b = u8(); value = (value shl 7) or (b and 127); if (b < 128) return value }
            error("MIDI variable length value exceeds four bytes")
        }
    }
}
