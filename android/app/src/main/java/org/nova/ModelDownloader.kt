package org.nova

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Simple, dependency-free GGUF downloader with resume support.
 *
 * Downloads to "<dest>.part" and atomically renames on completion, so a
 * killed download never leaves a model that looks complete. Interrupted
 * downloads resume from where they stopped using HTTP Range requests
 * (Hugging Face supports them).
 */
object ModelDownloader {

    sealed class State {
        object Idle : State()
        data class Downloading(
            val name: String,
            val downloaded: Long,
            val total: Long // -1 if unknown
        ) : State()

        data class Done(val file: File) : State()
        data class Failed(val name: String, val error: String) : State()
        data class Importing(val name: String, val copied: Long, val total: Long) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cancelled = false

    val isBusy: Boolean get() = _state.value is State.Downloading || _state.value is State.Importing

    /** Dismisses a terminal (Done/Failed) state back to Idle. */
    fun acknowledge() {
        val s = _state.value
        if (s is State.Done || s is State.Failed) _state.value = State.Idle
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
    }

    /**
     * Starts a download. Only one transfer runs at a time.
     * Observe [state] for progress.
     */
    fun download(url: String, dir: File) {
        if (isBusy) return
        cancelled = false
        job = scope.launch {
            try {
                _state.value = doDownload(url, dir)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
            } catch (e: Exception) {
                _state.value = State.Failed(fileNameFromUrl(url), e.message ?: "download failed")
            }
        }
    }

    /**
     * Copies a user-picked GGUF (SAF Uri already opened as stream) into
     * the models directory.
     */
    fun import(name: String, input: () -> java.io.InputStream?, totalHint: Long, dir: File) {
        if (isBusy) return
        cancelled = false
        job = scope.launch {
            try {
                val dest = uniqueFile(File(dir, sanitize(name)))
                val part = File(dest.absolutePath + ".part")
                var copied = 0L
                val buf = ByteArray(64 * 1024)
                input()?.use { ins ->
                    FileOutputStream(part).use { out ->
                        while (true) {
                            if (cancelled) throw IOException("cancelled")
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            _state.value = State.Importing(dest.name, copied, totalHint)
                        }
                    }
                } ?: throw IOException("cannot open selected file")
                if (copied < 16) throw IOException("selected file is not a valid GGUF")
                if (!part.renameTo(dest)) throw IOException("rename failed")
                _state.value = State.Done(dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                File(dir, sanitize(name) + ".part").delete()
                _state.value = State.Idle
            } catch (e: Exception) {
                _state.value = State.Failed(name, e.message ?: "import failed")
            }
        }
    }

    private suspend fun doDownload(url: String, dir: File): State = withContext(Dispatchers.IO) {
        val fileName = sanitize(fileNameFromUrl(url))
        if (!fileName.endsWith(".gguf")) throw IOException("URL does not point to a .gguf file")
        val dest = uniqueFile(File(dir, fileName))
        val part = File(dir, dest.name + ".part")

        var conn: HttpURLConnection? = null
        try {
            val existing = if (part.exists()) part.length() else 0L
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "NOVA-Android/2.0")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            val code = conn.responseCode
            if (code != 200 && code != 206) {
                throw IOException("HTTP $code from server")
            }

            // 200 = server ignored the range request -> restart from zero
            val resume = code == 206 && existing > 0
            val newBytes = conn.contentLengthLong
            val total = if (newBytes > 0) {
                (if (resume) existing + newBytes else newBytes)
            } else -1L

            var done = if (resume) existing else 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { ins ->
                FileOutputStream(part, resume).use { out ->
                    while (true) {
                        if (cancelled) throw IOException("cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _state.value = State.Downloading(dest.name, done, total)
                    }
                    out.fd.sync()
                }
            }

            if (total > 0 && done < total) throw IOException("incomplete download")
            if (!part.renameTo(dest)) {
                // rename can fail across weird mount points; fall back to copy
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            State.Done(dest)
        } finally {
            conn?.disconnect()
        }
    }

    private fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension
        val ext = target.extension
        var i = 1
        while (true) {
            val f = File(target.parentFile, "$base-$i.$ext")
            if (!f.exists()) return f
            i++
        }
    }

    fun fileNameFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { "model.gguf" }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
}
