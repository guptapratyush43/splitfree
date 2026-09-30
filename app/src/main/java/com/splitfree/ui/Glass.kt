package com.splitfree.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.splitfree.money.Money
import kotlinx.coroutines.delay

/** The content layer the floating glass controls of the current screen sample (null: no glass here). */
val LocalGlass = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Marks the content that this screen's glass controls sample. The glass itself must sit outside it. */
fun Modifier.glassSource(backdrop: LayerBackdrop): Modifier = this.layerBackdrop(backdrop)

/** True when the phone's animations are switched off: things still appear, just without motion. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val cr = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Blur needs Android 12; the lens refraction needs Android 13. Older phones get a solid surface. */
private val canBlur = Build.VERSION.SDK_INT >= 31

/** A light sheen and bright rim, so glass (and its fallback) catches the light at the top edge. */
fun Modifier.gloss(shape: Shape, strength: Float = 1f) = this
    .drawWithContent {
        drawContent()
        // Painted inside the element's own outline, so rounded corners stay rounded.
        drawOutline(shape.createOutline(size, layoutDirection, this),
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f * strength), 0.5f to Color.White.copy(alpha = 0.03f * strength), 1f to Color.Transparent))
    }
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.6f * strength), Color.White.copy(alpha = 0.05f * strength))), shape)

/**
 * Liquid glass: what's behind (the screen's [LocalGlass] layer) is blurred, bent at the edges
 * and brightened, then [tint] is laid over it. A tinted glass (the accent buttons) keeps its
 * colour strong enough for white text. Without a backdrop or on older phones: a solid surface.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color? = null, blurDp: Dp = 6.dp, lensDp: Dp = 14.dp): Modifier {
    val backdrop = LocalGlass.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    if (backdrop == null || !canBlur) {
        val fill = tint ?: scheme.surface.copy(alpha = 0.96f)
        return this.background(fill, shape).gloss(shape, if (tint != null) 0.8f else 0.5f)
    }
    val density = LocalDensity.current
    val b = with(density) { blurDp.toPx() }
    val l = with(density) { lensDp.toPx() }
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(b)
                lens(l, l * 2f)
            },
            onDrawSurface = {
                if (tint != null) {
                    drawRect(tint, blendMode = BlendMode.Hue)
                    drawRect(tint.copy(alpha = 0.78f))
                } else drawRect((if (dark) Color.Black else Color.White).copy(alpha = if (dark) 0.35f else 0.45f))
            }
        )
        .gloss(shape, if (tint != null) 0.9f else 0.7f)
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/**
 * The cards of a freshly opened screen cascade in: each rises, grows and fades in a beat after
 * the one before. Cards that scroll into view later just appear, so scrolling never jitters.
 */
class Entrance {
    val born = System.currentTimeMillis()
    var count = 0
}

val LocalEntrance = staticCompositionLocalOf<Entrance?> { null }

fun Modifier.entrance(): Modifier = composed {
    val stage = LocalEntrance.current
    val reduce = LocalReduceMotion.current
    val delayMs = remember {
        if (stage == null || reduce || System.currentTimeMillis() - stage.born > 700) -1L
        else (stage.count++).coerceAtMost(7) * 35L
    }
    if (delayMs < 0) return@composed this
    val k = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Starts from the first frame the card is actually drawn, so a slow first frame can't skip part of it.
        androidx.compose.runtime.withFrameMillis { }
        delay(delayMs)
        // Eases out without overshoot: the card lands exactly once, no wobble.
        k.animateTo(1f, tween(440, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)))
    }
    this.graphicsLayer {
        val v = k.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 18.dp.toPx()
        val s = 0.97f + 0.03f * v
        scaleX = s; scaleY = s
    }
}

/** Money that counts to its value (and rolls between values) instead of jumping. */
@Composable
fun CountingMoney(paise: Long, style: TextStyle, color: Color, modifier: Modifier = Modifier, text: (String) -> String = { it }) {
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(if (reduce) paise.toFloat() else 0f) }
    LaunchedEffect(paise) {
        if (reduce) a.snapTo(paise.toFloat()) else a.animateTo(paise.toFloat(), tween(700, easing = FastOutSlowInEasing))
    }
    val shown = if (a.isRunning) Math.round(a.value / 100f) * 100L else paise
    // Same-width digits, so the line doesn't shake while the number rolls.
    Text(text(Money.format(if (a.isRunning) shown else paise)), style = style.copy(fontFeatureSettings = "tnum"), color = color, modifier = modifier)
}
