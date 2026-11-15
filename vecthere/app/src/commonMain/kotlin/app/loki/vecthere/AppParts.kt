package app.loki.vecthere

// VecThere's own parts of the art workspace (first written by egui2compose, now the app's): the
// engine's tool palette (`ui.tool.list`) picked through `tool.select`, the fill and stroke swatches
// under it, and the Properties, Layers, Artboards and History panels, all read from
// `document.inspect` and acting through engine commands.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.loki.kit.ChoiceChip
import app.loki.kit.DockPanel
import app.loki.kit.NumberChip
import app.loki.kit.EngineClient
import app.loki.kit.RailTool
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowDownToLine
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ArrowUpToLine
import com.composables.icons.lucide.Blend
import com.composables.icons.lucide.CopyPlus
import com.composables.icons.lucide.Group
import com.composables.icons.lucide.LockOpen
import com.composables.icons.lucide.SquarePlus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Ungroup
import com.composables.icons.lucide.Brush
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Combine
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.FlipHorizontal2
import com.composables.icons.lucide.Frame
import com.composables.icons.lucide.Grid3x3
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.Hexagon
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Italic
import com.composables.icons.lucide.Lasso
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Merge
import com.composables.icons.lucide.MousePointer2
import com.composables.icons.lucide.MousePointerClick
import com.composables.icons.lucide.Move
import com.composables.icons.lucide.MoveHorizontal
import com.composables.icons.lucide.PaintBucket
import com.composables.icons.lucide.PenTool
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.PersonStanding
import com.composables.icons.lucide.Pipette
import com.composables.icons.lucide.Printer
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Ruler
import com.composables.icons.lucide.Scaling
import com.composables.icons.lucide.Scissors
import com.composables.icons.lucide.Shapes
import com.composables.icons.lucide.Slash
import com.composables.icons.lucide.Slice
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Spline
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquareRoundCorner
import com.composables.icons.lucide.Stamp
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Wand
import com.composables.icons.lucide.Waves
import com.composables.icons.lucide.ZoomIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

val STATE_COMMANDS: List<String> = listOf("document.inspect", "transparency.info")

/** Commands this file runs, so the coverage test checks the engine still has them. */
val PANEL_COMMANDS: Set<String> = setOf("tool.select", "layer.setCurrent", "layer.setProps", "file.open", "file.save", "file.export", "text.create", "text.setText", "text.getRange",
    // The options bar, the colour wells and the panels' own controls.
    "paint.setFill", "paint.setStroke", "paint.swap", "paint.default", "stroke.set", "transparency.set", "transparency.info", "select.set", "layer.new", "layer.delete",
    "edit.clear", "edit.duplicate", "object.group", "object.ungroup", "object.arrange.bringForward", "object.arrange.sendBackward", "object.arrange.bringToFront", "object.arrange.sendToBack")

val FIRST_PANEL: String? = "properties"

/** As on the desktop: Properties with Layers, then Artboards, then History. */
val PANEL_GROUPS: List<List<String>> = listOf(listOf("properties", "layers"), listOf("artboards"), listOf("history"))

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.str(key: String): String? = (obj()?.get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.num(key: String): Double? = (obj()?.get(key) as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.long(key: String): Long? = (obj()?.get(key) as? JsonPrimitive)?.longOrNull
private fun JsonElement?.bool(key: String): Boolean? = (obj()?.get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.arr(key: String): List<JsonElement> = (obj()?.get(key) as? JsonArray).orEmpty()

private fun doc(state: JsonElement?) = state.obj()?.get("document.inspect")

fun documentTitle(state: JsonElement?): String = doc(state).str("title") ?: APP_NAME

fun documentDirty(state: JsonElement?): Boolean = doc(state).bool("dirty") == true

private fun unitAbbrev(u: String?) = when (u?.lowercase()) {
    "points" -> "pt"; "pixels" -> "px"; "inches" -> "in"; "millimeters" -> "mm"; "centimeters" -> "cm"; "picas" -> "pc"; else -> u.orEmpty()
}

private fun fmt(v: Double?): String = when {
    v == null -> "?"
    v == kotlin.math.floor(v) -> v.toLong().toString()
    else -> ((v * 100).toLong() / 100.0).toString()
}

fun statusSegments(state: JsonElement?): List<String> {
    val d = doc(state) ?: return listOf("No document")
    val ab = d.arr("artboards")
    val sel = d.arr("selection").size
    return listOfNotNull(
        ab.firstOrNull()?.let { "${it.str("name") ?: "Artboard"} · ${fmt(it.num("width"))} × ${fmt(it.num("height"))} ${unitAbbrev(d.str("units"))}" },
        if (ab.size > 1) "${ab.size} artboards" else null,
        d.str("colorMode")?.uppercase(),
        d.long("objects")?.let { if (it == 1L) "1 object" else "$it objects" },
        if (sel > 0) "$sel selected" else null,
    )
}

/** The engine's tool glyph names to Lucide icons. Unknown names fall back to the slot's first letters. */
private fun glyph(icon: String): ImageVector? {
    val n = icon.removePrefix("tool-").removePrefix("dc-")
    return when {
        n == "selection" -> Lucide.MousePointer2
        n == "direct" || n == "group-select" || n == "live-select" || n == "perspective-select" -> Lucide.MousePointerClick
        n == "magic-wand" -> Lucide.Wand
        n == "lasso" -> Lucide.Lasso
        n.startsWith("pen") || n == "anchor" -> Lucide.PenTool
        n == "curvature" || n == "reshape" || n == "objects-on-path" || n == "spline-pointer" -> Lucide.Spline
        n.startsWith("type") || n == "touch-type" -> Lucide.Type
        n == "line" || n == "arc" -> Lucide.Slash
        n == "rect" || n == "box" -> Lucide.Square
        n == "rounded-rect" -> Lucide.SquareRoundCorner
        n == "ellipse" -> Lucide.Circle
        n == "polygon" || n == "hexagon" -> Lucide.Hexagon
        n == "star" -> Lucide.Star
        n == "flare" -> Lucide.Sparkles
        n == "brush" || n == "blob-brush" -> Lucide.Brush
        n == "shaper" -> Lucide.Shapes
        n == "pencil" -> Lucide.Pencil
        n == "smooth" || n in setOf("warp", "twirl", "pucker", "bloat", "scallop", "crystallize", "wrinkle") -> Lucide.Waves
        n.contains("eraser") || n == "remove-brush" -> Lucide.Eraser
        n == "scissors" || n.endsWith("-cut") -> Lucide.Scissors
        n == "knife" || n == "slice" -> Lucide.Slice
        n == "rotate" -> Lucide.RotateCw
        n == "reflect" -> Lucide.FlipHorizontal2
        n == "scale" -> Lucide.Scaling
        n == "shear" -> Lucide.Italic
        n == "width" -> Lucide.MoveHorizontal
        n == "free-transform" -> Lucide.Move
        n == "puppet" -> Lucide.PersonStanding
        n == "shape-builder" -> Lucide.Combine
        n == "bucket" -> Lucide.PaintBucket
        n == "perspective" || n == "mesh" || n.endsWith("grid") -> Lucide.Grid3x3
        n == "gradient" || n == "blend" -> Lucide.Blend
        n == "eyedropper" -> Lucide.Pipette
        n == "measure" || n == "ruler" || n == "dimension" -> Lucide.Ruler
        n == "symbol" -> Lucide.Stamp
        n == "graph" -> Lucide.ChartColumn
        n == "artboard" -> Lucide.Frame
        n == "print-tiling" -> Lucide.Printer
        n == "hand" -> Lucide.Hand
        n == "rotate-view" -> Lucide.RotateCcw
        n == "zoom" -> Lucide.ZoomIn
        n == "join" -> Lucide.Merge
        else -> null
    }
}

private fun railTool(t: JsonElement): RailTool? {
    val id = t.str("id") ?: return null
    return RailTool(id, t.str("label") ?: id, t.str("shortcut").orEmpty(), glyph(t.str("icon").orEmpty()))
}

/** The engine's own tool palette: groups of tools, the first of each in its slot. */
fun loadTools(engine: EngineClient): List<RailTool> =
    (runCatching { engine.call("ui.tool.list") }.getOrNull() as? JsonArray).orEmpty().mapNotNull { g ->
        val tools = (g as? JsonArray).orEmpty().mapNotNull(::railTool)
        tools.firstOrNull()?.let { RailTool(it.id, it.label, it.shortcut, it.icon, tools.drop(1)) }
    }

fun toolCommand(id: String): Pair<String, JsonObject>? = "tool.select" to buildJsonObject { put("tool", id) }

/** The engine keeps the tool in hand: `document.inspect` names it. */
fun toolInHand(state: JsonElement?): String? = doc(state).str("tool")

private val HEX = Regex("#[0-9a-fA-F]{6}")

/**
 * The colour of a paint as `document.inspect` labels it: `#rrggbb`, a swatch's "Name (#rrggbb)",
 * "None" (null), or a gradient or pattern (a mid grey stands in for it on a swatch).
 */
private fun paintColour(label: String?): Color? = when {
    label == null || label == "None" -> null
    else -> HEX.find(label)?.value?.let { app.loki.kit.parseHex(it) } ?: Color(0xFF808080)
}

private val NONE = JsonObject(emptyMap())

/**
 * The fill swatch over the stroke one: a tap on either opens the colour picker for it (with None).
 * The colour goes to the selected art and to what is drawn next, as the engine's fill and stroke do.
 * [withButtons] adds default and swap.
 */
@Composable
private fun PaintWells(state: JsonElement?, run: (String, JsonObject) -> Unit, well: androidx.compose.ui.unit.Dp, withButtons: Boolean) {
    val paint = doc(state).obj()?.get("paint")?.obj() ?: return
    val fill = paintColour(paint.str("fill")); val stroke = paintColour(paint.str("stroke"))
    // Which paint the picker is open for: true the fill, false the stroke, null neither.
    var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    picking?.let { isFill ->
        app.loki.kit.ColorPickerDialog(
            if (isFill) "Fill" else "Stroke", if (isFill) fill else stroke, allowNone = true,
            onPick = { c ->
                picking = null
                run(if (isFill) "paint.setFill" else "paint.setStroke", buildJsonObject { if (c == null) put("none", true) else put("color", app.loki.kit.hexOf(c)) })
            },
            onDismiss = { picking = null },
        )
    }
    val step = well * 0.55f
    Box(Modifier.size(well + step)) {
        app.loki.kit.ColorWell(stroke, "Stroke: ${paint.str("stroke") ?: "None"}. Tap to change", Modifier.offset(step, step).size(well)) { picking = false }
        app.loki.kit.ColorWell(fill, "Fill: ${paint.str("fill") ?: "None"}. Tap to change", Modifier.size(well)) { picking = true }
    }
    if (withButtons) {
        val size = if (app.loki.kit.isTouchPlatform) 36.dp else 24.dp
        IconButton(onClick = { run("paint.default", NONE) }, modifier = Modifier.size(size)) { Icon(Lucide.RotateCcw, contentDescription = "Default fill and stroke", modifier = Modifier.size(14.dp)) }
        IconButton(onClick = { run("paint.swap", NONE) }, modifier = Modifier.size(size)) { Icon(Lucide.ArrowLeftRight, contentDescription = "Swap fill and stroke", modifier = Modifier.size(14.dp)) }
    }
}

private val CAPS = listOf("butt" to "Butt", "round" to "Round", "square" to "Square")
private val JOINS = listOf("miter" to "Miter", "round" to "Round", "bevel" to "Bevel")

/**
 * The options bar: the tool in hand, then the fill, the stroke and the stroke's weight that the
 * selected art has and new art gets, and the selection's opacity (it scrolls sideways where the
 * window is narrow).
 */
@Composable
fun OptionsBar(tool: String, tools: List<RailTool>, state: JsonElement?, run: (String, JsonObject) -> Unit) {
    val t = tools.flatMap { listOf(it) + it.alternates }.firstOrNull { it.id == tool }
    val d = doc(state)
    val paint = d.obj()?.get("paint")?.obj()
    Row(
        Modifier.fillMaxWidth().height(if (app.loki.kit.isTouchPlatform) 48.dp else 40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        t?.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) }
        Text(t?.label ?: tool, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (paint != null) {
            VerticalDivider(Modifier.height(24.dp))
            PaintWells(state, run, 22.dp, withButtons = true)
            NumberChip("Stroke", paint.num("strokeWidth"), 0.25, 100.0, { run("stroke.set", buildJsonObject { put("weight", it) }) }, unit = " pt", decimals = 2, log = true)
            ChoiceChip("Cap", null, CAPS, { run("stroke.set", buildJsonObject { put("cap", it) }) })
            ChoiceChip("Corner", null, JOINS, { run("stroke.set", buildJsonObject { put("join", it) }) })
        }
        if (d.arr("selection").isNotEmpty()) {
            val opacity = state.obj()?.get("transparency.info").num("opacity")
            VerticalDivider(Modifier.height(24.dp))
            NumberChip("Opacity", opacity, 0.0, 100.0, { run("transparency.set", buildJsonObject { put("opacity", it) }) }, unit = "%", step = 5.0)
        }
    }
}

/** Beside the tools, always in reach: the fill swatch over the stroke one (a tap on one changes it). */
@Composable
fun ToolFooter(state: JsonElement?, run: (String, JsonObject) -> Unit) {
    Box(Modifier.padding(4.dp)) { PaintWells(state, run, if (app.loki.kit.isTouchPlatform) 28.dp else 24.dp, withButtons = false) }
}

@Composable
private fun PanelLines(lines: List<Pair<String, String>>, modifier: Modifier) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, v) in lines) Row {
            Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
            Text(v, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** What the Properties panel does to the selected art, as buttons: the things reached for most. */
private val SELECTION_ACTIONS: List<Triple<String, String, ImageVector>> = listOf(
    Triple("object.arrange.bringToFront", "Bring to front", Lucide.ArrowUpToLine), Triple("object.arrange.bringForward", "Bring forward", Lucide.ArrowUp),
    Triple("object.arrange.sendBackward", "Send backward", Lucide.ArrowDown), Triple("object.arrange.sendToBack", "Send to back", Lucide.ArrowDownToLine),
    Triple("object.group", "Group", Lucide.Group), Triple("object.ungroup", "Ungroup", Lucide.Ungroup),
    Triple("edit.duplicate", "Duplicate", Lucide.CopyPlus), Triple("edit.clear", "Delete", Lucide.Trash2),
)

fun dockPanels(state: JsonElement?, revision: Int, run: (String, JsonObject) -> Unit): List<DockPanel> {
    val d = doc(state)
    val u = unitAbbrev(d.str("units"))
    return listOf(
        DockPanel("properties", "Properties", Lucide.SlidersHorizontal) { m ->
            val b = d.obj()?.get("selectionBounds")?.obj()
            val paint = d.obj()?.get("paint")?.obj()
            val sel = d.arr("selection").size
            Column(m) {
            if (sel > 0) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp)) {
                for ((id, label, icon) in SELECTION_ACTIONS) IconButton(onClick = { run(id, NONE) }, modifier = Modifier.size(if (app.loki.kit.isTouchPlatform) 44.dp else 36.dp)) {
                    Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
                }
            }
            PanelLines(listOfNotNull(
                "Selection" to if (sel == 0) "Nothing selected" else if (sel == 1) "1 object" else "$sel objects",
                b?.let { "X / Y" to "${fmt(it.num("x"))} / ${fmt(it.num("y"))} $u" },
                b?.let { "W / H" to "${fmt(it.num("width"))} × ${fmt(it.num("height"))} $u" },
                paint?.let { "Fill" to (it.str("fill") ?: "None") },
                paint?.let { "Stroke" to "${it.str("stroke") ?: "None"}, ${fmt(it.num("strokeWidth"))} pt" },
                "Color mode" to (d.str("colorMode")?.uppercase() ?: ""),
                "Units" to (d.str("units") ?: ""),
            ), Modifier)
            }
        },
        DockPanel("layers", "Layers", Lucide.Layers) { m -> LayersPanel(d, run, m) },
        DockPanel("artboards", "Artboards", Lucide.Frame) { m ->
            PanelLines(d.arr("artboards").mapIndexed { i, a -> "${i + 1}  ${a.str("name") ?: "Artboard"}" to "${fmt(a.num("width"))} × ${fmt(a.num("height"))} $u" }, m)
        },
        DockPanel("history", "History", Lucide.History) { m ->
            val steps = listOf("Open") + d.arr("history").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            LazyColumn(m) {
                itemsIndexed(steps) { i, step ->
                    Text(step, style = MaterialTheme.typography.bodyMedium, fontWeight = if (i == steps.lastIndex) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        },
    )
}

/** One row of the Layers panel: a layer, or an object inside it (indented). */
private class LayerRow(val id: Long, val name: String, val kind: String, val visible: Boolean, val locked: Boolean, val depth: Int, val selected: Boolean)

private fun rows(d: JsonElement?): List<LayerRow> {
    val selection = d.arr("selection").mapNotNull { (it as? JsonPrimitive)?.longOrNull }.toSet()
    val current = d.long("currentLayer")
    val out = mutableListOf<LayerRow>()
    for (l in d.arr("layers")) {
        val id = l.long("id") ?: continue
        out += LayerRow(id, l.str("name") ?: "Layer", "Layer", l.bool("visible") != false, l.bool("locked") == true, 0, id == current)
        // Topmost art first, as the Layers panel lists it; at most a screenful per layer.
        for (c in l.arr("children").asReversed().take(200)) {
            val cid = c.long("id") ?: continue
            out += LayerRow(cid, c.str("name") ?: "<Object>", c.str("kind") ?: "", c.bool("visible") != false, c.bool("locked") == true, 1, cid in selection)
        }
    }
    return out
}

/** Layers and their objects: the eye shows or hides one, the lock locks it; a tap makes a layer current or selects an object. New layer and delete below. */
@Composable
private fun LayersPanel(d: JsonElement?, run: (String, JsonObject) -> Unit, modifier: Modifier) {
    val hasSelection = d.arr("selection").isNotEmpty()
    Column(modifier) {
        LazyColumn(Modifier.weight(1f)) {
            items(rows(d)) { r ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).background(if (r.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable {
                            if (r.depth == 0) run("layer.setCurrent", buildJsonObject { put("id", r.id) })
                            else run("select.set", buildJsonObject { put("ids", JsonArray(listOf(JsonPrimitive(r.id)))) })
                        }.padding(start = (4 + r.depth * 20).dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { run("layer.setProps", buildJsonObject { put("id", r.id); put("visible", !r.visible) }) }, modifier = Modifier.size(32.dp)) {
                        Icon(if (r.visible) Lucide.Eye else Lucide.EyeOff, contentDescription = if (r.visible) "Hide" else "Show", modifier = Modifier.size(16.dp))
                    }
                    Text(r.name, style = if (r.depth == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { run("layer.setProps", buildJsonObject { put("id", r.id); put("locked", !r.locked) }) }, modifier = Modifier.size(32.dp)) {
                        Icon(if (r.locked) Lucide.Lock else Lucide.LockOpen, contentDescription = if (r.locked) "Unlock" else "Lock", modifier = Modifier.size(14.dp),
                            tint = if (r.locked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { run("layer.new", NONE) }, modifier = Modifier.size(36.dp)) { Icon(Lucide.SquarePlus, contentDescription = "New layer", modifier = Modifier.size(18.dp)) }
            // With art selected the bin deletes it; otherwise the current layer.
            IconButton(onClick = { run(if (hasSelection) "edit.clear" else "layer.delete", NONE) }, modifier = Modifier.size(36.dp)) {
                Icon(Lucide.Trash2, contentDescription = if (hasSelection) "Delete the selected art" else "Delete the current layer", modifier = Modifier.size(18.dp))
            }
        }
    }
}


// ---- The canvas ---------------------------------------------------------------------------------

/** VectorCraft renders an artboard at any scale (screen pixels per point), so zoom renders crisp up to 16×. */
const val MAX_RENDER_SCALE = 16f
const val RENDER_OVERSAMPLE = 1f

/** The page is the first artboard, in points. */
fun pageRect(state: JsonElement?): androidx.compose.ui.geometry.Rect? {
    val a = doc(state).arr("artboards").firstOrNull() ?: return null
    val x = a.num("x")?.toFloat() ?: 0f; val y = a.num("y")?.toFloat() ?: 0f
    val w = a.num("width")?.toFloat() ?: return null; val h = a.num("height")?.toFloat() ?: return null
    return if (w > 0f && h > 0f) androidx.compose.ui.geometry.Rect(x, y, x + w, y + h) else null
}

fun pageIndex(state: JsonElement?): Int = 0

/** The point-type tools: a tap opens a text field there (the engine's Type tool takes keys, not a soft keyboard's text). */
private val POINT_TYPE = setOf("type", "verticalType")

private fun hexColour(s: String?): Color? {
    val h = s?.removePrefix("#") ?: return null
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) { 6 -> Color(0xFF000000 or v); 8 -> Color(((v and 0xFF) shl 24) or (v ushr 8)); else -> null }
}

/** The text object under (x, y) among the document's art, with its id: what a tap of the Type tool edits. */
private fun typeAt(d: JsonElement?, x: Double, y: Double): Long? {
    var hit: Long? = null
    fun walk(n: JsonElement) {
        if (n.str("kind") == "Type") n.obj()?.get("bounds")?.obj()?.let { b ->
            val bx = b.num("x") ?: return@let; val by = b.num("y") ?: return@let
            if (x >= bx && y >= by && x <= bx + (b.num("width") ?: 0.0) && y <= by + (b.num("height") ?: 0.0)) hit = n.long("id")
        }
        n.arr("children").forEach(::walk)
    }
    d.arr("layers").forEach(::walk)
    return hit
}

/**
 * The canvas's input, sent to VectorCraft's own tools as the desktop's pointer events would be
 * (`ui.pointer`), with each point's pressure and tilt: the engine's tools do the drawing,
 * selecting, moving and editing, and what they show on the canvas comes back from the engine
 * (`loki.overlay`: the selected art's outlines, anchors and handles; the tool's marquees, rubber
 * bands, guides and labels). Events go in order, a batch at a time, so a fast pen never waits on the
 * engine. A pen's eraser end borrows the Eraser tool; a tap of the Type tool opens a text field.
 */
class CanvasTools {
    /** Bumped after each streamed batch, to re-render while a drag runs. */
    var live by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var editing by androidx.compose.runtime.mutableStateOf<app.loki.kit.CanvasTextEdit?>(null)
    /** Nothing here marches. */
    val animating = false
    private var overlay by androidx.compose.runtime.mutableStateOf<JsonElement?>(null)
    private val pending = mutableListOf<JsonObject>()
    private var inFlight = false
    private var ending = false
    private var hoverQueued: JsonObject? = null
    private var restoreTool: String? = null
    /** Where a freehand stroke's ink goes first: the display's front buffer where the platform has one (ArtSurface sets it). */
    var ink: app.loki.kit.InkLayer = app.loki.kit.NoInkLayer
    /** True while committed ink waits for the engine's picture of the stroke; [inkCommits] counts strokes, to time a fallback clear. */
    var inkShown = false
    var inkCommits by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private var lastInk: app.loki.kit.CanvasPoint? = null
    private var inking = false

    /** The tools that draw a freehand path, whose ink is worth drawing before the engine has the path. */
    private val freehand = setOf("pencil", "paintbrush", "blobBrush")

    /** The stroke's new pieces into the front buffer, in the colour and width the path will get. */
    private fun inkTo(points: List<app.loki.kit.CanvasPoint>, ws: app.loki.kit.WorkspaceContext, view: app.loki.kit.CanvasView) {
        if (!inking) return
        val paint = doc(ws.state).obj()?.get("paint")?.obj()
        val colour = hexColour(paint.str(if (ws.tool == "blobBrush") "fill" else "stroke")) ?: Color.Black
        val width = ((paint.num("strokeWidth") ?: 1.0) * view.zoom).toFloat().coerceAtLeast(1.5f)
        for (q in points) {
            val p = lastInk ?: q
            val a = view.toScreen(p.x, p.y); val b = view.toScreen(q.x, q.y)
            ink.draw(app.loki.kit.InkSegment(a.x, a.y, b.x, b.y, if (penInk) width * q.pressure.coerceIn(0.3f, 1f) else width, colour))
            lastInk = q
        }
    }
    private var penInk = false

    fun pans(tool: String) = tool == "hand"

    /** The engine measures tolerances in screen pixels through the zoom. */
    fun onZoom(ws: app.loki.kit.WorkspaceContext, zoom: Float) {
        ws.call("loki.viewport", buildJsonObject { put("zoom", zoom.toDouble()) }) {}
    }

    fun onRevision(ws: app.loki.kit.WorkspaceContext, view: app.loki.kit.CanvasView) = readOverlay(ws)

    private fun readOverlay(ws: app.loki.kit.WorkspaceContext) {
        ws.call("loki.overlay", JsonObject(emptyMap())) { overlay = it }
    }

    fun commitText(ws: app.loki.kit.WorkspaceContext, edit: app.loki.kit.CanvasTextEdit, text: String) {
        editing = null
        val id = edit.target.toLongOrNull()
        when {
            id != null -> ws.run("text.setText", buildJsonObject { put("id", id); put("text", text) }) {}
            text.isNotBlank() -> ws.run("text.create", buildJsonObject {
                put("x", edit.x); put("y", edit.y); put("text", text); if (ws.tool == "verticalType") put("vertical", true)
            }) {}
        }
    }

    private fun event(kind: String, p: app.loki.kit.CanvasPoint, i: app.loki.kit.CanvasInput) = buildJsonObject {
        put("kind", kind); put("x", p.x); put("y", p.y)
        if (i.device == app.loki.kit.InputDevice.Pen || i.device == app.loki.kit.InputDevice.Eraser) {
            put("pressure", p.pressure.toDouble())
            p.tiltX?.let { put("tiltX", it.toDouble()) }
            p.tiltY?.let { put("tiltY", it.toDouble()) }
            p.rotation?.let { put("rotation", it.toDouble()) }
        }
        put("mods", buildJsonObject { put("shift", i.mods.shift); put("alt", i.mods.alt); put("cmd", i.mods.command) })
    }

    private fun flush(ws: app.loki.kit.WorkspaceContext) {
        if (inFlight) return
        val batch = if (pending.isNotEmpty()) pending.toList().also { pending.clear() } else hoverQueued?.let { listOf(it) }.also { hoverQueued = null } ?: return
        inFlight = true
        ws.call("ui.pointer", buildJsonObject { put("events", JsonArray(batch)) }) {
            inFlight = false
            if (batch.any { (it["kind"] as? JsonPrimitive)?.contentOrNull != "move" }) live++
            // What the tool now shows (a rubber band, a hover highlight, the new selection's handles).
            readOverlay(ws)
            if (pending.isEmpty() && ending) {
                ending = false
                // The gesture is over: give a borrowed tool back, then read the document again.
                restoreTool?.let { t -> restoreTool = null; ws.run("tool.select", buildJsonObject { put("tool", t) }) {} } ?: ws.refresh()
            }
            flush(ws)
        }
    }

    fun onInput(i: app.loki.kit.CanvasInput, ws: app.loki.kit.WorkspaceContext, view: app.loki.kit.CanvasView) {
        val p = i.points.lastOrNull() ?: return
        if (ws.tool in POINT_TYPE && i.device != app.loki.kit.InputDevice.Eraser) {
            if (i.phase == app.loki.kit.CanvasPhase.Up) {
                val hit = typeAt(doc(ws.state), p.x, p.y)
                if (hit == null) editing = app.loki.kit.CanvasTextEdit(p.x, p.y, "")
                else ws.call("engine.execute", buildJsonObject { put("command", "text.getRange"); put("params", buildJsonObject { put("id", hit) }) }) { r ->
                    val text = r.arr("runs").joinToString("") { it.str("text").orEmpty() }
                    editing = app.loki.kit.CanvasTextEdit(p.x, p.y, text, hit.toString())
                }
            }
            return
        }
        when (i.phase) {
            app.loki.kit.CanvasPhase.Hover -> if (pending.isEmpty()) { hoverQueued = event("move", p, i); flush(ws) }
            app.loki.kit.CanvasPhase.Down -> {
                if (i.device == app.loki.kit.InputDevice.Eraser && ws.tool != "eraser") {
                    restoreTool = ws.tool
                    ws.call("engine.execute", buildJsonObject { put("command", "tool.select"); put("params", buildJsonObject { put("tool", "eraser") }) }) {}
                }
                hoverQueued = null
                inking = ink.active && restoreTool == null && ws.tool in freehand
                penInk = i.device == app.loki.kit.InputDevice.Pen
                lastInk = null; inkTo(i.points, ws, view)
                pending += event("down", p, i); flush(ws)
            }
            app.loki.kit.CanvasPhase.Drag -> { inkTo(i.points, ws, view); i.points.forEach { pending += event("drag", it, i) }; flush(ws) }
            app.loki.kit.CanvasPhase.Up -> {
                if (inking) { inkTo(i.points, ws, view); ink.commit(); inkShown = true; inkCommits++; inking = false }
                pending += event("up", p, i); ending = true; flush(ws)
            }
            app.loki.kit.CanvasPhase.Cancel -> {
                // Two fingers took over a one-finger drag: lift it where it is, so nothing is left half done.
                if (inking) { ink.cancel(); inking = false }
                pending += event("up", p, i); ending = true; flush(ws)
            }
        }
    }

    /** The engine's overlay, drawn in screen space as the desktop draws it: selection marks, then the tool's feedback. */
    fun androidx.compose.ui.graphics.drawscope.DrawScope.draw(view: app.loki.kit.CanvasView, ws: app.loki.kit.WorkspaceContext?, phase: Float) {
        val o = overlay ?: return
        val accent = Color(0xFF4C9AFF)
        val map = { x: Double, y: Double -> view.toScreen(x, y) }
        fun pt(e: JsonElement?): androidx.compose.ui.geometry.Offset? {
            val a = e as? JsonArray ?: return null
            val x = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
            val y = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
            return view.toScreen(x, y)
        }
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
        fun square(c: androidx.compose.ui.geometry.Offset, size: Float, colour: Color, filled: Boolean) {
            val tl = c - androidx.compose.ui.geometry.Offset(size / 2, size / 2); val sz = androidx.compose.ui.geometry.Size(size, size)
            drawRect(if (filled) colour else Color.White, tl, sz)
            drawRect(colour, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        }
        // The selected art: outline, anchors (filled when selected), handles under Direct Selection.
        for (sel in o.arr("selection")) {
            sel.str("d")?.let { drawPath(app.loki.kit.svgPath(it, map), accent, style = androidx.compose.ui.graphics.drawscope.Stroke(1f)) }
            for (h in sel.arr("handles")) {
                val a = pt(h.obj()?.get("a")) ?: continue; val b = pt(h.obj()?.get("b")) ?: continue
                drawLine(accent, a, b, strokeWidth = 1f); drawCircle(accent, 3f, b)
            }
            for (a in sel.arr("anchors")) pt(a.obj()?.get("p"))?.let { square(it, 6f, accent, a.bool("selected") == true) }
        }
        // The tool's own feedback.
        for (t in o.arr("tool")) {
            val colour = hexColour(t.str("color")) ?: accent
            when (t.str("kind")) {
                "marquee" -> (t.obj()?.get("rect") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 4 }?.let { r ->
                    val a = view.toScreen(r[0], r[1]); val b = view.toScreen(r[2], r[3])
                    drawRect(accent, a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y), style = androidx.compose.ui.graphics.drawscope.Stroke(1f, pathEffect = dash))
                }
                "path" -> t.str("d")?.let { d ->
                    drawPath(app.loki.kit.svgPath(d, map), colour, style = androidx.compose.ui.graphics.drawscope.Stroke((t.num("width") ?: 1.0).toFloat(), pathEffect = if (t.bool("dashed") == true) dash else null))
                }
                "line", "gridLine" -> { val a = pt(t.obj()?.get("a")); val b = pt(t.obj()?.get("b")); if (a != null && b != null) drawLine(colour, a, b, strokeWidth = 1f, pathEffect = if (t.bool("dashed") == true) dash else null) }
                "anchor" -> pt(t.obj()?.get("p"))?.let { square(it, (t.num("size") ?: 6.0).toFloat(), colour, t.bool("filled") == true) }
                "curvePoint" -> pt(t.obj()?.get("p"))?.let { c -> val r = (t.num("size") ?: 6.0).toFloat() / 2; drawCircle(if (t.bool("filled") == true) colour else Color.White, r, c); drawCircle(colour, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f)) }
                "handle" -> pt(t.obj()?.get("p"))?.let { drawCircle(colour, 3f, it) }
                "highlight" -> (t.obj()?.get("quad") as? JsonArray)?.mapNotNull { pt(it) }?.takeIf { it.size == 4 }?.let { q ->
                    val path = androidx.compose.ui.graphics.Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                    drawPath(path, colour)
                }
                "swatch" -> pt(t.obj()?.get("p"))?.let { c -> drawCircle(colour, 6f, c); if (t.bool("selected") == true) drawCircle(accent, 8f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f)) }
                "distance" -> { val a = pt(t.obj()?.get("a")); val b = pt(t.obj()?.get("b")); if (a != null && b != null) drawLine(colour, a, b, strokeWidth = 1f) }
                // Labels and measurement pills need text drawing; their geometry is enough to place them later.
                else -> {}
            }
        }
    }
}

// ---- File actions -------------------------------------------------------------------------------

// The engine's Open, Save and Export are on no menu (the desktop's File menu runs them through its
// own dialogs), so the app puts them in File itself.
val APP_ACTIONS: List<app.loki.kit.RibbonItem> = listOf(
    app.loki.kit.RibbonItem("app.open", "Open…", ""),
    app.loki.kit.RibbonItem("app.save", "Save", ""),
    app.loki.kit.RibbonItem("app.saveAs", "Save As…", ""),
    app.loki.kit.RibbonItem("app.exportPng", "Export as PNG…", ""),
    app.loki.kit.RibbonItem("app.exportSvg", "Export as SVG…", ""),
)
val APP_ACTION_IDS: Set<String> = APP_ACTIONS.map { it.id }.toSet()

fun withAppActions(tabs: List<app.loki.kit.RibbonTab>): List<app.loki.kit.RibbonTab> = app.loki.kit.addToFileMenu(tabs, APP_ACTIONS)

private fun baseName(state: JsonElement?) = (doc(state).str("title") ?: "Untitled").substringBeforeLast('.').ifBlank { "Untitled" }

suspend fun appAction(id: String, state: JsonElement?, io: app.loki.kit.FileIo) {
    val path = doc(state).str("path")
    suspend fun exec(command: String, params: JsonObject) = io.call("engine.execute", buildJsonObject { put("command", command); put("params", params) })
    suspend fun saveTo(p: String, command: String, params: JsonObject) {
        if (exec(command, params) != null) { io.publish(p); io.refresh(); io.status("Saved ${p.substringAfterLast('/')}") }
    }
    when (id) {
        "app.open" -> {
            val p = io.pick(false, "") ?: return
            if (exec("file.open", buildJsonObject { put("path", p) }) != null) io.refresh()
        }
        "app.save" -> if (path != null) saveTo(path, "file.save", JsonObject(emptyMap())) else appAction("app.saveAs", state, io)
        "app.saveAs" -> {
            val p = io.pick(true, "${baseName(state)}.vectorcraft") ?: return
            saveTo(p, "file.save", buildJsonObject { put("path", p) })
        }
        "app.exportPng", "app.exportSvg" -> {
            val ext = if (id == "app.exportPng") "png" else "svg"
            val p = io.pick(true, "${baseName(state)}.$ext") ?: return
            saveTo(p, "file.export", buildJsonObject { put("path", p) })
        }
    }
}
