package org.nova

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads text out of PDF files for NOVA's document chat. */
object PdfDoc {

    /** Extracts up to [maxChars] characters of text from a PDF. */
    suspend fun extractText(context: Context, uri: Uri, maxChars: Int = 60_000): String =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    PDDocument.load(ins).use { doc ->
                        val t = PDFTextStripper().getText(doc)
                        if (t.length > maxChars) t.substring(0, maxChars) + "\n[...document truncated]"
                        else t
                    }
                } ?: ""
            } catch (e: Exception) {
                ""
            }
        }
}
