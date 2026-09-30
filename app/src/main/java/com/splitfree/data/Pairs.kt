package com.splitfree.data

import com.splitfree.money.Alloc
import com.splitfree.money.Balances
import com.splitfree.money.Due
import com.splitfree.money.Ledger

/**
 * Which expenses are settled, partly settled or still open between two people
 * in one group.
 *
 * How much one owes the other always comes from the balances the app already
 * shows (expenses both ways and every payment). This only works out how that
 * amount sits on individual expenses, so the two can never disagree.
 */
class Pairs(private val live: List<Expense>, private val simplify: Boolean) {

    private val byId = live.associateBy { it.id }
    private val nets = Balances.nets(live.map { it.flows })
    /** Without simplifying: exactly the "who owes whom" list the group shows. */
    private val shown: Map<Pair<String, String>, Long> =
        if (simplify) emptyMap() else Balances.pairwise(live.map { it.flows }).associate { (it.from to it.to) to it.amount }

    /**
     * Between [a] and [b]. [net] > 0 means a owes b that much (< 0: b owes a).
     * [aOwes] / [bOwes]: what is still open per expense; [aDues] / [bDues]: what
     * was owed on each to begin with.
     */
    class State(
        val net: Long,
        val aDues: List<Due>, val aOwes: Map<String, Long>,
        val bDues: List<Due>, val bOwes: Map<String, Long>
    )

    fun state(a: String, b: String): State {
        val aDues = live.mapNotNull { e -> owed(e, a, b).takeIf { it > 0 }?.let { Due(e.id, e.date, it) } }
        val bDues = live.mapNotNull { e -> owed(e, b, a).takeIf { it > 0 }?.let { Due(e.id, e.date, it) } }
        val aPays = live.filter { it.settlement && a in it.paid.keys && b in it.shares.keys }.sortedBy { it.createdAt }
        val bPays = live.filter { it.settlement && b in it.paid.keys && a in it.shares.keys }.sortedBy { it.createdAt }
        val aGross = aDues.sumOf { it.amount }
        val bGross = bDues.sumOf { it.amount }
        val raw = aGross - bGross - aPays.sumOf { it.amount } + bPays.sumOf { it.amount }
        val net = when {
            // The list the group shows is the truth (it already cancels money going in circles).
            !simplify -> (shown[a to b] ?: 0L) - (shown[b to a] ?: 0L)
            // Simplified: anyone whose balance is zero is square with everybody.
            (nets[a] ?: 0L) == 0L || (nets[b] ?: 0L) == 0L -> 0L
            else -> raw
        }
        // Whoever owes on balance has exactly that much open; the other side has nothing open.
        val aOpen = net.coerceIn(0, aGross)
        val bOpen = (-net).coerceIn(0, bGross)
        return State(
            net,
            aDues, Ledger.remaining(aDues, aGross - aOpen, aPays.map { allocOf(it, byId) }),
            bDues, Ledger.remaining(bDues, bGross - bOpen, bPays.map { allocOf(it, byId) })
        )
    }

    /** How a lump sum from [from] to [to] would be spread right now: smallest open amounts first. */
    fun allocate(from: String, to: String, amount: Long): Alloc {
        val s = state(from, to)
        return Ledger.allocate(amount, s.aOwes, s.aDues)
    }

    /** Open and original amounts on one expense, summed over the pairs that matter. */
    data class Progress(val owed: Long, val open: Long) {
        val settled get() = owed > 0 && open == 0L
        val partly get() = open in 1 until owed
    }

    /**
     * Settlement progress of every expense, seen from [viewer]: the pairs the
     * viewer is part of, or every pair when [viewer] is null or not involved.
     */
    fun progress(viewer: String?): Map<String, Progress> {
        val people = live.flatMap { it.involved }.distinct().sorted()
        val mine = HashMap<String, LongArray>()   // id -> [owed, open] for pairs with the viewer
        val all = HashMap<String, LongArray>()
        for (i in people.indices) for (j in i + 1 until people.size) {
            val a = people[i]; val b = people[j]
            val s = state(a, b)
            fun add(dues: List<Due>, open: Map<String, Long>) = dues.forEach { d ->
                val left = open[d.id] ?: 0L
                all.getOrPut(d.id) { LongArray(2) }.let { it[0] += d.amount; it[1] += left }
                if (viewer == a || viewer == b) mine.getOrPut(d.id) { LongArray(2) }.let { it[0] += d.amount; it[1] += left }
            }
            add(s.aDues, s.aOwes); add(s.bDues, s.bOwes)
        }
        return all.mapValues { (id, v) -> (mine[id] ?: v).let { Progress(it[0], it[1]) } }
    }

    companion object {
        /** What [from] owes [to] on this one expense, taken by itself. */
        fun owed(e: Expense, from: String, to: String): Long =
            if (e.settlement) 0L
            else Balances.settle(Balances.nets(listOf(e.flows))).filter { it.from == from && it.to == to }.sumOf { it.amount }

        /** "id=123;id=456" ⇄ map. */
        fun encode(alloc: Map<String, Long>): String = alloc.entries.joinToString(";") { "${it.key}=${it.value}" }

        fun decode(text: String?): Map<String, Long> = text.orEmpty().split(';').mapNotNull {
            val i = it.lastIndexOf('=')
            if (i <= 0) null else it.substring(i + 1).toLongOrNull()?.let { v -> it.substring(0, i) to v }
        }.toMap()

        /** The expenses a payment was recorded against, with the paise put towards each. */
        fun allocOf(payment: Expense, byId: Map<String, Expense>): Map<String, Long> {
            val stored = decode(payment.inputs["alloc"])
            if (stored.isNotEmpty()) return stored
            // Older payments only list the expenses they cleared in full.
            val from = payment.paid.keys.firstOrNull() ?: return emptyMap()
            val to = payment.shares.keys.firstOrNull() ?: return emptyMap()
            return payment.inputs["settles"].orEmpty().split(',').filter { it.isNotBlank() }
                .mapNotNull { id -> byId[id]?.let { id to owed(it, from, to) } }.toMap()
        }
    }
}
