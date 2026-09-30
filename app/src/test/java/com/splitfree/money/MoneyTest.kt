package com.splitfree.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Randomised checks that money never appears or disappears, to the paisa. */
class MoneyTest {
    private val rnd = Random(42)
    private val people = listOf("a", "b", "c", "d", "e", "f")

    private fun someOf(min: Int = 1) = people.shuffled(rnd).take(rnd.nextInt(min, people.size + 1))

    /** A random valid expense: (paid, shares), each adding up to the same amount. */
    private fun expense(): Pair<Map<String, Long>, Map<String, Long>> {
        val amount = rnd.nextLong(1, 5_000_00)
        val payers = someOf().take(rnd.nextInt(1, 4))
        val paid = Split.weighted(amount, payers.associateWith { rnd.nextLong(1, 10) }, payers, emptyMap())
        val split = someOf()
        val shares = when (rnd.nextInt(3)) {
            0 -> (Split.compute(amount, SplitMode.EQUAL, split, emptyMap(), paid) as SplitResult.Ok).shares
            1 -> Split.weighted(amount, split.associateWith { rnd.nextLong(1, 20) }, split, paid)
            else -> {
                // exact amounts typed by hand
                val w = Split.weighted(amount, split.associateWith { rnd.nextLong(1, 20) }, split, emptyMap())
                val typed = w.mapValues { Money.plain(it.value) }
                (Split.compute(amount, SplitMode.EXACT, split, typed, paid) as SplitResult.Ok).shares
            }
        }
        return paid to shares
    }

    @Test fun parseAndFormat() {
        assertEquals(123450L, Money.parse("1,234.5"))
        assertEquals(5L, Money.parse(".05"))
        assertEquals(100L, Money.parse("1."))
        assertNull(Money.parse("1.234"))
        assertNull(Money.parse("-5"))
        assertNull(Money.parse("abc"))
        // Android groups Indian-style (1,23,456); a desktop JVM may group as 123,456.
        assertTrue(Money.format(12345650) in setOf("₹1,23,456.50", "₹123,456.50"))
        assertEquals("₹0.05", Money.format(5))
        repeat(2000) { val p = rnd.nextLong(0, 10_00_00_000); assertEquals(p, Money.parse(Money.plain(p))) }
    }

    @Test fun splitsAddUpExactly() {
        repeat(5000) {
            val (paid, shares) = expense()
            assertEquals(paid.values.sum(), shares.values.sum())
            assertTrue(shares.values.all { it >= 0 })
            assertTrue(paid.values.all { it >= 0 })
        }
    }

    @Test fun equalSplitIsFair() {
        repeat(3000) {
            val amount = rnd.nextLong(1, 9_99_999)
            val split = someOf()
            val payer = people.random(rnd)
            val s = (Split.compute(amount, SplitMode.EQUAL, split, emptyMap(), mapOf(payer to amount)) as SplitResult.Ok).shares
            assertEquals(amount, s.values.sum())
            // nobody is more than the leftover paise away from anyone else
            assertTrue(s.values.max() - s.values.min() <= split.size)
            // when the payer is in the split, everyone else pays exactly the floor share
            if (payer in split) split.filter { it != payer }.forEach { assertEquals(amount / split.size, s.getValue(it)) }
        }
    }

    @Test fun exactSplitRejectsWrongTotals() {
        val r = Split.compute(1000, SplitMode.EXACT, listOf("a", "b"), mapOf("a" to "4", "b" to "5"), mapOf("a" to 1000L))
        assertTrue(r is SplitResult.Error)
        val (paid, err) = Split.payers(1000, listOf("a", "b"), mapOf("a" to "4", "b" to "5"))
        assertNull(paid); assertTrue(err != null)
    }

    @Test fun debtsClearEveryBalance() {
        repeat(1500) {
            val items = List(rnd.nextInt(1, 25)) { expense() }
            val nets = Balances.nets(items)
            assertEquals(0L, nets.values.sum())
            for (debts in listOf(Balances.settle(nets), Balances.pairwise(items))) {
                assertTrue(debts.all { it.amount > 0 && it.from != it.to })
                // paying every debt brings everyone to exactly zero
                val after = HashMap(people.associateWith { nets[it] ?: 0L })
                for (d in debts) { after[d.from] = after.getValue(d.from) + d.amount; after[d.to] = after.getValue(d.to) - d.amount }
                assertTrue("not cleared: $after", after.values.all { it == 0L })
            }
            // simplified debts never need more payments than people minus one
            assertTrue(Balances.settle(nets).size <= maxOf(0, nets.size - 1))
            // no pair appears twice (in either direction) without simplifying
            val pairwise = Balances.pairwise(items)
            val pairs = pairwise.map { setOf(it.from, it.to) }
            assertEquals(pairs.size, pairs.toSet().size)
            // and no money goes round in a circle: following "owes" links never returns to the start
            val next = pairwise.groupBy({ it.from }, { it.to })
            for (p in people) {
                val seen = HashSet<String>(); val todo = ArrayDeque(next[p].orEmpty())
                while (todo.isNotEmpty()) { val x = todo.removeFirst(); assertTrue(x != p); if (seen.add(x)) todo += next[x].orEmpty() }
            }
        }
    }

    @Test fun recordingEachDebtAsAPaymentSettlesTheGroup() {
        repeat(800) {
            val items = List(rnd.nextInt(1, 15)) { expense() }.toMutableList()
            val simplify = rnd.nextBoolean()
            val debts = if (simplify) Balances.settle(Balances.nets(items)) else Balances.pairwise(items)
            // a payment is stored as: payer = who owed, share = who was owed
            for (d in debts) items += mapOf(d.from to d.amount) to mapOf(d.to to d.amount)
            assertTrue(Balances.nets(items).isEmpty())
            assertTrue(Balances.settle(Balances.nets(items)).isEmpty())
            assertTrue(Balances.pairwise(items).isEmpty())
        }
    }
}
