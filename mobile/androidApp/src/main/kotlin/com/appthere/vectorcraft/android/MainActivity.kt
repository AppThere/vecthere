package com.appthere.vectorcraft.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.appthere.vectorcraft.PlatformFiles
import com.appthere.vectorcraft.VectorCraftApp
import com.appthere.vectorcraft.engine.VectorEngine
import com.appthere.vectorcraft.engine.setFontDirs

/**
 * VectorCraft on Android phones, tablets (Galaxy Tab with S Pen) and Chromebooks. The whole UI is
 * the shared Compose app; this activity only hosts it and lends it the system file pickers.
 */
class MainActivity : ComponentActivity() {
    private val engine by lazy { VectorEngine() }

    private var onOpened: ((String, ByteArray) -> Unit)? = null
    private var pendingSave: Pair<ByteArray, () -> Unit>? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readUri(uri)?.let { (name, bytes) -> onOpened?.invoke(name, bytes) }
        onOpened = null
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val (bytes, done) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri == null) return@registerForActivityResult
        val ok = runCatching { contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
        if (ok) done()
    }

    private val files = object : PlatformFiles {
        override fun open(onPicked: (name: String, bytes: ByteArray) -> Unit) {
            onOpened = onPicked
            openDocument.launch(arrayOf("*/*"))
        }

        override fun save(suggestedName: String, mimeType: String, bytes: ByteArray, onSaved: () -> Unit) {
            pendingSave = bytes to onSaved
            createDocument.launch(suggestedName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Fonts the user imported live in the app's own storage (the system fonts are found by the
        // engine itself).
        setFontDirs(listOf(filesDir.resolve("fonts").absolutePath))
        intent?.let(::openFromIntent)
        setContent { VectorCraftApp(engine, files) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    /** A file opened from another app (Files, Drive, a share sheet). */
    private fun openFromIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val (name, bytes) = readUri(uri) ?: return
        runCatching { engine.openBytes(name, bytes) }
    }

    /** The display name and bytes of a content URI, or null if it can't be read. */
    private fun readUri(uri: Uri): Pair<String, ByteArray>? = runCatching {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Untitled"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        name to bytes
    }.getOrNull()
}
