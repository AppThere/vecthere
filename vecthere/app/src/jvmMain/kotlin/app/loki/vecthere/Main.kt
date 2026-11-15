package app.loki.vecthere

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** A native file dialog; the path, or null when cancelled. */
fun pickFile(save: Boolean): String? {
    val dialog = FileDialog(null as Frame?, if (save) "Save As" else "Open", if (save) FileDialog.SAVE else FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it).path }
}

// The engine through the FFI crate (ffi/, built by scripts/build-desktop.sh). Without the native
// library the UI still opens, on the recorded command list (FixtureTransport): nothing executes.
fun main() = application {
    val native = remember { NativeEngine.open() }
    Window(onCloseRequest = ::exitApplication, title = APP_NAME, state = rememberWindowState(size = DpSize(1200.dp, 800.dp))) {
        if (native != null) {
            // Edits the surface makes itself (typing into a cell) re-read it too.
            var own by remember { mutableIntStateOf(0) }
            App(native, pickFile = ::pickFile, surface = { revision, modifier -> EngineSurface(native, revision + own, modifier, scale = 1.5f, onChanged = { own++ }) })
        } else {
            val commands = object {}.javaClass.getResource("/engine-commands.json")?.readText() ?: "[]"
            App(FixtureTransport(commands), pickFile = ::pickFile)
        }
    }
}
