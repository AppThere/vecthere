package com.appthere.vectorcraft

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appthere.vectorcraft.engine.CommandInfo

/** The title bar: document title, undo/redo, fit, panels, and (on phones) every menu. */
@Composable
fun TopBar(state: EditorState, actions: EditorActions, compact: Boolean, onPanel: (Panel) -> Unit) {
    val doc = state.document
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (doc.dirty) "• " else "") + doc.titleOrApp(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconButton(onClick = actions::undo, enabled = doc.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = doc.undoLabel?.let { "Undo $it" } ?: "Undo")
            }
            IconButton(onClick = actions::redo, enabled = doc.canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = doc.redoLabel?.let { "Redo $it" } ?: "Redo")
            }
            IconButton(onClick = state::fitArtboard) { Icon(Icons.Filled.FitScreen, contentDescription = "Fit Artboard in Window") }
            IconButton(onClick = { onPanel(Panel.Properties) }) { Icon(Icons.Filled.Tune, contentDescription = "Properties") }
            IconButton(onClick = { onPanel(Panel.Layers) }) { Icon(Icons.Filled.Layers, contentDescription = "Layers") }
            IconButton(onClick = { onPanel(Panel.Pen) }) { Icon(Icons.Filled.Edit, contentDescription = "Pen and touch") }
            if (compact) OverflowMenu(state, actions)
        }
    }
}

/** Phones: the menu bar folded into one overflow menu (File, Edit, Object… as submenus). */
@Composable
private fun OverflowMenu(state: EditorState, actions: EditorActions) {
    var open by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
        DropdownMenu(open, onDismissRequest = { open = false; sub = null }) {
            val tree = menuTree(state.commands)
            val current = sub
            if (current == null) {
                FileItems(actions) { open = false }
                HorizontalDivider()
                for (top in tree.keys) {
                    DropdownMenuItem(text = { Text("$top  ›") }, onClick = { sub = top })
                }
            } else {
                DropdownMenuItem(text = { Text("‹  $current") }, onClick = { sub = null })
                HorizontalDivider()
                for (c in tree[current].orEmpty()) {
                    CommandItem(state, c) { open = false; sub = null }
                }
            }
        }
    }
}

@Composable
private fun FileItems(actions: EditorActions, close: () -> Unit) {
    DropdownMenuItem(text = { Text("New") }, onClick = { close(); actions.new() })
    DropdownMenuItem(text = { Text("Open…") }, onClick = { close(); actions.open() })
    DropdownMenuItem(text = { Text("Save") }, onClick = { close(); actions.save() })
    DropdownMenuItem(text = { Text("Export SVG…") }, onClick = { close(); actions.export("svg", "image/svg+xml") })
    DropdownMenuItem(text = { Text("Export PDF…") }, onClick = { close(); actions.export("pdf", "application/pdf") })
    DropdownMenuItem(text = { Text("Export PNG…") }, onClick = { close(); actions.export("png", "image/png") })
}

/**
 * Commands a menu can run without asking for anything: no parameters, or an optional first one
 * (`{}`, `{index?}`, `{ids?|id? …}`). The others need a dialog the mobile UI doesn't have yet.
 */
private val bareParams = Regex("""^\{\s*(\}|[A-Za-z|]+\?)""")

private fun runsBare(c: CommandInfo): Boolean = bareParams.containsMatchIn(c.params)

@Composable
private fun CommandItem(state: EditorState, c: CommandInfo, close: () -> Unit) {
    val label = c.menu.drop(1).let { if (it.isEmpty()) c.label else (it + c.label).joinToString(" › ") }
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = c.shortcut?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
        enabled = c.enabled && runsBare(c),
        onClick = { close(); state.run(c.id) },
    )
}

/** The engine's commands by top-level menu, in the desktop menu bar's order. */
fun menuTree(commands: List<CommandInfo>): Map<String, List<CommandInfo>> {
    val order = listOf("File", "Edit", "Object", "Type", "Select", "Effect", "View", "Window", "Help")
    return commands
        .filter { it.menu.isNotEmpty() }
        .groupBy { it.menu.first() }
        .toList()
        .sortedBy { (k, _) -> order.indexOf(k).let { if (it < 0) order.size else it } }
        .toMap()
}

/** Tablets in landscape and Chromebooks: the desktop menu bar, built from the command registry. */
@Composable
fun MenuBar(state: EditorState, actions: EditorActions) {
    val tree = menuTree(state.commands)
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (top in tree.keys) {
                var open by remember(top) { mutableStateOf(false) }
                Box {
                    Text(
                        top,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        if (top == "File") {
                            FileItems(actions) { open = false }
                            HorizontalDivider()
                        }
                        for (c in tree[top].orEmpty()) CommandItem(state, c) { open = false }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBar(state: EditorState) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("${state.zoomPercent.toInt()}%", style = MaterialTheme.typography.labelMedium)
            val tool = state.toolGroups.flatMap { it.tools }.firstOrNull { it.id == state.document.tool }
            Text(tool?.label ?: state.document.tool, style = MaterialTheme.typography.labelMedium)
            if (state.document.selectionCount > 0u) {
                Text("${state.document.selectionCount} selected", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            if (state.stylusSeen) Text("Pen", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A dialog a tool asked for (Rectangle tool click: size; Scale tool Alt-click: options…). */
@Composable
fun ToolDialog(state: EditorState, id: String, params: String) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { state.dialog = null },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { state.dialog = null }) { Text("Close") } },
        title = { Text(id) },
        text = {
            Column {
                Text("This dialog is not available on mobile yet.")
                Spacer(Modifier.height(8.dp))
                Text(params.take(400), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
fun Swatch(color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        color = color,
        shape = androidx.compose.foundation.shape.CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.padding(4.dp).size(32.dp).clickable(onClick = onClick),
    ) {}
}

@Composable
fun RowSpacer() = Spacer(Modifier.width(8.dp))
