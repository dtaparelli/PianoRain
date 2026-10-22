package com.pianorain.data

import android.content.Context
import android.util.AtomicFile
import com.pianorain.domain.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ImportedSong(val id: String,val title: String,val state: String,val stage: String="",val progress: Float=0f,
    val durationUs: Long=0,val error: String?=null,val uri: String="") {
    val busy get()=state=="processing"
}
/** Small local library: one atomic manifest per song. No central index can become out of sync.
 * A single foreground importer owns writes; StateFlow exposes immutable snapshots to Compose.
 */
class LibraryStore internal constructor(
    private val root: File,
    private val writeManifest: (File, String) -> Unit = Companion::writeAtomic
) {
    private val _rows=MutableStateFlow<List<ImportedSong>>(emptyList())
    val rows=_rows.asStateFlow()
    init { root.mkdirs(); refresh(recover=true) }
    fun directory(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}")));return File(root,id) }
    @Synchronized fun refresh(recover: Boolean=false) {
        _rows.value=root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
            try {
                val r=JSONObject(AtomicFile(File(folder,"song.json")).openRead().bufferedReader().use { it.readText() })
                ImportedSong(r.getString("id"),r.getString("title"),r.getString("state"),r.optString("stage"),r.optDouble("progress",0.0).toFloat(),r.optLong("durationUs"),r.optString("error").ifEmpty { null },r.optString("uri"))
            } catch (_: Exception) { null }
        }.map { row ->
            if (recover && row.busy) row.copy(state="failed",stage="Interrupted",error="Import was interrupted. Tap Retry to start again.").also { persist(it) } else row
        }.sortedBy { it.title.lowercase() }
    }
    @Synchronized fun create(title: String,uri: String): ImportedSong {
        val row=ImportedSong(UUID.randomUUID().toString(),title,"processing","Importing audio",uri=uri)
        directory(row.id).mkdirs();persist(row);_rows.value=_rows.value+row;return row
    }
    @Synchronized fun update(row: ImportedSong) { persist(row);_rows.value=_rows.value.filterNot { it.id==row.id }+row }
    /** Always release the UI's busy state, even when the disk cannot save the outcome. */
    @Synchronized fun finish(row: ImportedSong, publish: (() -> Unit) -> Unit = { it() }): Exception? {
        require(!row.busy)
        var failure: Exception? = null
        val completed = try {
            persist(row)
            row
        } catch (error: Exception) {
            failure = error
            row.copy(state = "failed", stage = "Could not save import", progress = 0f,
                error = "Unable to save the import result. Check free storage, then tap Retry.")
        }
        publish {
            synchronized(this) {
                _rows.value = _rows.value.filterNot { it.id == row.id } + completed
            }
        }
        return failure
    }
    @Synchronized fun find(id: String)=_rows.value.find { it.id==id }
    @Synchronized fun remove(id: String) { directory(id).deleteRecursively();_rows.value=_rows.value.filterNot { it.id==id } }
    fun song(row: ImportedSong)=Song(row.id,row.title,"Imported piano recording",row.durationUs,File(directory(row.id),"audio").absolutePath,"",true)
    private fun persist(row: ImportedSong) {
        val json=JSONObject().put("id",row.id).put("title",row.title).put("state",row.state).put("stage",row.stage).put("progress",row.progress)
            .put("durationUs",row.durationUs).put("error",row.error?:"").put("uri",row.uri)
        writeManifest(File(directory(row.id),"song.json"),json.toString())
    }
    companion object {
        @Volatile private var instance: LibraryStore?=null
        fun get(context: Context)=instance?:synchronized(this) { instance?:LibraryStore(File(context.applicationContext.filesDir,"songs")).also { instance=it } }
        fun writeAtomic(file: File,text: String) {
            val atomic=AtomicFile(file);val stream=atomic.startWrite()
            try { stream.write(text.toByteArray());atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream);throw e }
        }
    }
}
