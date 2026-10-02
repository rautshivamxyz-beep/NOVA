package org.nova

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * v9.15.0 "Camera": NOVA's in-app camera - a live preview with a shutter.
 *
 * The photo is written to cache/camera/capture.jpg and handed back to
 * MainActivity as a FileProvider content URI; MainActivity runs the SAME
 * on-device ML Kit OCR the "attach photo" flow uses, so the recognised
 * text lands in the chat. No network, nothing leaves the device.
 *
 * The CAMERA permission is requested at runtime (approved by the user);
 * if it is refused the screen closes with an honest message. A device
 * with no camera gets the same honest failure rather than a crash.
 */
class CameraActivity : ComponentActivity() {

    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private var shutter: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0A0B0E"))

        val pv = PreviewView(this)
        pv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(pv)
        previewView = pv

        val close = Button(this)
        close.text = "\u2715"
        close.setTextColor(Color.WHITE)
        close.textSize = 20f
        close.background = null
        close.setOnClickListener { finish() }
        val closeLp = FrameLayout.LayoutParams(dp(56), dp(56))
        closeLp.gravity = Gravity.TOP or Gravity.START
        closeLp.setMargins(dp(8), dp(16), 0, 0)
        root.addView(close, closeLp)

        val sh = Button(this)
        sh.text = "\u25C9"
        sh.setTextColor(Color.parseColor("#7C87FF"))
        sh.textSize = 34f
        sh.background = ring()
        sh.setOnClickListener { capture() }
        val shLp = FrameLayout.LayoutParams(dp(88), dp(88))
        shLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        shLp.bottomMargin = dp(36)
        root.addView(sh, shLp)
        shutter = sh

        setContentView(root)
        requestCamera()
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERM)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is needed to take a photo",
                Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = cap
                previewView?.let { preview.setSurfaceProvider(it.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open the camera", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val cap = imageCapture ?: return
        shutter?.isEnabled = false
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val out = File(dir, "capture.jpg")
        try { if (out.exists()) out.delete() } catch (e: Exception) { }
        val opts = ImageCapture.OutputFileOptions.Builder(out).build()
        cap.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    try {
                        val uri = FileProvider.getUriForFile(
                            this@CameraActivity, "org.nova.fileprovider", out)
                        setResult(Activity.RESULT_OK, Intent().setData(uri))
                    } catch (e: Exception) {
                        Toast.makeText(this@CameraActivity,
                            "Couldn't hand back the photo", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
                override fun onError(exc: ImageCaptureException) {
                    shutter?.isEnabled = true
                    Toast.makeText(this@CameraActivity,
                        "Couldn't save the photo", Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun ring(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(dp(4), Color.parseColor("#7C87FF"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object { const val REQ_PERM = 61 }
}
