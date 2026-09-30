package com.splitfree.money

import com.splitfree.data.Category
import com.splitfree.data.Expense
import com.splitfree.data.Pairs
import com.splitfree.data.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Which expenses count as settled, partly settled or open, and that it always matches the balances. */
class PairsTest {
    private var clock = 1_000L

    private fun expense(id: String, paid: Map<String, Long>, shares: Map<String, Long>) = Expense(
        id = id, groupId = "g", title = id, note = "", category = Category.GENERAL, amount = paid.values.sum(), date = clock++,
        paid = paid, shares = shares, mode = "EXACT", inputs = emptyMap(), payerInputs = emptyMap(), createdBy = paid.keys.first(),
        createdAt = clock++, updatedAt = 0, deleted = false, deletedAt = 0, settlement = false, repeat = Repeat.NONE, nextDue = 0, templateId = null
    )

    private fun payment(id: String, from: String, to: String, amount: Long, alloc: Map<String, Long> = emptyMap()) = Expense(
        id = id, groupId = "g", title = "Payment", note = "", category = Category.GENERAL, amount = amount, date = clock++,
        paid = mapOf(from to amount), shares = mapOf(to to amount), mode = "EXACT",
        inputs = if (alloc.isEmpty()) emptyMap() else mapOf("alloc" to Pairs.encode(alloc)), payerInputs = emptyMap(), createdBy = to,
        createdAt = clock++, updatedAt = 0, deleted = false, deletedAt = 0, settlement = true, repeat = Repeat.NONE, nextDue = 0, templateId = null
    )

    /** Ramesh paid for both; I owe ₹20 for samosa and ₹30 for gujiya. */
    private fun snacks() = listOf(
        expense("samosa", mapOf("ramesh" to 4000L), mapOf("ramesh" to 2000L, "me" to 2000L)),
        expense("gujiya", mapOf("ramesh" to 6000L), mapOf("ramesh" to 3000L, "me" to 3000L))
    )

    /** Records a lump sum the way the app does: spread smallest first, then saved with that spread. */
    private fun pay(live: List<Expense>, id: String, from: String, to: String, amount: Long, simplify: Boolean = false): List<Expense> =
        live + payment(id, from, to, amount, Pairs(live, simplify).allocate(from, to, amount).amounts)

    /** B pays A ₹1,000 up front; A's later ₹200 expense (split equally) comes out of it, leaving ₹900. */
    @Test fun advanceSettlesLaterExpenses() {
        for (simplify in listOf(false, true)) {
            var live = listOf(payment("adv", "b", "a", 100_000))
            live = live + expense("tea", mapOf("a" to 20_000L), mapOf("a" to 10_000L, "b" to 10_000L))
            val book = Pairs(live, simplify)
            val s = book.state("b", "a")
            assertEquals(-90_000L, s.net)            // a still holds ₹900 of b's advance
            assertEquals(0L, s.aOwes["tea"])        // b's ₹100 share is already covered
            assertTrue(book.progress("b").getValue("tea").settled)
            // Nothing is left for a to mark as settled on b: b owes a nothing.
            assertTrue(book.state("b", "a").aOwes.values.all { it == 0L })
        }
    }

    @Test fun lumpSumClearsSmallestFirstThenPartlySettles() {
        val live = pay(snacks(), "p1", "me", "ramesh", 4000)   // paid ₹40 of ₹50
        val s = Pairs(live, false).state("me", "ramesh")
        assertEquals(1000L, s.net)
        assertEquals(0L, s.aOwes["samosa"])
        assertEquals(1000L, s.aOwes["gujiya"])
        val p = Pairs(live, false).progress("me")
        assertTrue(p.getValue("samosa").settled)
        assertTrue(p.getValue("gujiya").partly)
        assertEquals(1000L, p.getValue("gujiya").open)
        assertEquals(3000L, p.getValue("gujiya").owed)
    }

    @Test fun payingExtraReversesAndIsUsedUpByLaterExpenses() {
        var live = pay(snacks(), "p1", "me", "ramesh", 6000)   // owed ₹50, paid ₹60
        var s = Pairs(live, false).state("me", "ramesh")
        assertEquals(-1000L, s.net)                            // Ramesh now owes me ₹10
        assertTrue(s.aOwes.values.all { it == 0L })
        assertEquals(1, Balances.pairwise(live.map { it.flows }).size)
        assertEquals(Debt("ramesh", "me", 1000), Balances.pairwise(live.map { it.flows }).single())
        // Ramesh pays for tea later; my share is ₹25. The ₹10 I overpaid comes off it by itself.
        live = live + expense("tea", mapOf("ramesh" to 5000L), mapOf("ramesh" to 2500L, "me" to 2500L))
        s = Pairs(live, false).state("me", "ramesh")
        assertEquals(1500L, s.net)
        assertEquals(1500L, s.aOwes["tea"])
        assertTrue(Pairs(live, false).progress("me").getValue("tea").partly)
    }

    @Test fun secondPaymentFinishesAPartlySettledExpense() {
        var live = pay(snacks(), "p1", "me", "ramesh", 4000)
        live = pay(live, "p2", "me", "ramesh", 1000)
        val p = Pairs(live, false).progress("me")
        assertTrue(p.getValue("samosa").settled && p.getValue("gujiya").settled)
        assertTrue(Balances.nets(live.map { it.flows }).isEmpty())
    }

    @Test fun debtsBothWaysCancelBeforeAnythingShowsOpen() {
        // I owe Ramesh ₹50 on snacks; he owes me ₹50 on a cab. Nobody owes anything.
        val live = snacks() + expense("cab", mapOf("me" to 10000L), mapOf("me" to 5000L, "ramesh" to 5000L))
        val s = Pairs(live, false).state("me", "ramesh")
        assertEquals(0L, s.net)
        assertTrue(s.aOwes.values.all { it == 0L } && s.bOwes.values.all { it == 0L })
    }

    @Test fun openAmountsAlwaysAddUpToTheBalance() {
        val rnd = Random(7)
        val people = listOf("a", "b", "c", "d")
        repeat(400) {
            var live = emptyList<Expense>()
            val simplify = rnd.nextBoolean()
            repeat(rnd.nextInt(1, 14)) { k ->
                if (live.isNotEmpty() && rnd.nextInt(4) == 0) {
                    // a lump sum between two random people, sometimes more than is owed
                    val (x, y) = people.shuffled(rnd).take(2)
                    live = pay(live, "p$k", x, y, rnd.nextLong(1, 40_000), simplify)
                } else {
                    val amount = rnd.nextLong(100, 50_000)
                    val payer = people.random(rnd)
                    val split = people.shuffled(rnd).take(rnd.nextInt(1, 5))
                    live = live + expense("e$k", mapOf(payer to amount), Split.weighted(amount, split.associateWith { 1L }, split, mapOf(payer to amount)))
                }
            }
            val book = Pairs(live, simplify)
            val shown = Balances.pairwise(live.map { it.flows })
            for (i in people.indices) for (j in i + 1 until people.size) {
                val a = people[i]; val b = people[j]
                val s = book.state(a, b)
                // never negative, never more than the expense's own due
                s.aDues.forEach { assertTrue(s.aOwes.getValue(it.id) in 0..it.amount) }
                s.bDues.forEach { assertTrue(s.bOwes.getValue(it.id) in 0..it.amount) }
                // only the one who owes on balance has anything open, and it never exceeds the balance
                assertEquals(s.net.coerceIn(0, s.aDues.sumOf { it.amount }), s.aOwes.values.sum())
                assertEquals((-s.net).coerceIn(0, s.bDues.sumOf { it.amount }), s.bOwes.values.sum())
                // without simplifying, the balance is exactly the debt the group shows
                if (!simplify) assertEquals((shown.firstOrNull { it.from == a && it.to == b }?.amount ?: 0L) -
                    (shown.firstOrNull { it.from == b && it.to == a }?.amount ?: 0L), s.net)
            }
            // an expense is never "more than fully" open
            book.progress(null).values.forEach { assertTrue(it.open in 0..it.owed) }
        }
    }

    @Test fun allocationNeverLosesAPaisa() {
        val rnd = Random(11)
        repeat(3000) {
            val dues = List(rnd.nextInt(0, 8)) { Due("d$it", rnd.nextLong(0, 50), rnd.nextLong(1, 10_000)) }
            val open = dues.associate { it.id to rnd.nextLong(0, it.amount + 1) }
            val amount = rnd.nextLong(0, 40_000)
            val a = Ledger.allocate(amount, open, dues)
            assertEquals(amount, a.amounts.values.sum() + a.extra)
            a.amounts.forEach { (id, v) -> assertTrue(v in 1..open.getValue(id)) }
            // extra only once everything open is covered
            if (a.extra > 0) assertEquals(open.values.sum(), a.amounts.values.sum())
            // smallest first: at most one expense is left partly covered, and nothing smaller than it is skipped
            val partial = a.amounts.filter { (id, v) -> v < open.getValue(id) }
            assertTrue(partial.size <= 1)
            // what is still open afterwards matches
            val settled = dues.sumOf { it.amount } - open.values.sum() + a.amounts.values.sum()
            val after = Ledger.remaining(dues, settled, listOf(dues.associate { it.id to it.amount - open.getValue(it.id) }.filterValues { it > 0 }, a.amounts))
            assertEquals(dues.sumOf { it.amount } - settled, after.values.sum())
        }
    }
}
