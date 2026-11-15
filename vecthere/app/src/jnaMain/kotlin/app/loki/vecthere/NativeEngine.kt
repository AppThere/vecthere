package app.loki.vecthere

import app.loki.kit.EngineTransport
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

// The six functions of ffi/include/loki.h, the same in every Craft front end of this kind.
private interface Loki : Library {
    fun loki_session_new(): Pointer?
    fun loki_session_free(s: Pointer?)
    fun loki_call(s: Pointer?, method: String, paramsJson: String?): Pointer?
    fun loki_string_free(p: Pointer?)
    fun loki_render_page(s: Pointer?, page: Int, scale: Float, width: IntByReference, height: IntByReference, pages: IntByReference): Pointer?
    fun loki_buffer_free(p: Pointer?, len: Long)
}

/**
 * The engine in this process, through JNA. Desktop: `-Dloki.lib=<dir with libloki_ffi>` (the Gradle
 * run task passes `target/release`). Android: the library is packaged in the APK (jniLibs) and found by name. The native session is not thread-safe, and both the app's
 * command calls and the surface's renders reach it, so every call is serialised here.
 */
class NativeEngine private constructor(private val lib: Loki, private var handle: Pointer?) : EngineTransport, SurfaceEngine {
    override val transport: EngineTransport get() = this

    @Synchronized
    override fun callRaw(method: String, paramsJson: String?): String {
        val h = handle ?: return """{"ok":false,"error":"engine closed"}"""
        val out = lib.loki_call(h, method, paramsJson) ?: return """{"ok":false,"error":"engine returned null"}"""
        try {
            return out.getString(0, "UTF-8")
        } finally {
            lib.loki_string_free(out)
        }
    }

    @Synchronized
    override fun renderPage(page: Int, scale: Float): PageBitmap? {
        val h = handle ?: return null
        val w = IntByReference()
        val ht = IntByReference()
        val n = IntByReference()
        val p = lib.loki_render_page(h, page, scale, w, ht, n) ?: return null
        val len = w.value.toLong() * ht.value * 4
        try {
            return PageBitmap(w.value, ht.value, n.value, p.getByteArray(0, len.toInt()))
        } finally {
            lib.loki_buffer_free(p, len)
        }
    }

    @Synchronized
    override fun close() {
        lib.loki_session_free(handle)
        handle = null
    }

    /** This engine for one [App] that must not close it when it leaves the screen (the screenshot harness redraws the app per size). */
    fun borrowed(): EngineTransport = object : EngineTransport {
        override fun callRaw(method: String, paramsJson: String?) = this@NativeEngine.callRaw(method, paramsJson)
        override fun close() {}
    }

    companion object {
        /** The engine, or null (with the reason on stderr) when the native library cannot be loaded. */
        fun open(): NativeEngine? = try {
            System.getProperty("loki.lib")?.let { System.setProperty("jna.library.path", it) }
            val lib = Native.load("loki_ffi", Loki::class.java)
            lib.loki_session_new()?.let { NativeEngine(lib, it) }
        } catch (e: Throwable) {
            System.err.println("$APP_NAME: the engine library did not load (${e.message}); build it with scripts/build-desktop.sh")
            null
        }
    }
}
