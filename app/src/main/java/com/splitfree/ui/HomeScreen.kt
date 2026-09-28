package com.splitfree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.NavViewModel
import com.splitfree.Screen
import com.splitfree.data.Activity
import com.splitfree.data.Api
import com.splitfree.data.Auth
import com.splitfree.data.Group
import com.splitfree.data.Invite
import com.splitfree.data.Repo
import com.splitfree.money.Balances
import com.splitfree.money.Debt
import com.splitfree.money.Money
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: NavViewModel) {
    val tab by nav.homeTab.collectAsStateWithLifecycle()
    val tabs = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedContent(
            targetState = tab.coerceAtMost(2),
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap())
            },
            label = "tab", modifier = Modifier.weight(1f)
        ) { t ->
            tabs.SaveableStateProvider("tab$t") {
                when (t) {
                    0 -> GroupsTab(nav)
                    1 -> ActivityTab(nav)
                    else -> AccountTab(nav)
                }
            }
        }
        BottomTabs(
            listOf("Groups" to Icons.Rounded.Groups, "Activity" to Icons.Rounded.Timeline, "Account" to Icons.Rounded.AccountCircle),
            tab.coerceAtMost(2)
        ) { nav.homeTab.value = it }
    }
}

private val filters = listOf("All groups", "Outstanding balances", "Groups you owe", "Groups that owe you")

@Composable
private fun GroupsTab(nav: NavViewModel) {
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val expenses by Repo.expenses.collectAsStateWithLifecycle()
    val invites by Repo.invites.collectAsStateWithLifecycle()
    val loaded by Repo.groupsLoaded.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Back: the first press hides the keyboard (the system does that) and keeps the text; the next one leaves the app.
    // When the keyboard goes away, drop the focus too so the blinking cursor goes with it.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    androidx.compose.runtime.LaunchedEffect(imeVisible) { if (!imeVisible && searching) focusManager.clearFocus() }
    var filtering by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val me = Auth.uid

    val debtsByGroup = groups.associate { g ->
        val items = expenses[g.id].orEmpty().filter { !it.deleted }.map { it.flows }
        g.id to (if (g.simplify) Balances.settle(Balances.nets(items)) else Balances.pairwise(items))
    }
    val nets = groups.associate { g ->
        g.id to (Balances.nets(expenses[g.id].orEmpty().filter { !it.deleted }.map { it.flows })[me] ?: 0L)
    }
    val hasExpenses = groups.associate { g -> g.id to expenses[g.id].orEmpty().any { !it.deleted } }
    val total = nets.values.sum()
    // Look up place photos for every group, new ones included.
    androidx.compose.runtime.LaunchedEffect(groups.map { it.id to it.name }) { groups.forEach { com.splitfree.data.Cover.ensure(it) } }
    val visible = groups.filter {
        val n = nets[it.id] ?: 0
        when (filter) { 1 -> n != 0L; 2 -> n < 0; 3 -> n > 0; else -> true }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            // ---- app bar: search, new group ----
            item {
                HoliHeader("Split Free")
                // Search capsule: tap to type.
                SearchCapsule(query, { query = it }, focus, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) { searching = it }
            }

            val q = query.trim().lowercase()
            if (q.isNotEmpty()) {
                val groupHits = groups.filter { it.name.lowercase().contains(q) }
                val hits = groups.flatMap { g ->
                    expenses[g.id].orEmpty().filter { !it.deleted && (it.title.lowercase().contains(q) || it.note.lowercase().contains(q)) }.map { g to it }
                }.take(50)
                if (groupHits.isNotEmpty()) item { SectionLabel("Groups", Modifier.padding(start = 20.dp, top = 18.dp)) }
                items(groupHits, key = { "gh" + it.id }) { g ->
                    GroupRow(g, nets[g.id] ?: 0, hasExpenses[g.id] == true, debtsByGroup[g.id].orEmpty()) { nav.push(Screen.Group(g.id)) }
                }
                if (hits.isNotEmpty()) item { SectionLabel("Expenses", Modifier.padding(start = 20.dp, top = 18.dp)) }
                items(hits, key = { "eh" + it.second.id }) { (g, e) ->
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { ExpenseRow(g, e, showGroup = true) { nav.push(Screen.Detail(g.id, e.id)) } }
                }
                if (hits.isEmpty() && groupHits.isEmpty()) item { Spacer(Modifier.height(28.dp)); Footnote("Nothing matches “$query”.") }
                return@LazyColumn
            }

            // ---- overall + filter ----
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
                    Text(
                        buildAnnotatedString {
                            when {
                                total > 0 -> { append("Overall, you are owed "); withStyle(SpanStyle(color = moneyColor(1), fontWeight = FontWeight.SemiBold)) { append(Money.format(total)) } }
                                total < 0 -> { append("Overall, you owe "); withStyle(SpanStyle(color = moneyColor(-1), fontWeight = FontWeight.SemiBold)) { append(Money.format(-total)) } }
                                else -> append("You are all settled up!")
                            }
                        },
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { filtering = true }) {
                        Icon(Icons.Rounded.Tune, "Filter", tint = if (filter != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp))
                    }
                }
                if (filter != 0) Text(filters[filter], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
            }
            if (invites.isNotEmpty()) {
                items(invites, key = { "inv" + it.id }) { Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { InviteCard(it) } }
            }
            if (loaded && groups.isEmpty()) item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 28.dp)) {
                    IconBubble(Icons.Rounded.Groups)
                    Spacer(Modifier.height(16.dp))
                    Text("No groups yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Footnote("Make one for your flat, a trip or office lunches, then invite people by email or link.")
                }
            }
            if (groups.isNotEmpty() && visible.isEmpty()) item { Spacer(Modifier.height(20.dp)); Footnote("No groups match this filter.") }
            items(visible, key = { it.id }) { g ->
                Box(Modifier.animateItem(placementSpec = null, fadeOutSpec = null)) { GroupRow(g, nets[g.id] ?: 0, hasExpenses[g.id] == true, debtsByGroup[g.id].orEmpty()) { nav.push(Screen.Group(g.id)) } }
            }
        }

        FloatingAdd("New group", Icons.Rounded.GroupAdd, { creating = true }, Modifier.align(Alignment.BottomEnd).padding(20.dp))
    }

    if (creating) NewGroupDialog(onDismiss = { creating = false }) { id -> creating = false; nav.push(Screen.Group(id)) }
    if (filtering) WarmDialog("Sort by", onDismiss = { filtering = false }) {
        ListCard {
            filters.forEachIndexed { i, label ->
                if (i > 0) HairLine()
                SettingRow(label, null, null, onClick = { filter = i; filtering = false }) { RadioDot(filter == i) }
            }
        }
    }
}

@Composable
fun NewGroupDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    WarmDialog("New group", onDismiss = onDismiss) {
        Field(name, { name = it.take(60) }, label = "Group name", placeholder = "e.g. Goa trip, Flat 4B")
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Cancel", null, onDismiss, Modifier.weight(1f))
            PrimaryButton("Create", null, { onCreated(Repo.createGroup(name.trim())) }, Modifier.weight(1f), enabled = name.isNotBlank())
        }
    }
}

/**
 * Group card: the place photo fills the card, dimmed so white text reads well
 * in light and dark mode. Name, your status and who's in the group.
 */
@Composable
private fun GroupRow(g: Group, net: Long, hasExpenses: Boolean, debts: List<Debt>, onClick: () -> Unit) {
    val me = Auth.uid
    val others = g.members.filter { it != me }.map { g.info[it]?.name ?: "Someone" }
    val people = when {
        others.isEmpty() -> "Just you so far"
        others.size <= 3 -> others.joinToString(", ")
        else -> others.take(3).joinToString(", ") + " +${others.size - 3} more"
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val ctx = LocalContext.current
    Box(
        Modifier.padding(horizontal = 20.dp, vertical = 7.dp).fillMaxWidth().height(156.dp)
            .pressScale(src).clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(groupTint(g.id), groupTint(g.id).copy(alpha = 0.7f))))
            .clickable(interactionSource = src, indication = null) { Haptics.tick(ctx); onClick() }
    ) {
        if (g.cover.isNotBlank()) coil.compose.AsyncImage(
            model = g.cover, contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )
        // Dim the photo: lighter at the top, darker behind the text.
        Box(Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f),
                1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.62f)
            )
        ))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 18.dp)) {
            Text(g.name, style = MaterialTheme.typography.headlineSmall, color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tag = when {
                    net > 0 -> "You are owed ${Money.format(net)}"
                    net < 0 -> "You owe ${Money.format(-net)}"
                    !hasExpenses -> "No expenses"
                    else -> "Settled up"
                }
                val tagColor = when {
                    net > 0 -> androidx.compose.ui.graphics.Color(0xFF9BE8A8)
                    net < 0 -> androidx.compose.ui.graphics.Color(0xFFFFB199)
                    else -> androidx.compose.ui.graphics.Color.White
                }
                Text(tag, style = MaterialTheme.typography.titleSmall, color = tagColor,
                    modifier = Modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 5.dp))
                Spacer(Modifier.width(10.dp))
                Text(people, style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.88f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The app title over slowly drifting clouds of Holi colour powder. Positions are
 * read only while drawing, so the loop never recomposes and stays smooth.
 */
@Composable
private fun HoliHeader(title: String) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "holi")
    val t by loop.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(16000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "t"
    )
    val colors = listOf(0xFFFF4FA3, 0xFFFFC93C, 0xFF3DDC84, 0xFF3FA9F5, 0xFFFF7A2F, 0xFF8E5CFF)
        .map { androidx.compose.ui.graphics.Color(it) }
    val strength = if (dark) 0.30f else 0.38f
    Box(
        Modifier.fillMaxWidth().height(92.dp).drawBehind {
            val w = size.width; val h = size.height
            colors.forEachIndexed { i, c ->
                // Each cloud loops on its own slow path, at its own speed.
                val k = 1f + i * 0.37f
                val x = w * (0.12f + 0.16f * i + 0.10f * kotlin.math.sin(t * k + i))
                val y = h * (0.5f + 0.35f * kotlin.math.cos(t * (2f - k * 0.3f) + i * 1.7f))
                val r = h * (0.95f + 0.25f * kotlin.math.sin(t * 0.8f + i * 2f))
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(c.copy(alpha = strength), c.copy(alpha = 0f)), center = androidx.compose.ui.geometry.Offset(x, y), radius = r
                    ),
                    radius = r, center = androidx.compose.ui.geometry.Offset(x, y)
                )
            }
        }
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
    }
}

@Composable
fun InviteCard(invite: Invite) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    WarmCard(background = MaterialTheme.colorScheme.primaryContainer, borderColor = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MailOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(invite.groupName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                RowBody("${invite.fromName} invited you to join")
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            fun answer(accept: Boolean) {
                busy = true
                scope.launch {
                    try {
                        Repo.respond(invite, accept)
                        toast(context, if (accept) "You joined ${invite.groupName}" else "Invite declined")
                    } catch (e: Exception) { toast(context, Api.friendly(e)) } finally { busy = false }
                }
            }
            SecondaryButton("Reject", null, { answer(false) }, Modifier.weight(1f), enabled = !busy)
            PrimaryButton("Accept", null, { answer(true) }, Modifier.weight(1f), enabled = !busy)
        }
    }
}

/** Rounded search field that stays on the home screen; reports when it gains or loses focus. */
@Composable
private fun SearchCapsule(
    query: String, onQuery: (String) -> Unit, focus: androidx.compose.ui.focus.FocusRequester,
    modifier: Modifier = Modifier, onActive: (Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().background(scheme.surface, shape)
            .border(1.dp, scheme.outline, shape).clickable { focus.requestFocus() }.padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(Icons.Rounded.Search, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
            modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { onActive(it.isFocused) },
            decorationBox = { inner ->
                Box { if (query.isEmpty()) Text("Search groups and expenses", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant); inner() }
            }
        )
        if (query.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp).clickable { onQuery("") })
    }
}

/** Everything that happened across your groups, newest first, with the faces of everyone involved. */
@Composable
private fun ActivityTab(nav: NavViewModel) {
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val ids = groups.map { it.id }
    val flow = remember(ids) {
        if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
        else kotlinx.coroutines.flow.combine(ids.map { gid -> Repo.activity(gid) }) { lists ->
            lists.flatMapIndexed { i, l -> l.map { ids[i] to it } }.sortedByDescending { it.second.at }.take(200)
        }
    }
    val items by androidx.compose.runtime.produceState<List<Pair<String, com.splitfree.data.Activity>>?>(null, flow) { flow.collect { value = it } }
    val me = Auth.uid
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 14.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
        }
        when {
            items == null -> item { Spacer(Modifier.height(24.dp)); Footnote("Loading…") }
            items!!.isEmpty() -> item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp)) {
                    IconBubble(Icons.Rounded.Timeline)
                    Spacer(Modifier.height(18.dp))
                    Text("No activity yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text("Expenses, payments and comments in your groups show up here.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            else -> items!!.groupBy { Fmt.day(it.second.at) }.forEach { (day, dayItems) ->
            item(key = "d$day") {
                SectionLabel(day, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 2.dp))
            }
            items(dayItems, key = { it.second.id }) { (gid, a) ->
                val g = groups.firstOrNull { it.id == gid }
                Box(Modifier.animateItem(placementSpec = null, fadeOutSpec = null).padding(horizontal = 20.dp, vertical = 5.dp)) {
                    WarmCard(padding = 14.dp, onClick = {
                        if (a.expenseId != null && Repo.expenses.value[gid].orEmpty().any { it.id == a.expenseId }) nav.push(Screen.Detail(gid, a.expenseId))
                        else nav.push(Screen.Group(gid))
                    }) {
                        Row(verticalAlignment = Alignment.Top) {
                            Avatar(g?.info?.get(a.actor)?.name ?: "?", a.actor, 46.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (a.actor == me) a.text.replaceFirst(Auth.name, "You") else a.text,
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("${g?.name ?: ""} · ${Fmt.time(a.at)}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (a.people.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Overlapping faces of the others involved.
                                        a.people.take(5).forEachIndexed { i, uid ->
                                            Box(Modifier.padding(start = if (i == 0) 0.dp else 0.dp).offset(x = (-8 * i).dp)
                                                .background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.CircleShape).padding(2.dp)) {
                                                Avatar(g?.info?.get(uid)?.name ?: "?", uid, 26.dp)
                                            }
                                        }
                                        val names = a.people.map { if (it == me) "You" else g?.info?.get(it)?.name?.substringBefore(' ') ?: "Someone" }
                                        Text(
                                            names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else "",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.offset(x = (-8 * (a.people.take(5).size - 1)).dp).padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
