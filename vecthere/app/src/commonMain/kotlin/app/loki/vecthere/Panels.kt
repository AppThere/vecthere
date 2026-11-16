package app.loki.vecthere

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.loki.kit.ChoiceChip
import app.loki.kit.NumberChip
import app.loki.kit.isTouchPlatform
import com.composables.icons.lucide.AlignCenterHorizontal
import com.composables.icons.lucide.AlignCenterVertical
import com.composables.icons.lucide.AlignEndHorizontal
import com.composables.icons.lucide.AlignEndVertical
import com.composables.icons.lucide.AlignHorizontalDistributeCenter
import com.composables.icons.lucide.AlignHorizontalDistributeEnd
import com.composables.icons.lucide.AlignHorizontalDistributeStart
import com.composables.icons.lucide.AlignHorizontalSpaceBetween
import com.composables.icons.lucide.AlignStartHorizontal
import com.composables.icons.lucide.AlignStartVertical
import com.composables.icons.lucide.AlignVerticalDistributeCenter
import com.composables.icons.lucide.AlignVerticalDistributeEnd
import com.composables.icons.lucide.AlignVerticalDistributeStart
import com.composables.icons.lucide.AlignVerticalSpaceBetween
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A swatch as `swatch.list` gives it: a colour (`hex`), a gradient (`gradient`: linear|radial|…), the pattern kinds, or None. */
data class Swatch(val name: String, val kind: String, val hex: String?, val gradient: String?, val spot: Boolean)

/** The swatches in `swatch.list`'s answer; a malformed entry is left out. */
fun parseSwatches(answer: JsonElement?): List<Swatch> =
    ((answer as? JsonObject)?.get("swatches") as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        Swatch(
            name, (o["kind"] as? JsonPrimitive)?.contentOrNull ?: "color", (o["hex"] as? JsonPrimitive)?.contentOrNull,
            (o["gradient"] as? JsonPrimitive)?.contentOrNull, (o["spot"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

/** The command that paints with a swatch: the fill or the stroke, whichever the paint proxy has in front. */
fun swatchCommand(fillActive: Boolean?): String = if (fillActive == false) "paint.setStroke" else "paint.setFill"

/** A brush as `brush.list` gives it. */
data class BrushInfo(val name: String, val type: String)

fun parseBrushes(answer: JsonElement?): Pair<List<BrushInfo>, String?> {
    val o = answer as? JsonObject ?: return emptyList<BrushInfo>() to null
    val brushes = (o["brushes"] as? JsonArray).orEmpty().mapNotNull { e ->
        val b = e as? JsonObject ?: return@mapNotNull null
        BrushInfo((b["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null, (b["type"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }
    return brushes to (o["current"] as? JsonPrimitive)?.contentOrNull
}

private fun hexOrNull(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    return if (h.length == 6) h.toLongOrNull(16)?.let { Color(0xFF000000L or it) } else null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwatchesPanel(state: JsonElement?, run: (String, JsonObject) -> Unit, modifier: Modifier) {
    val swatches = parseSwatches((state as? JsonObject)?.get("swatch.list"))
    val paint = ((state as? JsonObject)?.get("document.inspect") as? JsonObject)?.get("paint") as? JsonObject
    val fillActive = (paint?.get("fillActive") as? JsonPrimitive)?.booleanOrNull
    val chip = if (isTouchPlatform) 36.dp else 26.dp
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (fillActive == false) "Click a swatch to paint the stroke" else "Click a swatch to paint the fill",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in swatches) {
                val base = Modifier.size(chip).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
                val look = when {
                    s.kind == "color" && hexOrNull(s.hex) != null -> base.background(hexOrNull(s.hex)!!, RoundedCornerShape(3.dp))
                    s.kind == "gradient" -> base.background(Brush.horizontalGradient(listOf(Color.White, Color.Black)), RoundedCornerShape(3.dp))
                    else -> base.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                }
                Box(
                    look.clickable {
                        run(swatchCommand(fillActive), buildJsonObject { if (s.kind == "none") put("none", true) else put("swatch", s.name) })
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (s.kind == "none") Text("∅", color = Color(0xFFD32F2F), style = MaterialTheme.typography.labelMedium)
                    else if (s.kind !in setOf("color", "gradient")) Text(s.kind.take(1).uppercase(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        OutlinedButton(onClick = { run("swatch.new", buildJsonObject { }) }) {
            Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(16.dp)); Text(" New swatch from the fill")
        }
    }
}

/** An Align or Distribute button: the engine command it runs (`align.*` are the desktop's own and are not run here) and with what. */
private class AlignButton(val command: String, val key: String, val value: String, val label: String, val icon: ImageVector)

private val ALIGN: List<AlignButton> = listOf(
    AlignButton("object.align", "horizontal", "left", "Align left", Lucide.AlignStartVertical),
    AlignButton("object.align", "horizontal", "center", "Align horizontal centers", Lucide.AlignCenterVertical),
    AlignButton("object.align", "horizontal", "right", "Align right", Lucide.AlignEndVertical),
    AlignButton("object.align", "vertical", "top", "Align top", Lucide.AlignStartHorizontal),
    AlignButton("object.align", "vertical", "center", "Align vertical centers", Lucide.AlignCenterHorizontal),
    AlignButton("object.align", "vertical", "bottom", "Align bottom", Lucide.AlignEndHorizontal),
)
private val DISTRIBUTE: List<AlignButton> = listOf(
    AlignButton("object.distribute", "horizontal", "left", "Distribute left edges", Lucide.AlignHorizontalDistributeStart),
    AlignButton("object.distribute", "horizontal", "center", "Distribute horizontal centers", Lucide.AlignHorizontalDistributeCenter),
    AlignButton("object.distribute", "horizontal", "right", "Distribute right edges", Lucide.AlignHorizontalDistributeEnd),
    AlignButton("object.distribute", "vertical", "top", "Distribute top edges", Lucide.AlignVerticalDistributeStart),
    AlignButton("object.distribute", "vertical", "center", "Distribute vertical centers", Lucide.AlignVerticalDistributeCenter),
    AlignButton("object.distribute", "vertical", "bottom", "Distribute bottom edges", Lucide.AlignVerticalDistributeEnd),
    AlignButton("object.distributeSpacing", "axis", "horizontal", "Distribute horizontal spacing", Lucide.AlignHorizontalSpaceBetween),
    AlignButton("object.distributeSpacing", "axis", "vertical", "Distribute vertical spacing", Lucide.AlignVerticalSpaceBetween),
)

/** The command and params an align or distribute button sends: exposed so a test can check each against the engine's command list. */
internal fun alignCalls(): List<Pair<String, JsonObject>> = (ALIGN + DISTRIBUTE).map { it.command to buildJsonObject { put(it.key, it.value) } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlignPanel(selection: Int, run: (String, JsonObject) -> Unit, modifier: Modifier) {
    val size = if (isTouchPlatform) 44.dp else 36.dp
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Align objects", style = MaterialTheme.typography.labelMedium)
        FlowRow { for (b in ALIGN) IconButton({ run(b.command, buildJsonObject { put(b.key, b.value) }) }, Modifier.size(size), enabled = selection >= 1) { Icon(b.icon, b.label, Modifier.size(18.dp)) } }
        Text("Distribute objects", style = MaterialTheme.typography.labelMedium)
        FlowRow { for (b in DISTRIBUTE) IconButton({ run(b.command, buildJsonObject { put(b.key, b.value) }) }, Modifier.size(size), enabled = selection >= 3) { Icon(b.icon, b.label, Modifier.size(18.dp)) } }
        if (selection == 0) Text("Select objects first", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (selection < 3) Text("Distribute needs three objects", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val PATHFINDER = listOf(
    "object.pathfinder.unite" to "Unite", "object.pathfinder.minusFront" to "Minus Front", "object.pathfinder.intersect" to "Intersect", "object.pathfinder.exclude" to "Exclude",
    "object.pathfinder.divide" to "Divide", "object.pathfinder.trim" to "Trim", "object.pathfinder.merge" to "Merge", "object.pathfinder.crop" to "Crop",
    "object.pathfinder.outline" to "Outline", "object.pathfinder.minusBack" to "Minus Back",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PathfinderPanel(selection: Int, run: (String, JsonObject) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Shape modes and pathfinders", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, label) in PATHFINDER) OutlinedButton({ run(id, JsonObject(emptyMap())) }, enabled = selection >= 2, modifier = Modifier.heightIn(min = if (isTouchPlatform) 44.dp else 32.dp)) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
        if (selection < 2) Text("Select two or more shapes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StrokePanel(state: JsonElement?, run: (String, JsonObject) -> Unit, modifier: Modifier) {
    val paint = ((state as? JsonObject)?.get("document.inspect") as? JsonObject)?.get("paint") as? JsonObject
    val weight = (paint?.get("strokeWidth") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    fun set(k: String, v: String) = run("stroke.set", buildJsonObject { put(k, v) })
    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberChip("Weight", weight, 0.0, 100.0, { run("stroke.set", buildJsonObject { put("weight", it) }) }, unit = " pt", step = 0.25, decimals = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip("Cap", null, listOf("butt" to "Butt", "round" to "Round", "square" to "Projecting"), { set("cap", it) })
            ChoiceChip("Corner", null, listOf("miter" to "Miter", "round" to "Round", "bevel" to "Bevel"), { set("join", it) })
            ChoiceChip("Align", null, listOf("center" to "Center", "inside" to "Inside", "outside" to "Outside"), { set("align", it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ run("stroke.set", buildJsonObject { put("dash", JsonArray(emptyList())) }) }) { Text("Solid", style = MaterialTheme.typography.labelMedium) }
            OutlinedButton({ run("stroke.set", buildJsonObject { put("dash", JsonArray(listOf(JsonPrimitive(12), JsonPrimitive(6)))) }) }) { Text("Dashed", style = MaterialTheme.typography.labelMedium) }
            OutlinedButton({ run("stroke.set", buildJsonObject { put("dash", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(6)))); put("cap", "round") }) }) { Text("Dotted", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
fun BrushesPanel(state: JsonElement?, run: (String, JsonObject) -> Unit, modifier: Modifier) {
    val (brushes, current) = parseBrushes((state as? JsonObject)?.get("brush.list"))
    LazyColumn(modifier) {
        items(brushes) { b ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (isTouchPlatform) 44.dp else 32.dp)
                    .background(if (b.name == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { run("brush.apply", buildJsonObject { put("name", b.name) }) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(b.name, style = MaterialTheme.typography.bodySmall)
                Text(b.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
