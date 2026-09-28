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
    Column(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedContent(
            targetState = tab.coerceAtMost(1),
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap())
            },
            label = "tab", modifier = Modifier.weight(1f)
        ) { t ->
            when (t) {
                0 -> GroupsTab(nav)
                else -> AccountTab(nav)
            }
        }
        BottomTabs(
            listOf("Groups" to Icons.Rounded.Groups, "Account" to Icons.Rounded.AccountCircle),
            tab.coerceAtMost(1)
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
                Text("Split Free", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 14.dp))
                HairLine()
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

/** Group card: badge, name, your status, who's in it, and who owes whom with you. */
@Composable
private fun GroupRow(g: Group, net: Long, hasExpenses: Boolean, debts: List<Debt>, onClick: () -> Unit) {
    val me = Auth.uid
    val mine = debts.filter { it.from == me || it.to == me }
    val others = g.members.filter { it != me }.map { g.info[it]?.name ?: "Someone" }
    val people = when {
        others.isEmpty() -> "Just you so far"
        others.size <= 3 -> others.joinToString(", ")
        else -> others.take(3).joinToString(", ") + " +${others.size - 3} more"
    }
    Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        WarmCard(onClick = onClick, padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupBadge(g.name, g.id, 60.dp, g.cover)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            net > 0 -> "You are owed ${Money.format(net)}"
                            net < 0 -> "You owe ${Money.format(-net)}"
                            !hasExpenses -> "No expenses"
                            else -> "Settled up"
                        },
                        style = MaterialTheme.typography.titleSmall, color = moneyColor(net)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(people, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (mine.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HairLine()
                Spacer(Modifier.height(10.dp))
                mine.take(3).forEach { d ->
                    Text(
                        buildAnnotatedString {
                            append(if (d.to == me) "${g.name(d.from, me)} owes you " else "You owe ${g.name(d.to, me)} ")
                            withStyle(SpanStyle(color = moneyColor(if (d.to == me) 1 else -1), fontWeight = FontWeight.Bold)) { append(Money.format(d.amount)) }
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (mine.size > 3) Text("Plus ${mine.size - 3} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
