package app.vectorcraft

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Platform hooks: opening a transport and decoding PNG bytes. */
interface Platform {
    suspend fun connect(host: String, port: Int): Transport
    fun decodePng(bytes: ByteArray): ImageBitmap
}

@Composable
fun App(platform: Platform) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) { Main(platform) }
    }
}

@Composable
private fun Main(platform: Platform) {
    val scope = rememberCoroutineScope()
    var client by remember { mutableStateOf<EngineClient?>(null) }
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("7979") }
    var path by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not connected. Start: vectorcraft --control 7979") }
    var tools by remember { mutableStateOf(listOf<ToolInfo>()) }
    var commands by remember { mutableStateOf(listOf<CommandInfo>()) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var filter by remember { mutableStateOf("") }
    var paramText by remember { mutableStateOf("") }
    var activeTool by remember { mutableStateOf<String?>(null) }

    /** Runs engine work, reporting any failure in the status line instead of crashing. */
    fun guarded(block: suspend (EngineClient) -> Unit) {
        val c = client ?: run { status = "Connect first"; return }
        scope.launch {
            try { block(c) } catch (e: Exception) { status = e.message ?: e.toString() }
        }
    }
    suspend fun refresh(c: EngineClient) {
        image = platform.decodePng(c.render().png)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(host, { host = it }, Modifier.width(160.dp), label = { Text("Host") }, singleLine = true)
            OutlinedTextField(port, { port = it }, Modifier.width(90.dp), label = { Text("Port") }, singleLine = true)
            Button(onClick = {
                val p = port.toIntOrNull()
                if (p == null) { status = "Port must be a number"; return@Button }
                scope.launch {
                    try {
                        client?.let { /* old connection is replaced */ }
                        val c = EngineClient(platform.connect(host, p))
                        tools = c.tools(); commands = c.commands(); client = c
                        status = "Connected"
                        runCatching { refresh(c) }
                    } catch (e: Exception) { status = "Connect failed: ${e.message}" }
                }
            }) { Text("Connect") }
            OutlinedTextField(path, { path = it }, Modifier.weight(1f), label = { Text("File to open") }, singleLine = true)
            Button(onClick = { guarded { it.open(path); refresh(it); status = "Opened" } }, enabled = path.isNotBlank()) { Text("Open") }
            Button(onClick = { guarded { refresh(it) } }) { Text("Refresh") }
        }
        HorizontalDivider()
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.width(180.dp).fillMaxHeight().padding(4.dp)) {
                items(tools) { t ->
                    FilterChip(
                        selected = activeTool == t.id,
                        onClick = { guarded { it.selectTool(t.id); activeTool = t.id } },
                        label = { Text(t.label + (t.shortcut?.let { " ($it)" } ?: ""), maxLines = 1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight().background(androidx.compose.ui.graphics.Color(0xFF2B2B2B)), contentAlignment = Alignment.Center) {
                val img = image
                if (img != null) Image(img, "Artboard", Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
                else Text("No document rendered")
            }
            VerticalDivider()
            Column(Modifier.width(320.dp).fillMaxHeight().padding(8.dp)) {
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Search commands") }, singleLine = true)
                OutlinedTextField(paramText, { paramText = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("Params (JSON object)") })
                val shown = commands.filter { filter.isBlank() || it.label.contains(filter, true) || it.id.contains(filter, true) }
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.id }) { c ->
                        TextButton(
                            onClick = {
                                guarded {
                                    val params = parseParams(paramText)
                                    it.execute(c.id, params)
                                    refresh(it)
                                    status = "Ran ${c.id}"
                                }
                            },
                            enabled = c.enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(c.label + (c.shortcut?.let { "  $it" } ?: ""), Modifier.fillMaxWidth(), maxLines = 1) }
                    }
                }
            }
        }
        HorizontalDivider()
        Text(status, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
    }
}
