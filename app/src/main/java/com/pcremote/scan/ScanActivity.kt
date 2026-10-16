package com.pcremote.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.limelight.R
import com.pcremote.ui.components.PrimaryButton
import com.pcremote.ui.theme.LocalMidnight
import com.pcremote.ui.theme.MidnightTheme
import com.pcremote.ui.theme.rememberHaptics
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Starts the scanner and hands back the first QR text it reads. */
object QrScan {
    @Volatile private var pending: ((String) -> Unit)? = null

    fun start(activity: Activity, onResult: (String) -> Unit) {
        pending = onResult
        activity.startActivity(Intent(activity, ScanActivity::class.java))
    }

    internal fun deliver(text: String) {
        pending?.invoke(text)
        pending = null
    }
}

/** Full-screen camera scanner (CameraX + ZXing); works without Google Play services. */
class ScanActivity : ComponentActivity() {
    private val analyzer = Executors.newSingleThreadExecutor()
    private val done = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MidnightTheme(dark = true) { Scanner() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer.shutdown()
    }

    @Composable
    private fun Scanner() {
        val c = LocalMidnight.current
        val haptics = rememberHaptics()
        val owner = LocalLifecycleOwner.current
        var granted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
        var torch by remember { mutableStateOf(false) }
        var camera by remember { mutableStateOf<Camera?>(null) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                AndroidView(factory = { ctx ->
                    val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analyzer) { img ->
                            img.use {
                                val plane = it.planes[0]
                                val buf = plane.buffer
                                val bytes = ByteArray(buf.remaining()).also { b -> buf.get(b) }
                                val text = QrDecoder.decode(bytes, it.width, it.height, plane.rowStride)
                                if (text != null && done.compareAndSet(false, true)) runOnUiThread {
                                    haptics.click()
                                    QrScan.deliver(text)
                                    finish()
                                }
                            }
                        }
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                }, modifier = Modifier.fillMaxSize())
                Viewfinder()
            } else {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.pcr_scan_need_camera), color = c.text, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(stringResource(R.string.pcr_scan_allow), { ask.launch(Manifest.permission.CAMERA) })
                }
            }
            Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(::finish) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Color.White) }
                Spacer(Modifier.weight(1f))
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    IconButton({ torch = !torch; camera?.cameraControl?.enableTorch(torch) }) {
                        Icon(if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, null, tint = Color.White)
                    }
                }
            }
            Text(stringResource(R.string.pcr_scan_hint), color = Color.White, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(32.dp))
        }
    }

    /** Dimmed surround with a clear rounded window and an accent outline. */
    @Composable
    private fun Viewfinder() {
        val accent = LocalMidnight.current.accent
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val side = size.minDimension * 0.68f
            val tl = Offset((size.width - side) / 2, (size.height - side) / 2.4f)
            drawRect(Color.Black.copy(alpha = 0.55f))
            drawRoundRect(Color.Transparent, tl, Size(side, side), CornerRadius(36.dp.toPx()), blendMode = BlendMode.Clear)
            drawRoundRect(accent, tl, Size(side, side), CornerRadius(36.dp.toPx()), style = Stroke(4.dp.toPx()))
        }
    }
}
