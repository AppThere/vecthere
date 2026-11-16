package app.loki.vecthere

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PanelsTest {
    private fun parse(s: String) = Json.parseToJsonElement(s)

    @Test fun swatchesAreReadAndABadOneIsLeftOut() {
        val s = parseSwatches(parse("""{"swatches":[{"name":"[None]","kind":"none"},{"name":"Red","kind":"color","hex":"#ff0000","spot":false},
            {"name":"Fade","kind":"gradient","gradient":"linear"},{"kind":"color"},"junk",{"name":"Spot","kind":"color","hex":"#00ff00","spot":true}]}"""))
        assertEquals(listOf("[None]", "Red", "Fade", "Spot"), s.map { it.name })
        assertEquals("#ff0000", s[1].hex); assertEquals("linear", s[2].gradient); assertEquals(true, s[3].spot)
        assertEquals(emptyList(), parseSwatches(null)); assertEquals(emptyList(), parseSwatches(parse("[]")))
    }

    @Test fun aSwatchPaintsTheFillUnlessTheStrokeIsInFront() {
        assertEquals("paint.setFill", swatchCommand(true)); assertEquals("paint.setFill", swatchCommand(null)); assertEquals("paint.setStroke", swatchCommand(false))
    }

    @Test fun brushesAndTheCurrentOneAreRead() {
        val (b, cur) = parseBrushes(parse("""{"brushes":[{"name":"Charcoal","type":"art"},{"type":"x"},{"name":"3 pt. Round","type":"calligraphic"}],"current":"Charcoal"}"""))
        assertEquals(listOf("Charcoal", "3 pt. Round"), b.map { it.name }); assertEquals("Charcoal", cur)
        assertEquals(emptyList<BrushInfo>() to null, parseBrushes(null))
    }

    @Test fun everyAlignButtonRunsACommandTheEngineHasAndAcceptsItsKey() {
        val commands = Json.parseToJsonElement(java.io.File("src/jvmTest/resources/engine-commands.json").readText()).let { (it as kotlinx.serialization.json.JsonArray) }
            .associate { c -> val o = c as kotlinx.serialization.json.JsonObject; (o["id"] as kotlinx.serialization.json.JsonPrimitive).content to (o["params"] as kotlinx.serialization.json.JsonPrimitive).content }
        val calls = alignCalls()
        assertEquals(14, calls.size)
        for ((command, params) in calls) {
            val doc = commands[command] ?: error("the engine has no $command")
            for (key in params.keys) assertEquals(true, Regex("""\b$key\??:""").containsMatchIn(doc), "$command does not take $key")
        }
        assertEquals(false, commands.keys.any { it.startsWith("align.") && alignCalls().any { c -> c.first == it } }, "the desktop's own align.* commands are not run")
    }
}
