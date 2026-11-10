package app.vectorcraft

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class SocketTransport(private val socket: Socket) : Transport {
    private val reader = socket.getInputStream().bufferedReader()
    private val writer = socket.getOutputStream().bufferedWriter()

    override suspend fun call(line: String): String = withContext(Dispatchers.IO) {
        synchronized(this) {
            writer.write(line); writer.newLine(); writer.flush()
            reader.readLine() ?: throw EngineException("connection closed")
        }
    }

    override fun close() = socket.close()
}

object DesktopPlatform : Platform {
    override suspend fun connect(host: String, port: Int): Transport = withContext(Dispatchers.IO) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 3000)
        SocketTransport(s)
    }

    override fun decodePng(bytes: ByteArray): ImageBitmap =
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "VectorCraft (Compose)") {
        App(DesktopPlatform)
    }
}
