package com.splitfree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
/** A soft bordered card, the same block the Aadhaar Verifier uses everywhere. */
@Composable
fun WarmCard(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    borderWidth: Dp = 1.dp,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .entrance()
            .then(if (onClick != null || onLongClick != null) Modifier.pressScale(src, 0.97f) else Modifier)
            .clip(shape)
            .background(background, shape)
            .border(borderWidth, borderColor, shape)
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(interactionSource = src, indication = null, onLongClick = onLongClick?.let { l -> { Haptics.press(ctx); l() } }, onClick = { Haptics.tick(ctx); onClick?.invoke() }) else Modifier)
            .padding(padding),
        content = content
    )
}

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
fun IconBubble(icon: ImageVector, size: Dp = 76.dp, iconSize: Dp = 34.dp, background: Color = MaterialTheme.colorScheme.primaryContainer) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).background(background, CircleShape)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Button(
        onClick = { Haptics.press(ctx); onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Two or more pills in a bordered track, as in the verifier's date choice. */
@Composable
fun PillToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = modifier
            .background(scheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) scheme.primary else Color.Transparent)
                    .clickable { Haptics.tick(ctx); onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) scheme.onPrimary else scheme.onSurfaceVariant)
            }
        }
    }
}

/** Grey description under a row title, spaced so two-line text reads evenly. */
@Composable
fun RowBody(text: String) {
    Spacer(Modifier.height(2.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun Footnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    )
}

object Fmt {
    private val zone get() = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val clockFmt = DateTimeFormatter.ofPattern("h:mm", Locale.US)
    private val ampmFmt = DateTimeFormatter.ofPattern("a", Locale.US)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.US)
    private val longDayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US)

    private fun zoned(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)

    fun time(ms: Long): String = timeFmt.format(zoned(ms))
    fun clock(ms: Long): String = clockFmt.format(zoned(ms))
    fun ampm(ms: Long): String = ampmFmt.format(zoned(ms))
    fun longDay(ms: Long): String = longDayFmt.format(zoned(ms))

    fun day(ms: Long, now: Long = System.currentTimeMillis()): String {
        val date = zoned(ms).toLocalDate()
        return when (ChronoUnit.DAYS.between(zoned(now).toLocalDate(), date)) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            else -> dayFmt.format(date)
        }
    }

    fun date(date: LocalDate): String = when (ChronoUnit.DAYS.between(LocalDate.now(), date)) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> dayFmt.format(date)
    }

    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = ms - now
        val mins = Math.abs(diff) / 60_000
        val text = when {
            mins < 1 -> return if (diff >= 0) "in under a minute" else "just now"
            mins < 60 -> "$mins min"
            mins < 24 * 60 -> "${mins / 60} h" + if (mins % 60 > 0) " ${mins % 60} min" else ""
            else -> "${mins / (24 * 60)} d" + if ((mins / 60) % 24 > 0) " ${(mins / 60) % 24} h" else ""
        }
        return if (diff >= 0) "in $text" else "$text ago"
    }

    fun millisOf(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Next time the wall clock shows hour:minute, today or tomorrow. */
    fun nextOccurrence(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val today = millisOf(zoned(now).toLocalDate(), hour, minute)
        return if (today > now) today else millisOf(zoned(now).toLocalDate().plusDays(1), hour, minute)
    }
}
