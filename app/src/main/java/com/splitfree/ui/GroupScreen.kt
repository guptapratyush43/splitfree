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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.Payments
import com.splitfree.data.Pairs
import androidx.compose.material.icons.rounded.Undo
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
private val weekShort = DateTimeFormatter.ofPattern("EEE", Locale.US)
private fun zoned(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

private val tabNames = listOf("Expenses", "Balances", "Totals", "Pay back")
private const val PAY_BACK = 3

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
            if (!seen) kotlinx.coroutines.delay(8000)
            nav.home()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Footnote("Opening group…") }
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
    // Payments someone says they made to me, waiting for my yes.
    val toConfirm = all[gid].orEmpty().count { it.pending && me in it.shares.keys }

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
                    // "Pay back" is an action rather than a view, so it wears a soft tint instead of an outline.
                    Box {
                        ActionPill(tabNames[i], { scope.launch { pager.animateScrollToPage(i) } }, filled = pager.currentPage == i, soft = i == PAY_BACK)
                        if (i == PAY_BACK && toConfirm > 0) AlertDot(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp))
                    }
                }
            }
            HairLine()

            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                when (page) {
                    // Payments marked as settled live in Balances and Activity, not in the expense list.
                    0 -> ExpensesPage(nav, group, expenses.filter { !it.settlement }, searching, query) { query = it }
                    1 -> BalancesPage(nav, group, nets, debts, me) { remind = it }
                    2 -> TotalsPage(group, all[gid].orEmpty(), me)
                    else -> PayBackPage(group, all[gid].orEmpty(), expenses, me)
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

private enum class ExpenseSort(val label: String) { DATE("Date"), ADDED("Recently added"), NAME("Name") }

@Composable
private fun ExpensesPage(nav: NavViewModel, group: Group, expenses: List<Expense>, searching: Boolean, query: String, onQuery: (String) -> Unit) {
    val q = query.trim().lowercase()
    val shown = if (!searching || q.isEmpty()) expenses else expenses.filter {
        it.title.lowercase().contains(q) || it.note.lowercase().contains(q) || it.category.label.lowercase().contains(q)
    }
    var sort by rememberSaveable { mutableStateOf(ExpenseSort.DATE) }
    // Date: month, then each day under it. Recently added: newest entry first. Name: same titles together (Hostel, Hostel, Food…).
    val byDate = sort == ExpenseSort.DATE
    val sections: List<Pair<String, List<Pair<String?, List<Expense>>>>> = when (sort) {
        ExpenseSort.DATE -> shown.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.createdAt })
            .groupBy { monthFmt.format(zoned(it.date)) }.toList()
            .map { (m, list) -> m to list.groupBy { Fmt.day(it.date) }.toList().map { (d, l) -> (d as String?) to l } }
        ExpenseSort.ADDED -> listOf("Newest first" to listOf(null to shown.sortedByDescending { it.createdAt }))
        ExpenseSort.NAME -> shown.groupBy { it.title.trim().lowercase() }.toList().sortedBy { it.first }
            .map { (_, list) -> list.first().title.trim().replaceFirstChar { it.uppercase() } to listOf(null to list.sortedByDescending { it.date }) }
    }
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
        val move = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        sections.forEachIndexed { si, (month, days) ->
            item(key = "m$sort$month") {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null).fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = if (si == 0) 0.dp else 10.dp, bottom = 6.dp)) {
                    Text(month.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (si == 0 && expenses.size > 1) SortCapsule(sort.label) { sort = ExpenseSort.entries[(sort.ordinal + 1) % ExpenseSort.entries.size] }
                }
            }
            days.forEach { (day, list) ->
                if (day != null) item(key = "d$sort$month$day") {
                    Text(day, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null).padding(start = 24.dp, top = 4.dp, bottom = 8.dp))
                }
                items(list, key = { it.id }) { e ->
                    // Re-sorting glides each card to its new place; the date column folds away under day headings.
                    Column(Modifier.animateItem(fadeInSpec = null, placementSpec = move, fadeOutSpec = null).padding(horizontal = 20.dp)) {
                        ExpenseRow(group, e, showDate = !byDate) { nav.push(Screen.Detail(group.id, e.id)) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/** Round dark translucent button over the banner photo (a plain surface button when there is no photo). */
@Composable
private fun GlassCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onPhoto: Boolean, onClick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val fill = if (onPhoto) Color.Black.copy(alpha = 0.42f) else MaterialTheme.colorScheme.surface
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(46.dp).pressScale(src).clip(androidx.compose.foundation.shape.CircleShape)
            .background(fill)
            .clickable(interactionSource = src, indication = null) { Haptics.tick(ctx); onClick() }
    ) { Icon(icon, label, tint = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
}

/**
 * "Everyone is settled up": the capsule itself stays still while soft rings
 * ripple out of it and fade, like a slow breath.
 */
@Composable
private fun BreathingCapsule(text: String) {
    val green = moneyColor(1)
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp)
            .rippleRings(green, spread = 16.dp, period = 2400)
            .background(MaterialTheme.colorScheme.surface, pill)
            .background(green.copy(alpha = 0.14f), pill)
            .border(1.dp, green.copy(alpha = 0.55f), pill)
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
            if (debts.isEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BreathingCapsule("Everyone is settled up") }
            else Spacer(Modifier.height(10.dp))
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
                SortCapsule(sort.label) { sort = BalanceSort.entries[(sort.ordinal + 1) % BalanceSort.entries.size] }
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

/** Every expense and payment [uid] took part in, newest first, with the effect it had on them. */
private fun settledFor(uid: String, expenses: List<Expense>): List<Pair<Expense, Long>> =
    expenses.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.createdAt })
        .map { it to ((it.paid[uid] ?: 0) - (it.shares[uid] ?: 0)) }
        .filter { it.second != 0L }

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
    var paidIt by remember { mutableStateOf<Debt?>(null) }
    val live = all[gid].orEmpty().filter { !it.deleted }
    val book = remember(live, group.simplify) { Pairs(live, group.simplify) }
    val byId = live.associateBy { it.id }
    // Settled, partly settled or open: each expense as it stands for this member.
    val progress = remember(live, group.simplify, uid) { book.progress(uid) }
    // Payments they made to you that you can take back ("unsettle"), and what each was put towards.
    val myPayments = if (uid == me) emptyList() else live.filter { it.settlement && uid in it.paid.keys && me in it.shares.keys }
    val allocs = myPayments.associate { it.id to Pairs.allocOf(it, byId) }
    val settledBy: Map<String, List<Expense>> = myPayments
        .flatMap { pay -> allocs.getValue(pay.id).keys.map { it to pay } }.groupBy({ it.first }, { it.second })
    // A payment spent entirely on particular expenses shows as tags on those, not as a row of its own.
    val hidden = live.filter { it.settlement }.filter { pay ->
        val al = Pairs.allocOf(pay, byId); al.isNotEmpty() && al.values.sum() >= pay.amount
    }.map { it.id }.toSet()
    val pending = settledFor(uid, live).filter { it.first.id !in hidden }
    // Unsettle: expenses a payment was put towards, plus whole payments they made to you.
    val unsettleIds = pending.map { it.first.id }.filter { it in settledBy }.toSet() +
        myPayments.filter { it.id !in hidden }.map { it.id }
    // On someone else's page, what they still owe you on each expense can be picked and marked as settled.
    val owedToMe: Map<String, Long> = if (uid == me) emptyMap() else book.state(uid, me).aOwes.filterValues { it > 0 }
    val picked = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(
        save = { it.toList() }, restore = { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(it) } }
    )) { androidx.compose.runtime.mutableStateListOf<String>() }
    // Long-press starts picking: a pending expense to settle, or a settled one to unsettle.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var unsettling by rememberSaveable { mutableStateOf(false) }
    val pickableNow = if (unsettling) unsettleIds else owedToMe.keys
    LaunchedEffect(pickableNow) { picked.retainAll(pickableNow) }
    var unsettlePicked by remember { mutableStateOf(false) }
    LaunchedEffect(picked.size) { if (picked.isEmpty()) selecting = false }
    androidx.activity.compose.BackHandler(selecting) { picked.clear(); selecting = false }
    LaunchedEffect(selecting) { if (!selecting) unsettling = false }
    var settlePicked by remember { mutableStateOf(false) }
    val pickedTotal = picked.sumOf { owedToMe[it] ?: 0L }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopBar(group.name(uid, me).let { if (it == "You") "Your balance" else it }, onBack = { nav.pop() })
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 130.dp)) {
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
                    Text("Only the person you owe can mark it as settled. Paid already? Tap I've paid to let them know.", style = MaterialTheme.typography.bodySmall,
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
                        Text("${if (d.from == me) "You" else group.name(d.from, me)} → ${if (d.to == me) "you" else group.name(d.to, me)}",
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text(Money.format(d.amount), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            color = if (d.to == me) moneyColor(1) else if (d.from == me) moneyColor(-1) else MaterialTheme.colorScheme.onSurface)
                    }
                    if (d.to == me) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                            ActionPill("Remind", { remind = d })
                            ActionPill("Mark as settled", { settle = d }, filled = true)
                        }
                    } else if (d.from == me) {
                        // You owe: nudge them to mark it as settled once you've paid.
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                            ActionPill("I've paid", { paidIt = d }, filled = true)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (pending.isNotEmpty()) item {
                Spacer(Modifier.height(8.dp))
                SectionLabel(if (n == 0L) "All settled up expenses" else "Expenses", Modifier.padding(bottom = 0.dp))
                if (owedToMe.isNotEmpty() || unsettleIds.isNotEmpty()) Text("You can also long-press an expense to mark it as settled or unsettled.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
            pending.groupBy { Fmt.day(it.first.date) }.entries.forEachIndexed { di, (day, list) ->
                item(key = "d$day") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
                        Text(day, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(start = 4.dp))
                        if (di == 0 && pickableNow.isNotEmpty() && selecting) {
                            val allOn = picked.size == pickableNow.size
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            Text(if (allOn) "Deselect all" else "Select all", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                    .clickable { Haptics.tick(ctx); if (allOn) picked.clear() else { picked.clear(); picked.addAll(pickableNow) } }
                                    .padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
                items(list, key = { "p" + it.first.id }) { (e, effect) ->
                    val canSettle = e.id in owedToMe
                    val canUnsettle = e.id in unsettleIds
                    // Once picking has started, only rows of that kind (settle or unsettle) stay pickable.
                    val pickable = if (selecting) e.id in pickableNow else canSettle || canUnsettle
                    val toggle = { if (e.id in picked) picked.remove(e.id) else picked.add(e.id); Unit }
                    PendingRow(group, e, effect, uid, me, progress = if (e.settlement) null else progress[e.id], pickable = pickable && selecting, picked = e.id in picked, onPick = toggle,
                        onLongClick = if (canSettle || canUnsettle) ({
                            if (!selecting) { unsettling = canUnsettle && !canSettle; picked.clear(); picked.add(e.id); selecting = true }
                            else if (pickable && e.id !in picked) picked.add(e.id)
                        }) else null) {
                        if (selecting && pickable) toggle() else nav.push(Screen.Detail(gid, e.id))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    // "Mark as settled" rises from the bottom once anything is picked; the page fades out behind it.
    androidx.compose.animation.AnimatedVisibility(picked.isNotEmpty(), Modifier.align(Alignment.BottomCenter),
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260)) { it / 2 },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(200)) { it / 2 }) {
        val bg = MaterialTheme.colorScheme.background
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(130.dp).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(0f to bg.copy(alpha = 0f), 0.35f to bg.copy(alpha = 0.75f), 0.6f to bg, 1f to bg)
            ).padding(top = 40.dp)
        ) {
            if (unsettling) FloatingAdd("Mark as unsettled", Icons.Rounded.Undo, { unsettlePicked = true })
            else FloatingAdd("Mark as settled · ${Money.format(pickedTotal)}", Icons.Rounded.Handshake, { settlePicked = true })
        }
    }
    }
    if (unsettlePicked) {
        ConfirmDialog("Mark as unsettled?", "${group.name(uid, me)} will owe you for ${picked.size} ${if (picked.size == 1) "item" else "items"} again.", "Unsettle",
            onConfirm = {
                val chosen = picked.toSet()
                // Whole payments picked directly.
                val gone = myPayments.filter { it.id in chosen }
                gone.forEach { Repo.unsettle(group, it) }
                // Expenses: take them out of the payments put towards them (a payment with nothing left is dropped).
                chosen.flatMap { settledBy[it].orEmpty() }.distinctBy { it.id }.filter { pay -> gone.none { it.id == pay.id } }.forEach { pay ->
                    val al = allocs.getValue(pay.id)
                    val keep = al.filterKeys { it !in chosen }
                    val left = pay.amount - al.filterKeys { it in chosen }.values.sum()
                    if (left <= 0) Repo.unsettle(group, pay)
                    else Repo.saveExpense(group, pay.copy(amount = left, paid = mapOf(uid to left), shares = mapOf(me to left),
                        inputs = if (keep.isEmpty()) emptyMap() else mapOf("alloc" to Pairs.encode(keep)),
                        updatedAt = System.currentTimeMillis()), isNew = false)
                }
                picked.clear(); Haptics.success(context); toast(context, "Marked as unsettled")
            }, onDismiss = { unsettlePicked = false }, danger = false)
    }
    if (settlePicked) {
        val amount = pickedTotal
        ConfirmDialog("Mark as settled?", "${group.name(uid, me)} paid you ${Money.format(amount)} for ${picked.size} ${if (picked.size == 1) "expense" else "expenses"}.", "Settle",
            onConfirm = {
                Repo.settle(group, uid, me, amount, System.currentTimeMillis(), picked.associateWith { owedToMe[it] ?: 0L }.filterValues { it > 0 })
                picked.clear(); Haptics.success(context); toast(context, "Marked as settled")
            }, onDismiss = { settlePicked = false }, danger = false)
    }
    remind?.let { d ->
        ConfirmDialog("Send a reminder?", "${group.name(d.from, me)} gets a notification that they owe you ${Money.format(d.amount)}.", "Remind",
            onConfirm = { Repo.remind(group, d.from, d.amount); toast(context, "Reminder sent") }, onDismiss = { remind = null }, danger = false)
    }
    paidIt?.let { d ->
        ConfirmDialog("Let them know?", "${group.name(d.to, me)} gets a reminder that you've paid ${Money.format(d.amount)}, so they can mark it as settled.", "Send",
            onConfirm = { Repo.askToSettle(group, d.to, d.amount); toast(context, "Reminder sent") }, onDismiss = { paidIt = null }, danger = false)
    }
    settle?.let { d ->
        ConfirmDialog("Mark as settled?", "${group.name(d.from, me)} paid you ${Money.format(d.amount)}. This clears it from the balances.", "Settle",
            onConfirm = {
                // The whole balance: spread over their open expenses, smallest first.
                Repo.settle(group, d.from, d.to, d.amount, System.currentTimeMillis(), book.allocate(d.from, d.to, d.amount).amounts)
                Haptics.success(context); toast(context, "Marked as settled")
            }, onDismiss = { settle = null }, danger = false)
    }
}

/** One expense behind a member's balance: category, title, who added it and what it means for them. */
@Composable
private fun PendingRow(group: Group, e: Expense, effect: Long, uid: String, me: String, progress: Pairs.Progress? = null,
                       pickable: Boolean = false, picked: Boolean = false, onPick: () -> Unit = {}, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    WarmCard(onClick = onClick, onLongClick = onLongClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBubble(if (e.settlement && e.category == com.splitfree.data.Category.GENERAL) "🤝" else e.category.emoji, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = if (e.settlement) {
                    val from = e.paid.keys.firstOrNull()?.let { group.name(it, me) } ?: "Someone"
                    val to = e.shares.keys.firstOrNull()?.let { if (it == me) "you" else group.name(it, me) } ?: "someone"
                    "$from paid $to"
                } else e.title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (progress?.settled == true || progress?.partly == true) {
                        // A quiet tag: cleared, or how much is still open.
                        Spacer(Modifier.width(6.dp))
                        Text(if (progress.settled) "Settled" else "Partly settled · ${Money.format(progress.open)} left", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(50))
                                .padding(horizontal = 7.dp, vertical = 1.dp))
                    }
                }
                Text(if (e.settlement) "Payment · Marked by ${group.name(e.createdBy, me)}" else "${e.category.label} · Added by ${group.name(e.createdBy, me)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                val you = uid == me
                if (e.settlement) {
                    // A payment settles money; it is not lending or borrowing, so it stays neutral.
                    val grey = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(if (effect > 0) (if (you) "You paid" else "Paid") else (if (you) "You received" else "Received"),
                        style = MaterialTheme.typography.bodySmall, color = grey)
                    Text(Money.format(abs(effect)), style = MaterialTheme.typography.titleSmall, color = grey)
                } else {
                    Text(if (effect > 0) (if (you) "You lent" else "Lent") else (if (you) "You borrowed" else "Borrowed"),
                        style = MaterialTheme.typography.bodySmall, color = moneyColor(effect))
                    Text(Money.format(abs(effect)), style = MaterialTheme.typography.titleSmall, color = moneyColor(effect))
                }
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.animation.AnimatedVisibility(pickable,
                enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)),
                exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))) {
                Box(Modifier.padding(start = 12.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable { Haptics.tick(ctx); onPick() }) { RadioDot(picked) }
            }
        }
    }
}

/** In words: what a lump sum would settle. [open] is what was open on each expense before it. */
private fun describeAlloc(alloc: com.splitfree.money.Alloc, open: Map<String, Long>, byId: Map<String, Expense>, extraLine: String): List<String> {
    val out = ArrayList<String>()
    val full = alloc.amounts.filter { (id, v) -> v >= (open[id] ?: 0L) }.keys.mapNotNull { byId[it]?.title }
    if (full.isNotEmpty()) out += "Settles " + (if (full.size <= 3) full.joinToString(", ") else full.take(2).joinToString(", ") + " and ${full.size - 2} more") + "."
    alloc.amounts.filter { (id, v) -> v < (open[id] ?: 0L) }.forEach { (id, v) ->
        out += "Partly settles ${byId[id]?.title ?: "an expense"}: ${Money.format(v)} of ${Money.format(open[id] ?: 0L)}."
    }
    if (alloc.extra > 0) out += extraLine.replace("%s", Money.format(alloc.extra))
    return out
}

/**
 * "Pay back": record one lump sum paid to someone without naming the expenses.
 * It clears the smallest open amounts first and partly settles the next one.
 * A payment you made waits here until the receiver confirms it.
 */
@Composable
private fun PayBackPage(group: Group, raw: List<Expense>, live: List<Expense>, me: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val book = remember(live, group.simplify) { Pairs(live, group.simplify) }
    val byId = live.associateBy { it.id }
    val claims = raw.filter { it.pending && me in it.involved }.sortedByDescending { it.createdAt }
    val others = group.members.filter { it != me }
    var recording by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)) {
        item {
            Text("Paid someone a lump sum?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Text("Record it here. Your smallest dues are settled first; the other person confirms it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Record a payment", Icons.Rounded.Payments, { recording = true }, Modifier.fillMaxWidth(), enabled = others.isNotEmpty())
            if (others.isEmpty()) { Spacer(Modifier.height(10.dp)); Footnote("Add people to this group first.") }
            Spacer(Modifier.height(22.dp))
            if (claims.isNotEmpty()) SectionLabel("Waiting for confirmation")
        }
        items(claims, key = { it.id }) { c ->
            Box(Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)) { ClaimCard(group, c, me) }
            Spacer(Modifier.height(10.dp))
        }
    }
    if (recording) RecordPaymentDialog(group, book, byId, others, me) { recording = false }
}

/**
 * A payment someone says they made, waiting for the receiver: what confirming
 * would settle, with Reject / Confirm for the receiver (and "Ignore for now" in
 * the pop-up), or Cancel for the one who recorded it.
 */
@Composable
fun ClaimCard(group: Group, c: Expense, me: String, showGroup: Boolean = false, onIgnore: (() -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val all by Repo.expenses.collectAsStateWithLifecycle()
    val live = all[group.id].orEmpty().filter { !it.deleted }
    val book = remember(live, group.simplify) { Pairs(live, group.simplify) }
    val byId = live.associateBy { it.id }
    val from = c.paid.keys.firstOrNull().orEmpty()
    val to = c.shares.keys.firstOrNull().orEmpty()
    val mine = to == me
    WarmCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(group.info[if (mine) from else to]?.name ?: "?", if (mine) from else to, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (mine) "${group.name(from, me)} says they paid you" else "Waiting for ${group.name(to, me)} to confirm",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text((if (showGroup) "${group.name} · " else "") + Fmt.relative(c.createdAt), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Text(Money.format(c.amount), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        // What confirming would do, in words.
        val st = book.state(from, to)
        val alloc = com.splitfree.money.Ledger.allocate(c.amount, st.aOwes, st.aDues)
        val lines = describeAlloc(alloc, st.aOwes, byId, if (mine) "%s is extra: you will owe it back." else "%s is extra: they will owe it back.")
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(12.dp))
        if (mine) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton("Reject", null, { Repo.rejectPayment(group, c); toast(context, "Payment rejected") }, Modifier.weight(1f))
                PrimaryButton("Confirm", null, {
                    Repo.confirmPayment(group, c, alloc.amounts); Haptics.success(context); toast(context, "Payment confirmed")
                }, Modifier.weight(1f))
            }
            if (onIgnore != null) {
                Spacer(Modifier.height(10.dp))
                TertiaryButton("Ignore for now", onIgnore, Modifier.fillMaxWidth())
            }
        } else TertiaryButton("Cancel this payment", { Repo.rejectPayment(group, c); toast(context, "Payment cancelled") }, Modifier.fillMaxWidth())
    }
}

/** Who, which way, how much, with a plain-words preview of what it will settle. */
@Composable
private fun RecordPaymentDialog(group: Group, book: Pairs, byId: Map<String, Expense>, others: List<String>, me: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Start on whoever you owe the most.
    val nets = remember(book) { others.associateWith { book.state(me, it).net } }
    var who by remember { mutableStateOf(others.maxByOrNull { nets[it] ?: 0L } ?: others.first()) }
    var iPaid by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    val amount = Money.parse(text) ?: 0L
    WarmDialog("Record a payment", onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("I paid", iPaid) { iPaid = true }
            Chip("I received", !iPaid) { iPaid = false }
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel(if (iPaid) "Paid to" else "Received from")
        ListCard {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                others.forEachIndexed { i, uid ->
                    if (i > 0) HairLine()
                    val n = nets[uid] ?: 0L
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { Haptics.tick(ctx); who = uid }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Avatar(group.info[uid]?.name ?: "?", uid, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(group.name(uid, me), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when { n > 0 -> "You owe ${Money.format(n)}"; n < 0 -> "Owes you ${Money.format(-n)}"; else -> "Settled up" },
                                style = MaterialTheme.typography.bodySmall, color = moneyColor(-n))
                        }
                        RadioDot(who == uid)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Field(text, { text = it }, label = "Amount", placeholder = "0.00", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal, prefix = "₹")
        // What this amount would do, before anything is sent.
        val from = if (iPaid) me else who
        val to = if (iPaid) who else me
        val st = book.state(from, to)
        val alloc = com.splitfree.money.Ledger.allocate(amount, st.aOwes, st.aDues)
        val lines = if (amount <= 0) emptyList() else describeAlloc(alloc, st.aOwes, byId,
            if (iPaid) "%s is extra: ${group.name(who, me)} will owe it back." else "%s is extra: you will owe it back.")
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (iPaid) "${group.name(who, me)} gets a notification to confirm it." else "This counts right away, since you received it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Cancel", null, onDismiss, Modifier.weight(1f))
            PrimaryButton(if (iPaid) "Send" else "Record", null, {
                if (iPaid) {
                    Repo.claimPayment(group, who, amount)
                    toast(context, "Sent to ${group.name(who, me)} to confirm")
                } else {
                    Repo.settle(group, who, me, amount, System.currentTimeMillis(), alloc.amounts)
                    Haptics.success(context); toast(context, "Payment recorded")
                }
                onDismiss()
            }, Modifier.weight(1f), enabled = amount > 0)
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
fun ExpenseRow(group: Group, e: Expense, showGroup: Boolean = false, showDate: Boolean = true, onClick: () -> Unit) {
    val me = Auth.uid
    val z = zoned(e.date)
    WarmCard(onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Under day headings the date is already shown: the column folds away and the rest slides left.
            androidx.compose.animation.AnimatedVisibility(showDate,
                enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)),
                exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(34.dp)) {
                        Text(monShort.format(z), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(dayNum.format(z), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.width(10.dp))
                }
            }
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
