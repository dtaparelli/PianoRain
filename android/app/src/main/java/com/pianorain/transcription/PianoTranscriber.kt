package com.pianorain.transcription

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.pianorain.domain.NoteTimeline
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

class PianoTranscriber(private val context: Context) {
    data class Result(val timeline: NoteTimeline, val durationUs: Long, val samples: Long, val nativeRate: Int)
    fun transcribe(audio: File, temp: File, check: () -> Unit, progress: (String, Float) -> Unit): Result {
        temp.mkdirs()
        try {
            val decoded=AudioDecoder().decode(audio,temp,check,progress)
            check(); progress("Loading piano model",0f)
            val model=modelFile(check)
            val env=OrtEnvironment.getEnvironment()
            PredictionFiles(temp).use { frames ->
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(4); options.setInterOpNumThreads(1)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    env.createSession(model.absolutePath,options).use { session ->
                        val padded=((decoded.samples+159999)/160000*160000).coerceAtLeast(160000)
                        val windows=((padded-160000)/80000+1).toInt()
                        RandomAccessFile(decoded.pcm,"r").use { input ->
                            val bytes=ByteArray(160000*4)
                            val direct=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                            for (window in 0 until windows) {
                                check(); bytes.fill(0)
                                input.seek(window*80000L*4)
                                var read=0
                                while (read<bytes.size) { val n=input.read(bytes,read,bytes.size-read); if (n<0) break; read+=n }
                                direct.clear(); direct.put(bytes); direct.rewind()
                                OnnxTensor.createTensor(env,direct.asFloatBuffer(),longArrayOf(1,160000)).use { tensor ->
                                    session.run(mapOf("waveform" to tensor)).use { outputs ->
                                        check()
                                        for ((name,columns) in OUTPUTS) {
                                            val floats=(outputs.get(name).get() as OnnxTensor).floatBuffer
                                            require(floats.remaining()==1001*columns) { "Unexpected model output shape." }
                                            val begin=if (window==0) 0 else 250
                                            val end=if (windows==1) 1001 else if (window==windows-1) 1000 else 750
                                            val buffer=ByteBuffer.allocate((end-begin)*columns*4).order(ByteOrder.LITTLE_ENDIAN)
                                            floats.position(begin*columns); floats.limit(end*columns)
                                            buffer.asFloatBuffer().put(floats)
                                            frames.append(name,buffer.array())
                                        }
                                    }
                                }
                                progress("Generating notes",(window+1f)/windows)
                            }
                        }
                    }
                }
                // Release model session before scanning predictions, so long clips do not keep both live.
                check(); progress("Saving piano notes",0f)
                val timeline=NoteExtractor.extract(frames,decoded.durationUs,check)
                require(timeline.notes.isNotEmpty()) { "No piano notes detected. Try a clearer solo-piano recording." }
                return Result(timeline,decoded.durationUs,decoded.samples,decoded.nativeRate)
            }
        } finally { temp.deleteRecursively() }
    }
    private fun modelFile(check: () -> Unit): File {
        val folder=File(context.filesDir,"models").apply { mkdirs() }
        val file=File(folder,"piano-$MODEL_HASH.onnx")
        if (file.length()==MODEL_BYTES) return file
        val pending=File(folder,"model.partial")
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            context.assets.open("models/piano.onnx").use { input -> pending.outputStream().use { output ->
                val buffer=ByteArray(64*1024)
                while (true) { check(); val count=input.read(buffer); if (count<0) break; digest.update(buffer,0,count); output.write(buffer,0,count) }
            } }
            require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==MODEL_HASH) { "Piano model checksum failed. Reinstall PianoRain." }
            require(pending.renameTo(file)) { "Unable to save piano model. Free some storage and retry." }
            return file
        } finally { pending.delete() }
    }
    companion object {
        const val MODEL_HASH="99418f7e760edd759d0cbf46c001c97bedf56dae621667376e1ba8a42a78fec4"
        const val MODEL_BYTES=154214211L
        const val VERSION="kong-0.0.6-onnx17-f32-sinc96-pcm16-gapless-v2"
        val OUTPUTS=linkedMapOf("reg_onset_output" to 88,"reg_offset_output" to 88,"frame_output" to 88,"velocity_output" to 88,
            "reg_pedal_offset_output" to 1,"pedal_frame_output" to 1)
    }
}

/** Append only the reference window's retained middle section, then read one pitch at a time.
 * Predictions are spooled to disk; the extractor never allocates a whole-song tensor graph.
 */
class PredictionFiles(private val directory: File) : NoteExtractor.Frames, Closeable {
    private val files=PianoTranscriber.OUTPUTS.mapValues { File(directory,"${it.key}.f32").apply { writeBytes(byteArrayOf()) } }
    private val maps=mutableMapOf<String,ByteBuffer>()
    fun append(name: String, bytes: ByteArray) { files.getValue(name).appendBytes(bytes) }
    override fun column(name: String,pitch: Int): FloatArray {
        val columns=PianoTranscriber.OUTPUTS.getValue(name)
        val data=maps.getOrPut(name) {
            val file=files.getValue(name)
            require(file.length()>0 && file.length()%(columns*4)==0L)
            RandomAccessFile(file,"r").use { it.channel.map(FileChannel.MapMode.READ_ONLY,0,it.length()).order(ByteOrder.LITTLE_ENDIAN) }
        }
        return FloatArray(data.limit()/4/columns) { index -> data.getFloat((index*columns+pitch)*4).also { require(it.isFinite()) } }
    }
    override fun close() { maps.clear() }
}
