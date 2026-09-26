#!/usr/bin/env python3
"""NOVA v6.3.0 app patch: image input (photo -> on-device OCR -> chat).

The existing attach button only accepted PDFs/text for the knowledge
base. Now it also accepts photos (JPEG/PNG): the image runs through the
already-bundled ML Kit text recognizer (100% offline, no new model), and
the extracted text lands in the input box - with a "Solve it" button for
textbook maths questions. The user can fix garbled symbols before sending.

Idempotent - safe to run on every CI build.
Usage: patch_v630.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")

def rep(src, old, new, what):
    n = src.count(old)
    assert n == 1, "%s: anchor found %dx (expected 1x)" % (what, n)
    return src.replace(old, new)

# ---- 1) the attach picker now offers images too ----
A1_OLD = 'putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/plain"))'
A1_NEW = ('putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "text/plain", '
          '"image/jpeg", "image/png"))')

# ---- 2) picked file routing: image -> OCR, everything else -> old path ----
A2_OLD = '''        if (requestCode == 7700 && resultCode == RESULT_OK) {
            data?.data?.let { loadSharedDocument(it) }
            return
        }
'''
A2_NEW = '''        if (requestCode == 7700 && resultCode == RESULT_OK) {
            // v6.3.0: a photo goes to on-device OCR and lands in the chat
            // box; documents keep the old knowledge-base path
            data?.data?.let { uri ->
                val isImage = (contentResolver.getType(uri) ?: "").startsWith("image/")
                if (isImage) ocrImage(uri) else loadSharedDocument(uri)
            }
            return
        }
'''

# ---- 3) the OCR flow itself, right after openDocPicker ----
A3_OLD = '''            startActivityForResult(pick, 7700)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }
'''
A3_NEW = '''            startActivityForResult(pick, 7700)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    /** v6.3.0: photo of a question -> on-device OCR -> editable text in the
     *  input box. Printed textbook questions read well; the user can fix
     *  any garbled math symbols in the box before sending. */
    private fun ocrImage(uri: android.net.Uri) {
        try {
            val img = com.google.mlkit.vision.common.InputImage.fromFilePath(this, uri)
            val rec = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
            rec.process(img)
                .addOnSuccessListener { t ->
                    rec.close()
                    val txt = t.text.trim().replace(Regex("\\n{3,}"), "\\n\\n")
                    if (txt.isEmpty()) {
                        toast("No readable text in that image")
                        return@addOnSuccessListener
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Text from image")
                        .setMessage(if (txt.length > 400) txt.take(400) + "\\n\\u2026" else txt)
                        .setPositiveButton("Solve it") { _, _ ->
                            input.setText("Solve this step by step:\\n\\n$txt")
                            input.setSelection(input.text.length)
                            input.requestFocus()
                        }
                        .setNegativeButton("Add text") { _, _ ->
                            input.setText(txt)
                            input.setSelection(input.text.length)
                            input.requestFocus()
                        }
                        .show()
                }
                .addOnFailureListener { toast("Couldn't read image: " + (it.message ?: "error")) }
        } catch (e: Exception) {
            toast("Couldn't open image")
        }
    }
'''

src = open(MA, encoding="utf-8").read()
if "private fun ocrImage" in src:
    print("MainActivity.kt: v6.3.0 image-OCR patch already applied")
else:
    src = rep(src, A1_OLD, A1_NEW, "attach picker MIME types")
    src = rep(src, A2_OLD, A2_NEW, "onActivityResult routing")
    src = rep(src, A3_OLD, A3_NEW, "ocrImage insertion")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: attach button now accepts photos (OCR -> chat)")
