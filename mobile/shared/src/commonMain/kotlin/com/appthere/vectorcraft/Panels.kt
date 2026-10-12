@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.appthere.vectorcraft

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.appthere.vectorcraft.engine.TouchMode

/** The side panel (docked on big screens, a bottom sheet on small ones), with its tabs. */
@Composable
fun PanelContent(state: EditorState, panel: Panel, onSelect: (Panel) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = panel.ordinal) {
            Panel.entries.forEach { p ->
                Tab(selected = p == panel, onClick = { onSelect(p) }, text = { Text(p.title) })
            }
        }
        when (panel) {
            Panel.Properties -> PropertiesPanel(state)
            Panel.Layers -> LayersPanel(state)
            Panel.Pen -> PenPanel(state)
        }
    }
}

val Panel.title: String
    get() = when (this) {
        Panel.Properties -> "Properties"
        Panel.Layers -> "Layers"
        Panel.Pen -> "Pen & Touch"
    }

/** A small palette for quick fills and strokes until the Color and Swatches panels come over. */
private val QUICK_COLORS = listOf(
    "#000000", "#ffffff", "#e53935", "#fb8c00", "#fdd835", "#43a047", "#1e88e5", "#8e24aa", "#6d4c41", "#9e9e9e",
)

private fun hexColor(hex: String): Color = Color(("ff" + hex.removePrefix("#")).toLong(16).toInt())

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropertiesPanel(state: EditorState) {
    val doc = state.document
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        SectionTitle(if (doc.selectionCount == 0u) "No selection" else "${doc.selectionCount} selected")
        SectionTitle("Fill")
        FlowRow(Modifier.padding(horizontal = 12.dp)) {
            for (c in QUICK_COLORS) Swatch(hexColor(c)) { state.run("paint.setFill", """{"color":"$c"}""") }
            AssistChip(onClick = { state.run("paint.setFill", """{"none":true}""") }, label = { Text("None") })
        }
        SectionTitle("Stroke")
        FlowRow(Modifier.padding(horizontal = 12.dp)) {
            for (c in QUICK_COLORS) Swatch(hexColor(c)) { state.run("paint.setStroke", """{"color":"$c"}""") }
            AssistChip(onClick = { state.run("paint.setStroke", """{"none":true}""") }, label = { Text("None") })
        }
        SectionTitle("Stroke weight")
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in listOf(0.5, 1.0, 2.0, 4.0, 8.0)) {
                AssistChip(onClick = { state.run("stroke.set", """{"weight":$w}""") }, label = { Text("$w pt") })
            }
        }
        SectionTitle("Arrange")
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CommandChip(state, "object.group", "Group")
            CommandChip(state, "object.ungroup", "Ungroup")
            CommandChip(state, "edit.duplicate", "Duplicate")
            CommandChip(state, "edit.clear", "Delete")
            CommandChip(state, "select.all", "Select All")
            CommandChip(state, "select.none", "Deselect")
        }
    }
}

@Composable
private fun CommandChip(state: EditorState, id: String, label: String) {
    AssistChip(onClick = { state.run(id) }, label = { Text(label) }, enabled = state.commandEnabled(id))
}

@Composable
private fun LayersPanel(state: EditorState) {
    // Re-read on every document revision.
    val rows = remember(state.document.revision, state.document.selectionCount) { state.engine.layers(1u) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
        items(rows, key = { it.id.toLong() }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (row.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable {
                        if (row.isLayer) {
                            state.run("layer.setCurrent", """{"id":${row.id}}""")
                        } else {
                            state.run("select.set", """{"ids":[${row.id}]}""")
                        }
                    }
                    .padding(start = (8 + 16 * row.depth.toInt()).dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(width = 4.dp, height = 28.dp).background(Color(row.color.toInt())))
                IconButton(onClick = { state.run("layer.setProps", """{"id":${row.id},"visible":${!row.visible}}""") }) {
                    Icon(if (row.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = if (row.visible) "Hide" else "Show")
                }
                IconButton(onClick = { state.run("layer.setProps", """{"id":${row.id},"locked":${!row.locked}}""") }) {
                    Icon(if (row.locked) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = if (row.locked) "Unlock" else "Lock")
                }
                Text(
                    row.name,
                    style = if (row.isLayer) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { state.run("layer.new") }, label = { Text("New Layer") })
        CommandChip(state, "layer.delete", "Delete Layer")
    }
}

/** What a finger, the pen's eraser end and its barrel button do (S Pen, USI, Apple Pencil). */
@Composable
private fun PenPanel(state: EditorState) {
    val p = state.inputPrefs
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        SectionTitle("Fingers")
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((mode, label) in listOf(TouchMode.AUTO to "Auto", TouchMode.DRAW to "Draw", TouchMode.NAVIGATE to "Pan & zoom")) {
                FilterChip(selected = p.touchMode == mode, onClick = { state.updateInputPrefs { it.copy(touchMode = mode) } }, label = { Text(label) })
            }
        }
        Text(
            if (state.stylusSeen) "Pen detected: in Auto, fingers pan and zoom and a resting palm never draws."
            else "Auto: fingers draw until a pen touches the screen, then they pan and zoom.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SettingSwitch("Two-finger tap undoes, three-finger tap redoes", p.tapUndo) { v -> state.updateInputPrefs { it.copy(tapUndo = v) } }
        SettingSwitch("Two fingers rotate the view", p.rotateGesture) { v -> state.updateInputPrefs { it.copy(rotateGesture = v) } }
        SectionTitle("Pen barrel button (S Pen side button, USI)")
        ToolChoice(state, p.stylusButtonTool) { v -> state.updateInputPrefs { it.copy(stylusButtonTool = v) } }
        SectionTitle("Pen eraser end (USI and Wacom-style pens)")
        ToolChoice(state, p.eraserTool) { v -> state.updateInputPrefs { it.copy(eraserTool = v) } }
        SectionTitle("View")
        val o = state.viewOptions
        SettingSwitch("Smart guides", o.smartGuides) { v -> state.updateViewOptions { it.copy(smartGuides = v) } }
        SettingSwitch("Snap to grid", o.snapToGrid) { v -> state.updateViewOptions { it.copy(snapToGrid = v) } }
        SettingSwitch("Outline", o.outline) { v -> state.updateViewOptions { it.copy(outline = v) } }
    }
}

private val BUTTON_TOOLS = listOf("" to "Nothing", "eraser" to "Eraser", "hand" to "Hand", "eyedropper" to "Eyedropper", "directSelection" to "Direct Selection", "selection" to "Selection")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolChoice(state: EditorState, current: String, onPick: (String) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((id, label) in BUTTON_TOOLS) {
            FilterChip(selected = current == id, onClick = { onPick(id) }, label = { Text(label) })
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
