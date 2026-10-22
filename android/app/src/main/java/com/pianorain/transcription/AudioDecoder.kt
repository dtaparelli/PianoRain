package com.pianorain.transcription

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToLong

class AudioDecoder {
    data class Decoded(val pcm: File, val samples: Long, val durationUs: Long, val nativeRate: Int)
    fun decode(audio: File, directory: File, check: () -> Unit, progress: (String, Float) -> Unit): Decoded {
        val extractor = MediaExtractor()
        val mono = File(directory, "native.f32")
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(audio.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio track found.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            require(!format.containsKey("crypto-key")) { "Protected audio is not supported." }
            val declaredUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            require(declaredUs <= MAX_DURATION_US) { "Choose a recording up to 10 minutes long." }
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            var written = 0L
            val encoderDelay=if (format.containsKey("encoder-delay")) format.getInteger("encoder-delay").coerceAtLeast(0) else 0
            val encoderPadding=if (format.containsKey("encoder-padding")) format.getInteger("encoder-padding").coerceAtLeast(0) else 0
            RandomAccessFile(mono, "rw").use { output ->
                output.setLength(0)
                fun consume(data: ByteBuffer, timeUs: Long) {
                    require(rate in 8000..96000 && channels in 1..2) { "Use mono or stereo audio at 8–96 kHz." }
                    val size = when (encoding) {
                        AudioFormat.ENCODING_PCM_8BIT -> 1
                        AudioFormat.ENCODING_PCM_16BIT -> 2
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
                        else -> error("Unsupported decoded PCM format.")
                    }
                    require(data.remaining() % (size*channels) == 0) { "Incomplete PCM frame." }
                    data.order(ByteOrder.LITTLE_ENDIAN)
                    val count = data.remaining()/(size*channels)
                    val target = PcmTiming.sourceFrame(timeUs,rate,encoderDelay)
                    // Ignore sub-frame timestamp rounding, retain actual gaps and negative priming PTS.
                    val gap = target-written
                    if (gap > 2) {
                        require(target <= rate * MAX_DURATION_US/1_000_000) { "Choose a recording up to 10 minutes long." }
                        repeat(gap.toInt()) { output.writeInt(0); written++ }
                    }
                    val skip = if (gap < -2) (-gap).coerceAtMost(count.toLong()).toInt() else 0
                    val floats = ByteBuffer.allocate((count-skip).coerceAtLeast(0)*4).order(ByteOrder.LITTLE_ENDIAN)
                    for (frame in 0 until count) {
                        var value = 0f
                        repeat(channels) {
                            value += when (encoding) {
                                AudioFormat.ENCODING_PCM_8BIT -> ((data.get().toInt() and 255)-128)/128f
                                AudioFormat.ENCODING_PCM_16BIT -> data.short/32768f
                                AudioFormat.ENCODING_PCM_FLOAT -> data.float
                                AudioFormat.ENCODING_PCM_32BIT -> data.int/2147483648f
                                else -> { val v=(data.get().toInt() and 255) or ((data.get().toInt() and 255) shl 8) or (data.get().toInt() shl 16); v/8388608f }
                            }
                        }
                        if (frame >= skip) { require(value.isFinite()) { "Invalid PCM samples." }; floats.putFloat(value/channels) }
                    }
                    output.write(floats.array()); written += count-skip
                    require(written <= rate*MAX_DURATION_US/1_000_000) { "Choose a recording up to 10 minutes long." }
                    progress("Decoding audio", if (declaredUs > 0) (written*1_000_000.0/rate/declaredUs).toFloat().coerceIn(0f,1f) else 0f)
                }
                if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") {
                    val buffer=ByteBuffer.allocateDirect(1024*1024)
                    while (true) {
                        check(); buffer.clear(); val count=extractor.readSampleData(buffer,0); if (count < 0) break
                        buffer.limit(count); consume(buffer,extractor.sampleTime); extractor.advance()
                    }
                } else {
                    val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
                    codec=decoder
                    // Some codecs trim both PCM and output PTS when these keys are passed.
                    // Own gapless removal, like Media3's audio sink, to retain a stable source clock.
                    format.setInteger("encoder-delay",0);format.setInteger("encoder-padding",0)
                    if (format.getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm")
                        format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_FLOAT)
                    decoder.configure(format,null,null,0);decoder.start()
                    var inputEnd=false; var done=false; var lastProgress=SystemClock.elapsedRealtime()
                    val info=MediaCodec.BufferInfo()
                    while (!done) {
                        check()
                        if (!inputEnd) {
                            val index=decoder.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val buffer=checkNotNull(decoder.getInputBuffer(index)); val count=extractor.readSampleData(buffer,0)
                                if (count < 0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd=true }
                                else { decoder.queueInputBuffer(index,0,count,extractor.sampleTime,0); extractor.advance() }
                            }
                        }
                        when (val index=decoder.dequeueOutputBuffer(info,10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val changed=decoder.outputFormat
                                val newRate=changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                require(written == 0L || newRate == rate) { "Changing sample rates are unsupported." }
                                rate=newRate; channels=changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                encoding=if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING)) changed.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            }
                            else -> if (index >= 0) {
                                try {
                                    if (info.size > 0) {
                                        val buffer=checkNotNull(decoder.getOutputBuffer(index)).duplicate()
                                        buffer.position(info.offset); buffer.limit(info.offset+info.size)
                                        consume(buffer.slice(),info.presentationTimeUs)
                                    }
                                    done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    lastProgress=SystemClock.elapsedRealtime()
                                } finally { decoder.releaseOutputBuffer(index,false) }
                            }
                        }
                        require(SystemClock.elapsedRealtime()-lastProgress < 30_000) { "The audio decoder stopped responding." }
                    }
                }
                written=PcmTiming.sourceLength(written,declaredUs,rate,encoderDelay,encoderPadding)
                output.setLength(written*4)
            }
            require(written > 0) { "The audio file is empty." }
            val durationUs=(written*1_000_000.0/rate).roundToLong()
            val pcm=File(directory,"mono-16000.f32")
            RandomAccessFile(mono,"r").use { input ->
                val samples=input.channel.map(FileChannel.MapMode.READ_ONLY,0,input.length()).order(ByteOrder.LITTLE_ENDIAN)
                val resampler=SincResampler(rate); val count=resampler.outputCount(written)
                pcm.outputStream().buffered().use { output ->
                    val buffer=ByteBuffer.allocate(4096*4).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until count) {
                        if (i%4096==0L) { check(); progress("Preparing piano audio", i.toFloat()/count) }
                        val sample=resampler.sample(i,written) { at -> samples.getFloat((at*4).toInt()) }
                        // The accepted Python sample used a 16-bit WAV after resampling.
                        buffer.putFloat(SincResampler.pcm16(sample))
                        if (!buffer.hasRemaining()) { output.write(buffer.array()); buffer.clear() }
                    }
                    if (buffer.position()>0) output.write(buffer.array(),0,buffer.position())
                }
                mono.delete()
                return Decoded(pcm,count,durationUs,rate)
            }
        } finally { try { codec?.stop() } finally { codec?.release(); extractor.release() }; mono.delete() }
    }
    companion object { const val MAX_DURATION_US=600_000_000L }
}
