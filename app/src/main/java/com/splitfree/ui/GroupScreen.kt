package com.splitfree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.splitfree.ui.theme.LocalStatusColors
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import com.splitfree.money.Debt
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.NavViewModel
import com.splitfree.Screen
import com.splitfree.data.Auth
import com.splitfree.data.Expense
import com.splitfree.data.Group
import com.splitfree.data.Repeat
import com.splitfree.data.Repo
import com.splitfree.money.Balances
import com.splitfree.money.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val monShort = DateTimeFormatter.ofPattern("MMM", Locale.US)
private val dayNum = DateTimeFormatter.ofPattern("dd", Locale.US)
private fun zoned(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

private val tabNames = listOf("Expenses", "Balances", "Totals")

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GroupScreen(nav: NavViewModel, gid: String, startTab: Int = 0) {
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val all by Repo.expenses.collectAsStateWithLifecycle()
    val loaded by Repo.groupsLoaded.collectAsStateWithLifecycle()
    val group = groups.firstOrNull { it.id == gid }
    var seen by rememberSaveable { mutableStateOf(false) }
    if (group != null) seen = true
    if (group == null) {
        // A group created a moment ago may not be in the list yet, so wait a
        // little before deciding it's gone (removed from it, or deleted).
        LaunchedEffect(loaded, seen) {
            if (!loaded) return@LaunchedEffect
            if (!seen) kotlinx.coroutines.delay(4000)
            nav.home()
        }
        return
    }
    LaunchedEffect(group.name) { com.splitfree.data.Cover.ensure(group) }

    val me = Auth.uid!!
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val expenses = all[gid].orEmpty().filter { !it.deleted }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var remind by remember { mutableStateOf<Debt?>(null) }
    val pager = rememberPagerState(initialPage = startTab.coerceIn(0, tabNames.size - 1)) { tabNames.size }

    val items = expenses.map { it.flows }
    val nets = Balances.nets(items)
    val debts = if (group.simplify) Balances.settle(nets) else Balances.pairwise(items)
    val mine = debts.filter { it.from == me || it.to == me }
    val myNet = nets[me] ?: 0

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- banner: place photo when found, the group's colour otherwise ----
            val hasPhoto = group.cover.isNotBlank()
            Box(Modifier.fillMaxWidth().height(if (hasPhoto) 190.dp else 150.dp).background(groupTint(group.id).copy(alpha = 0.16f))) {
                if (hasPhoto) {
                    coil.compose.AsyncImage(
                        model = group.cover, contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                    // Soft shade at the bottom keeps the title readable on any photo.
                    Box(Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.15f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)
                        )
                    ))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    GlassCircle(Icons.AutoMirrored.Rounded.ArrowBack, "Back", hasPhoto) { nav.pop() }
                    Spacer(Modifier.weight(1f))
                    GlassCircle(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search", hasPhoto) {
                        searching = !searching; query = ""
                        if (searching) scope.launch { pager.scrollToPage(0) }
                    }
                    Spacer(Modifier.width(8.dp))
                    GlassCircle(Icons.Rounded.Settings, "Group settings", hasPhoto) { nav.push(Screen.GroupSettings(gid)) }
                }
                Text(
                    group.name, style = MaterialTheme.typography.displaySmall,
                    color = if (hasPhoto) Color.White else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                )
            }

            // ---- your balance in this group ----
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                if (expenses.isNotEmpty()) Text(
                    when {
                        myNet > 0 -> "You are owed ${Money.format(myNet)} overall"
                        myNet < 0 -> "You owe ${Money.format(-myNet)} overall"
                        else -> "You are all settled up in this group"
                    },
                    style = MaterialTheme.typography.titleMedium, color = moneyColor(myNet)
                )
                mine.take(2).forEach { d ->
                    Text(
                        buildAnnotatedString {
                            append(if (d.to == me) "${group.name(d.from, me)} owes you " else "You owe ${group.name(d.to, me)} ")
                            withStyle(SpanStyle(color = moneyColor(if (d.to == me) 1 else -1))) { append(Money.format(d.amount)) }
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (mine.size > 2) Text("Plus ${mine.size - 2} more balances", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ---- tabs: tap or swipe between them ----
            val chips = androidx.compose.foundation.lazy.rememberLazyListState()
            var lastPage by remember { mutableStateOf(pager.settledPage) }
            LaunchedEffect(pager.settledPage) {
                if (pager.settledPage != lastPage) { Haptics.tick(context); lastPage = pager.settledPage }
                chips.animateScrollToItem((pager.settledPage - 1).coerceAtLeast(0))
            }
            androidx.compose.foundation.lazy.LazyRow(
                state = chips,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(tabNames.size) { i ->
                    ActionPill(tabNames[i], { scope.launch { pager.animateScrollToPage(i) } }, filled = pager.currentPage == i)
                }
            }
            HairLine()

            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                when (page) {
                    // Payments marked as settled live in Balances and Activity, not in the expense list.
                    0 -> ExpensesPage(nav, group, expenses.filter { !it.settlement }, searching, query) { query = it }
                    1 -> BalancesPage(nav, group, nets, debts, me) { remind = it }
                    else -> TotalsPage(group, all[gid].orEmpty(), me)
                }
            }
        }
        if (pager.currentPage == 0) FloatingAdd("Add expense", Icons.Rounded.ReceiptLong, { nav.push(Screen.Editor(gid, null)) },
            Modifier.align(Alignment.BottomEnd).padding(20.dp))
    }

    remind?.let { d ->
        ConfirmDialog("Send a reminder?", "${group.name(d.from, me)} gets a notification that they owe you ${Money.format(d.amount)}.", "Remind",
            onConfirm = { Repo.remind(group, d.from, d.amount); toast(context, "Reminder sent") }, onDismiss = { remind = null }, danger = false)
    }
}

@Composable
private fun ExpensesPage(nav: NavViewModel, group: Group, expenses: List<Expense>, searching: Boolean, query: String, onQuery: (String) -> Unit) {
    val q = query.trim().lowercase()
    val shown = if (!searching || q.isEmpty()) expenses else expenses.filter {
        it.title.lowercase().contains(q) || it.note.lowercase().contains(q) || it.category.label.lowercase().contains(q)
    }
    val byMonth = shown.groupBy { monthFmt.format(zoned(it.date)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 110.dp)) {
        if (searching) item { Box(Modifier.padding(horizontal = 20.dp)) { Field(query, onQuery, placeholder = "Search this group") }; Spacer(Modifier.height(12.dp)) }
        if (expenses.isEmpty()) item {
            EmptyState(Icons.Rounded.ReceiptLong, "No expenses here yet",
                if (group.members.size == 1) "Add people to this group, then add an expense to get this party started."
                else "Add an expense to get this party started.") {
                if (group.members.size == 1) {
                    Spacer(Modifier.height(16.dp))
                    SecondaryButton("Add people", Icons.Rounded.PersonAdd, { nav.push(Screen.GroupSettings(group.id)) })
                }
            }
        } else if (shown.isEmpty()) item { Footnote("Nothing matches “$query”.") }
        byMonth.forEach { (month, list) ->
            item(key = "m$month") { SectionLabel(month, Modifier.padding(start = 24.dp, top = 4.dp)) }
            items(list, key = { it.id }) { e ->
                Box(Modifier.animateItem(placementSpec = null, fadeOutSpec = null).padding(horizontal = 20.dp)) { ExpenseRow(group, e) { nav.push(Screen.Detail(group.id, e.id)) } }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Round see-through button over the banner photo: the picture shows through, a light rim keeps its edge. */
@Composable
private fun GlassCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onPhoto: Boolean, onClick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val fill = if (onPhoto) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    val rim = if (onPhoto) Color.White.copy(alpha = 0.38f) else MaterialTheme.colorScheme.outline
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(46.dp).pressScale(src).clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(fill.copy(alpha = fill.alpha * 1.5f), fill)))
            .border(1.dp, rim, androidx.compose.foundation.shape.CircleShape)
            .clickable(interactionSource = src, indication = null) { Haptics.tick(ctx); onClick() }
    ) { Icon(icon, label, tint = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
}

/** A small capsule that gently breathes: "Everyone is settled up". */
@Composable
private fun BreathingCapsule(text: String) {
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "breathe")
    val k by loop.animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "k")
    val green = moneyColor(1)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 10.dp)
            .graphicsLayer { val sc = 1f + 0.04f * k; scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            .drawBehind {
                // A soft glow that swells and fades with each breath.
                drawRoundRect(green.copy(alpha = 0.10f + 0.10f * k), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            }
            .border(1.dp, green.copy(alpha = 0.35f + 0.25f * k), androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Rounded.CheckCircle, null, tint = green, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = green)
    }
}

private enum class BalanceSort(val label: String) { GETS_BACK("Gets back"), OWES("Owes"), NAME("Name") }

/** Your card first, a thin line, then everyone else, sortable. Tap anyone to see their pending expenses. */
@Composable
private fun BalancesPage(nav: NavViewModel, group: Group, nets: Map<String, Long>, debts: List<Debt>, me: String, onRemind: (Debt) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sort by rememberSaveable { mutableStateOf(BalanceSort.GETS_BACK) }
    val others = (group.members + nets.keys).distinct().filter { it != me }.let { list ->
        when (sort) {
            BalanceSort.GETS_BACK -> list.sortedWith(compareByDescending<String> { nets[it] ?: 0 }.thenBy { group.name(it, me).lowercase() })
            BalanceSort.OWES -> list.sortedWith(compareBy<String> { nets[it] ?: 0 }.thenBy { group.name(it, me).lowercase() })
            BalanceSort.NAME -> list.sortedBy { group.name(it, me).lowercase() }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp)) {
        item {
            if (debts.isEmpty()) BreathingCapsule("Everyone is settled up") else Spacer(Modifier.height(10.dp))
            SectionLabel("Members", Modifier.padding(bottom = 0.dp))
            Text(if (debts.isEmpty()) "Tap a member to see their expenses." else "Tap a member to see what is pending.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        }
        item(key = me) { MemberCard(nav, group, me, nets[me] ?: 0, me) }
        if (others.isNotEmpty()) item(key = "others") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
                Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline))
                Spacer(Modifier.width(10.dp))
                // Small sort capsule: tap to cycle Gets back → Owes → Name.
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.pressScale(src).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .clickable(interactionSource = src, indication = null) {
                            Haptics.tick(ctx); sort = BalanceSort.entries[(sort.ordinal + 1) % BalanceSort.entries.size]
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Rounded.SwapVert, "Sort", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.animation.AnimatedContent(sort, label = "sort",
                        transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) }
                    ) { st -> Text(st.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        items(others, key = { it }) { uid ->
            Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { MemberCard(nav, group, uid, nets[uid] ?: 0, me) }
        }
    }
}

@Composable
private fun MemberCard(nav: NavViewModel, group: Group, uid: String, n: Long, me: String) {
    WarmCard(padding = 14.dp, onClick = { nav.push(Screen.Member(group.id, uid)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(group.info[uid]?.name ?: "?", uid, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name(uid, me) + if (uid !in group.members) " (left)" else "", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when { n > 0 -> "Gets back ${Money.format(n)}"; n < 0 -> "Owes ${Money.format(-n)}"; else -> "Settled up" },
                    style = MaterialTheme.typography.bodyMedium, color = moneyColor(n)
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
}

/**
 * The expenses behind [uid]'s current balance: everything since the last time
 * their balance was zero, newest first, with the effect each one had on them.
 */
private fun pendingFor(uid: String, expenses: List<Expense>): List<Pair<Expense, Long>> {
    val ordered = expenses.sortedWith(compareBy<Expense> { it.date }.thenBy { it.createdAt })
    var bal = 0L
    var from = 0
    ordered.forEachIndexed { i, e ->
        bal += (e.paid[uid] ?: 0) - (e.shares[uid] ?: 0)
        if (bal == 0L) from = i + 1
    }
    return ordered.drop(from).map { it to ((it.paid[uid] ?: 0) - (it.shares[uid] ?: 0)) }
        .filter { it.second != 0L }.reversed()
}

/** One member's pending payments with everyone else in the group. */
@Composable
fun MemberScreen(nav: NavViewModel, gid: String, uid: String) {
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val all by Repo.expenses.collectAsStateWithLifecycle()
    val group = groups.firstOrNull { it.id == gid } ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    val me = Auth.uid!!
    val context = androidx.compose.ui.platform.LocalContext.current
    val items = all[gid].orEmpty().filter { !it.deleted }.map { it.flows }
    val nets = Balances.nets(items)
    val debts = (if (group.simplify) Balances.settle(nets) else Balances.pairwise(items)).filter { it.from == uid || it.to == uid }
    val n = nets[uid] ?: 0
    var remind by remember { mutableStateOf<Debt?>(null) }
    var settle by remember { mutableStateOf<Debt?>(null) }
    val pending = if (n == 0L) emptyList() else pendingFor(uid, all[gid].orEmpty().filter { !it.deleted })
    Column(Modifier.fillMaxSize()) {
        TopBar(group.name(uid, me).let { if (it == "You") "Your balance" else it }, onBack = { nav.pop() })
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Avatar(group.info[uid]?.name ?: "?", uid, 96.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(group.info[uid]?.name ?: "Someone", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when { n > 0 -> "Gets back ${Money.format(n)} in total"; n < 0 -> "Owes ${Money.format(-n)} in total"; else -> "All settled up" },
                        style = MaterialTheme.typography.titleMedium, color = moneyColor(n)
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (debts.any { it.to != me && it.from == me }) {
                    Text("Only the person you owe can mark a payment as settled.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                }
                if (debts.isEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BreathingCapsule("All settled up") }
                else SectionLabel("Who pays whom")
            }
            items(debts, key = { it.from + it.to }) { d ->
                val other = if (d.from == uid) d.to else d.from
                WarmCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(group.info[other]?.name ?: "?", other, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${if (d.from == me) "You" else group.name(d.from, me)} → ${if (d.to == me) "you" else group.name(d.to, me)}",
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(Money.format(d.amount), style = MaterialTheme.typography.titleMedium,
                                color = if (d.to == me) moneyColor(1) else if (d.from == me) moneyColor(-1) else MaterialTheme.colorScheme.onSurface)
                        }
                        if (d.to == me) {
                            ActionPill("Remind", { remind = d })
                            Spacer(Modifier.width(8.dp))
                            ActionPill("Mark as settled", { settle = d }, filled = true)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (pending.isNotEmpty()) item { Spacer(Modifier.height(8.dp)); SectionLabel("Pending expenses") }
            pending.groupBy { Fmt.day(it.first.date) }.forEach { (day, list) ->
                item(key = "d$day") {
                    Text(day, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp))
                }
                items(list, key = { "p" + it.first.id }) { (e, effect) ->
                    PendingRow(group, e, effect, uid, me) { nav.push(Screen.Detail(gid, e.id)) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    remind?.let { d ->
        ConfirmDialog("Send a reminder?", "${group.name(d.from, me)} gets a notification that they owe you ${Money.format(d.amount)}.", "Remind",
            onConfirm = { Repo.remind(group, d.from, d.amount); toast(context, "Reminder sent") }, onDismiss = { remind = null }, danger = false)
    }
    settle?.let { d ->
        ConfirmDialog("Mark as settled?", "${group.name(d.from, me)} paid you ${Money.format(d.amount)}. This clears it from the balances.", "Mark as settled",
            onConfirm = {
                Repo.settle(group, d.from, d.to, d.amount, System.currentTimeMillis())
                Haptics.success(context); toast(context, "Marked as settled")
            }, onDismiss = { settle = null }, danger = false)
    }
}

/** One expense behind a member's balance: category, title, who added it and what it means for them. */
@Composable
private fun PendingRow(group: Group, e: Expense, effect: Long, uid: String, me: String, onClick: () -> Unit) {
    WarmCard(onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBubble(if (e.settlement && e.category == com.splitfree.data.Category.GENERAL) "🤝" else e.category.emoji, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = if (e.settlement) {
                    val from = e.paid.keys.firstOrNull()?.let { group.name(it, me) } ?: "Someone"
                    val to = e.shares.keys.firstOrNull()?.let { if (it == me) "you" else group.name(it, me) } ?: "someone"
                    "$from paid $to"
                } else e.title
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${e.category.label} · Added by ${group.name(e.createdBy, me)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                val you = uid == me
                Text(if (effect > 0) (if (you) "You lent" else "Lent") else (if (you) "You borrowed" else "Borrowed"),
                    style = MaterialTheme.typography.bodySmall, color = moneyColor(effect))
                Text(Money.format(abs(effect)), style = MaterialTheme.typography.titleSmall, color = moneyColor(effect))
            }
        }
    }
}

/** Centred icon in a soft circle, a title and a line of text: the shared empty / status block. */
@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, extra: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
        IconBubble(icon)
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        extra()
    }
}

/** Spending at a glance: a hero total, your part of it, who paid, and where the money went. */
@Composable
private fun TotalsPage(group: Group, allExpenses: List<Expense>, me: String) {
    val real = allExpenses.filter { !it.deleted && !it.settlement }
    val payments = allExpenses.filter { !it.deleted && it.settlement }
    val total = real.sumOf { it.amount }
    if (real.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            EmptyState(Icons.Rounded.PieChart, "Nothing to total yet", "Totals appear once the group has its first expense.") {}
        }
        return
    }
    val paid = real.sumOf { it.paid[me] ?: 0 }
    val share = real.sumOf { it.shares[me] ?: 0 }
    val byPayer = real.flatMap { it.paid.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }.entries.sortedByDescending { it.value }
    val cats = real.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amount } }.entries.sortedByDescending { it.value }
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp)) {
        // hero
        Column(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.72f))))
                .padding(22.dp)
        ) {
            Text("TOTAL GROUP SPENDING", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(6.dp))
            Text(Money.format(total), style = MaterialTheme.typography.displaySmall, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text("${real.size} expense${if (real.size == 1) "" else "s"} · ${payments.size} payment${if (payments.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        }
        Spacer(Modifier.height(14.dp))
        // you
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("You paid", paid, total, LocalStatusColors.current.success, Modifier.weight(1f))
            StatCard("Your share", share, total, LocalStatusColors.current.owe, Modifier.weight(1f))
        }
        // who paid
        Spacer(Modifier.height(24.dp))
        SectionLabel("Who paid")
        WarmCard(padding = 16.dp) {
            val max = byPayer.firstOrNull()?.value ?: 1
            byPayer.forEachIndexed { i, (uid, amt) ->
                if (i > 0) Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(group.info[uid]?.name ?: "?", uid, 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(group.name(uid, me), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(Money.format(amt), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.height(6.dp))
                        Bar(amt.toFloat() / max, groupTint(uid))
                    }
                }
            }
        }
        // categories
        Spacer(Modifier.height(24.dp))
        SectionLabel("Where the money went")
        WarmCard(padding = 16.dp) {
            cats.forEachIndexed { i, (c, amt) ->
                if (i > 0) Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBubble(c.emoji, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(c.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text("${Money.format(amt)} · ${(amt * 100 / total)}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.height(6.dp))
                        Bar(amt.toFloat() / total, accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Long, total: Long, color: Color, modifier: Modifier) {
    WarmCard(modifier = modifier, padding = 16.dp) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(Money.format(value), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        Bar(if (total > 0) value.toFloat() / total else 0f, color)
        Spacer(Modifier.height(6.dp))
        Text("${if (total > 0) value * 100 / total else 0}% of total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Rounded progress bar in the app palette. */
@Composable
private fun Bar(fraction: Float, color: Color) {
    val f by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), androidx.compose.animation.core.tween(500), label = "bar")
    Box(Modifier.fillMaxWidth().height(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(f).height(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(color))
    }
}

@Composable
private fun TotalLine(label: String, value: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(Money.format(value), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** One expense: date column, category, title with who paid, and what it means for you. */
@Composable
fun ExpenseRow(group: Group, e: Expense, showGroup: Boolean = false, onClick: () -> Unit) {
    val me = Auth.uid
    val z = zoned(e.date)
    WarmCard(onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(34.dp)) {
                Text(monShort.format(z), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dayNum.format(z), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(10.dp))
            CategoryBubble(if (e.settlement && e.category == com.splitfree.data.Category.GENERAL) "🤝" else e.category.emoji, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (e.settlement) {
                    val from = e.paid.keys.firstOrNull()?.let { group.name(it, me) } ?: "Someone"
                    val to = e.shares.keys.firstOrNull()?.let { if (it == me) "you" else group.name(it, me) } ?: "someone"
                    Text("$from paid $to ${Money.format(e.amount)}", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(e.title, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val payers = e.paid.keys.map { group.name(it, me) }
                    val who = if (payers.size == 1) "${payers[0]} paid" else "${payers.size} people paid"
                    Text("$who ${Money.format(e.amount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showGroup) Text(group.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!e.settlement) {
                Spacer(Modifier.width(8.dp))
                val mine = (e.paid[me] ?: 0) - (e.shares[me] ?: 0)
                Column(horizontalAlignment = Alignment.End) {
                    when {
                        me != null && me !in e.involved -> Text("Not involved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        mine == 0L -> Text("No balance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            Text(if (mine > 0) "You lent" else "You borrowed", style = MaterialTheme.typography.bodySmall, color = moneyColor(mine))
                            Text(Money.format(abs(mine)), style = MaterialTheme.typography.titleSmall, color = moneyColor(mine))
                        }
                    }
                }
            }
        }
    }
}
