package com.pianorain

import com.pianorain.transcription.NoteExtractor
import com.pianorain.transcription.SincResampler
import com.pianorain.transcription.PcmTiming
import com.pianorain.data.TimelineJson
import com.pianorain.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TranscriptionTest {
    private class Frames(val count: Int): NoteExtractor.Frames {
        val values=mutableMapOf<Pair<String,Int>,FloatArray>()
        fun get(name: String,pitch: Int=0)=values.getOrPut(name to pitch) { FloatArray(count) }
        override fun column(name: String,pitch: Int)=get(name,pitch)
        fun peak(name: String,at: Int,pitch: Int=0) { val x=get(name,pitch);for (i in -4..4) if (at+i in x.indices) x[at+i]=(1-abs(i)*.2f).coerceAtLeast(0f) }
    }
    @Test fun repeatedAttacksReleaseKeysIndependentlyOfPedalAndClipToAudioEnd() {
        val f=Frames(200)
        f.peak("reg_onset_output",10);f.peak("reg_onset_output",40)
        for (i in 10..100) f.get("frame_output")[i]=.8f
        f.get("velocity_output").fill(.6f)
        for (i in 5..150) f.get("pedal_frame_output")[i]=.8f
        f.peak("reg_pedal_offset_output",140)
        val t=NoteExtractor.extract(f,800_000)
        assertEquals(2,t.notes.size)
        assertEquals(100_000,t.notes[0].startUs);assertEquals(390_000,t.notes[0].endUs)
        assertEquals(400_000,t.notes[1].startUs);assertEquals(800_000,t.notes[1].endUs)
        assertEquals(76,t.notes[0].velocity)
        assertEquals(listOf(127,0),t.pedals.map { it.value });assertEquals(800_000,t.pedals.last().timeUs)
    }
    @Test fun stitchedBoundaryOnsetAndLongHeldNoteUseSourceTime() {
        val f=Frames(1300)
        f.peak("reg_onset_output",749,39)
        f.get("frame_output",39).fill(.8f);f.get("velocity_output",39).fill(.5f)
        val n=NoteExtractor.extract(f,12_000_000).notes.single()
        assertEquals(60,n.pitch);assertEquals(7_490_000,n.startUs);assertEquals(12_000_000,n.endUs)
    }
    @Test fun silenceAndFlatPlateausDoNotInventNotes() {
        val f=Frames(50);f.get("reg_onset_output").fill(.9f)
        assertTrue(NoteExtractor.extract(f,500_000).notes.isEmpty())
    }
    @Test fun inferredOffsetInLatterHalfWinsOverAcousticFrameDisappearance() {
        val f=Frames(100);f.peak("reg_onset_output",10);f.peak("reg_offset_output",45)
        for (i in 10 until 50) f.get("frame_output")[i]=.8f
        f.get("velocity_output").fill(.5f)
        assertEquals(450_000,NoteExtractor.extract(f,1_000_000).notes.single().endUs)
    }
    @Test fun resamplingPreservesTimeAndRejectsOutOfBandAlias() {
        val r=SincResampler(48000)
        assertEquals(16000,r.outputCount(48000))
        fun rms(frequency: Double): Double = sqrt((1000L until 2000L).map { i ->
            val v=r.sample(i,48000) { at -> sin(2*PI*frequency*at/48000).toFloat() };v.toDouble()*v
        }.average())
        assertTrue(rms(1000.0)>.70);assertTrue(rms(12000.0)<.003)
        val impulse=LongArray(1) { 4800 }
        val peak=(1500L..1700L).maxBy { abs(r.sample(it,10000) { at -> if (at==impulse[0]) 1f else 0f }) }
        assertEquals(1600,peak)
        assertEquals(0f,r.sample(100,10000) { at -> if (at>=4800) 1f else 0f },0f)
    }
    @Test fun codecPrimingIsRemovedOnceWhileRealLeadingSilenceRemains() {
        assertEquals(-2112L,PcmTiming.sourceFrame(0,44100,2112))
        assertEquals(0L,PcmTiming.sourceFrame(47_891,44100,2112))
        assertEquals(44_100L,PcmTiming.sourceFrame(1_047_891,44100,2112))
        assertEquals(1_217_532L,PcmTiming.sourceLength(1_218_496,27_678_185,44100,2112,964))
        assertEquals(16_000L,PcmTiming.sourceFrame(1_000_000,16000,0))
    }
    @Test fun timelinePersistenceValidatesSchemaAndRetainsClippedPedalAndMicroseconds() {
        val t=NoteTimeline(listOf(NoteEvent(60,123_456,999_999,84)),listOf(PedalEvent(120_000,0,127),PedalEvent(700_000,0,0)))
        val json=TimelineJson.encode(t,800_000,"test")
        val loaded=TimelineJson.decode(json)
        assertEquals(123_456,loaded.notes.single().startUs);assertEquals(800_000,loaded.notes.single().endUs)
        assertEquals(t.pedals,loaded.pedals)
        json.put("schema",9)
        assertThrows(IllegalArgumentException::class.java) { TimelineJson.decode(json) }
    }
}
