package com.pianorain.transcription

import android.app.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pianorain.MainActivity
import com.pianorain.data.LibraryStore
import com.pianorain.data.TimelineJson
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** User-started, serial offline preparation. Persisted rows survive process death; retry starts clean. */
class ImportService : Service() {
    private val cancelled=AtomicBoolean(false)
    @Volatile private var activeId: String?=null
    private lateinit var store: LibraryStore
    override fun onCreate() {
        super.onCreate();store=LibraryStore.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Piano preparation",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if (intent?.action==CANCEL) { if (intent.getStringExtra("id")==activeId) cancelled.set(true);return START_NOT_STICKY }
        if (activeId!=null) return START_NOT_STICKY
        val id=intent?.getStringExtra("id")?:run { stopSelf();return START_NOT_STICKY }
        val row=store.find(id)?:run { stopSelf();return START_NOT_STICKY }
        activeId=id;cancelled.set(false)
        val notification=notification(id,row.title,"Importing audio",0f)
        if (Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,notification,when {
            Build.VERSION.SDK_INT>=35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT>=34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }) else startForeground(NOTIFICATION,notification)
        Thread({
            val folder=store.directory(id)
            val wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PianoRain:import")
            var outcome=row.copy(state="failed",stage="Import failed",error="Import did not complete. Tap Retry.")
            try {
                wake.acquire(30*60*1000L)
                fun check() { if (cancelled.get()) throw CancellationException("Import cancelled.") }
                var last=0L;var previous=""
                fun progress(stage: String,value: Float) {
                    check();val now=SystemClock.elapsedRealtime()
                    if (stage!=previous || now-last>=350 || value>=1f) {
                        store.update(row.copy(stage=stage,progress=value))
                        if (Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(id,row.title,stage,value))
                        last=now;previous=stage
                    }
                }
                require(folder.usableSpace>500L*1024*1024) { "Free at least 500 MB of storage, then retry." }
                val audio=File(folder,"audio")
                if (!audio.exists()) {
                    val pending=File(folder,"audio.partial")
                    try {
                        contentResolver.openInputStream(Uri.parse(row.uri)).use { input ->
                            requireNotNull(input) { "Cannot open this file. Choose a file available on this device." }
                            pending.outputStream().use { output ->
                                val buffer=ByteArray(64*1024);var size=0L
                                while (true) {
                                    check();val count=input.read(buffer);if (count<0) break
                                    size+=count;require(size<=100L*1024*1024) { "Choose an audio file smaller than 100 MB." }
                                    output.write(buffer,0,count);progress("Importing audio",0f)
                                }
                                require(size>0) { "The selected file is empty." }
                            }
                        }
                        require(pending.renameTo(audio)) { "Unable to save imported audio." }
                    } finally { pending.delete() }
                }
                // The copied audio is now durable; do not retain access to the user's document unnecessarily.
                runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(row.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val result=PianoTranscriber(this).transcribe(audio,File(folder,"processing"),::check,::progress)
                check()
                val digest=MessageDigest.getInstance("SHA-256")
                audio.inputStream().use { input -> val buffer=ByteArray(64*1024);while (true) { check();val n=input.read(buffer);if (n<0) break;digest.update(buffer,0,n) } }
                val timeline=TimelineJson.encode(result.timeline,result.durationUs,PianoTranscriber.VERSION)
                    .put("audioSha256",digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                    .put("nativeRate",result.nativeRate).put("resampledSamples",result.samples)
                LibraryStore.writeAtomic(File(folder,"timeline.json"),timeline.toString())
                check();outcome=row.copy(state="ready",stage="Ready",progress=1f,durationUs=result.durationUs,uri="")
            } catch (error: Exception) {
                val wasCancelled=error is CancellationException
                outcome=row.copy(state=if (wasCancelled) "cancelled" else "failed",stage=if (wasCancelled) "Cancelled" else "Import failed",
                    error=if (wasCancelled) "Import cancelled. Tap Retry to start again." else when (error) {
                        is SecurityException -> "Cannot read this file. Remove this entry and choose a local copy again."
                        is java.io.FileNotFoundException -> "The file is no longer available. Remove this entry and select it again."
                        is java.io.IOException -> "Unable to read or save this audio. Check free storage and retry."
                        is ai.onnxruntime.OrtException -> "Piano analysis could not finish. Restart PianoRain and retry."
                        else -> error.message?:"Unable to process this recording."
                    })
            } finally {
                File(folder,"processing").deleteRecursively();File(folder,"audio.partial").delete()
                if (wake.isHeld) wake.release()
                // Cancel intents have their own start IDs. Stop the whole serial service on
                // the main thread before publishing a state that enables another import.
                store.finish(outcome) { publish ->
                    Handler(Looper.getMainLooper()).post {
                        activeId=null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        publish()
                    }
                }?.let { Log.e("PianoRain", "Unable to persist import result", it) }
            }
        },"piano-import").start()
        return START_NOT_STICKY
    }
    override fun onDestroy() { cancelled.set(true);super.onDestroy() }
    override fun onTimeout(startId: Int,fgsType: Int) { cancelled.set(true);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
    private fun notification(id: String,title: String,stage: String,value: Float): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel=PendingIntent.getService(this,1,Intent(this,ImportService::class.java).setAction(CANCEL).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(stage).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100,(value*100).toInt(),value<=0f).addAction(0,"Cancel",cancel).build()
    }
    companion object { const val CANCEL="com.pianorain.CANCEL_IMPORT";private const val CHANNEL="piano_import";private const val NOTIFICATION=41 }
}
