package com.appthere.vectorcraft

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.appthere.vectorcraft.engine.ToolGroup
import com.appthere.vectorcraft.engine.ToolInfo

/**
 * The icon for a tool. Material Symbols (Apache-2.0, from the Compose icon library) stand in until
 * VectorCraft's own icon set (`assets/icons`) is exported as Compose vectors; tools without a fitting
 * symbol show their initials.
 */
fun toolIcon(id: String): ImageVector? = when (id) {
    "selection" -> Icons.Filled.NearMe
    "directSelection", "groupSelection" -> Icons.Outlined.NearMe
    "magicWand" -> Icons.Filled.AutoFixHigh
    "lasso" -> Icons.Filled.Gesture
    "pen", "addAnchor", "deleteAnchor", "anchorPoint", "curvature" -> Icons.Filled.Draw
    "type", "areaType", "typeOnPath", "verticalType", "verticalAreaType", "verticalTypeOnPath", "touchType" -> Icons.Filled.TextFields
    "lineSegment", "arc", "spiral" -> Icons.Filled.Remove
    "rectangularGrid", "polarGrid", "perspectiveGrid", "perspectiveSelection", "mesh" -> Icons.Filled.GridOn
    "rectangle", "roundedRectangle" -> Icons.Filled.CropSquare
    "ellipse" -> Icons.Filled.RadioButtonUnchecked
    "polygon", "star", "flare" -> Icons.Filled.StarOutline
    "paintbrush", "blobBrush" -> Icons.Filled.Brush
    "shaper", "pencil", "smooth", "join" -> Icons.Filled.Gesture
    "pathEraser", "eraser" -> Icons.Filled.AutoFixNormal
    "scissors", "knife", "mirrorCut", "lineCut", "rectCut" -> Icons.Filled.ContentCut
    "rotate" -> Icons.AutoMirrored.Filled.RotateRight
    "reflect" -> Icons.Filled.Flip
    "scale", "shear", "reshape" -> Icons.Filled.OpenInFull
    "width", "warp", "twirl", "pucker", "bloat", "scallop", "crystallize", "wrinkle" -> Icons.Filled.Waves
    "freeTransform", "puppetWarp" -> Icons.Filled.Transform
    "shapeBuilder", "livePaintBucket", "livePaintSelection" -> Icons.Filled.FormatPaint
    "gradient" -> Icons.Filled.Gradient
    "eyedropper", "measure" -> if (id == "measure") Icons.Filled.Straighten else Icons.Filled.Colorize
    "artboard", "slice", "sliceSelection", "printTiling" -> Icons.Filled.Dashboard
    "hand", "rotateView" -> Icons.Filled.PanTool
    "zoom" -> Icons.Filled.ZoomIn
    else -> if (id.endsWith("Graph")) Icons.Filled.BarChart else null
}

private fun initials(label: String): String =
    label.removeSuffix(" Tool").split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }

/** The tool a group's button shows: the active one if it is in the group, else the first. */
private fun ToolGroup.shown(active: String): ToolInfo? = tools.firstOrNull { it.id == active } ?: tools.firstOrNull()

@Composable
private fun ToolGlyph(tool: ToolInfo) {
    val icon = toolIcon(tool.id)
    if (icon != null) {
        Icon(icon, contentDescription = null, Modifier.size(22.dp))
    } else {
        Text(initials(tool.label), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * One toolbar button for a group of tools: tap chooses the shown tool, long-press (or a pen's
 * barrel-button press, which Android reports as a long press) opens the group's other tools.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolButton(state: EditorState, group: ToolGroup) {
    val active = state.document.tool
    val tool = group.shown(active) ?: return
    var open by remember { mutableStateOf(false) }
    val selected = group.tools.any { it.id == active }
    Box {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(2.dp)
                .size(44.dp)
                .semantics { contentDescription = tool.label }
                .combinedClickable(
                    onClick = { state.selectTool(tool.id) },
                    onLongClick = { if (group.tools.size > 1) open = true },
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                ToolGlyph(tool)
                if (group.tools.size > 1) {
                    // The corner mark that says the button holds more tools.
                    Text("◢", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                }
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (t in group.tools) {
                DropdownMenuItem(
                    text = { Text(t.label + (t.shortcut?.let { "  ($it)" } ?: "")) },
                    leadingIcon = { ToolGlyph(t) },
                    onClick = {
                        open = false
                        state.selectTool(t.id)
                    },
                )
            }
        }
    }
}

/** Phones: every tool group in one scrolling row above the bottom edge (in reach of the thumb). */
@Composable
fun ToolStrip(state: EditorState, modifier: Modifier = Modifier) {
    Surface(modifier, tonalElevation = 2.dp) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp)) {
            for (g in state.toolGroups) ToolButton(state, g)
        }
    }
}

/** Tablets and laptops: the tool groups in a scrolling column on the left, as on the desktop. */
@Composable
fun ToolRail(state: EditorState, modifier: Modifier = Modifier) {
    Surface(modifier, tonalElevation = 1.dp) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            for (g in state.toolGroups) ToolButton(state, g)
        }
    }
}
