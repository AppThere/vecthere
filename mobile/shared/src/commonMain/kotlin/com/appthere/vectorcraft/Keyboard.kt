package com.appthere.vectorcraft

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.appthere.vectorcraft.engine.Modifiers
import com.appthere.vectorcraft.engine.Key as EngineKey

/** Apple keyboards' Command is the desktop's Cmd; everywhere else (ChromeOS, Android) it is Ctrl. */
expect val commandIsMeta: Boolean

private val LETTERS: Map<Key, String> = buildMap {
    val keys = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    keys.forEachIndexed { i, k -> put(k, ('A' + i).toString()) }
    listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        .forEachIndexed { i, k -> put(k, i.toString()) }
    put(Key.LeftBracket, "[")
    put(Key.RightBracket, "]")
    put(Key.Minus, "-")
    put(Key.Equals, "=")
    put(Key.Plus, "+")
}

private fun toolKey(k: Key): EngineKey? = when (k) {
    Key.Enter, Key.NumPadEnter -> EngineKey.Enter
    Key.Escape -> EngineKey.Escape
    Key.Backspace -> EngineKey.Backspace
    Key.Delete -> EngineKey.Delete
    Key.DirectionUp -> EngineKey.Up
    Key.DirectionDown -> EngineKey.Down
    Key.DirectionLeft -> EngineKey.Left
    Key.DirectionRight -> EngineKey.Right
    Key.Tab -> EngineKey.Tab
    Key.MoveHome -> EngineKey.Home
    Key.MoveEnd -> EngineKey.End
    else -> null
}

/** The desktop shortcut chord for a key event (`Cmd+Shift+Z`), as the command registry spells it. */
fun chordOf(e: KeyEvent): String? {
    val base = LETTERS[e.key] ?: return null
    val cmd = if (commandIsMeta) e.isMetaPressed else e.isCtrlPressed
    val parts = buildList {
        if (cmd) add("Cmd")
        if (e.isAltPressed) add("Alt")
        if (e.isShiftPressed) add("Shift")
        if (!commandIsMeta && e.isMetaPressed) add("Meta")
        add(base)
    }
    return parts.joinToString("+")
}

/**
 * Hardware keyboards (Chromebooks, Samsung Book Cover, iPad Magic Keyboard): modifiers reach the
 * tools (Shift constrains, Alt duplicates), arrows and Escape go to the active tool, and shortcut
 * chords run commands or choose tools. While the Type tool edits, typing is left to the text field.
 */
fun Modifier.editorKeys(state: EditorState): Modifier = onPreviewKeyEvent { e ->
    val cmd = if (commandIsMeta) e.isMetaPressed else e.isCtrlPressed
    state.setModifiers(Modifiers(shift = e.isShiftPressed, alt = e.isAltPressed, cmd = cmd, ctrl = e.isCtrlPressed))
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (state.wantsText && !cmd) {
        // Typing: only the keys the text field doesn't handle go to the tool.
        val k = toolKey(e.key) ?: return@onPreviewKeyEvent false
        if (k == EngineKey.Escape || k == EngineKey.Enter && e.isCtrlPressed) {
            state.key(k)
            return@onPreviewKeyEvent true
        }
        return@onPreviewKeyEvent false
    }
    toolKey(e.key)?.let {
        state.key(it)
        return@onPreviewKeyEvent true
    }
    val chord = chordOf(e) ?: return@onPreviewKeyEvent false
    state.shortcut(chord)
}

/**
 * The Type tool's text entry: an invisible text field that holds focus while the tool edits, so
 * the soft keyboard (and IMEs for CJK input) appear and their text reaches the engine.
 */
@Composable
fun TextInputBridge(state: EditorState) {
    if (!state.wantsText) return
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue("​", TextRange(1))) }
    BasicTextField(
        value = value,
        onValueChange = { v ->
            val sentinel = "​"
            when {
                // The sentinel was deleted: Backspace.
                !v.text.startsWith(sentinel) -> state.key(EngineKey.Backspace)
                v.text.length > 1 && v.composition == null -> state.text(v.text.removePrefix(sentinel))
            }
            value = if (v.composition != null) v else TextFieldValue(sentinel, TextRange(1))
        },
        modifier = Modifier.size(1.dp).focusRequester(focus),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
