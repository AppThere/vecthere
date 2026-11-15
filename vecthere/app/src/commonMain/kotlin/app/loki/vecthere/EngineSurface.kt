package app.loki.vecthere

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.loki.kit.EngineClient
import app.loki.kit.EngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** What the surface needs of the engine: its JSON calls, and a page as pixels. */
interface SurfaceEngine {
    val transport: app.loki.kit.EngineTransport
    fun renderPage(page: Int, scale: Float): PageBitmap?
}

/** One rendered page: straight-alpha RGBA8, and how many pages there are. */
class PageBitmap(val width: Int, val height: Int, val pages: Int, val rgba: ByteArray)

/** Most pages drawn at once; the rest are listed by number until paging is built. */
private const val PAGE_CAP = 30

/** Straight-alpha RGBA8 as an image: Skia on the desktop, a Bitmap on Android. */
expect fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap

/** What `loki.surface` says the engine draws: `kind` (`slides`, `artboards`, `documents`, `cells`), page count, current page. */
data class SurfaceInfo(val kind: String, val pages: Int, val current: Int)

fun parseSurface(e: JsonElement?): SurfaceInfo {
    val o = e as? JsonObject ?: return SurfaceInfo("", 0, 0)
    return SurfaceInfo(
        (o["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        (o["pages"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0,
        (o["current"] as? JsonPrimitive)?.intOrNull ?: 0,
    )
}

/**
 * The document surface on the real engine: every page the engine renders (slides, artboards, open
 * documents) in a scrolling column fitted to the width, or for `cells` a grid drawn from `sheet.read`.
 * Re-reads after every command ([revision]). Pointer editing on the page is not built yet: the
 * playbook's phase 5, and the coverage test's pointer gaps.
 */
@Composable
fun EngineSurface(native: SurfaceEngine, revision: Int, modifier: Modifier, scale: Float = 1.5f, onChanged: () -> Unit = {}) {
    val client = remember(native) { EngineClient(native.transport) }
    var info by remember { mutableStateOf(SurfaceInfo("", 0, 0)) }
    var pages by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        withContext(Dispatchers.Default) {
            val i = parseSurface(runCatching { client.call("loki.surface") }.getOrNull())
            val imgs = if (i.kind == "cells") emptyList() else (0 until minOf(i.pages, PAGE_CAP)).mapNotNull { p ->
                native.renderPage(p, scale)?.let { rgbaToImageBitmap(it.width, it.height, it.rgba) }
            }
            info = i
            pages = imgs
            error = if (i.kind != "cells" && i.pages > 0 && imgs.isEmpty()) "the engine rendered nothing" else null
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        when {
            info.kind == "cells" -> CellGrid(client, revision, onChanged, Modifier.fillMaxSize())
            pages.isEmpty() -> Text(
                error ?: if (info.pages == 0) "No document open: File › New." else "",
                Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // An art app shows the whole page: fitted to the width and to the height, centred on the pasteboard.
                val pageWidth = maxWidth - 32.dp
                val viewHeight = maxHeight - 32.dp
                LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    itemsIndexed(pages) { i, img ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = if (i == 0) 16.dp else 0.dp)) {
                            Image(
                                img, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Fit,
                                modifier = Modifier.width(minOf(pageWidth, viewHeight * (img.width.toFloat() / img.height.coerceAtLeast(1)))).aspectRatio(img.width.toFloat() / img.height.coerceAtLeast(1))
                                    .border(if (i == info.current && pages.size > 1) 2.dp else 1.dp, if (i == info.current && pages.size > 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            )
                            if (pages.size > 1) Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (info.pages > pages.size) item { Text("…and ${info.pages - pages.size} more", Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

private const val GRID_COLS = 26
private const val GRID_ROWS = 100

private fun colName(c: Int): String {
    var n = c + 1
    val sb = StringBuilder()
    while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
    return sb.toString()
}

/** `sheet.read`'s rows of values (`{"range": "A1:D3", "values": [[…]]}`), as text, never throwing. */
fun parseSheetValues(e: JsonElement?): List<List<String>> =
    ((e as? JsonObject)?.get("values") as? JsonArray).orEmpty().map { row ->
        (row as? JsonArray).orEmpty().map { v -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
    }

/** A spreadsheet surface: the active sheet's top-left [GRID_ROWS] × [GRID_COLS], a cell to pick, a bar to type into it. */
@Composable
private fun CellGrid(client: EngineClient, revision: Int, onChanged: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var selRow by remember { mutableIntStateOf(0) }
    var selCol by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val ref = "${colName(selCol)}${selRow + 1}"
    LaunchedEffect(revision) {
        values = withContext(Dispatchers.Default) {
            parseSheetValues(runCatching { client.call("sheet.read", buildJsonObject { put("range", "A1:${colName(GRID_COLS - 1)}$GRID_ROWS"); put("formatted", true) }) }.getOrNull())
        }
    }
    fun cellText(r: Int, c: Int) = values.getOrNull(r)?.getOrNull(c).orEmpty()
    fun select(r: Int, c: Int) {
        selRow = r; selCol = c
        val target = "${colName(c)}${r + 1}"
        scope.launch {
            withContext(Dispatchers.Default) {
                runCatching { client.execute("edit.goTo", buildJsonObject { put("reference", target) }) }
                // The formula, not its value, is what the bar edits.
                runCatching { client.call("sheet.read", buildJsonObject { put("range", target); put("formulas", true) }) }.getOrNull()
            }?.let { input = parseSheetValues(it).firstOrNull()?.firstOrNull().orEmpty() }
        }
    }
    fun commit() {
        scope.launch {
            note = withContext(Dispatchers.Default) {
                try { client.execute("cell.set", buildJsonObject { put("cell", ref); put("input", input) }); null } catch (e: EngineException) { e.message }
            }
            onChanged()
        }
    }
    val hScroll = rememberScrollState()
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ref, Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                input, { input = it }, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("Value or =formula") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }),
                supportingText = note?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(hScroll)) {
            Row {
                HeaderCell("", 40)
                for (c in 0 until GRID_COLS) HeaderCell(colName(c), 96)
            }
            for (r in 0 until GRID_ROWS) {
                Row {
                    HeaderCell("${r + 1}", 40)
                    for (c in 0 until GRID_COLS) {
                        val picked = r == selRow && c == selCol
                        Box(
                            Modifier.width(96.dp).height(26.dp)
                                .border(if (picked) 2.dp else 0.5.dp, if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                .clickable { select(r, c) }.padding(horizontal = 4.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text(cellText(r, c), maxLines = 1, overflow = TextOverflow.Clip, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Int) {
    Box(
        Modifier.width(width.dp).height(26.dp).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
