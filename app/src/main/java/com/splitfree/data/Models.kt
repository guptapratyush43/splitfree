package com.splitfree.data

import com.google.firebase.firestore.DocumentSnapshot

data class Member(val uid: String, val name: String, val email: String)

data class Group(
    val id: String,
    val name: String,
    val createdBy: String,
    val members: List<String>,
    /** Everyone who was ever in the group, so old expenses keep their names. */
    val info: Map<String, Member>,
    val simplify: Boolean,
    val joinCode: String,
    /** Emails with a pending invite. */
    val invited: List<String>,
    /** Banner photo URL found for the place in the name ("" when none), and the name it was found for. */
    val cover: String,
    val coverFor: String?,
    val createdAt: Long,
    val deleted: Boolean
) {
    fun name(uid: String, me: String?): String = if (uid == me) "You" else info[uid]?.name ?: "Someone"
}

enum class Category(val label: String, val emoji: String) {
    GENERAL("General", "🧾"), FOOD("Food & drink", "🍽️"), GROCERIES("Groceries", "🛒"), TRAVEL("Travel", "✈️"),
    TRANSPORT("Transport", "🚕"), FUEL("Fuel", "⛽"), RENT("Rent", "🏠"), BILLS("Bills & utilities", "💡"),
    SHOPPING("Shopping", "🛍️"), ENTERTAINMENT("Entertainment", "🎬"), HEALTH("Health", "💊"), GIFTS("Gifts", "🎁"),
    STAY("Hotel & stay", "🏨"), OTHER("Other", "📌");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: GENERAL
    }
}

enum class Repeat(val label: String) { NONE("Doesn't repeat"), WEEKLY("Every week"), MONTHLY("Every month") }

data class Expense(
    val id: String,
    val groupId: String,
    val title: String,
    val note: String,
    val category: Category,
    val amount: Long,
    /** Day the expense happened (epoch ms), not when it was typed in. */
    val date: Long,
    val paid: Map<String, Long>,
    val shares: Map<String, Long>,
    val mode: String,
    val inputs: Map<String, String>,
    val payerInputs: Map<String, String>,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val deletedAt: Long,
    val settlement: Boolean,
    val repeat: Repeat,
    val nextDue: Long,
    val templateId: String?
) {
    /** Everyone touched by this expense: payers and people it is split with. */
    val involved: Set<String> get() = paid.keys + shares.keys
    val flows get() = paid to shares
}

/** One line of group history. [people] are the others involved: payers, those it was split with, a payment's receiver. */
data class Activity(val id: String, val actor: String, val text: String, val at: Long, val expenseId: String?, val people: List<String> = emptyList())

data class Invite(
    val id: String, val groupId: String, val groupName: String,
    val fromUid: String, val fromName: String, val toEmail: String, val status: String, val createdAt: Long
)

data class Comment(val id: String, val uid: String, val name: String, val text: String, val at: Long)

@Suppress("UNCHECKED_CAST")
private fun DocumentSnapshot.longMap(field: String): Map<String, Long> =
    (get(field) as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toLong() } }.toMap()

@Suppress("UNCHECKED_CAST")
private fun DocumentSnapshot.stringMap(field: String): Map<String, String> =
    (get(field) as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

private fun DocumentSnapshot.long(field: String) = (get(field) as? Number)?.toLong() ?: 0L

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.toGroup() = Group(
    id = id,
    name = getString("name").orEmpty(),
    createdBy = getString("createdBy").orEmpty(),
    members = (get("members") as? List<String>).orEmpty(),
    info = (get("memberInfo") as? Map<String, Map<String, Any?>>).orEmpty().mapValues { (uid, m) ->
        Member(uid, m["name"] as? String ?: "Someone", m["email"] as? String ?: "")
    },
    simplify = getBoolean("simplify") ?: false,
    joinCode = getString("joinCode").orEmpty(),
    invited = (get("invited") as? List<String>).orEmpty(),
    cover = getString("cover").orEmpty(),
    coverFor = getString("coverFor"),
    createdAt = long("createdAt"),
    deleted = getBoolean("deleted") ?: false
)

fun DocumentSnapshot.toExpense(groupId: String) = Expense(
    id = id,
    groupId = groupId,
    title = getString("title").orEmpty(),
    note = getString("note").orEmpty(),
    category = Category.of(getString("category")),
    amount = long("amount"),
    date = long("date"),
    paid = longMap("paid"),
    shares = longMap("shares"),
    mode = getString("mode") ?: "EQUAL",
    inputs = stringMap("inputs"),
    payerInputs = stringMap("payerInputs"),
    createdBy = getString("createdBy").orEmpty(),
    createdAt = long("createdAt"),
    updatedAt = long("updatedAt"),
    deleted = getBoolean("deleted") ?: false,
    deletedAt = long("deletedAt"),
    settlement = getBoolean("settlement") ?: false,
    repeat = runCatching { Repeat.valueOf(getString("repeat") ?: "NONE") }.getOrDefault(Repeat.NONE),
    nextDue = long("nextDue"),
    templateId = getString("templateId")
)

fun Expense.toMap(): Map<String, Any?> = mapOf(
    "title" to title, "note" to note, "category" to category.name, "amount" to amount, "date" to date,
    "paid" to paid, "shares" to shares, "mode" to mode, "inputs" to inputs, "payerInputs" to payerInputs,
    "createdBy" to createdBy, "createdAt" to createdAt, "updatedAt" to updatedAt,
    "deleted" to deleted, "deletedAt" to deletedAt, "settlement" to settlement,
    "repeat" to repeat.name, "nextDue" to nextDue, "templateId" to templateId
)
