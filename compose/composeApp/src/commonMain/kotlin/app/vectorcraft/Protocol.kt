package app.vectorcraft

import kotlinx.serialization.json.*

/** One way to reach the engine: send a JSON-lines request, get the reply line. */
interface Transport {
    suspend fun call(line: String): String
    fun close()
}

class EngineException(message: String) : Exception(message)

data class ToolInfo(val id: String, val label: String, val shortcut: String?)
data class CommandInfo(val id: String, val label: String, val shortcut: String?, val enabled: Boolean, val params: String)
data class Rendered(val width: Int, val height: Int, val png: ByteArray)

/** Client of the app's control channel (`--control PORT`, see crates/ui-egui/src/control.rs). */
class EngineClient(private val transport: Transport) {
    private var nextId = 1

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val id = nextId++
        val req = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val reply = Json.parseToJsonElement(transport.call(req.toString())).jsonObject
        if (reply["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw EngineException(reply["error"]?.jsonPrimitive?.contentOrNull ?: "request failed")
        }
        return reply["result"] ?: JsonNull
    }

    suspend fun execute(command: String, params: JsonObject = JsonObject(emptyMap())) =
        request("engine.execute", buildJsonObject {
            put("command", command)
            put("params", params)
        })

    suspend fun tools(): List<ToolInfo> = parseTools(request("ui.tool.list"))
    suspend fun commands(): List<CommandInfo> = parseCommands(request("engine.commands"))

    suspend fun render(scale: Double = 1.0): Rendered = parseRendered(request("ui.render", buildJsonObject {
        put("data", true)
        put("scale", scale)
    }))

    suspend fun selectTool(id: String) = request("ui.tool.select", buildJsonObject { put("tool", id) })
    suspend fun open(path: String) = request("app.open", buildJsonObject { put("path", path) })
}

fun parseTools(v: JsonElement): List<ToolInfo> =
    (v as? JsonArray).orEmpty().flatMap { group -> (group as? JsonArray).orEmpty() }.mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        ToolInfo(id, o["label"]?.jsonPrimitive?.contentOrNull ?: id, o["shortcut"]?.jsonPrimitive?.contentOrNull)
    }

fun parseCommands(v: JsonElement): List<CommandInfo> =
    (v as? JsonArray).orEmpty().mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        CommandInfo(
            id,
            o["label"]?.jsonPrimitive?.contentOrNull ?: id,
            o["shortcut"]?.jsonPrimitive?.contentOrNull,
            o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            o["params"]?.toString() ?: "",
        )
    }

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun parseRendered(v: JsonElement): Rendered {
    val o = v.jsonObject
    val b64 = o["pngBase64"]?.jsonPrimitive?.contentOrNull ?: throw EngineException("render returned no image")
    return Rendered(
        o["width"]?.jsonPrimitive?.intOrNull ?: 0,
        o["height"]?.jsonPrimitive?.intOrNull ?: 0,
        kotlin.io.encoding.Base64.decode(b64),
    )
}

/** Parses the params text box: empty means no params; anything else must be a JSON object. */
fun parseParams(text: String): JsonObject {
    if (text.isBlank()) return JsonObject(emptyMap())
    return try {
        Json.parseToJsonElement(text) as? JsonObject ?: throw EngineException("params must be a JSON object")
    } catch (e: SerializationException) {
        throw EngineException("params are not valid JSON")
    }
}
