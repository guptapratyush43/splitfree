package com.splitfree.ui

import androidx.compose.foundation.layout.offset
import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.NavViewModel
import com.splitfree.data.Auth
import com.splitfree.data.Category
import com.splitfree.data.Expense
import com.splitfree.data.Group
import com.splitfree.data.Recurring
import com.splitfree.data.Repeat
import com.splitfree.data.Repo
import com.splitfree.money.Money
import com.splitfree.money.Split
import com.splitfree.money.SplitMode
import com.splitfree.money.SplitResult
import com.splitfree.ui.theme.LocalStatusColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun LocalDate.toMillis() = atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun Long.toDate() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private enum class Page { MAIN, PAID_BY, SPLIT }

/**
 * Laid out like Splitwise: description and amount up top, a "Paid by … and
 * split …" pill that opens the two detail pages, and date / repeat / note
 * along the bottom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpenseEditor(nav: NavViewModel, startGroup: String?, expenseId: String?, payment: com.splitfree.Screen.SettleUp? = null) {
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val me = Auth.uid!!
    // From the home screen the group is picked in the "With you and" capsule;
    // from inside a group it is already set.
    var gid by rememberSaveable { mutableStateOf(startGroup ?: groups.singleOrNull()?.id) }
    val picked = gid?.let { id -> groups.firstOrNull { it.id == id } }
    if (gid != null && picked == null && expenseId != null) { LaunchedEffect(Unit) { nav.pop() }; return }
    val group = picked ?: Group(
        id = "", name = "", createdBy = me, members = listOf(me),
        info = mapOf(me to com.splitfree.data.Member(me, Auth.name, Auth.email)),
        simplify = false, joinCode = "", invited = emptyList(), cover = "", coverFor = null, createdAt = 0, deleted = false
    )
    val existing = remember(expenseId) { expenseId?.let { id -> Repo.expenses.value[gid]?.firstOrNull { it.id == id } } }
    val context = LocalContext.current
    // A payment ("A paid B") is recorded on this same screen: one payer, one receiver.
    val isPayment = payment != null || existing?.settlement == true
    val defaultReceiver = payment?.to ?: group.members.firstOrNull { it != (payment?.from ?: me) }
    // Current members, plus anyone already on this expense who has since left.
    val people = remember(group.id, group.members, existing) { (group.members + (existing?.involved ?: emptySet())).distinct() }

    var title by remember { mutableStateOf(existing?.title ?: if (isPayment) "Payment" else "") }
    var amountText by remember { mutableStateOf(existing?.amount?.let { Money.plain(it) } ?: payment?.amount?.takeIf { it > 0 }?.let { Money.plain(it) }.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var category by remember { mutableStateOf(existing?.category ?: Category.GENERAL) }
    var date by remember { mutableStateOf(existing?.date?.toDate() ?: LocalDate.now()) }
    var repeat by remember { mutableStateOf(existing?.repeat ?: Repeat.NONE) }
    val payers = remember(group.id) { mutableStateListOf<String>().apply { addAll(existing?.paid?.keys?.toList() ?: listOf(payment?.from ?: me)) } }
    val payerInputs = remember(group.id) { mutableStateMapOf<String, String>().apply { existing?.paid?.forEach { (k, v) -> put(k, Money.plain(v)) } } }
    var mode by remember { mutableStateOf(existing?.let { runCatching { SplitMode.valueOf(it.mode) }.getOrNull() } ?: SplitMode.EQUAL) }
    val split = remember(group.id) { mutableStateListOf<String>().apply { addAll(existing?.shares?.keys?.toList() ?: if (isPayment) listOfNotNull(defaultReceiver) else group.members) } }
    val inputs = remember(group.id) { mutableStateMapOf<String, String>().apply { existing?.inputs?.let { putAll(it) } } }
    var page by remember { mutableStateOf(Page.MAIN) }
    var dialog by remember { mutableStateOf<String?>(null) }

    val amount = Money.parse(amountText) ?: 0L
    val orderedPayers = people.filter { it in payers }
    val (paid, payerError) = if (amount > 0) Split.payers(amount, orderedPayers, payerInputs) else null to null
    val orderedSplit = people.filter { it in split }
    val result = if (amount > 0) Split.compute(amount, mode, orderedSplit, inputs, paid ?: emptyMap()) else null
    val shares = (result as? SplitResult.Ok)?.shares
    val error = when {
        picked == null -> "Choose a group"
        title.isBlank() -> "Enter a description"
        isPayment && (orderedPayers.size != 1 || orderedSplit.size != 1) -> "Pick one person who paid and one who received"
        isPayment && orderedPayers[0] == orderedSplit[0] -> "Payer and receiver must be different people"
        amount <= 0 -> "Enter an amount"
        payerError != null -> payerError
        result is SplitResult.Error -> result.message
        else -> null
    }

    fun save() {
        val now = System.currentTimeMillis()
        val dateMs = date.toMillis()
        val sameSchedule = existing != null && existing.repeat == repeat && existing.date == dateMs
        val e = Expense(
            id = existing?.id ?: Repo.newExpenseId(group.id), groupId = group.id, title = title.trim(), note = note.trim(),
            category = category, amount = amount, date = dateMs, paid = paid!!, shares = shares!!,
            mode = mode.name, inputs = if (mode == SplitMode.EQUAL) emptyMap() else orderedSplit.associateWith { inputs[it].orEmpty() },
            payerInputs = if (orderedPayers.size > 1) orderedPayers.associateWith { payerInputs[it].orEmpty() } else emptyMap(),
            createdBy = existing?.createdBy ?: me, createdAt = existing?.createdAt ?: now, updatedAt = now,
            deleted = false, deletedAt = 0, settlement = isPayment, repeat = if (isPayment) Repeat.NONE else repeat,
            nextDue = when {
                repeat == Repeat.NONE -> 0
                sameSchedule -> existing!!.nextDue
                else -> Recurring.next(dateMs, repeat)
            },
            templateId = existing?.templateId
        )
        Repo.saveExpense(group, e, isNew = existing == null)
        Haptics.success(context)
        toast(context, when { isPayment && existing == null -> "Payment recorded"; isPayment -> "Payment updated"; existing == null -> "Expense added"; else -> "Expense updated" })
        nav.pop()
    }

    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }

    when (page) {
        Page.PAID_BY -> {
            PaidByPage(group, people, me, amount, payers, payerInputs, payerError, allowMulti = !isPayment, onDone = { page = Page.MAIN })
            return
        }
        Page.SPLIT -> {
            if (isPayment) ReceiverPage(group, people, me, split, onDone = { page = Page.MAIN })
            else SplitPage(group, people, me, amount, mode, { mode = it }, split, inputs, shares, result, onDone = { page = Page.MAIN })
            return
        }
        Page.MAIN -> {}
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // ---- top bar: close, title, save ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.Rounded.Close, "Close", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp)) }
            Text(if (isPayment) (if (existing == null) "Record payment" else "Edit payment") else if (existing == null) "Add expense" else "Edit expense", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = { save() }, enabled = error == null) {
                Icon(Icons.Rounded.Check, "Save", tint = if (error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            // ---- with you and ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("With you and:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (picked == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .border(1.dp, if (picked == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                        .clickable(enabled = existing == null) { dialog = "group" }
                        .padding(start = if (picked == null) 14.dp else 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    if (picked != null) {
                        GroupBadge(group.name, group.id, 28.dp, group.cover, round = true)
                        Spacer(Modifier.width(8.dp))
                        Text("All of ${group.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
                    } else {
                        Text("Choose group", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                    if (existing == null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.ExpandMore, null, tint = if (picked == null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(Modifier.height(28.dp))

            // ---- description + amount ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).clickable { dialog = "category" }
                ) { Text(if (isPayment && category == Category.GENERAL) "🤝" else category.emoji, style = MaterialTheme.typography.headlineSmall) }
                Spacer(Modifier.width(14.dp))
                UnderlineField(title, { title = it.take(80) }, Modifier.weight(1f), placeholder = "Enter a description")
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                ) { Text("₹", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(14.dp))
                UnderlineField(amountText, { t -> if (t.isEmpty() || Money.parse(t) != null || t == ".") amountText = t },
                    Modifier.weight(1f), placeholder = "0.00", keyboard = KeyboardType.Decimal, big = true)
            }
            Spacer(Modifier.height(28.dp))

            // ---- paid by … and split … ----
            val payerLabel = when {
                orderedPayers.size > 1 -> "${orderedPayers.size} people"
                orderedPayers.size == 1 -> group.name(orderedPayers[0], me).let { if (it == "You") "you" else it }
                else -> "…"
            }
            val splitLabel = when (mode) {
                SplitMode.EQUAL -> if (orderedSplit.size == group.members.size) "equally" else "equally (${orderedSplit.size})"
                else -> "unequally"
            }
            FlowRow(
                horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Paid by", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.CenterVertically))
                Spacer(Modifier.width(8.dp))
                ActionPill(payerLabel, { page = Page.PAID_BY })
                Spacer(Modifier.width(8.dp))
                Text(if (isPayment) "to" else "and split", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.CenterVertically))
                Spacer(Modifier.width(8.dp))
                ActionPill(
                    if (isPayment) orderedSplit.singleOrNull()?.let { group.name(it, me).let { n -> if (n == "You") "you" else n } } ?: "Choose" else splitLabel,
                    { page = Page.SPLIT }
                )
            }
            Spacer(Modifier.height(12.dp))
            if (amount > 0 && orderedSplit.size == 1 && orderedPayers.size == 1 && orderedSplit[0] != orderedPayers[0]) {
                Footnote("${group.name(orderedSplit[0], me)} owe${if (orderedSplit[0] == me) "" else "s"} ${group.name(orderedPayers[0], me).let { if (it == "You") "you" else it }} the full ${Money.format(amount)}. Only the people in this expense get notified.")
            } else if (amount > 0 && mode == SplitMode.EQUAL && orderedSplit.isNotEmpty() && shares != null) {
                Footnote("${Money.format(amount / orderedSplit.size)} per person")
            }
            if (error != null && title.isNotBlank() && amount > 0) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (note.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                WarmCard(padding = 14.dp, onClick = { dialog = "note" }) {
                    CardLabel("Note")
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // ---- bottom bar: date, repeat, note ----
        HairLine()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            ActionPill(Fmt.date(date).let { if (it == "Today") "Today" else it }, {
                DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth)
                    .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
            }, icon = Icons.Rounded.CalendarMonth)
            ActionPill(if (note.isBlank()) "Add note" else "Note", { dialog = "note" }, icon = Icons.Rounded.EditNote)
        }
    }

    when (dialog) {
        "group" -> WarmDialog("Choose group", onDismiss = { dialog = null }) {
            if (groups.isEmpty()) Footnote("Create a group first from the Groups screen.")
            ListCard {
                groups.forEachIndexed { i, g ->
                    if (i > 0) HairLine()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { gid = g.id; dialog = null }.padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        GroupBadge(g.name, g.id, 36.dp, g.cover, round = true)
                        Spacer(Modifier.width(12.dp))
                        Text(g.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        RadioDot(g.id == gid)
                    }
                }
            }
        }
        "category" -> WarmDialog("Category", onDismiss = { dialog = null }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { c -> Chip("${c.emoji}  ${c.label}", c == category) { category = c; dialog = null } }
            }
        }
        "repeat" -> WarmDialog("Repeat", onDismiss = { dialog = null }) {
            ListCard {
                Repeat.entries.forEachIndexed { i, r ->
                    if (i > 0) HairLine()
                    SettingRow(r.label, null, null, onClick = { repeat = r; dialog = null }) { CheckDot(r == repeat) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Footnote("A repeating expense is added again automatically on the same date each week or month.")
        }
        "note" -> {
            var draft by remember { mutableStateOf(note) }
            WarmDialog("Note", onDismiss = { dialog = null }) {
                Field(draft, { draft = it.take(300) }, placeholder = "Any detail worth remembering", singleLine = false)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton("Cancel", null, { dialog = null }, Modifier.weight(1f))
                    PrimaryButton("Done", null, { note = draft; dialog = null }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** "Who paid?": tick everyone who chipped in; with more than one, enter each person's amount. */
@Composable
private fun PaidByPage(
    group: Group, people: List<String>, me: String, amount: Long,
    payers: MutableList<String>, inputs: MutableMap<String, String>, error: String?, allowMulti: Boolean = true, onDone: () -> Unit
) {
    val multi = allowMulti && payers.size > 1
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("Who paid?", onDone, enabled = !multi || error == null)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(if (allowMulti) "Tick everyone who chipped in." else "Who paid?", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            ListCard {
                people.forEachIndexed { i, uid ->
                    if (i > 0) HairLine()
                    val on = uid in payers
                    PersonRow(group.name(uid, me), uid, on, onToggle = {
                        when {
                            !allowMulti -> { payers.clear(); payers.add(uid); onDone() }
                            on && payers.size > 1 -> payers.remove(uid)
                            !on -> payers.add(uid)
                        }
                    }) {
                        androidx.compose.animation.AnimatedVisibility(multi && on,
                            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
                            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally()) {
                            MiniField(inputs[uid].orEmpty(), { inputs[uid] = it }, "₹", null)
                        }
                    }
                }
            }
            if (multi) {
                Spacer(Modifier.height(12.dp))
                val entered = payers.sumOf { Money.parse(inputs[it].orEmpty()) ?: 0L }
                Footnote("${Money.format(entered)} of ${Money.format(amount)}" + if (amount - entered > 0) " · ${Money.format(amount - entered)} left" else if (entered > amount) " · ${Money.format(entered - amount)} over" else "")
            }
        }
    }
}

/** "Split options": equally, exact ₹, percentage or shares, with a live summary at the bottom. */
@Composable
private fun SplitPage(
    group: Group, people: List<String>, me: String, amount: Long,
    mode: SplitMode, setMode: (SplitMode) -> Unit, split: MutableList<String>, inputs: MutableMap<String, String>,
    shares: Map<String, Long>?, result: SplitResult?, onDone: () -> Unit
) {
    val ok = result is SplitResult.Ok
    Column(Modifier.fillMaxSize().imePadding()) {
        val shake = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
        val shakeScope = androidx.compose.runtime.rememberCoroutineScope()
        val ctx = LocalContext.current
        PageBar("Adjust split", {
            if (ok || amount == 0L) onDone()
            else {
                // Money still to assign: shake the total, like a wrong PIN.
                Haptics.warn(ctx)
                shakeScope.launch {
                    for (x in listOf(-18f, 16f, -12f, 10f, -6f, 4f, 0f)) shake.animateTo(x, androidx.compose.animation.core.tween(45))
                }
            }
        }, enabled = true)
        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = if (mode == SplitMode.EXACT) 1 else 0) { 2 }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // Swiping and tapping a tab both switch the split type.
        LaunchedEffect(pager.settledPage) { setMode(if (pager.settledPage == 1) SplitMode.EXACT else SplitMode.EQUAL) }
        UnderlineTabs(listOf("Equally", "Unequally"), pager.currentPage) { i -> scope.launch { pager.animateScrollToPage(i) } }
        androidx.compose.foundation.pager.HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
        val shownMode = if (page == 1) SplitMode.EXACT else SplitMode.EQUAL
        val pageShares = if (shownMode == mode) shares else null
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(
                when (shownMode) {
                    SplitMode.EQUAL -> "Split equally"
                    SplitMode.EXACT -> "Split unequally"
                    SplitMode.PERCENT -> "Split by percentages"
                    SplitMode.SHARES -> "Split by shares"
                },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when (shownMode) {
                    SplitMode.EQUAL -> "Select which people owe an equal share."
                    SplitMode.EXACT -> "Enter exactly how much each person owes."
                    SplitMode.PERCENT -> "Enter the percentage split that's fair for your situation."
                    SplitMode.SHARES -> "Great for time-based splitting (2 nights → 2 shares) and splitting across families."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            ListCard {
                people.forEachIndexed { i, uid ->
                    if (i > 0) HairLine()
                    val on = uid in split
                    PersonRow(group.name(uid, me), uid, on, onToggle = { if (on) split.remove(uid) else split.add(uid) }) {
                        if (on) Column(horizontalAlignment = Alignment.End) {
                            when (shownMode) {
                                SplitMode.EQUAL -> pageShares?.get(uid)?.let { Text(Money.format(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                SplitMode.EXACT -> MiniField(inputs[uid].orEmpty(), { inputs[uid] = it }, "₹", null)
                                SplitMode.PERCENT -> MiniField(inputs[uid].orEmpty(), { inputs[uid] = it }, null, "%")
                                SplitMode.SHARES -> MiniField(inputs[uid].orEmpty(), { inputs[uid] = it.filter(Char::isDigit).take(4) }, null, "shares", KeyboardType.Number)
                            }
                            if (shownMode == SplitMode.PERCENT || shownMode == SplitMode.SHARES) pageShares?.get(uid)?.let {
                                Spacer(Modifier.height(2.dp))
                                Text(Money.format(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        }
        // ---- summary bar ----
        HairLine()
        val (line1, line2) = when (mode) {
            SplitMode.EQUAL -> if (split.isEmpty()) "Pick at least one person" to null
                else "${Money.format(amount / split.size)}/person" to "(${split.size} ${if (split.size == 1) "person" else "people"})"
            else -> {
                val sum = split.sumOf { Money.parse(inputs[it].orEmpty()) ?: 0L }
                "${Money.format(sum)} of ${Money.format(amount)}" to when {
                    sum < amount -> "${Money.format(amount - sum)} left"
                    sum > amount -> "${Money.format(sum - amount)} over"
                    else -> "All assigned"
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).offset { androidx.compose.ui.unit.IntOffset(shake.value.dp.roundToPx(), 0) }) {
                Text(line1, style = MaterialTheme.typography.titleMedium,
                    color = if (ok || amount == 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                if (line2 != null) Text(line2, style = MaterialTheme.typography.bodyMedium,
                    color = if (ok || amount == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            if (mode == SplitMode.EQUAL) {
                Box(Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outline))
                val all = group.members.all { it in split }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { if (all) split.clear() else { split.clear(); split.addAll(group.members) } }.padding(horizontal = 22.dp, vertical = 8.dp)
                ) {
                    Text("All", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    CheckDot(all)
                }
            }
        }
    }
}

/** "Paid to": exactly one person receives a payment. */
@Composable
private fun ReceiverPage(group: Group, people: List<String>, me: String, split: MutableList<String>, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageBar("Paid to", onDone, enabled = split.size == 1)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ListCard {
                people.forEachIndexed { i, uid ->
                    if (i > 0) HairLine()
                    PersonRow(group.name(uid, me), uid, uid in split, onToggle = { split.clear(); split.add(uid); onDone() }) {}
                }
            }
        }
    }
}

@Composable
private fun PageBar(title: String, onDone: () -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp)) }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f).padding(start = 4.dp))
        IconButton(onClick = onDone, enabled = enabled) {
            Icon(Icons.Rounded.Check, "Done", tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun PersonRow(name: String, uid: String, checked: Boolean, onToggle: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Avatar(name, uid, 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        trailing()
        Spacer(Modifier.width(12.dp))
        CheckDot(checked)
    }
}

/** Basis points as a percent string: 3333 → "33.33", 10000 → "100". */
private fun pct(bp: Long) = if (bp % 100 == 0L) "${bp / 100}" else "${bp / 100}.${(bp % 100).toString().padStart(2, '0')}"
