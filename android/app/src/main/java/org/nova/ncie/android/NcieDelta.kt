package org.nova.ncie.android

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * v9.7.0 "Delta Updates": the on-device bspatch applier - a small, faithful
 * Kotlin port of the classic bspatch.c against the bsdiff40 patches the CI
 * produces (pip bsdiff4, see nova-apk.yml's "Attach delta patch" step).
 *
 * Format, exactly as those patches are written:
 *  - a 32-byte header: the ASCII magic "BSDIFF40", then three int64s -
 *    the compressed length of the control block, the compressed length of
 *    the diff block, and the NEW file size
 *  - three bzip2 streams at their own offsets: (a) the CONTROL block, a
 *    sequence of (add, copy, seek) triples; (b) the DIFF block, bytes that
 *    are ADDED to the corresponding old bytes; (c) the EXTRA block, bytes
 *    copied literally (for inserted content)
 *
 * The int64s (header and control triples) are encoded the way pip bsdiff4
 * encodes them: LITTLE-endian magnitude with the sign bit folded into the
 * top bit of the 8th byte (same sign convention as classic bsdiff's
 * offtout, little-endian base). Plain big-endian parsing misreads every
 * one of these patches.
 *
 * The apply loop is bspatch's own: for every control triple, `add` bytes
 * are read from the diff block, added to old[oldpos .. oldpos+add) and
 * written out; then `copy` bytes are taken from the extra block and
 * written out; then oldpos advances by `seek` (which may be negative).
 * Nothing is loaded into memory whole: the diff bytes are applied in
 * 64 KB chunks, the extra block is streamed, and the old APK is read via
 * a RandomAccessFile that seeks to oldpos (the installed APK never moves
 * and need not be copied first).
 *
 * The patch itself is opened through a contentResolver InputStream (a
 * SAF pick); it is staged into the cache first because SAF streams are
 * not seekable and each bzip2 block must be opened at its own offset.
 *
 * Design law, kept: everything runs locally on the phone - no network, no
 * accounts; the only new dependency is commons-compress (pure Java) for
 * the BZip2CompressorInputStream. Any failure (bad magic, corrupt block,
 * wrong file, OutOfMemory) returns false and cleans up after itself -
 * the caller shows an honest error.
 */
object NcieDelta {

    private const val MAGIC = "BSDIFF40"
    private const val BUF = 1 shl 16

    /** sanity gate on the patched size - a NOVA APK is ~35 MB; anything
     *  below 10 MB or above 200 MB means the picked file is not a NOVA
     *  delta patch (or is corrupt), and the honest answer is failure */
    private const val MIN_NEW_SIZE = 10L * 1024 * 1024
    private const val MAX_NEW_SIZE = 200L * 1024 * 1024

    /**
     * Applies [patchUri] (a picked NOVA-delta-*.patch) to the INSTALLED
     * APK and writes the result to [outApk]. Returns true on success; on
     * any failure (including OutOfMemory) returns false with the partial
     * output deleted - never leave a half-written APK behind.
     */
    fun applyPatch(context: Context, patchUri: Uri, outApk: File): Boolean {
        val app = context.applicationContext
        // the SAF stream is staged into the cache: the three bzip2 blocks
        // must each be opened at their own offset, and SAF streams are
        // not seekable
        val staged = File(app.cacheDir, "delta-incoming.patch")
        try {
            app.contentResolver.openInputStream(patchUri)?.use { ins ->
                BufferedOutputStream(FileOutputStream(staged), BUF).use { os ->
                    ins.copyTo(os, BUF)
                }
            } ?: return false
            val oldApk = File(app.applicationInfo.sourceDir)
            outApk.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(outApk), BUF).use { sink ->
                RandomAccessFile(oldApk, "r").use { old ->
                    apply(staged, old, old.length(), sink)
                }
            }
            return true
        } catch (t: Throwable) {
            // honest failure - OutOfMemory included; clean the partial
            // output so nothing half-built is ever offered to install
            try { outApk.delete() } catch (e: Exception) { }
            return false
        } finally {
            try { staged.delete() } catch (e: Exception) { }
        }
    }

    /** The bspatch control loop itself. Throws on any inconsistency -
     *  the callers above turn that into an honest false. */
    private fun apply(patch: File, old: RandomAccessFile, oldSize: Long, sink: OutputStream) {
        // 1. the 32-byte header
        val h = ByteArray(32)
        RandomAccessFile(patch, "r").use { pf -> pf.readFully(h) }
        if (String(h, 0, 8, Charsets.US_ASCII) != MAGIC)
            throw IOException("not a BSDIFF40 patch")
        val ctrlLen = offtin(h, 8)
        val diffLen = offtin(h, 16)
        val newSize = offtin(h, 24)
        if (newSize < MIN_NEW_SIZE || newSize > MAX_NEW_SIZE)
            throw IOException("patched size out of range: $newSize")
        if (ctrlLen <= 0 || diffLen < 0 || 32 + ctrlLen + diffLen > patch.length())
            throw IOException("corrupt patch header")
        // 2. the three blocks, each its own bzip2 stream at its own offset
        BZip2CompressorInputStream(block(patch, 32)).use { ctrl ->
            BZip2CompressorInputStream(block(patch, 32 + ctrlLen)).use { diff ->
                BZip2CompressorInputStream(block(patch, 32 + ctrlLen + diffLen)).use { extra ->
                    var oldpos = 0L
                    var newpos = 0L
                    val db = ByteArray(BUF)
                    val ob = ByteArray(BUF)
                    while (newpos < newSize) {
                        val add = readQword(ctrl)
                        val copy = readQword(ctrl)
                        val seek = readQword(ctrl)
                        if (add < 0 || copy < 0)
                            throw IOException("corrupt control block")
                        if (newpos + add > newSize)
                            throw IOException("patch adds past the new file's end")
                        // 3a. `add` diff bytes, added to the old bytes at
                        // oldpos, in bounded chunks
                        var left = add
                        while (left > 0) {
                            val n = if (left < BUF) left.toInt() else BUF
                            readFully(diff, db, n)
                            if (oldpos < 0 || oldpos + n > oldSize)
                                throw EOFException("patch reads past the old APK")
                            old.seek(oldpos)
                            var got = 0
                            while (got < n) {
                                val r = old.read(ob, got, n - got)
                                if (r < 0) throw EOFException("old APK ended early")
                                got += r
                            }
                            for (i in 0 until n) db[i] = (db[i] + ob[i]).toByte()
                            sink.write(db, 0, n)
                            oldpos += n
                            newpos += n
                            left -= n
                        }
                        // 3b. `copy` extra bytes, straight out
                        if (copy > 0) {
                            if (newpos + copy > newSize)
                                throw IOException("patch copies past the new file's end")
                            var cleft = copy
                            while (cleft > 0) {
                                val n = if (cleft < BUF) cleft.toInt() else BUF
                                readFully(extra, db, n)
                                sink.write(db, 0, n)
                                newpos += n
                                cleft -= n
                            }
                        }
                        // 3c. jump in the old file - may be negative, may
                        // legally land at the very end of the last triple
                        oldpos += seek
                        if (oldpos < 0)
                            throw IOException("patch seeks before the old APK's start")
                    }
                    if (newpos != newSize)
                        throw IOException("patch is incomplete")
                }
            }
        }
    }

    /** pip bsdiff4's int64: little-endian magnitude over bytes 0..7 with
     *  the sign in the top bit of byte 7 (offtout's sign convention on a
     *  little-endian base). Classic big-endian parsing reads garbage out
     *  of these patches - this is the one thing a naive port gets wrong. */
    private fun offtin(b: ByteArray, off: Int): Long {
        var v = (b[off + 7].toLong() and 0x7FL)
        for (i in 6 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFFL)
        return if ((b[off + 7].toInt() and 0x80) != 0) -v else v
    }

    /** one control qword from the decompressed control stream */
    private fun readQword(s: InputStream): Long {
        val b = ByteArray(8)
        var off = 0
        while (off < 8) {
            val r = s.read(b, off, 8 - off)
            if (r < 0) throw EOFException("control block ended early")
            off += r
        }
        return offtin(b, 0)
    }

    private fun readFully(s: InputStream, b: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = s.read(b, off, n - off)
            if (r < 0) throw EOFException("patch block ended early")
            off += r
        }
    }

    /** a read-only view of the file starting at [start] - each bzip2
     *  block gets its own RandomAccessFile, because a decompressor
     *  over-reads into the next block's bytes */
    private class BlockInput(file: File, start: Long) : InputStream() {
        private val raf = RandomAccessFile(file, "r")
        init { raf.seek(start) }
        override fun read(): Int = raf.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
        override fun close() { raf.close() }
    }

    private fun block(file: File, start: Long): InputStream =
        BufferedInputStream(BlockInput(file, start), BUF)
}
