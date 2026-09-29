package com.splitfree.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.splitfree.NavViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val qrHints = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true
)

/** (group id, join code) from a Split Free invite link, or null for anything else. */
private fun inviteFrom(text: String): Pair<String, String>? {
    val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
    val seg = uri.pathSegments
    val i = seg.indexOf("j")
    return if (i >= 0 && seg.size >= i + 3) seg[i + 1] to seg[i + 2] else null
}

/** Reads a QR code from a still picture (the gallery), trying both binarizers. */
private fun decodeBitmap(bmp: Bitmap): String? {
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    val src = RGBLuminanceSource(bmp.width, bmp.height, px)
    for (bin in listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src), HybridBinarizer(src.invert()))) {
        runCatching { return MultiFormatReader().decode(BinaryBitmap(bin), qrHints).text }
    }
    return null
}

/** Loads a picture scaled down to at most ~1600 px on its long side. */
private fun loadScaled(ctx: android.content.Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}

/**
 * "Scan QR to join": the camera looks for a Split Free invite QR code; the
 * gallery button reads one from a saved picture. A found invite asks to join.
 */
@Composable
fun ScanScreen(nav: NavViewModel) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    val done = remember { AtomicBoolean(false) }

    fun found(text: String) {
        val invite = inviteFrom(text)
        if (invite == null) {
            done.set(false)
            toast(ctx, "That isn't a Split Free invite code")
            return
        }
        Haptics.success(ctx)
        nav.pop()
        nav.pendingJoin.value = invite
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.Default) { runCatching { loadScaled(ctx, uri)?.let(::decodeBitmap) }.getOrNull() }
            if (text == null) toast(ctx, "No QR code found in that picture") else if (done.compareAndSet(false, true)) found(text)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed) {
            val executor = remember { Executors.newSingleThreadExecutor() }
            DisposableEffect(Unit) { onDispose { executor.shutdown() } }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        val reader = MultiFormatReader().apply { setHints(qrHints) }
                        analysis.setAnalyzer(executor) { image ->
                            try {
                                if (!done.get()) {
                                    val plane = image.planes[0]
                                    val buf = plane.buffer
                                    val data = ByteArray(buf.remaining()).also { buf.get(it) }
                                    val stride = plane.rowStride
                                    val src = PlanarYUVLuminanceSource(data, stride, image.height, 0, 0, image.width, image.height, false)
                                    val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text }.getOrNull()
                                    reader.reset()
                                    if (text != null && done.compareAndSet(false, true)) ContextCompat.getMainExecutor(c).execute { found(text) }
                                }
                            } finally { image.close() }
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(c))
                    view
                }
            )
        }

        // Dim everything except a rounded square window, with accent corners.
        val accent = MaterialTheme.colorScheme.primary
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            val side = size.minDimension * 0.68f
            val tl = Offset((size.width - side) / 2, (size.height - side) / 2.4f)
            drawRect(Color.Black.copy(alpha = 0.55f))
            drawRoundRect(Color.Transparent, tl, Size(side, side), CornerRadius(28.dp.toPx()), blendMode = BlendMode.Clear)
            drawRoundRect(accent, tl, Size(side, side), CornerRadius(28.dp.toPx()), style = Stroke(3.dp.toPx()))
        })

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            ScanButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { nav.pop() }
            Spacer(Modifier.weight(1f))
            Text(
                if (allowed) "Point at a Split Free QR code" else "Allow the camera to scan, or pick a saved QR code",
                style = MaterialTheme.typography.titleSmall, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ScanButton(Icons.Rounded.PhotoLibrary, "Pick from gallery", big = true) {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Gallery", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}

/** Round dark see-through button, the same style as the ones over group photos. */
@Composable
private fun ScanButton(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(if (big) 60.dp else 46.dp).pressScale(src).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(interactionSource = src, indication = null) { Haptics.tick(ctx); onClick() }
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(if (big) 28.dp else 24.dp)) }
}
