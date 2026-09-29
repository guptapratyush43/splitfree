package com.splitfree.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.NavViewModel
import com.splitfree.data.Auth
import com.splitfree.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

private val genders = listOf("Male", "Female", "Other", "Prefer not to say")

/** Edit your name and picture (a gallery photo cropped to a circle, or one of the cartoons). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(nav: NavViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meNow by Repo.me.collectAsStateWithLifecycle()
    val uid = Auth.uid.orEmpty()
    var name by remember { mutableStateOf(meNow?.name ?: Auth.name) }
    var gender by remember { mutableStateOf(meNow?.gender.orEmpty()) }
    var avatar by remember { mutableIntStateOf(meNow?.avatar ?: -1) }
    var photo by remember { mutableStateOf(meNow?.photo.orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            cropping = withContext(Dispatchers.IO) { loadScaled(context, uri, 1600) }
            if (cropping == null) toast(context, "Couldn't open that photo")
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.Rounded.Close, "Close", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp)) }
            Text("Edit profile", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = {
                saving = true
                scope.launch {
                    Repo.saveProfile(name.trim(), gender, avatar, photo)
                    Haptics.success(context); toast(context, "Profile updated"); nav.pop()
                }
            }, enabled = name.isNotBlank() && !saving) {
                Icon(Icons.Rounded.Check, "Save", tint = if (name.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            // ---- picture ----
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Box(Modifier.size(128.dp).clickable { picking = true }) {
                    when {
                        photo.isNotBlank() -> decodePhoto(photo)?.let {
                            androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(128.dp).clip(CircleShape))
                        }
                        else -> AvatarArt(if (avatar >= 0) avatar else Math.floorMod(uid.hashCode(), avatarCount), 128.dp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                ActionPill("Change picture", { picking = true })
            }
            Spacer(Modifier.height(16.dp))
            Field(name, { name = it.take(40) }, label = "Name", placeholder = "Your name")
            Spacer(Modifier.height(10.dp))
            Text(Auth.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking) WarmDialog("Change picture", onDismiss = { picking = false }) {
        ListCard {
            SettingRow("Choose from gallery", "Crop it to fit the circle", Icons.Rounded.PhotoLibrary, onClick = {
                picking = false
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            HairLine()
            SettingRow("Pick a character", "One of the 24 cartoons", Icons.Rounded.Face, onClick = { picking = false; choosing = true })
        }
    }

    if (choosing) WarmDialog("Pick a character", onDismiss = { choosing = false }) {
        LazyVerticalGrid(GridCells.Fixed(4), modifier = Modifier.height(360.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(avatarCount) { i ->
                val on = photo.isBlank() && (if (avatar >= 0) avatar else Math.floorMod(uid.hashCode(), avatarCount)) == i
                Box(
                    Modifier.clip(CircleShape).border(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { avatar = i; photo = ""; choosing = false; Haptics.tick(context) }.padding(3.dp)
                ) { AvatarArt(i, 60.dp) }
            }
        }
    }

    cropping?.let { bmp ->
        CropDialog(bmp, onCancel = { cropping = null }) { cropped ->
            photo = cropped; cropping = null
        }
    }
}

/**
 * Full-screen cropper: pinch and drag the photo under a circle guide. What's
 * inside the circle becomes the profile picture (320×320 JPEG).
 */
@Composable
private fun CropDialog(bmp: Bitmap, onCancel: () -> Unit, onDone: (String) -> Unit) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Text("Move and scale", style = MaterialTheme.typography.titleMedium, color = Color.White,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val density = LocalDensity.current
                val w = with(density) { maxWidth.toPx() }
                val h = with(density) { maxHeight.toPx() }
                val d = min(w, h) * 0.82f
                val base = max(d / bmp.width, d / bmp.height)
                fun clamp(o: Offset, z: Float): Offset {
                    val s = base * z
                    val mx = max(0f, (bmp.width * s - d) / 2); val my = max(0f, (bmp.height * s - d) / 2)
                    return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
                }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, pan, gz, _ ->
                            zoom = (zoom * gz).coerceIn(1f, 6f)
                            off = clamp(off + pan, zoom)
                        }
                    }
                ) {
                    val s = base * zoom
                    val iw = bmp.width * s; val ih = bmp.height * s
                    drawImage(
                        image, dstOffset = IntOffset((w / 2 + off.x - iw / 2).toInt(), (h / 2 + off.y - ih / 2).toInt()),
                        dstSize = IntSize(iw.toInt(), ih.toInt())
                    )
                    // Dim everything outside the circle, then outline it.
                    val hole = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, size.width, size.height))
                        addOval(Rect(Offset(w / 2 - d / 2, h / 2 - d / 2), androidx.compose.ui.geometry.Size(d, d)))
                    }
                    drawPath(hole, Color.Black.copy(alpha = 0.6f))
                    drawCircle(Color.White, radius = d / 2, center = Offset(w / 2, h / 2), style = Stroke(width = 2.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.35f), radius = d / 3, center = Offset(w / 2, h / 2), style = Stroke(width = 1.dp.toPx()))
                }
                // Keep the crop maths in step with the gestures above.
                LaunchedEffect(zoom, off) { cropState = Triple(base * zoom, off, d) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                SecondaryButton("Cancel", null, onCancel, Modifier.weight(1f))
                PrimaryButton("Use photo", null, {
                    val (s, o, d) = cropState ?: return@PrimaryButton
                    onDone(cropToBase64(bmp, s, o, d))
                }, Modifier.weight(1f))
            }
        }
    }
}

private var cropState: Triple<Float, Offset, Float>? = null

/** Cuts the square behind the circle out of [bmp] and encodes it small enough to share. */
private fun cropToBase64(bmp: Bitmap, scale: Float, off: Offset, d: Float): String {
    val side = d / scale
    val left = (bmp.width / 2f - off.x / scale - side / 2).coerceIn(0f, bmp.width - side)
    val top = (bmp.height / 2f - off.y / scale - side / 2).coerceIn(0f, bmp.height - side)
    val square = Bitmap.createBitmap(bmp, left.toInt(), top.toInt(), side.toInt().coerceAtMost(bmp.width), side.toInt().coerceAtMost(bmp.height))
    val out = Bitmap.createScaledBitmap(square, 320, 320, true)
    val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/** Loads a gallery image no larger than [maxSide], upright. */
private fun loadScaled(context: android.content.Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val rotation = context.contentResolver.openInputStream(uri)?.use {
        when (androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
    } ?: 0f
    if (rotation == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rotation) }, true)
}.getOrNull()
