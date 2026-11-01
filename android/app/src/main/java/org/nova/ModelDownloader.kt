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

    // ---- v9.29.1: progress in the notification shade ------------------
    // A multi-GB model download used to be visible only while the Models
    // screen stayed open. This mirrors the same State into an ongoing
    // notification, so the download and its progress survive leaving the
    // screen. UI only - the transfer itself is unchanged.
    private const val CHANNEL = "nova_downloads"
    private const val NOTIF_ID = 9092
    private var lastNotifiedPct = -1
    /** v9.33.7: with an unknown total the percent never moves, so
     *  throttle by bytes instead of silently freezing the notification. */
    private var lastNotifiedDone = -1L

    private fun postProgress(ctx: android.content.Context, name: String,
                             done: Long, total: Long) {
        try {
            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
            if (pct >= 0) {
                if (pct == lastNotifiedPct) return
            } else if (lastNotifiedDone >= 0 && done - lastNotifiedDone < 2_000_000L) {
                return   // unknown total: repost at most every ~2 MB
            }
            lastNotifiedPct = pct
            lastNotifiedDone = done
            val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL, "Model downloads",
                android.app.NotificationManager.IMPORTANCE_LOW))
            val text = if (total > 0)
                String.format(java.util.Locale.US, "%.2f / %.2f GB  (%d%%)",
                    done / 1e9, total / 1e9, pct)
            else String.format(java.util.Locale.US, "%.2f GB", done / 1e9)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading $name")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, if (pct >= 0) pct else 0, pct < 0)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) { NovaLog.quiet("ModelDownloader", e) }
    }

    private fun dismissProgress(ctx: android.content.Context) {
        try {
            val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (e: Exception) { NovaLog.quiet("ModelDownloader", e) }
        lastNotifiedPct = -1
        lastNotifiedDone = -1L
    }

    /**
     * Starts a download. Only one transfer runs at a time.
     * Observe [state] for progress. [appCtx] mirrors that progress into an
     * ongoing notification so it survives leaving the Models screen.
     */
    fun download(url: String, dir: File, appCtx: android.content.Context? = null) {
        if (isBusy) return
        cancelled = false
        lastNotifiedPct = -1
        lastNotifiedDone = -1L
        job = scope.launch {
            try {
                _state.value = doDownload(url, dir, appCtx)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
            } catch (e: java.io.IOException) {
                // a clean stop is not a failure
                _state.value = if (cancelled) State.Idle
                    else State.Failed(fileNameFromUrl(url), e.message ?: "download failed")
            } catch (e: Exception) {
                _state.value = State.Failed(fileNameFromUrl(url), e.message ?: "download failed")
            } finally {
                try { appCtx?.let { dismissProgress(it) } } catch (e: Exception) { NovaLog.quiet("ModelDownloader", e) }
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
            var partFile: File? = null
            try {
                val dest = uniqueFile(File(dir, sanitize(name)))
                val part = File(dest.absolutePath + ".part")
                partFile = part
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
                if (!looksLikeGguf(dest)) {
                    dest.delete()
                    throw IOException("selected file is not a valid GGUF")
                }
                _state.value = State.Done(dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v7.6: delete the REAL partial file - with a uniquified
                // name (model-1.gguf) the old cleanup deleted a non-existent
                // path and stranded the multi-GB .part
                partFile?.delete()
                _state.value = State.Idle
            } catch (e: Exception) {
                partFile?.delete()
                _state.value = State.Failed(name, e.message ?: "import failed")
            }
        }
    }

    private suspend fun doDownload(url: String, dir: File,
                                   appCtx: android.content.Context?): State = withContext(Dispatchers.IO) {
        val fileName = sanitize(fileNameFromUrl(url))
        if (!fileName.endsWith(".gguf")) throw IOException("URL does not point to a .gguf file")
        val dest = uniqueFile(File(dir, fileName))
        val part = File(dir, dest.name + ".part")

        var conn: HttpURLConnection? = null
        try {
            val existing = if (part.exists()) part.length() else 0L
            conn = NovaNet.open(appCtx, url).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "NOVA-Android/2.0")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            val code = conn.responseCode
            // v9.33.78 "Unstuck download": a .part left behind by an earlier
            // attempt that is already complete, or longer than the object the
            // server now has, makes every retry answer 416 Range Not
            // Satisfiable. The download was then stuck for good - the same
            // "HTTP 416 from server" on every tap, with no way out but
            // clearing app data. Bytes on disk plus a 416 means the partial
            // cannot be appended to, so drop it and say so plainly; the next
            // tap starts clean.
            if (code == 416 && existing > 0) {
                part.delete()
                throw IOException(
                    "the partial download could not be resumed and was cleared - tap download again")
            }
            if (code != 200 && code != 206) {
                throw IOException("HTTP $code from server")
            }

            // A 206 response is only safe to append when Content-Range proves
            // the server resumed at exactly the requested byte. Otherwise a
            // CDN/proxy error can silently corrupt a multi-GB GGUF.
            if (code == 206 && existing == 0L)
                throw IOException("unexpected partial response without a resume request")
            val resume = code == 206 && existing > 0
            val contentRange = if (resume) conn.getHeaderField("Content-Range") else null
            val rangeMatch = if (contentRange != null)
                Regex("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$").matchEntire(contentRange.trim())
            else null
            if (resume) {
                val match = rangeMatch ?: throw IOException("server returned an invalid Content-Range; retry download")
                val start = match.groupValues[1].toLongOrNull()
                    ?: throw IOException("invalid resume start")
                val end = match.groupValues[2].toLongOrNull()
                    ?: throw IOException("invalid resume end")
                val rangeTotal = match.groupValues[3].toLongOrNull()
                    ?: throw IOException("server omitted the full size in Content-Range; retry with a supported source")
                if (start != existing || end < start || end >= rangeTotal)
                    throw IOException("server resumed at the wrong byte; partial file was not appended")
                val expectedChunk = end - start + 1
                if (conn.contentLengthLong > 0 && conn.contentLengthLong != expectedChunk)
                    throw IOException("resume length does not match Content-Range")
            }

            // Use Content-Range's full object size when available; Content-Length
            // on a 206 response is only the remaining chunk, not the whole file.
            val newBytes = conn.contentLengthLong
            val rangeTotal = rangeMatch?.groupValues?.get(3)?.toLongOrNull()
            val total = when {
                rangeTotal != null -> rangeTotal
                newBytes > 0 -> if (resume) existing + newBytes else newBytes
                else -> -1L
            }

            // v7.6: refuse to start into a full disk - the old failure was
            // ENOSPC deep into a multi-GB download
            if (total > 0 && android.os.StatFs(dir.absolutePath).availableBytes < total)
                throw IOException("not enough free space for this model")
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
                        appCtx?.let { postProgress(it, dest.name, done, total) }
                    }
                    out.fd.sync()
                }
            }

            if (total > 0 && done != total) throw IOException("incomplete or oversized download")
            if (!part.renameTo(dest)) {
                // rename can fail across weird mount points; fall back to copy
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            if (!looksLikeGguf(dest)) {
                dest.delete()
                throw IOException("downloaded file is not a GGUF model")
            }
            State.Done(dest)
        } finally {
            conn?.disconnect()
        }
    }

    /** v9.16.0: a GGUF file starts with the ASCII magic "GGUF". Cheap
     *  sanity check so a truncated or wrong file never lands as a model. */
    private fun looksLikeGguf(file: File): Boolean = try {
        val head = ByteArray(4)
        java.io.FileInputStream(file).use { it.read(head) }
        String(head, Charsets.US_ASCII) == "GGUF"
    } catch (e: Exception) { false }

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
