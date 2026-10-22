package com.pianorain

import com.pianorain.midi.MidiTimelineParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.abs

class MidiTimelineParserTest {
    private val parser = MidiTimelineParser()
    @Test fun publicDemoMidiMatchesEveryReferenceNote() {
        val midi = javaClass.getResourceAsStream("/goldberg-aria.mid")!!.use { it.readBytes() }
        val reference = JSONObject(javaClass.getResourceAsStream("/goldberg-aria.json")!!.bufferedReader().use { it.readText() })
        val parsed = parser.parse(midi)
        val expected = reference.getJSONArray("notes")
        assertEquals(69, expected.length())
        assertEquals(expected.length(), parsed.notes.size)
        val unmatched = parsed.notes.toMutableList()
        for (i in 0 until expected.length()) {
            val n = expected.getJSONObject(i)
            val start = (n.getDouble("onset_time") * 1_000_000).toLong()
            val end = (n.getDouble("offset_time") * 1_000_000).toLong()
            val match = unmatched.filter { it.pitch == n.getInt("midi_note") }.minBy { abs(it.startUs - start) }
            assertTrue("Onset of note $i", abs(match.startUs - start) < 1_400)
            assertTrue("Release of note $i", abs(match.endUs - end) < 1_400)
            assertEquals(n.getInt("velocity"), match.velocity)
            unmatched.remove(match)
        }
        assertTrue(unmatched.isEmpty())
        assertTrue(parsed.notes.first().startUs > 0) // Preserve the recording's real leading silence.
        val durationUs = reference.getLong("durationUs")
        val clipped = parsed.clippedTo(durationUs)
        assertEquals(expected.length(), clipped.notes.size)
        assertTrue(clipped.notes.all { it.endUs <= durationUs })
        assertTrue(parsed.pedals.isNotEmpty())
    }
    @Test fun tempoMapIsAppliedAcrossTracks() {
        val tempo = track(event(480, 0xff, 0x51, 3, 0x0f, 0x42, 0x40), end(480))
        val notes = track(event(240, 0x90, 60, 100), event(480, 0x80, 60, 0), end(0))
        val n = parser.parse(file(1, tempo, notes)).notes.single()
        assertEquals(250_000L, n.startUs)
        assertEquals(1_000_000L, n.endUs)
    }
    @Test fun clippingShortensHeldNotesAndRemovesOnsetsAtAudioEnd() {
        val notes = track(event(0, 0x90, 60, 100), event(480, 0x90, 64, 80),
            event(480, 0x80, 60, 0), event(0, 0x80, 64, 0), end(0))
        val clipped = parser.parse(file(0, notes)).clippedTo(500_000)
        assertEquals(1, clipped.notes.size)
        assertEquals(60, clipped.notes.single().pitch)
        assertEquals(500_000L, clipped.notes.single().endUs)
    }
    @Test fun runningStatusAndVelocityZeroAreHandled() {
        val t = track(event(0, 0x90, 60, 100), event(0, 64, 90), event(480, 60, 0), event(0, 64, 0), end(0))
        val n = parser.parse(file(0, t)).notes
        assertEquals(listOf(60, 64), n.map { it.pitch })
        assertEquals(listOf(500_000L, 500_000L), n.map { it.endUs })
    }
    @Test fun samePitchOnDifferentChannelsAndOverlappingNotesDoNotCollide() {
        val t = track(event(0, 0x90, 60, 100), event(120, 0x90, 60, 80), event(0, 0x91, 60, 70),
            event(120, 0x80, 60, 0), event(120, 0x81, 60, 0), event(0, 0x80, 60, 0), end(0))
        val n = parser.parse(file(0, t)).notes
        assertEquals(3, n.size)
        val first = n.first { it.velocity == 100 }; val second = n.first { it.velocity == 80 }; val other = n.first { it.channel == 1 }
        assertEquals(250_000L, first.endUs)
        assertEquals(125_000L, second.startUs); assertEquals(375_000L, second.endUs)
        assertEquals(125_000L, other.startUs); assertEquals(375_000L, other.endUs)
    }
    @Test fun sustainDoesNotStretchKeyReleaseAndMissingReleaseClosesAtFileEnd() {
        val t = track(event(0, 0xb0, 64, 127), event(0, 0x90, 60, 90), event(240, 0x80, 60, 0),
            event(0, 0x90, 62, 90), event(240, 0xb0, 64, 0), end(0))
        val result = parser.parse(file(0, t))
        assertEquals(250_000L, result.notes.first().endUs)
        assertEquals(500_000L, result.notes.last().endUs)
        assertEquals(2, result.pedals.size)
    }
    @Test fun rejectsMalformedAndUnsupportedFiles() {
        listOf(byteArrayOf(), file(2, track(end(0))), file(0, track(event(0, 60, 100), end(0))),
            file(0, track(event(0, 0x90, 60, 100))), file(0, track(end(0))).copyOf(20),
            file(0, track(end(0))).also { it[12] = 0xe7.toByte() }).forEach {
            assertThrows(Exception::class.java) { parser.parse(it) }
        }
    }
    private fun file(format: Int, vararg tracks: ByteArray): ByteArray = ByteArrayOutputStream().also { stream ->
        DataOutputStream(stream).apply {
            writeBytes("MThd"); writeInt(6); writeShort(format); writeShort(tracks.size); writeShort(480)
            tracks.forEach { writeBytes("MTrk"); writeInt(it.size); write(it) }
        }
    }.toByteArray()
    private fun track(vararg events: ByteArray) = events.fold(byteArrayOf()) { a, b -> a + b }
    private fun end(delta: Int) = event(delta, 0xff, 0x2f, 0)
    private fun event(delta: Int, vararg data: Int): ByteArray {
        var value = delta
        val reversed = mutableListOf(value and 127)
        while (value > 127) { value = value ushr 7; reversed += (value and 127) or 128 }
        return reversed.reversed().map { it.toByte() }.toByteArray() + data.map { it.toByte() }.toByteArray()
    }
}
