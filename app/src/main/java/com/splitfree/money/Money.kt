package com.splitfree.money

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * All money is whole paise in a Long. Nothing here ever touches a Double, so
 * an expense always adds up to exactly what was typed.
 */
object Money {
    private val rupees = NumberFormat.getIntegerInstance(Locale("en", "IN"))

    /** ₹1,23,456.50 — Indian digit grouping, always two decimals. */
    fun format(paise: Long, sign: Boolean = false): String {
        val a = abs(paise)
        val body = "₹" + rupees.format(a / 100) + "." + (a % 100).toString().padStart(2, '0')
        return when {
            paise < 0 -> "−$body"
            sign && paise > 0 -> "+$body"
            else -> body
        }
    }

    /** Plain number for text fields: 1234.5 → "1234.50", whole rupees without decimals. */
    fun plain(paise: Long): String =
        if (paise % 100 == 0L) (paise / 100).toString() else "${paise / 100}.${(abs(paise) % 100).toString().padStart(2, '0')}"

    /** Parses "1,234.5" to paise. Null when empty, malformed, negative or more than two decimals. */
    fun parse(text: String): Long? {
        val t = text.replace(",", "").replace("₹", "").trim()
        if (t.isEmpty() || !Regex("""\d+(\.\d{0,2})?|\.\d{1,2}""").matches(t)) return null
        return runCatching { BigDecimal(t).movePointRight(2).longValueExact() }.getOrNull()
            ?.takeIf { it <= 99_99_99_999_99L }
    }

    /** Percent text with up to two decimals to basis points (12.5 → 1250). */
    fun parsePercent(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty() || !Regex("""\d+(\.\d{0,2})?|\.\d{1,2}""").matches(t)) return null
        return runCatching { BigDecimal(t).movePointRight(2).longValueExact() }.getOrNull()
    }
}

enum class SplitMode(val label: String) { EQUAL("Equally"), EXACT("Exact ₹"), PERCENT("By %"), SHARES("By shares") }

sealed interface SplitResult {
    data class Ok(val shares: Map<String, Long>) : SplitResult
    data class Error(val message: String) : SplitResult
}

object Split {

    /**
     * Turns what the user entered into exact per-person paise that add up to
     * [amount]. [people] is the ordered list of selected members; [inputs] holds
     * their typed values for exact / percent / shares. Any leftover paisa from
     * rounding goes to the biggest payer who is part of the split; if no payer
     * is in the split it is spread one paisa at a time by largest remainder.
     */
    fun compute(amount: Long, mode: SplitMode, people: List<String>, inputs: Map<String, String>, paid: Map<String, Long>): SplitResult {
        if (amount <= 0) return SplitResult.Error("Enter an amount")
        if (people.isEmpty()) return SplitResult.Error("Pick at least one person to split with")
        return when (mode) {
            SplitMode.EQUAL -> SplitResult.Ok(weighted(amount, people.associateWith { 1L }, people, paid))
            SplitMode.EXACT -> {
                val values = people.associateWith { Money.parse(inputs[it].orEmpty()) ?: 0L }
                val sum = values.values.sum()
                when {
                    sum != amount -> SplitResult.Error(
                        if (sum < amount) "${Money.format(amount - sum)} left to assign" else "${Money.format(sum - amount)} over the total"
                    )
                    else -> SplitResult.Ok(values.filterValues { it > 0 })
                }
            }
            SplitMode.PERCENT -> {
                val bp = people.associateWith { Money.parsePercent(inputs[it].orEmpty()) ?: 0L }
                val sum = bp.values.sum()
                if (sum != 10_000L) SplitResult.Error("Percentages add up to ${pct(sum)}%, need 100%")
                else SplitResult.Ok(weighted(amount, bp.filterValues { it > 0 }, people, paid))
            }
            SplitMode.SHARES -> {
                val w = people.associateWith { inputs[it]?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: 0L }
                if (w.values.sum() <= 0) SplitResult.Error("Give at least one person a share")
                else SplitResult.Ok(weighted(amount, w.filterValues { it > 0 }, people, paid))
            }
        }
    }

    private fun pct(bp: Long) = if (bp % 100 == 0L) "${bp / 100}" else "${bp / 100}.${(bp % 100).toString().padStart(2, '0')}"

    /** Proportional split by integer weights, exact to the paisa. */
    fun weighted(amount: Long, weights: Map<String, Long>, order: List<String>, paid: Map<String, Long>): Map<String, Long> {
        val total = weights.values.sum()
        val ids = order.filter { (weights[it] ?: 0) > 0 }
        val out = LinkedHashMap<String, Long>()
        val rem = HashMap<String, Long>()
        for (id in ids) {
            val num = amount.toBigInteger() * weights.getValue(id).toBigInteger()
            val (q, r) = num.divideAndRemainder(total.toBigInteger())
            out[id] = q.toLong(); rem[id] = r.toLong()
        }
        var left = amount - out.values.sum()
        if (left == 0L) return out
        val payerInSplit = ids.filter { (paid[it] ?: 0) > 0 }.maxByOrNull { paid.getValue(it) }
        if (payerInSplit != null) {
            out[payerInSplit] = out.getValue(payerInSplit) + left
            return out
        }
        for (id in ids.sortedByDescending { rem.getValue(it) }) {
            if (left == 0L) break
            out[id] = out.getValue(id) + 1; left--
        }
        return out
    }

    /** Checks who-paid entries: one payer pays everything, several must add up. */
    fun payers(amount: Long, payers: List<String>, inputs: Map<String, String>): Pair<Map<String, Long>?, String?> {
        if (payers.isEmpty()) return null to "Pick who paid"
        if (payers.size == 1) return mapOf(payers[0] to amount) to null
        val values = payers.associateWith { Money.parse(inputs[it].orEmpty()) ?: 0L }
        val sum = values.values.sum()
        return when {
            sum < amount -> null to "Payers are ${Money.format(amount - sum)} short of the total"
            sum > amount -> null to "Payers are ${Money.format(sum - amount)} over the total"
            else -> values.filterValues { it > 0 } to null
        }
    }
}

data class Debt(val from: String, val to: String, val amount: Long)

object Balances {

    /** Net per person: positive means they are owed, negative means they owe. */
    fun nets(items: List<Pair<Map<String, Long>, Map<String, Long>>>): Map<String, Long> {
        val net = HashMap<String, Long>()
        for ((paid, shares) in items) {
            paid.forEach { (u, p) -> net[u] = (net[u] ?: 0) + p }
            shares.forEach { (u, s) -> net[u] = (net[u] ?: 0) - s }
        }
        return net.filterValues { it != 0L }
    }

    /** Fewest payments that clear the given nets: biggest debtor pays biggest creditor. */
    fun settle(nets: Map<String, Long>): List<Debt> {
        val cred = nets.filterValues { it > 0 }.map { it.key to it.value }.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }).toMutableList()
        val debt = nets.filterValues { it < 0 }.map { it.key to -it.value }.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }).toMutableList()
        val out = ArrayList<Debt>()
        var i = 0; var j = 0
        while (i < debt.size && j < cred.size) {
            val pay = minOf(debt[i].second, cred[j].second)
            out += Debt(debt[i].first, cred[j].first, pay)
            debt[i] = debt[i].first to debt[i].second - pay
            cred[j] = cred[j].first to cred[j].second - pay
            if (debt[i].second == 0L) i++
            if (cred[j].second == 0L) j++
        }
        return out
    }

    /**
     * Who owes whom without simplifying: each expense is settled on its own,
     * then flows between the same two people cancel out.
     */
    fun pairwise(items: List<Pair<Map<String, Long>, Map<String, Long>>>): List<Debt> {
        val pair = HashMap<Pair<String, String>, Long>()
        for (item in items) for (d in settle(nets(listOf(item)))) {
            val (a, b) = if (d.from < d.to) d.from to d.to else d.to to d.from
            val signed = if (d.from < d.to) d.amount else -d.amount
            pair[a to b] = (pair[a to b] ?: 0) + signed
        }
        return pair.mapNotNull { (k, v) ->
            when {
                v > 0 -> Debt(k.first, k.second, v)
                v < 0 -> Debt(k.second, k.first, -v)
                else -> null
            }
        }.sortedByDescending { it.amount }
    }
}
