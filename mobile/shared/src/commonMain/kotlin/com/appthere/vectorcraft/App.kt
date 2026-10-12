package com.appthere.vectorcraft

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appthere.vectorcraft.engine.VectorEngine

/**
 * How much room there is, after Material 3's window size classes: phones in portrait are
 * [Compact]; tablets in portrait, foldables and split screen are [Medium]; tablets in landscape,
 * Chromebooks and desktop windows are [Expanded].
 */
enum class WidthClass { Compact, Medium, Expanded }

fun widthClass(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.Compact
    width < 840.dp -> WidthClass.Medium
    else -> WidthClass.Expanded
}

/** What the platform does for the app: files, sharing (Android/iOS pickers, desktop dialogs). */
interface PlatformFiles {
    /** Ask the user for a file to open; call [onPicked] with its name and bytes. */
    fun open(onPicked: (name: String, bytes: ByteArray) -> Unit)

    /** Ask where to save [bytes] as [suggestedName] ([mimeType]); call [onSaved] once written. */
    fun save(suggestedName: String, mimeType: String, bytes: ByteArray, onSaved: () -> Unit)
}

/** Which side panel shows. */
enum class Panel { Properties, Layers, Pen }

@Composable
fun VectorCraftApp(engine: VectorEngine, files: PlatformFiles) {
    val scope = rememberCoroutineScope()
    val state = remember(engine) { EditorState(engine, scope) }
    LaunchedEffect(state) {
        if (!state.document.hasDocument) state.newDocument()
        state.refresh()
    }
    VectorCraftTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Editor(state, files, widthClass(maxWidth))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(state: EditorState, files: PlatformFiles, width: WidthClass) {
    val snackbar = remember { SnackbarHostState() }
    var panel by rememberSaveable { mutableStateOf<Panel?>(if (width == WidthClass.Expanded) Panel.Properties else null) }
    val actions = remember(state, files) { EditorActions(state, files) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            state.message = null
        }
    }

    // Hardware keyboards: the editor takes the keys whenever no text field has focus.
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(keyFocus) { keyFocus.requestFocus() }
    Box(Modifier.fillMaxSize().focusRequester(keyFocus).editorKeys(state).focusable()) {
        when (width) {
            // Phone: title bar on top, the canvas, a scrolling tool strip at the bottom; panels open
            // as a bottom sheet.
            WidthClass.Compact -> Column(Modifier.fillMaxSize()) {
                TopBar(state, actions, compact = true, onPanel = { panel = it })
                ArtCanvas(state, Modifier.weight(1f).fillMaxWidth())
                ToolStrip(state, Modifier.fillMaxWidth())
            }
            // Tablet portrait / foldable: a tool rail on the left; panels open as a sheet.
            WidthClass.Medium -> Column(Modifier.fillMaxSize()) {
                TopBar(state, actions, compact = false, onPanel = { panel = it })
                Row(Modifier.weight(1f)) {
                    ToolRail(state, Modifier.fillMaxHeight())
                    VerticalDivider()
                    ArtCanvas(state, Modifier.weight(1f).fillMaxHeight())
                }
                StatusBar(state)
            }
            // Tablet landscape / Chromebook: menu bar, tool rail, docked panels, status bar, as on
            // the desktop app.
            WidthClass.Expanded -> Column(Modifier.fillMaxSize()) {
                MenuBar(state, actions)
                TopBar(state, actions, compact = false, onPanel = { panel = if (panel == it) null else it })
                Row(Modifier.weight(1f)) {
                    ToolRail(state, Modifier.fillMaxHeight())
                    VerticalDivider()
                    ArtCanvas(state, Modifier.weight(1f).fillMaxHeight())
                    panel?.let {
                        VerticalDivider()
                        Box(Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                            PanelContent(state, it, onSelect = { p -> panel = p })
                        }
                    }
                }
                StatusBar(state)
            }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    if (width != WidthClass.Expanded) {
        panel?.let {
            ModalBottomSheet(onDismissRequest = { panel = null }) {
                Box(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    PanelContent(state, it, onSelect = { p -> panel = p })
                }
            }
        }
    }

    state.dialog?.let { (id, params) -> ToolDialog(state, id, params) }
    TextInputBridge(state)
}

/** File and edit actions shared by the menus, the top bar and keyboard shortcuts. */
class EditorActions(private val state: EditorState, private val files: PlatformFiles) {
    fun new() = state.newDocument()
    fun open() = files.open { name, bytes -> state.openBytes(name, bytes) }
    fun save() = export("vectorcraft", "application/octet-stream", markSaved = true)
    fun export(format: String, mime: String, markSaved: Boolean = false) {
        val bytes = state.saveBytes(format) ?: return
        val base = state.document.title.substringBeforeLast('.').ifBlank { "Untitled" }
        files.save("$base.$format", mime, bytes) { if (markSaved) state.markSaved() }
    }
    fun undo() = state.run("edit.undo")
    fun redo() = state.run("edit.redo")
}
