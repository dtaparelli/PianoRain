package com.pianorain

import com.pianorain.data.LibraryStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class LibraryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completedImportPersistsBeforeBecomingReady() {
        val writes = mutableListOf<String>()
        val store = LibraryStore(temporary.newFolder()) { _, text -> writes += text }
        val row = store.create("Piano", "content://audio/1")
        assertNull(store.finish(row.copy(state = "ready", durationUs = 1_000_000)))
        assertTrue(writes.last().contains("\"state\":\"ready\""))
        assertEquals("ready", store.find(row.id)?.state)
    }

    @Test fun failedTerminalSaveReleasesBusyStateAndAllowsRetryAfterStorageRecovers() {
        var diskFull = false
        val store = LibraryStore(temporary.newFolder()) { _, _ ->
            if (diskFull) throw IOException("No space left")
        }
        val row = store.create("Piano", "content://audio/1")
        diskFull = true
        assertTrue(store.finish(row.copy(state = "ready")) is IOException)
        val failed = store.find(row.id)!!
        assertEquals("failed", failed.state)
        assertTrue(failed.error!!.contains("Check free storage"))
        assertFalse(store.rows.value.any { it.busy })

        diskFull = false
        store.update(failed.copy(state = "processing", error = null))
        assertNull(store.finish(failed.copy(state = "ready", error = null)))
        assertEquals("ready", store.find(row.id)?.state)
    }

    @Test fun cancelledImportAlsoReleasesBusyStateWhenSavingFails() {
        var fail = false
        val store = LibraryStore(temporary.newFolder()) { _, _ ->
            if (fail) throw IOException("No space left")
        }
        val row = store.create("Piano", "content://audio/1")
        fail = true
        assertNotNull(store.finish(row.copy(state = "cancelled")))
        assertFalse(store.find(row.id)!!.busy)
    }

    @Test fun terminalStateWaitsForServiceShutdownPublication() {
        val store = LibraryStore(temporary.newFolder()) { _, _ -> }
        val row = store.create("Piano", "content://audio/1")
        var publish: (() -> Unit)? = null
        store.finish(row.copy(state = "cancelled")) { publish = it }
        // The UI must not offer another import while service shutdown is still queued.
        assertTrue(store.find(row.id)!!.busy)
        publish!!()
        assertEquals("cancelled", store.find(row.id)?.state)
    }
}
