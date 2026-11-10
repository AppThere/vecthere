package app.vectorcraft

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

private class Fake(val replies: ArrayDeque<String>) : Transport {
    val sent = mutableListOf<String>()
    override suspend fun call(line: String): String { sent += line; return replies.removeFirst() }
    override fun close() {}
}

class ProtocolTest {
    @Test fun parsesToolGroups() {
        val v = Json.parseToJsonElement("""[[{"id":"selection","label":"Selection Tool","shortcut":"V"}],[{"id":"pen","label":"Pen"}]]""")
        assertEquals(listOf("selection", "pen"), parseTools(v).map { it.id })
    }

    @Test fun errorsSurface() = runTest {
        val c = EngineClient(Fake(ArrayDeque(listOf("""{"ok":false,"error":"no document"}"""))))
        assertFailsWith<EngineException> { c.render() }
    }

    @Test fun executeSendsCommand() = runTest {
        val t = Fake(ArrayDeque(listOf("""{"ok":true,"result":{}}""")))
        EngineClient(t).execute("shape.rectangle", parseParams("""{"x":1}"""))
        val sent = Json.parseToJsonElement(t.sent[0]).jsonObject
        assertEquals("engine.execute", sent["method"]!!.jsonPrimitive.content)
    }

    @Test fun badParamsRejected() {
        assertFailsWith<EngineException> { parseParams("[1]") }
        assertFailsWith<EngineException> { parseParams("{") }
    }
}
