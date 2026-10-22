package com.pianorain.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local legal documents remain readable without a browser or network connection. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var documents by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            documents = withContext(Dispatchers.IO) {
                listOf("PIANORAIN.txt", "DEMO.txt", "CC0-1.0.txt", "MODEL.txt", "CC-BY-4.0.txt",
                    "ANDROID-DEPENDENCIES.txt", "ONNX-RUNTIME.txt").flatMap { name ->
                    val text = context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
                    // Bound each text layout while preserving every character of the document.
                    text.chunked(8_000).mapIndexed { index, chunk ->
                        (if (index == 0) name else "$name (continued)") to chunk
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Unable to load license notices. Return to Songs and try again."
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("Back to Songs") }
        Text("Licenses", style = MaterialTheme.typography.headlineSmall)
        val loaded = documents
        when {
            error != null -> Text(error.orEmpty())
            loaded == null -> CircularProgressIndicator()
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)) {
                items(loaded) { (title, text) ->
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
