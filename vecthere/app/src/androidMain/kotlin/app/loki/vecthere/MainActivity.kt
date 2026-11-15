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
    /**
     * The engine takes paths and the Storage Access Framework hands out content URIs. Open copies the
     * chosen file into app storage and returns that path. Save returns a path in the app's own
     * documents folder (a picker that copies it out is still to be built). Blocks until the picker
     * closes; the generated forms call it off the UI thread.
     */
    private fun pickFile(save: Boolean): String? {
        val docs = getExternalFilesDir("Documents") ?: filesDir
        if (save) return File(docs, "Untitled").path
        val result = CompletableFuture<Uri?>()
        runOnUiThread {
            activityResultRegistry.register("loki-open-${System.nanoTime()}", ActivityResultContracts.OpenDocument()) { uri: Uri? -> result.complete(uri) }
                .launch(arrayOf("*/*"))
        }
        val uri = runCatching { result.get() }.getOrNull() ?: return null
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val file = File(File(cacheDir, "open").apply { mkdirs() }, safeName(name, "document"))
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
            file.path
        }.getOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        val native = engine
        setContent {
            if (native != null) {
                var own by remember { mutableIntStateOf(0) }
                App(native.borrowed(), pickFile = ::pickFile, surface = { revision, modifier -> EngineSurface(native, revision + own, modifier, scale = 1.5f, onChanged = { own++ }) })
            } else {
                val commands = assets.open("engine-commands.json").bufferedReader().use { it.readText() }
                App(FixtureTransport(commands), pickFile = ::pickFile)
            }
        }
    }
}
