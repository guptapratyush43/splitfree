package com.splitfree.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.NavViewModel
import com.splitfree.Screen
import com.splitfree.data.Auth
import com.splitfree.data.Repeat
import com.splitfree.data.Repo
import com.splitfree.money.Balances
import com.splitfree.money.Money
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ExpenseDetail(nav: NavViewModel, gid: String, eid: String, toComments: Boolean = false) {
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val all by Repo.expenses.collectAsStateWithLifecycle()
    val group = groups.firstOrNull { it.id == gid }
    val e = all[gid]?.firstOrNull { it.id == eid }
    if (group == null || e == null) {
        Column(Modifier.fillMaxSize()) { TopBar("Expense", onBack = { nav.pop() }); Footnote("This expense isn't available.") }
        return
    }
    val me = Auth.uid!!
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val commentsFlow = remember(gid, eid) { Repo.comments(gid, eid) }
    val comments by commentsFlow.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(if (e.settlement) "Payment" else "Expense", onBack = { nav.pop() }) {
            if (!e.deleted) {
                // Expenses: only the people who paid. Payments: whoever made it or recorded it, never
                // the receiver of someone else's advance (removing it would wipe what they owe).
                val canDelete = if (e.settlement) me in e.paid.keys || me == e.createdBy else me in e.paid.keys
                IconButton(onClick = {
                    if (canDelete) nav.push(Screen.Editor(gid, eid))
                    else toast(context, if (e.settlement) "Only the person who paid or recorded this can edit it" else "Only the people who paid for this can edit it")
                }) {
                    Icon(Icons.Rounded.Edit, "Edit", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (canDelete) 1f else 0.4f), modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = {
                    if (canDelete) confirmDelete = true
                    else toast(context, if (e.settlement) "Only the person who paid or recorded this can delete it" else "Only the people who paid for this can delete it")
                }) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = if (canDelete) 1f else 0.4f), modifier = Modifier.size(28.dp)) }
            }
        }
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(toComments, comments.size) { if (toComments && comments.isNotEmpty()) list.animateScrollToItem(comments.size) }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            item {
                // A payment that settled particular expenses shows what it was for, with that expense's icon.
                val allHere by Repo.expenses.collectAsState()
                val liveHere = allHere[gid].orEmpty().filter { !it.deleted }
                val alloc = if (e.settlement) com.splitfree.data.Pairs.allocOf(e, liveHere.associateBy { it.id }) else emptyMap()
                val lump = com.splitfree.data.Pairs.isLump(e)
                // A lump sum keeps the handshake; what it covered is listed below instead.
                val settled = if (lump) emptyList() else alloc.keys.mapNotNull { id -> liveHere.firstOrNull { it.id == id } }
                // How much of this expense is still open, from where you stand.
                val progress = if (e.settlement || e.deleted) null
                    else remember(liveHere, group.simplify, e.id) { com.splitfree.data.Pairs(liveHere, group.simplify).progress(me)[e.id] }
                WarmCard(padding = 20.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryBubble(when {
                            settled.isNotEmpty() -> settled.first().category.emoji
                            e.settlement && (lump || e.category == com.splitfree.data.Category.GENERAL) -> "🤝"
                            else -> e.category.emoji
                        }, 60.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            if (settled.isNotEmpty()) Text("This settlement was for", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when {
                                    settled.isNotEmpty() -> settled.joinToString(", ") { it.title }
                                    lump -> "Lump-sum payment"
                                    e.settlement -> "Payment"
                                    else -> e.title
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            // Partly settled: the big figure is what is still open.
                            CountingMoney(if (progress?.partly == true) progress.open else e.amount, MaterialTheme.typography.headlineMedium, MaterialTheme.colorScheme.primary)
                            if (progress?.partly == true) {
                                Spacer(Modifier.height(2.dp))
                                Text("Partly settled · ${Money.format(progress.owed - progress.open)} of ${Money.format(progress.owed)} paid",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Expense total ${Money.format(e.amount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (progress?.settled == true) {
                                Spacer(Modifier.height(2.dp))
                                Text("Settled", style = MaterialTheme.typography.bodySmall, color = moneyColor(1))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(if (e.settlement) "Settled on ${settledAt(e.createdAt)}" else whenText(e.date, e.createdAt),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (e.note.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        HairLine()
                        Spacer(Modifier.height(12.dp))
                        Text(e.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (e.deleted) {
                        Spacer(Modifier.height(12.dp))
                        Text("Deleted ${Fmt.relative(e.deletedAt)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("Restore", Icons.Rounded.Restore, { Repo.restoreExpense(group, e); toast(context, "Restored") })
                    }
                }
                Spacer(Modifier.height(16.dp))
                SectionLabel(if (e.settlement) "Payment" else "Split ${modeLabel(e.mode)}")
                ListCard {
                    val people = (e.paid.keys + e.shares.keys).distinct()
                    people.forEachIndexed { i, uid ->
                        if (i > 0) HairLine()
                        val p = e.paid[uid] ?: 0
                        val s = e.shares[uid] ?: 0
                        val you = uid == me
                        val name = group.name(uid, me)
                        val hint = e.inputs[uid]?.takeIf { it.isNotBlank() }?.let { v ->
                            when (e.mode) { "PERCENT" -> " ($v%)"; "SHARES" -> " ($v share${if (v == "1") "" else "s"})"; else -> "" }
                        }.orEmpty()
                        val line = when {
                            e.settlement && p > 0 -> "$name paid ${Money.format(p)}"
                            e.settlement -> "$name received ${Money.format(s)}"
                            p > 0 && s > 0 -> "$name paid ${Money.format(p)} and ${if (you) "owe" else "owes"} ${Money.format(s)}$hint"
                            p > 0 -> "$name paid ${Money.format(p)}"
                            else -> "$name ${if (you) "owe" else "owes"} ${Money.format(s)}$hint"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Avatar(group.info[uid]?.name ?: "?", uid, 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                // An expense cleared by an advance paid earlier: how much it took, the advance, and what is left of it.
                if (!e.settlement && !e.deleted) {
                    val book = remember(liveHere, group.simplify) { com.splitfree.data.Pairs(liveHere, group.simplify) }
                    val idx = liveHere.associateBy { it.id }
                    val rows = e.involved.flatMap { d -> e.involved.map { c -> d to c } }
                        .filter { (d, c) -> d != c && (me == d || me == c || me !in e.involved) }
                        .mapNotNull { (d, c) ->
                            val owed = com.splitfree.data.Pairs.owed(e, d, c).takeIf { it > 0 } ?: return@mapNotNull null
                            val st = book.state(d, c)
                            val pays = liveHere.filter { it.settlement && d in it.paid.keys && c in it.shares.keys }
                            val explicit = pays.sumOf { com.splitfree.data.Pairs.allocOf(it, idx)[e.id] ?: 0L }
                            val fromAdvance = (owed - (st.aOwes[e.id] ?: owed) - explicit).coerceAtLeast(0)
                            // Advances: payments made before this expense with money left over after their own expenses.
                            val advances = pays.filter { it.amount > com.splitfree.data.Pairs.allocOf(it, idx).values.sum() && it.date <= e.moment }
                            if (fromAdvance <= 0 || advances.isEmpty()) null
                            else AdvanceUse(d, c, fromAdvance, advances.sortedBy { it.date }, (-st.net).coerceAtLeast(0))
                        }
                    if (rows.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Settled with an advance")
                        rows.forEach { r ->
                            val payer = if (r.from == me) "You" else group.name(r.from, me)
                            val holder = if (r.to == me) "you" else group.name(r.to, me)
                            WarmCard(padding = 16.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CategoryBubble("🤝", 40.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("${Money.format(r.used)} of ${if (r.from == me) "your" else group.name(r.from, me) + "'s"} share came from the advance",
                                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                r.advances.forEach { a ->
                                    Text("$payer paid $holder ${Money.format(a.amount)} in advance on ${settledAt(a.date)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable { nav.push(Screen.Detail(gid, a.id)) }.padding(vertical = 2.dp))
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(if (r.left > 0) "${Money.format(r.left)} of the advance is still left with $holder" else "Nothing is left of the advance now",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
                // Each expense this payment went towards: how much, and when that expense was added.
                val covered = alloc.mapNotNull { (id, v) -> liveHere.firstOrNull { it.id == id }?.let { it to v } }
                    .sortedBy { it.first.date }
                if (e.settlement && covered.isNotEmpty()) {
                    val from = e.paid.keys.firstOrNull().orEmpty()
                    val to = e.shares.keys.firstOrNull().orEmpty()
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Settled with this payment")
                    ListCard {
                        covered.forEachIndexed { i, (x, v) ->
                            if (i > 0) HairLine()
                            val owed = com.splitfree.data.Pairs.owed(x, from, to)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { nav.push(Screen.Detail(gid, x.id)) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                CategoryBubble(x.category.emoji, 38.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(x.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(whenText(x.date, x.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(Money.format(v), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                    Text(if (owed in 1..v) "Settled" else if (owed > v) "of ${Money.format(owed)}" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    val extra = e.amount - covered.sumOf { it.second }
                    if (extra > 0) Text("${Money.format(extra)} was extra and carried forward.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
                Spacer(Modifier.height(18.dp))
                SectionLabel("Comments")
                if (comments.isEmpty()) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    IconBubble(Icons.Rounded.ChatBubbleOutline, size = 60.dp, iconSize = 28.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("No comments yet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text("Everyone in this expense is notified of new comments.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            items(comments, key = { it.id }) { c ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Avatar(c.name, c.uid, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (c.uid == me) "You" else c.name) + " · " + Fmt.relative(c.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (!e.deleted) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Field(draft, { draft = it.take(500) }, Modifier.weight(1f), placeholder = "Add a comment")
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { Repo.comment(group, e, draft.trim()); draft = "" }, enabled = draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
        }
    }

    if (confirmDelete) ConfirmDialog(
        "Delete this ${if (e.settlement) "payment" else "expense"}?",
        "Everyone in it will be notified. You can restore it later from Group settings → Recently deleted.",
        "Delete", onConfirm = { Repo.deleteExpense(group, e); toast(context, "Deleted"); nav.pop() },
        onDismiss = { confirmDelete = false }
    )
}

/** "28 Sep 2026, 6:47 PM": the day of the expense, with the time it was added when that was the same day. */
/** "29 Sep 2026, 10:43 PM": the moment a payment was recorded. */
private fun settledAt(ms: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", java.util.Locale.US).format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

private fun whenText(date: Long, created: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(date).atZone(zone)
    val c = java.time.Instant.ofEpochMilli(created).atZone(zone)
    val day = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.US).format(d)
    return if (d.toLocalDate() == c.toLocalDate()) day + ", " + java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US).format(c) else day
}

private fun modeLabel(m: String) = when (m) { "EXACT" -> "by exact amounts"; "PERCENT" -> "by percentage"; "SHARES" -> "by shares"; else -> "equally" }

@Composable
private fun MoneyLine(name: String, uid: String, right: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Avatar(name, uid, 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(right, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DeletedScreen(nav: NavViewModel, gid: String) {
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val all by Repo.expenses.collectAsStateWithLifecycle()
    val group = groups.firstOrNull { it.id == gid } ?: run { nav.pop(); return }
    val deleted = all[gid].orEmpty().filter { it.deleted && !it.pending }.sortedByDescending { it.deletedAt }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar("Recently deleted", onBack = { nav.pop() })
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (deleted.isEmpty()) item { Footnote("Nothing deleted in this group.") }
            items(deleted, key = { it.id }) { e ->
                ExpenseRow(group, e) { nav.push(Screen.Detail(gid, e.id)) }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Text("Deleted ${Fmt.relative(e.deletedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(top = 12.dp, start = 4.dp))
                    SecondaryButton("Restore", Icons.Rounded.Restore, { Repo.restoreExpense(group, e); toast(context, "Restored") })
                }
            }
        }
    }
}

/** Part of [from]'s share of an expense paid out of the advance [to] was holding; [left] is what remains of it. */
private data class AdvanceUse(val from: String, val to: String, val used: Long, val advances: List<com.splitfree.data.Expense>, val left: Long)
