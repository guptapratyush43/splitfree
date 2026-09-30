package com.splitfree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.splitfree.ui.theme.LocalStatusColors

/** A bordered text field matching the cards: label above, soft rounded box. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    prefix: String? = null,
    big: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        if (label != null) SectionLabel(label)
        val shape = RoundedCornerShape(14.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(scheme.surface, shape).border(1.dp, scheme.outline, shape)
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            val style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge
            if (prefix != null) Text(prefix, style = style, color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = style.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboard,
                    capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, style = style, color = scheme.onSurfaceVariant.copy(alpha = 0.6f))
                        inner()
                    }
                }
            )
        }
    }
}

/** Small inline amount box used in split rows. */
@Composable
fun MiniField(value: String, onChange: (String) -> Unit, prefix: String?, suffix: String?, keyboard: KeyboardType = KeyboardType.Decimal) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.width(118.dp).background(scheme.background, shape).border(1.dp, scheme.outline, shape).padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (prefix != null) Text(prefix, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text("0", color = scheme.onSurfaceVariant.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium); inner() } }
        )
        if (suffix != null) Text(suffix, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val color = if (danger) scheme.error else scheme.primary
    val shape = RoundedCornerShape(14.dp)
    val src = remember { MutableInteractionSource() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier.pressScale(src).clip(shape).background(scheme.surface, shape).border(1.dp, scheme.outline, shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled) { if (danger) Haptics.warn(ctx) else Haptics.tick(ctx); onClick() }.padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (enabled) color else scheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else scheme.onSurfaceVariant)
    }
}

/**
 * The quietest button, for "not now" choices: same shape and height as the
 * others, a soft neutral fill, no outline, muted text.
 */
@Composable
fun TertiaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val src = remember { MutableInteractionSource() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier.pressScale(src).clip(shape).background(scheme.onSurface.copy(alpha = 0.06f), shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled) { Haptics.tick(ctx); onClick() }.padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

/** Small rounded chip, filled when active. */
@Composable
fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val src = remember { MutableInteractionSource() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bg by animateColorAsState(if (active) scheme.primary else scheme.surface, tween(180), label = "chip")
    val fg by animateColorAsState(if (active) scheme.onPrimary else scheme.onSurface, tween(180), label = "chipText")
    Box(
        Modifier.pressScale(src).clip(shape).background(bg, shape)
            .border(1.dp, if (active) scheme.primary else scheme.outline, shape)
            .clickable(interactionSource = src, indication = null) { Haptics.tick(ctx); onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** One of the bundled cartoons by index. */
@Composable
fun AvatarArt(index: Int, size: Dp, description: String? = null) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(avatarPool[index.coerceIn(0, avatarPool.size - 1)]),
        contentDescription = description, modifier = Modifier.size(size).clip(CircleShape)
    )
}

val avatarCount get() = avatarPool.size

/** People who never picked a cartoon keep the one they had from the original 24. */
private const val DEFAULT_AVATARS = 24

private val photoCache = android.util.LruCache<Int, androidx.compose.ui.graphics.ImageBitmap>(40)

/** Base64 JPEG to an image, cached so lists don't decode the same face twice. */
fun decodePhoto(b64: String): androidx.compose.ui.graphics.ImageBitmap? {
    photoCache.get(b64.hashCode())?.let { return it }
    return runCatching {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
    }.getOrNull()?.also { photoCache.put(b64.hashCode(), it) }
}

/** 24 bundled cartoon characters; everyone gets one, the same in every group. */
private val avatarPool = listOf(com.splitfree.R.drawable.av_01, com.splitfree.R.drawable.av_02, com.splitfree.R.drawable.av_03, com.splitfree.R.drawable.av_04, com.splitfree.R.drawable.av_05, com.splitfree.R.drawable.av_06, com.splitfree.R.drawable.av_07, com.splitfree.R.drawable.av_08, com.splitfree.R.drawable.av_09, com.splitfree.R.drawable.av_10, com.splitfree.R.drawable.av_11, com.splitfree.R.drawable.av_12, com.splitfree.R.drawable.av_13, com.splitfree.R.drawable.av_14, com.splitfree.R.drawable.av_15, com.splitfree.R.drawable.av_16, com.splitfree.R.drawable.av_17, com.splitfree.R.drawable.av_18, com.splitfree.R.drawable.av_19, com.splitfree.R.drawable.av_20, com.splitfree.R.drawable.av_21, com.splitfree.R.drawable.av_22, com.splitfree.R.drawable.av_23, com.splitfree.R.drawable.av_24, com.splitfree.R.drawable.av_25, com.splitfree.R.drawable.av_26, com.splitfree.R.drawable.av_27, com.splitfree.R.drawable.av_28, com.splitfree.R.drawable.av_29, com.splitfree.R.drawable.av_30, com.splitfree.R.drawable.av_31, com.splitfree.R.drawable.av_32, com.splitfree.R.drawable.av_33, com.splitfree.R.drawable.av_34, com.splitfree.R.drawable.av_35, com.splitfree.R.drawable.av_36, com.splitfree.R.drawable.av_37, com.splitfree.R.drawable.av_38, com.splitfree.R.drawable.av_39, com.splitfree.R.drawable.av_40, com.splitfree.R.drawable.av_41, com.splitfree.R.drawable.av_42, com.splitfree.R.drawable.av_43, com.splitfree.R.drawable.av_44, com.splitfree.R.drawable.av_45, com.splitfree.R.drawable.av_46, com.splitfree.R.drawable.av_47, com.splitfree.R.drawable.av_48, com.splitfree.R.drawable.av_49, com.splitfree.R.drawable.av_50, com.splitfree.R.drawable.av_51, com.splitfree.R.drawable.av_52, com.splitfree.R.drawable.av_53, com.splitfree.R.drawable.av_54, com.splitfree.R.drawable.av_55, com.splitfree.R.drawable.av_56, com.splitfree.R.drawable.av_57, com.splitfree.R.drawable.av_58, com.splitfree.R.drawable.av_59, com.splitfree.R.drawable.av_60, com.splitfree.R.drawable.av_61, com.splitfree.R.drawable.av_62, com.splitfree.R.drawable.av_63, com.splitfree.R.drawable.av_64, com.splitfree.R.drawable.av_65, com.splitfree.R.drawable.av_66, com.splitfree.R.drawable.av_67, com.splitfree.R.drawable.av_68, com.splitfree.R.drawable.av_69, com.splitfree.R.drawable.av_70, com.splitfree.R.drawable.av_71, com.splitfree.R.drawable.av_72, com.splitfree.R.drawable.av_73, com.splitfree.R.drawable.av_74, com.splitfree.R.drawable.av_75, com.splitfree.R.drawable.av_76)

/**
 * A person's cartoon avatar, picked from their account id so it never changes.
 * Falls back to a tinted initial when there is no id.
 */
@Composable
fun Avatar(name: String, key: String, size: Dp = 40.dp) {
    if (key.isNotBlank()) {
        val people by com.splitfree.data.Repo.people.collectAsState()
        val p = people[key]
        val photo = p?.photo.orEmpty()
        if (photo.isNotBlank()) {
            val bmp = remember(photo) { decodePhoto(photo) }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bmp, contentDescription = name, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape)
                )
                return
            }
        }
        AvatarArt(p?.avatar?.takeIf { it in avatarPool.indices } ?: Math.floorMod(key.hashCode(), DEFAULT_AVATARS), size, name)
        return
    }
    val palette = listOf(0xFFC15F3C, 0xFF2F6F73, 0xFF7A5BA6, 0xFF3C7A3F, 0xFFA3662A, 0xFF3F5DA8, 0xFF9C3F63, 0xFF5B6770)
    val c = Color(palette[Math.floorMod(key.hashCode(), palette.size)])
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).background(c.copy(alpha = 0.16f), CircleShape)) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = c, style = MaterialTheme.typography.titleMedium, fontSize = (size.value * 0.42f).sp)
    }
}

/** Green when owed to you, clay when you owe, grey when settled. */
@Composable
fun moneyColor(net: Long): Color = when {
    net > 0 -> LocalStatusColors.current.success
    net < 0 -> LocalStatusColors.current.owe
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun CheckDot(checked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(24.dp).background(animateColorAsState(if (checked) scheme.primary else Color.Transparent, tween(160), label = "check").value, RoundedCornerShape(7.dp))
            .border(1.5.dp, if (checked) scheme.primary else scheme.outline, RoundedCornerShape(7.dp))
    ) {
        if (checked) Icon(Icons.Rounded.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
    }
}

/** On/off switch in the app's palette (not the Material switch). */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val knob by animateDpAsState(if (on) 20.dp else 0.dp, tween(180), label = "knob")
    val track by animateColorAsState(if (on) scheme.primary else scheme.surfaceVariant, tween(180), label = "track")
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.size(width = 48.dp, height = 28.dp).clip(CircleShape)
            .background(track)
            .border(1.dp, if (on) scheme.primary else scheme.outline, CircleShape)
            .clickable { Haptics.tick(ctx); onChange(!on) }.padding(3.dp)
    ) {
        Box(Modifier.padding(start = knob).size(22.dp).background(if (on) scheme.onPrimary else scheme.surface, CircleShape))
    }
}

@Composable
fun WarmDialog(title: String, onDismiss: () -> Unit, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val reduce = LocalReduceMotion.current
        val pop = remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
        androidx.compose.runtime.LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 420f)) }
        WarmCard(padding = 22.dp, background = MaterialTheme.colorScheme.background,
            modifier = Modifier.graphicsLayer { val s = 0.88f + 0.12f * pop.value; scaleX = s; scaleY = s; alpha = pop.value.coerceIn(0f, 1f) }
                .gloss(RoundedCornerShape(16.dp), 0.6f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                if (action != null) action()
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    WarmDialog(title, onDismiss) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Cancel", null, onDismiss, Modifier.weight(1f))
            if (danger) SecondaryButton(confirm, null, { onConfirm(); onDismiss() }, Modifier.weight(1f), danger = true)
            else PrimaryButton(confirm, null, { onConfirm(); onDismiss() }, Modifier.weight(1f))
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    body: String?,
    icon: ImageVector?,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    trailing: @Composable (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { Haptics.tick(ctx); onClick() } else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) scheme.error else scheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (danger) scheme.error else scheme.onSurface)
            if (body != null) RowBody(body)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

/** Card with no inner padding, for stacked [SettingRow]s split by hairlines. */
@Composable
fun ListCard(content: @Composable ColumnScope.() -> Unit) = WarmCard(padding = 0.dp, content = content)

/** Section title inside a card: same as [SectionLabel] but flush with the card's text. */
@Composable
fun CardLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/** Rounded pill action, filled for the main action, outlined for the rest. */
@Composable
fun ActionPill(text: String, onClick: () -> Unit, filled: Boolean = false, icon: ImageVector? = null, soft: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val src = remember { MutableInteractionSource() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // soft: a tinted pill with no outline, for an action that stands apart from its neighbours
    val bg by animateColorAsState(if (filled) scheme.primary else if (soft) scheme.primary.copy(alpha = 0.12f) else scheme.surface, tween(200), label = "pill")
    val fg by animateColorAsState(if (filled) scheme.onPrimary else if (soft) scheme.primary else scheme.onSurface, tween(200), label = "pillText")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressScale(src).clip(shape).background(bg, shape)
            .border(1.dp, if (filled) scheme.primary else if (soft) Color.Transparent else scheme.outline, shape)
            .clickable(interactionSource = src, indication = null) { Haptics.tick(ctx); onClick() }.padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (filled) scheme.onPrimary else scheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Floating action button: accent-tinted liquid glass that springs in when it appears. */
@Composable
fun FloatingAdd(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(50)
    val reduce = LocalReduceMotion.current
    val pop = remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 380f)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer { val s = 0.6f + 0.4f * pop.value; scaleX = s; scaleY = s; alpha = pop.value.coerceIn(0f, 1f) }
            .pressScale(src)
            .shadow(12.dp, shape, ambientColor = scheme.primary, spotColor = scheme.primary)
            .glass(shape, tint = scheme.primary, blurDp = 3.dp, lensDp = 12.dp)
            .clickable(interactionSource = src, indication = null) { Haptics.press(ctx); onClick() }
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Icon(icon, null, tint = scheme.onPrimary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = scheme.onPrimary)
    }
}

/** Bottom tab bar: icon over label, the active tab in clay. */
/**
 * Soft rings that ripple out of this element's rounded outline and fade, two
 * at a time half a beat apart. The element itself never changes size.
 */
@Composable
fun Modifier.rippleRings(color: Color, spread: Dp = 14.dp, period: Int = 2200): Modifier {
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "rings")
    val k by loop.animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(period, easing = androidx.compose.animation.core.LinearEasing)), label = "k")
    return this.drawBehind {
        for (phase in listOf(0f, 0.5f)) {
            val p = (k + phase) % 1f
            val grow = spread.toPx() * androidx.compose.animation.core.FastOutSlowInEasing.transform(p)
            drawRoundRect(
                color.copy(alpha = 0.5f * (1f - p)),
                topLeft = androidx.compose.ui.geometry.Offset(-grow, -grow),
                size = androidx.compose.ui.geometry.Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension / 2 + grow),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx())
            )
        }
    }
}

/** Small sort capsule: tap to move to the next order; the label cross-fades. */
@Composable
fun SortCapsule(label: String, onClick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val src = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pressScale(src).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable(interactionSource = src, indication = null) { Haptics.tick(ctx); onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Icon(androidx.compose.material.icons.Icons.Rounded.SwapVert, "Sort", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        androidx.compose.animation.AnimatedContent(label, label = "sort",
            transitionSpec = {
                (androidx.compose.animation.fadeIn(tween(180)) togetherWith androidx.compose.animation.fadeOut(tween(120))).using(
                    androidx.compose.animation.SizeTransform(clip = false) { _, _ -> tween(180) })
            }
        ) { t -> Text(t, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
    }
}

/** Small red dot with rings rippling out of it: something here needs you. */
@Composable
fun AlertDot(modifier: Modifier = Modifier) {
    val red = MaterialTheme.colorScheme.error
    Box(modifier.size(8.dp).rippleRings(red, spread = 8.dp, period = 1800).background(red, CircleShape))
}

/** Room the floating tab bar takes at the bottom of the Home tabs; lists pad by this much. */
val TabBarSpace = 96.dp

/**
 * Bottom tabs as a floating glass capsule over the content. A clay pill glides to the
 * chosen tab and its icon lifts a little; nothing changes size, so it never jitters.
 */
@Composable
fun BottomTabs(tabs: List<Pair<String, ImageVector>>, selected: Int, dots: Set<Int> = emptySet(), modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(50)
    val slide by androidx.compose.animation.core.animateFloatAsState(selected.toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 460f), label = "tabPill")
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.padding(horizontal = 22.dp, vertical = 12.dp).fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .glass(shape, blurDp = 8.dp, lensDp = 16.dp)
            .padding(5.dp)
    ) {
        val tabW = maxWidth / tabs.size
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((tabW.toPx() * slide).toInt(), 0) }.width(tabW).height(58.dp)
            .background(scheme.primary.copy(alpha = 0.16f), shape))
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, (label, icon) ->
                val on by androidx.compose.animation.core.animateFloatAsState(if (i == selected) 1f else 0f,
                    androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 520f), label = "tabOn")
                val tint = androidx.compose.ui.graphics.lerp(scheme.onSurfaceVariant, scheme.primary, on)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).height(58.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (i != selected) Haptics.tick(ctx); onSelect(i) }
                ) {
                    Box(Modifier.graphicsLayer { translationY = -2.dp.toPx() * on; val s = 1f + 0.08f * on; scaleX = s; scaleY = s }) {
                        Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
                        androidx.compose.animation.AnimatedVisibility(i in dots, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-3).dp),
                            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) { AlertDot() }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}

/** Rounded-square badge with the group's initial, like Splitwise's group image. */
@Composable
fun GroupBadge(name: String, key: String, size: Dp = 52.dp, photo: String = "", round: Boolean = false) {
    val c = groupTint(key)
    val shape = if (round) CircleShape else RoundedCornerShape(size * 0.28f)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(shape).background(c.copy(alpha = 0.16f), shape)) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = c, style = MaterialTheme.typography.headlineSmall, fontSize = (size.value * 0.42f).sp)
        // The place photo, when one was found, covers the letter once it loads.
        if (photo.isNotBlank()) coil.compose.AsyncImage(
            model = photo, contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize()
        )
    }
}

/** Category emoji in a soft circle. */
@Composable
fun CategoryBubble(emoji: String, size: Dp = 42.dp) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)) {
        Text(emoji, fontSize = (size.value * 0.45f).sp)
    }
}

/** Round radio for single choice. */
@Composable
fun RadioDot(selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp).border(1.5.dp, if (selected) scheme.primary else scheme.outline, CircleShape)) {
        if (selected) Box(Modifier.size(12.dp).background(scheme.primary, CircleShape))
    }
}

/** Text field with only an underline, as in Splitwise's add-expense form. */
@Composable
fun UnderlineField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    big: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyLarge
    Column(modifier) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = style.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard,
                capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = scheme.onSurfaceVariant.copy(alpha = 0.6f)); inner() } }
        )
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(if (value.isEmpty()) scheme.outline else scheme.primary))
    }
}

/** Underline tabs that scroll sideways, like Splitwise's split types. */
@Composable
fun UnderlineTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 8.dp)) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).clickable { Haptics.tick(ctx); onSelect(i) }.padding(horizontal = 12.dp)
                ) {
                    Text(t, style = MaterialTheme.typography.titleSmall, color = if (on) scheme.onBackground else scheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) scheme.primary else Color.Transparent, RoundedCornerShape(2.dp)))
                }
            }
        }
        HairLine()
    }
}

/** Each group keeps one colour, picked from its id. */
fun groupTint(key: String): Color {
    val palette = listOf(0xFFC15F3C, 0xFF2F6F73, 0xFF7A5BA6, 0xFF3C7A3F, 0xFFA3662A, 0xFF3F5DA8, 0xFF9C3F63, 0xFF5B6770)
    return Color(palette[Math.floorMod(key.hashCode(), palette.size)])
}

/** Icon button on a soft round background, used over the group header band. */
@Composable
fun CircleIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)
    ) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp)) }
}

/**
 * Press feedback: the element dips while held and springs back on release.
 * Even a quick tap shows the full dip, so every tap visibly registers.
 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, depth: Float = 0.93f): Modifier {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(source) {
        source.interactions.collect { i ->
            when (i) {
                is androidx.compose.foundation.interaction.PressInteraction.Press ->
                    launch { scale.animateTo(depth, tween(90)) }
                is androidx.compose.foundation.interaction.PressInteraction.Release,
                is androidx.compose.foundation.interaction.PressInteraction.Cancel -> launch {
                    if (scale.value > depth + 0.01f) scale.animateTo(depth, tween(70))
                    scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 650f))
                }
            }
        }
    }
    return this.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
