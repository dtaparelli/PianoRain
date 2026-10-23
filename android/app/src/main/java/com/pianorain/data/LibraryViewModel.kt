package com.pianorain.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pianorain.transcription.ImportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(app: Application): AndroidViewModel(app) {
    private val store=LibraryStore.get(app)
    val rows=store.rows
    private val _starting=MutableStateFlow(false);val starting=_starting.asStateFlow()
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    fun clearError() { _error.value=null }
    fun add(uri: Uri) {
        if (_starting.value || rows.value.any { it.busy }) return
        _starting.value=true
        viewModelScope.launch {
            try {
                val app=getApplication<Application>()
                runCatching { app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val row=withContext(Dispatchers.IO) {
                    val title=app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }?.substringBeforeLast('.')?.take(100)?.ifBlank { null }?:"Piano recording"
                    store.create(title,uri.toString())
                }
                start(row)
            } catch (error: Exception) { _error.value=error.message?:"Unable to import this audio file." }
            finally { _starting.value=false }
        }
    }
    fun addRecording(file: java.io.File, title: String) {
        if (_starting.value || rows.value.any { it.busy }) {
            file.delete()
            return
        }
        _starting.value = true
        viewModelScope.launch {
            try {
                val row = withContext(Dispatchers.IO) {
                    val entry = store.create(title.trim().take(100).ifBlank { "Piano recording" }, "")
                    try {
                        val audio = java.io.File(store.directory(entry.id), "audio")
                        val pending = java.io.File(store.directory(entry.id), "audio.partial")
                        file.inputStream().use { input ->
                            java.io.FileOutputStream(pending).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        check(pending.renameTo(audio)) { "Unable to save recording." }
                        file.delete()
                        entry
                    } catch (error: Exception) {
                        store.remove(entry.id)
                        throw error
                    }
                }
                start(row)
            } catch (error: Exception) {
                _error.value = error.message ?: "Unable to save recording."
            } finally {
                // Once adopted, the library owns the recording even if service startup fails.
                file.delete()
                _starting.value = false
            }
        }
    }
    private suspend fun start(row: ImportedSong) {
        try {
            ContextCompat.startForegroundService(getApplication(),Intent(getApplication(),ImportService::class.java).putExtra("id",row.id))
        } catch (error: Exception) {
            withContext(Dispatchers.IO) { store.update(row.copy(state="failed",stage="Could not start",error="Open PianoRain and tap Retry.")) }
            throw error
        }
    }
    fun retry(row: ImportedSong) {
        if (_starting.value || rows.value.any { it.busy }) return
        _starting.value=true
        viewModelScope.launch {
            try { val updated=row.copy(state="processing",stage="Preparing audio",error=null,progress=0f)
                withContext(Dispatchers.IO) { store.update(updated) };start(updated)
            } catch (error: Exception) { _error.value=error.message?:"Unable to restart import." }
            finally { _starting.value=false }
        }
    }
    fun cancel(row: ImportedSong) { getApplication<Application>().startService(Intent(getApplication(),ImportService::class.java).setAction(ImportService.CANCEL).putExtra("id",row.id)) }
    fun remove(row: ImportedSong) {
        if (row.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(row.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            store.remove(row.id)
        }
    }
    fun song(row: ImportedSong)=store.song(row)
}
