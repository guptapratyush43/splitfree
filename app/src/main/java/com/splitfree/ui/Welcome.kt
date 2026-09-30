package com.splitfree.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Shown once, before the first sign-in; remembered on this phone afterwards. */
object Welcome {
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("welcome", android.content.Context.MODE_PRIVATE)
    fun seen(c: android.content.Context) = prefs(c).getBoolean("seen", false)
    fun markSeen(c: android.content.Context) = prefs(c).edit().putBoolean("seen", true).apply()
}

// --- Pages: a little split coin character and a colourful scene per page ---

private class WelcomePage(val title: String, val body: String, val light: Color, val deep: Color)

private val PAGES = listOf(
    WelcomePage(
        "Split any bill in seconds",
        "Add an expense, pick who paid and who shared it. Split it equally or by exact amounts, right down to the paisa.",
        Color(0xFFE8906A), Color(0xFFC15F3C)
    ),
    WelcomePage(
        "Everyone sees the same balances",
        "Make a group for your flat, a trip or office lunches. Invite friends by link or QR, and everyone sees who owes whom, live.",
        Color(0xFF7AA7F5), Color(0xFF3B5FD0)
    ),
    WelcomePage(
        "Settle up, stay friends",
        "Pay back in one go, send gentle reminders and keep it all backed up to your own Google Drive. Free, with no ads.",
        Color(0xFF45C9AE), Color(0xFF14806E)
    )
)

private val ART_TOP = 24.dp
private val ART_HEIGHT = 300.dp
private val MASCOT = 136.dp
private val INK = Color(0xFF2A1B14)
private val GOLD = Color(0xFFF2B53A)

private fun PagerState.position() = currentPage + currentPageOffsetFraction

/** The page colour at any point of a swipe, blended between neighbours. */
private fun blend(position: Float, pick: (WelcomePage) -> Color): Color {
    val p = position.coerceIn(0f, PAGES.lastIndex.toFloat())
    val i = floor(p).toInt().coerceAtMost(PAGES.lastIndex - 1)
    return lerp(pick(PAGES[i]), pick(PAGES[i + 1]), p - i)
}

/** Seconds since first shown, for idle loops. Read it only in draw/layer lambdas. Frozen when motion is reduced. */
@Composable
private fun rememberClock(): State<Float> {
    val reduce = LocalReduceMotion.current
    return produceState(0f, reduce) {
        if (reduce) return@produceState
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
}

/** 0 → 1 once its page is on screen (after [delayMs]), settling without a wobble; back to 0 when it leaves. */
@Composable
private fun rememberPop(shown: Boolean, delayMs: Long): Animatable<Float, AnimationVector1D> {
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(shown, reduce) {
        when {
            reduce -> a.snapTo(1f)
            shown -> { delay(delayMs); a.animateTo(1f, spring(0.72f, 320f)) }
            else -> a.animateTo(0f, tween(160))
        }
    }
    return a
}

/** Tap with the app's press dip and a haptic tick. */
@Composable
private fun Modifier.tap(depth: Float = 0.88f, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val ctx = LocalContext.current
    return this.pressScale(src, depth).clickable(src, null) { Haptics.tick(ctx); onClick() }
}

@Composable
fun WelcomePager(onFinish: () -> Unit) {
    CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) { WelcomeBody(onFinish) }
}

@Composable
private fun WelcomeBody(onFinish: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val pager = rememberPagerState { PAGES.size }
    val clock = rememberClock()
    var pokes by remember { mutableIntStateOf(0) }
    val dark = scheme.background.luminance() < 0.5f
    // A light tick each time a new page settles.
    LaunchedEffect(pager.settledPage) { if (pager.settledPage > 0) Haptics.tick(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .drawBehind {
                // Two soft colour clouds drift behind everything, tinted by the page.
                val t = clock.value
                val pos = pager.position()
                val a = blend(pos) { it.light }.copy(alpha = if (dark) 0.30f else 0.28f)
                val b = blend(pos) { it.deep }.copy(alpha = if (dark) 0.24f else 0.14f)
                val c1 = Offset(size.width * (0.2f + 0.08f * sin(t * 0.5f)), size.height * (0.16f + 0.05f * cos(t * 0.4f)))
                val c2 = Offset(size.width * (0.86f + 0.07f * cos(t * 0.45f)), size.height * (0.5f + 0.06f * sin(t * 0.35f)))
                val r1 = size.width * 0.8f
                val r2 = size.width * 0.72f
                drawCircle(Brush.radialGradient(listOf(a, Color.Transparent), c1, r1), r1, c1)
                drawCircle(Brush.radialGradient(listOf(b, Color.Transparent), c2, r2), r2, c2)
            }
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val p = PAGES[page]
                val shown = pager.currentPage == page
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Spacer(Modifier.height(ART_TOP))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().height(ART_HEIGHT).graphicsLayer {
                            // The scene trails the swipe a little, so it has depth.
                            translationX = -(page - pager.position()) * size.width * 0.35f
                        }
                    ) {
                        when (page) {
                            0 -> BillScene(shown, clock)
                            1 -> PeopleScene(shown, clock)
                            else -> SettleScene(shown, clock)
                        }
                        // The character is drawn above the pager; this catches its taps and still lets swipes through.
                        Box(Modifier.size(MASCOT).clickable(remember { MutableInteractionSource() }, indication = null) {
                            Haptics.press(ctx)
                            pokes++
                        })
                    }
                    Spacer(Modifier.height(24.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer {
                            val off = page - pager.position()
                            alpha = (1f - abs(off) * 1.4f).coerceIn(0f, 1f)
                            translationY = abs(off) * 40.dp.toPx()
                        }
                    ) {
                        Text(p.title, style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp, lineHeight = 36.sp),
                            color = scheme.onBackground, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        Text(p.body, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
            // The character stays put while the pages slide beneath it, changing colour as you swipe.
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(ART_TOP))
                Box(Modifier.fillMaxWidth().height(ART_HEIGHT), contentAlignment = Alignment.Center) {
                    Splitty(pager, pokes, clock, Modifier.size(MASCOT))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
            repeat(PAGES.size) { i ->
                val active = pager.currentPage == i
                val w by animateFloatAsState(if (active) 24f else 8f, spring(0.8f, 420f), label = "welcomeDot")
                val c by animateColorAsState(if (active) PAGES[i].deep else scheme.outline, label = "welcomeDotColor")
                Box(Modifier.height(8.dp).width(w.dp).background(c, RoundedCornerShape(50)))
            }
        }
        Spacer(Modifier.height(28.dp))
        val last = pager.currentPage == PAGES.lastIndex
        PrimaryButton(
            if (last) "Get started" else "Next", null,
            onClick = { if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        )
        Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
            if (!last) Text(
                "Skip", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant,
                modifier = Modifier.tap(0.92f) { onFinish() }.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

// --- The character ---------------------------------------------------------

/**
 * A round coin that floats, blinks, looks the way you swipe and jumps with a grin when tapped.
 * It is made of two halves: they drift apart while the bill is being split and snap back
 * together, with a handshake, once everyone is settled on the last page.
 */
@Composable
private fun Splitty(pager: PagerState, pokes: Int, clock: State<Float>, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val appear = remember { Animatable(if (reduce) 1f else 0f) }
    val blink = remember { Animatable(0f) }
    val squash = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }
    val happy = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (appear.value < 1f) { delay(180); appear.animateTo(1f, spring(0.7f, 380f)) }
        // Says hello once it has landed.
        if (!reduce) { delay(250); happy.snapTo(1f); delay(900); happy.animateTo(0f, tween(200)) }
    }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(2200, 4200))
            repeat(if (Random.nextInt(4) == 0) 2 else 1) {
                blink.animateTo(1f, tween(70)); blink.animateTo(0f, tween(120))
            }
        }
    }
    LaunchedEffect(pokes) {
        if (pokes == 0) return@LaunchedEffect
        launch { squash.animateTo(1f, tween(90)); squash.animateTo(0f, spring(0.5f, 380f)) }
        launch { delay(60); hop.animateTo(1f, spring(1f, 700f)); hop.animateTo(0f, spring(0.6f, 420f)) }
        // The halves pop apart and click back together.
        launch { burst.animateTo(1f, tween(120)); burst.animateTo(0f, spring(0.55f, 300f)) }
        happy.snapTo(1f); delay(1100); happy.animateTo(0f, tween(200))
    }

    Box(modifier) {
        // Its shadow stays on the ground and shrinks as it jumps.
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width
            val k = 1f - 0.45f * hop.value
            drawOval(Color.Black.copy(alpha = 0.12f * k * appear.value), Offset(0.5f * s - 0.3f * s * k, 0.91f * s), Size(0.6f * s * k, 0.07f * s))
        }
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                val sq = squash.value
                val a = appear.value
                translationY = sin(clock.value * 2.2f) * 5.dp.toPx() - hop.value * 30.dp.toPx()
                scaleX = a * (1f + 0.16f * sq)
                scaleY = a * (1f - 0.16f * sq)
                transformOrigin = TransformOrigin(0.5f, 0.9f)
            }
        ) {
            drawSplitty(pager.position(), pager.currentPageOffsetFraction, clock.value, blink.value, happy.value, burst.value)
        }
    }
}

private fun DrawScope.drawSplitty(pos: Float, drag: Float, t: Float, blink: Float, happy: Float, burst: Float) {
    val s = size.width
    val light = blend(pos) { it.light }
    val deep = blend(pos) { it.deep }
    val limb = lerp(deep, Color.Black, 0.22f)
    // How far apart the halves are: a little on page 1, most while splitting (page 2), joined when settled (page 3).
    val split = when {
        pos <= 1f -> 0.03f + 0.05f * pos
        else -> 0.08f * (1f - (pos - 1f).coerceIn(0f, 1f))
    } * s + burst * 0.07f * s
    val settled = (pos - 1f).coerceIn(0f, 1f)
    val cx = 0.5f * s
    val cy = 0.47f * s
    val r = 0.37f * s

    // Feet
    drawRoundRect(limb, Offset(0.28f * s - split / 2f, 0.8f * s), Size(0.15f * s, 0.1f * s), CornerRadius(0.05f * s))
    drawRoundRect(limb, Offset(0.57f * s + split / 2f, 0.8f * s), Size(0.15f * s, 0.1f * s), CornerRadius(0.05f * s))
    // Arms: waving while it grins; on the last page they meet in front for a handshake.
    val wave = happy * sin(t * 16f) * 22f
    val reach = settled * 58f
    rotate(-18f - wave + reach, Offset(0.16f * s - split / 2f, 0.55f * s)) {
        drawRoundRect(limb, Offset(0.02f * s - split / 2f, 0.5f * s), Size(0.18f * s, 0.1f * s), CornerRadius(0.05f * s))
    }
    rotate(18f + wave - reach, Offset(0.84f * s + split / 2f, 0.55f * s)) {
        drawRoundRect(limb, Offset(0.8f * s + split / 2f, 0.5f * s), Size(0.18f * s, 0.1f * s), CornerRadius(0.05f * s))
    }

    // Body: a coin in two halves with a rim and a glossy top.
    val body = Brush.verticalGradient(listOf(light, deep), startY = cy - r, endY = cy + r)
    for (side in intArrayOf(-1, 1)) {
        translate(left = side * split / 2f) {
            clipRect(left = if (side < 0) 0f else cx, right = if (side < 0) cx else s) {
                drawCircle(body, r, Offset(cx, cy))
                drawCircle(Color.White.copy(alpha = 0.35f), r - 0.035f * s, Offset(cx, cy), style = Stroke(0.012f * s))
            }
        }
    }
    drawRoundRect(Color.White.copy(alpha = 0.24f), Offset(0.27f * s - split / 2f, 0.15f * s), Size(0.2f * s, 0.07f * s), CornerRadius(0.035f * s))

    // Eyes ride on their halves: follow the swipe, wander a little, blink; happy arcs while it grins.
    val look = (-drag * 2.4f).coerceIn(-1f, 1f) * 0.8f + 0.3f * sin(t * 0.7f)
    val eyeY = 0.4f * s
    val eyeR = 0.075f * s
    for ((ex, side) in listOf(0.37f * s to -1, 0.63f * s to 1)) {
        val x = ex + side * split / 2f
        if (happy > 0.5f) {
            drawArc(INK, 200f, 140f, false, Offset(x - eyeR * 0.9f, eyeY - eyeR * 0.5f), Size(eyeR * 1.8f, eyeR * 1.5f), style = Stroke(0.032f * s, cap = StrokeCap.Round))
        } else {
            val h = eyeR * 2f * (1f - 0.9f * blink)
            drawOval(Color.White, Offset(x - eyeR, eyeY - h / 2f), Size(eyeR * 2f, h))
            if (blink < 0.6f) {
                val px = x + look * 0.03f * s
                drawCircle(INK, 0.042f * s * (1f - blink), Offset(px, eyeY + 0.01f * s))
                drawCircle(Color.White, 0.014f * s, Offset(px + 0.015f * s, eyeY - 0.011f * s))
            }
        }
    }
    // Cheeks
    drawCircle(Color(0xFFFF8FA3).copy(alpha = 0.5f), 0.04f * s, Offset(0.25f * s - split / 2f, 0.5f * s))
    drawCircle(Color(0xFFFF8FA3).copy(alpha = 0.5f), 0.04f * s, Offset(0.75f * s + split / 2f, 0.5f * s))
    // Mouth: a smile, or wide open while it grins (drawn across the gap).
    if (happy > 0.5f || settled > 0.95f) {
        drawArc(INK, 0f, 180f, true, Offset(0.42f * s, 0.49f * s), Size(0.16f * s, 0.13f * s))
        drawCircle(Color(0xFFFF7A8A), 0.03f * s, Offset(0.5f * s, 0.585f * s))
    } else {
        drawArc(INK, 20f, 140f, false, Offset(0.43f * s, 0.49f * s), Size(0.14f * s, 0.09f * s), style = Stroke(0.028f * s, cap = StrokeCap.Round))
    }
}

// --- Scenes ------------------------------------------------------------------

private class Item(val emoji: String, val label: String, val x: Dp, val y: Dp)

private val ITEMS = listOf(
    Item("🍕", "Pizza  ₹600", (-100).dp, (-96).dp),
    Item("🚕", "Cab  ₹240", 104.dp, (-70).dp),
    Item("☕", "Chai  ₹90", (-108).dp, 78.dp)
)

/** Page 1: bill items fly out of the character and float; coins bob around it. Tap anything. */
@Composable
private fun BillScene(shown: Boolean, clock: State<Float>) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ITEMS.forEachIndexed { i, it ->
            val pop = rememberPop(shown, 120L + i * 90L)
            val wiggle = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .graphicsLayer {
                        val p = pop.value
                        translationX = it.x.toPx() * p
                        translationY = it.y.toPx() * p + sin(clock.value * 1.8f + i * 1.3f) * 5.dp.toPx()
                        rotationZ = sin(clock.value * 1.3f + i) * 3f + wiggle.value
                        scaleX = p; scaleY = p
                        alpha = p.coerceIn(0f, 1f)
                    }
                    .tap {
                        scope.launch {
                            for (r in floatArrayOf(-12f, 10f, -6f, 3f)) wiggle.animateTo(r, tween(60))
                            wiggle.animateTo(0f, spring(0.6f, 400f))
                        }
                    }
                    .background(scheme.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, PAGES[0].deep.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                Text(it.emoji, fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text(it.label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
            }
        }
        Coin(118.dp, 60.dp, 0f, shown, 380, clock)
        Coin(84.dp, 118.dp, 2f, shown, 460, clock, 26.dp)
        Coin((-40).dp, (-128).dp, 4f, shown, 300, clock, 28.dp)
    }
}

/** A gold rupee coin that bobs, and spins when tapped. */
@Composable
private fun Coin(x: Dp, y: Dp, phase: Float, shown: Boolean, delayMs: Long, clock: State<Float>, size: Dp = 34.dp) {
    val pop = rememberPop(shown, delayMs)
    val spin = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .graphicsLayer {
                translationX = x.toPx()
                translationY = y.toPx() + sin(clock.value * 2.4f + phase) * 6.dp.toPx()
                scaleX = pop.value; scaleY = pop.value
                rotationY = spin.value
                cameraDistance = 12f * density
            }
            .tap(0.85f) { scope.launch { spin.animateTo(spin.value + 360f, spring(0.8f, 120f)) } }
            .size(size)
            .background(Brush.linearGradient(listOf(Color(0xFFFFD66B), Color(0xFFE0A21C))), CircleShape)
            .border(2.dp, Color(0xFFFFEBB0), CircleShape)
    ) { Text("₹", color = Color(0xFF8A5A00), fontWeight = FontWeight.Bold, fontSize = (size.value * 0.47f).sp) }
}

private class Friend(val avatar: Int, val tag: String, val owed: Boolean, val x: Dp, val y: Dp)

private val FRIENDS = listOf(
    Friend(3, "+₹340", true, (-110).dp, (-84).dp),
    Friend(11, "−₹120", false, 110.dp, (-84).dp),
    Friend(17, "+₹90", true, (-104).dp, 82.dp),
    Friend(6, "−₹310", false, 106.dp, 82.dp)
)

/** Page 2: friends pop out around the character with what they owe or get back; tap one to make it hop. */
@Composable
private fun PeopleScene(shown: Boolean, clock: State<Float>) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Dotted lines from each friend to the character: everyone is in the same group.
        val link = rememberPop(shown, 200)
        Canvas(Modifier.fillMaxSize()) {
            val o = link.value.coerceIn(0f, 1f)
            FRIENDS.forEach { f ->
                val end = Offset(center.x + f.x.toPx() * o, center.y + f.y.toPx() * o)
                drawLine(PAGES[1].deep.copy(alpha = 0.28f * o), center, end, 2.dp.toPx(), StrokeCap.Round,
                    androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()), (clock.value * 18f) % 12f))
            }
        }
        FRIENDS.forEachIndexed { i, f ->
            val pop = rememberPop(shown, 120L + i * 90L)
            val hop = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer {
                        val p = pop.value
                        translationX = f.x.toPx() * p
                        translationY = f.y.toPx() * p + sin(clock.value * 1.7f + i * 1.6f) * 5.dp.toPx() - hop.value * 18.dp.toPx()
                        scaleX = p; scaleY = p
                        alpha = p.coerceIn(0f, 1f)
                    }
                    .tap { scope.launch { hop.animateTo(1f, spring(1f, 700f)); hop.animateTo(0f, spring(0.55f, 420f)) } }
            ) {
                Box(Modifier.border(2.dp, Color.White, CircleShape)) { AvatarArt(f.avatar, 52.dp) }
                Spacer(Modifier.height(4.dp))
                Text(f.tag, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1,
                    modifier = Modifier.background(if (f.owed) Color(0xFF1E9E6A) else Color(0xFFD9534F), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

private class Badge(val icon: ImageVector, val label: String, val x: Dp, val y: Dp)

private val BADGES = listOf(
    Badge(Icons.Rounded.Handshake, "Pay back", (-96).dp, (-104).dp),
    Badge(Icons.Rounded.NotificationsActive, "Reminders", 100.dp, (-60).dp),
    Badge(Icons.Rounded.CloudDone, "Drive backup", (-90).dp, 96.dp)
)

private val SPARKS = listOf(-128f to -40f, 128f to 20f, -60f to -128f, 70f to -118f, 120f to 110f, -130f to 60f)

/** Page 3: celebration rings pulse out of the character, sparkles twinkle, and badges float by. */
@Composable
private fun SettleScene(shown: Boolean, clock: State<Float>) {
    val scheme = MaterialTheme.colorScheme
    val on = rememberPop(shown, 150)
    val green = Color(0xFF1E9E8A)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val o = on.value.coerceIn(0f, 1f)
            val base = 74.dp.toPx()
            for (i in 0 until 3) {
                val k = (t * 0.45f + i / 3f) % 1f
                drawCircle(green.copy(alpha = (1f - k) * 0.4f * o), base + k * 72.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            }
            SPARKS.forEachIndexed { i, (fx, fy) ->
                val tw = 0.55f + 0.45f * sin(t * 2.6f + i * 1.7f)
                sparkle(Offset(center.x + fx.dp.toPx(), center.y + fy.dp.toPx()), (6 + (i % 3) * 3).dp.toPx() * tw * o, if (i % 2 == 0) GOLD else green)
            }
        }
        BADGES.forEachIndexed { i, b ->
            val pop = rememberPop(shown, 250L + i * 110L)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .graphicsLayer {
                        translationX = b.x.toPx()
                        translationY = b.y.toPx() + sin(clock.value * 1.7f + i * 2f) * 5.dp.toPx()
                        scaleX = pop.value; scaleY = pop.value
                    }
                    .tap(0.9f) {}
                    .background(scheme.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, green.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Icon(b.icon, null, tint = green, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(b.label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1)
            }
        }
    }
}

/** A four-pointed twinkle. */
private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    if (r <= 0.5f) return
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(p, color)
}
