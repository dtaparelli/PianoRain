package com.pianorain

import com.pianorain.domain.NoteEvent
import com.pianorain.rendering.PianoLayout
import com.pianorain.rendering.TimelineIndex
import com.pianorain.rendering.tileBounds
import org.junit.Assert.*
import org.junit.Test

class PianoLayoutTest {
    @Test fun heldNotesRemainVisibleAfterSeekingPastTheirOnsets() {
        val held = NoteEvent(60, 0, 20_000_000, 100)
        val expired = NoteEvent(62, 1_000_000, 2_000_000, 90)
        val future = NoteEvent(64, 11_000_000, 12_000_000, 90)
        val distant = NoteEvent(65, 17_000_000, 18_000_000, 90)
        val index = TimelineIndex(listOf(held, expired, future, distant))
        assertEquals(listOf(held, future), index.visible(10_000_000, 3_000_000))
        assertEquals(emptyList<NoteEvent>(), index.visible(20_000_000, 3_000_000))
    }
    @Test fun tileEdgesMeetStrikeLineAtOnsetAndRelease() {
        val note = NoteEvent(60, 2_000_000, 4_000_000, 90)
        val onset = tileBounds(note, 2_000_000, 500f, 100f)
        assertEquals(500f, onset.bottom, .001f); assertEquals(300f, onset.top, .001f)
        assertEquals(500f, tileBounds(note, 4_000_000, 500f, 100f).top, .001f)
    }
    @Test fun blackKeysSitAtWhiteKeyBoundariesAndLayoutContainsReferenceRange() {
        val layout = PianoLayout(listOf(NoteEvent(32, 0, 100, 90), NoteEvent(87, 0, 100, 90)))
        assertEquals(24, layout.low); assertEquals(95, layout.high)
        val c = layout.keys.first { it.pitch == 60 }; val cs = layout.keys.first { it.pitch == 61 }
        assertTrue(cs.black)
        assertEquals(c.left + c.width, cs.left + cs.width / 2f, .0001f)
        val white = layout.keys.filter { !it.black }
        assertEquals(1f, white.last().left + white.last().width, .0001f)
    }
    @Test fun negativeCountdownTimeShowsUpcomingNotes() {
        val note = NoteEvent(60, 0, 500_000, 90)
        assertEquals(listOf(note), TimelineIndex(listOf(note)).visible(-3_000_000, 3_000_000))
    }
}
