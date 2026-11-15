package app.loki.vecthere

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.CompletableFuture

actual fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap {
    val px = IntArray(width * height) { i ->
        val o = i * 4
        val r = rgba[o].toInt() and 0xFF
        val g = rgba[o + 1].toInt() and 0xFF
        val b = rgba[o + 2].toInt() and 0xFF
        val a = rgba[o + 3].toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The engine for the whole process: the open document lives in it, so the activity never owns it. */
private val engine: NativeEngine? by lazy { NativeEngine.open() }

/** File names from a picker are the user's; keep only what is safe as a single path segment. */
private fun safeName(name: String?, fallback: String) =
    name.orEmpty().substringAfterLast('/').replace(Regex("[^\\w. -]"), "_").trim().ifEmpty { fallback }

class MainActivity : ComponentActivity() {
    /** Where each file the engine reads or writes in app storage came from, or is to go: its content URI. */
    private val origins = HashMap<String, Uri>()

    /** Runs a picker on the UI thread and waits for its answer; called off the UI thread. */
    private fun <I> await(contract: androidx.activity.result.contract.ActivityResultContract<I, Uri?>, input: I): Uri? {
        val result = CompletableFuture<Uri?>()
        runOnUiThread {
            activityResultRegistry.register("loki-${System.nanoTime()}", contract) { uri: Uri? -> result.complete(uri) }.launch(input)
        }
        return runCatching { result.get() }.getOrNull()
    }

    private fun nameOf(uri: Uri, fallback: String) = safeName(
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }, fallback,
    )

    /**
     * The engine takes paths and the Storage Access Framework hands out content URIs. Open copies the
     * chosen file into app storage and returns that path, remembering where it came from, so Save
     * writes it back there ([publish]). Blocks until the picker closes; called off the UI thread.
     */
    private fun open(): String? {
        val uri = await(ActivityResultContracts.OpenDocument(), arrayOf("*/*")) ?: return null
        val file = File(File(cacheDir, "open").apply { mkdirs() }, nameOf(uri, "document"))
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
            origins[file.path] = uri
            file.path
        }.getOrNull()
    }

    /** Save As: the system's "create document" picker, starting from [suggested]; the engine writes into app storage, then [publish] copies it there. */
    private fun saveAs(suggested: String): String? {
        val ext = suggested.substringAfterLast('.', "").lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val uri = await(ActivityResultContracts.CreateDocument(mime), suggested) ?: return null
        var name = nameOf(uri, suggested)
        if (ext.isNotEmpty() && !name.lowercase().endsWith(".$ext")) name += ".$ext"
        val file = File(File(cacheDir, "save").apply { mkdirs() }, name)
        origins[file.path] = uri
        return file.path
    }

    /** Copies what the engine wrote at [path] to the place the person chose for it, if any. */
    private fun publish(path: String) {
        val uri = origins[path] ?: return
        runCatching { contentResolver.openOutputStream(uri, "wt")?.use { out -> File(path).inputStream().use { it.copyTo(out) } } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        val native = engine
        setContent {
            if (native != null) {
                var own by remember { mutableIntStateOf(0) }
                App(native.borrowed(), pickFile = { if (it) saveAs("Untitled") else open() }, saveAs = ::saveAs, publishFile = ::publish, surface = { revision, modifier -> ArtSurface(native, revision + own, modifier) })
            } else {
                val commands = assets.open("engine-commands.json").bufferedReader().use { it.readText() }
                App(FixtureTransport(commands), pickFile = { if (it) saveAs("Untitled") else open() })
            }
        }
    }
}
