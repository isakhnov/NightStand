package com.nightstand.photos.smart

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The image-encoder model file. NOT bundled in the APK (size): downloaded on
 * demand when the user turns Smart selection on, or imported from a file.
 * Either way it must match [SHA256] before it is used. Stored in app files.
 */
object ModelStore {
    const val URL = "https://github.com/isakhnov/NightStand/releases/download/model-mobileclip-s0-v1/mobileclip_s0_image.tflite"
    /** docs/photos-intelligence/step-a/tflite/README.md: weight-only INT8 TFLite of MobileCLIP-S0's image encoder. */
    const val SHA256 = "8eaa055f1250e69b1f77271f4a6cb092aaefbdc8ff8a485f773df61e8b1a39bc"
    const val SIZE_BYTES = 13_086_656L
    /** Stored with every embedding: rows from another model are re-embedded lazily. */
    const val VERSION = "mobileclip_s0_w8_v1"
    private const val FILE = "mobileclip_s0_image.tflite"

    sealed class State {
        object Missing : State()
        data class Downloading(val done: Long, val total: Long) : State()
        object Verifying : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    fun file(c: Context) = File(File(c.filesDir, "models"), FILE)

    /** Cheap check (size only); the SHA-256 was verified when the file was installed. */
    fun isInstalled(c: Context): Boolean = file(c).let { it.isFile && (SIZE_BYTES <= 0 || it.length() == SIZE_BYTES) }

    fun refresh(c: Context) {
        if (_state.value is State.Downloading || _state.value == State.Verifying) return
        _state.value = if (isInstalled(c)) State.Ready else if (_state.value is State.Failed) _state.value else State.Missing
    }

    @Volatile private var busy = false

    /** Download from [url] on a background thread; progress in [state]. */
    fun download(c: Context, url: String = URL, onDone: (Boolean) -> Unit = {}) {
        if (busy) return
        busy = true
        val app = c.applicationContext
        Thread({
            val ok = runCatching { downloadBlocking(app, url) }.onFailure {
                _state.value = State.Failed(it.message ?: it.toString())
            }.getOrDefault(false)
            busy = false
            onDone(ok)
        }, "model-download").start()
    }

    fun downloadBlocking(c: Context, url: String): Boolean {
        _state.value = State.Downloading(0, SIZE_BYTES)
        var u = URL(url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) { // follow redirects (GitHub release → object storage) by hand: they cross hosts
            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Nightstand")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) { u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code != 200) { conn.disconnect(); throw IllegalStateException("Download failed: HTTP $code") }
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: SIZE_BYTES
        return conn.inputStream.use { install(c, it, total) }.also { conn.disconnect() }
    }

    /** "Import model file…" (ACTION_OPEN_DOCUMENT). */
    fun import(c: Context, uri: Uri, onDone: (Boolean) -> Unit = {}) {
        if (busy) return
        busy = true
        val app = c.applicationContext
        Thread({
            val ok = runCatching {
                val len = runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: SIZE_BYTES
                app.contentResolver.openInputStream(uri)!!.use { install(app, it, len) }
            }.onFailure { _state.value = State.Failed(it.message ?: it.toString()) }.getOrDefault(false)
            busy = false
            onDone(ok)
        }, "model-import").start()
    }

    /** Copy [input] to a temp file with progress, check SHA-256, then move into place. */
    fun install(c: Context, input: InputStream, total: Long, expectedSha: String = SHA256): Boolean {
        val dst = file(c); dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, "$FILE.part")
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L; var lastReport = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); md.update(buf, 0, n); done += n
                if (done - lastReport > 256 * 1024) { lastReport = done; _state.value = State.Downloading(done, total) }
            }
        }
        _state.value = State.Verifying
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(expectedSha, ignoreCase = true)) {
            tmp.delete()
            _state.value = State.Failed("Not the expected model file (SHA-256 ${sha.take(12)}…)")
            return false
        }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) { tmp.delete(); _state.value = State.Failed("Could not save the model"); return false }
        _state.value = State.Ready
        return true
    }

    fun delete(c: Context) { file(c).delete(); _state.value = State.Missing }
}
