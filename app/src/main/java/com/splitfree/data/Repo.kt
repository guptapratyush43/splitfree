package com.splitfree.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.splitfree.money.Balances
import com.splitfree.money.Money
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Live view of everything the signed-in user can see, straight from Firestore.
 * Firestore keeps an offline cache, so writes land instantly on this phone and
 * sync when the network returns.
 */
object Repo {
    private const val TAG = "Repo"
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private lateinit var app: Context

    val groups = MutableStateFlow<List<Group>>(emptyList())
    /** Every expense per group, including deleted ones (the UI filters). */
    val expenses = MutableStateFlow<Map<String, List<Expense>>>(emptyMap())
    val invites = MutableStateFlow<List<Invite>>(emptyList())
    val muted = MutableStateFlow<Set<String>>(emptySet())
    val groupsLoaded = MutableStateFlow(false)
    /** My own profile (name, gender, cartoon or photo), from my private user record. */
    val me = MutableStateFlow<Member?>(null)
    /** Everyone I share a group with, plus me: what avatars and names are drawn from. */
    val people = MutableStateFlow<Map<String, Member>>(emptyMap())

    private val regs = mutableListOf<ListenerRegistration>()
    private val expenseRegs = HashMap<String, ListenerRegistration>()

    fun init(context: Context) { app = context.applicationContext }

    fun start() {
        stop()
        val uid = Auth.uid ?: return
        db.collection("users").document(uid).set(
            mapOf("name" to Auth.name, "email" to Auth.email, "seenAt" to System.currentTimeMillis()), SetOptions.merge()
        )
        regs += db.collection("users").document(uid).addSnapshotListener { s, _ ->
            @Suppress("UNCHECKED_CAST")
            muted.value = (s?.get("muted") as? List<String>).orEmpty().toSet()
            if (s != null && s.exists()) {
                me.value = Member(
                    uid, s.getString("name")?.takeIf { it.isNotBlank() } ?: Auth.name, Auth.email,
                    s.getString("gender").orEmpty(), (s.getLong("avatar") ?: -1L).toInt(), s.getString("photo").orEmpty()
                )
                refreshPeople(); syncMyProfile()
            }
        }
        regs += db.collection("groups").whereArrayContains("members", uid).addSnapshotListener { s, e ->
            if (e != null) { Log.w(TAG, "groups", e); return@addSnapshotListener }
            val list = s!!.documents.map { it.toGroup() }.filter { !it.deleted }.sortedByDescending { it.createdAt }
            groups.value = list
            groupsLoaded.value = true
            refreshPeople(); syncMyProfile()
            syncExpenseListeners(list.map { it.id }.toSet())
        }
        regs += db.collection("invites").whereEqualTo("toEmail", Auth.email).whereEqualTo("status", "pending")
            .addSnapshotListener { s, e ->
                if (e != null) { Log.w(TAG, "invites", e); return@addSnapshotListener }
                invites.value = s!!.documents.map {
                    Invite(it.id, it.getString("groupId").orEmpty(), it.getString("groupName").orEmpty(),
                        it.getString("fromUid").orEmpty(), it.getString("fromName").orEmpty(),
                        it.getString("toEmail").orEmpty(), it.getString("status").orEmpty(),
                        it.getLong("createdAt") ?: 0)
                }.sortedByDescending { it.createdAt }
            }
        FirebaseMessaging.getInstance().token.addOnSuccessListener { saveToken(it) }
    }

    fun stop() {
        regs.forEach { it.remove() }; regs.clear()
        expenseRegs.values.forEach { it.remove() }; expenseRegs.clear()
        groups.value = emptyList(); expenses.value = emptyMap(); invites.value = emptyList()
        groupsLoaded.value = false
        me.value = null; people.value = emptyMap()
    }

    private fun syncExpenseListeners(ids: Set<String>) {
        (expenseRegs.keys - ids).forEach { gid ->
            expenseRegs.remove(gid)?.remove()
            expenses.value = expenses.value - gid
        }
        (ids - expenseRegs.keys).forEach { gid ->
            expenseRegs[gid] = db.collection("groups").document(gid).collection("expenses").addSnapshotListener { s, e ->
                if (e != null) { Log.w(TAG, "expenses $gid", e); return@addSnapshotListener }
                // Changes made by other members also get backed up.
                if (!s!!.metadata.hasPendingWrites() && !s.metadata.isFromCache && expenseRegs.containsKey(gid) && expenses.value.containsKey(gid))
                    com.splitfree.backup.Backup.onDataChanged()
                expenses.value = expenses.value + (gid to s.documents.map { it.toExpense(gid) }
                    .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.createdAt }))
            }
        }
    }

    private fun refreshPeople() {
        val map = HashMap<String, Member>()
        groups.value.forEach { g -> g.info.forEach { (uid, m) -> if (uid !in map || uid in g.members) map[uid] = m } }
        me.value?.let { map[it.uid] = it }
        people.value = map
    }

    /** Copies my profile into every group I'm in, so members see my latest name and face. */
    private fun syncMyProfile() {
        val p = me.value ?: return
        groups.value.forEach { g ->
            val mine = g.info[p.uid]
            if (mine == null || mine.name != p.name || mine.gender != p.gender || mine.avatar != p.avatar || mine.photo != p.photo) {
                group(g.id).update(
                    com.google.firebase.firestore.FieldPath.of("memberInfo", p.uid),
                    mapOf("name" to p.name, "email" to p.email, "gender" to p.gender, "avatar" to p.avatar, "photo" to p.photo)
                )
            }
        }
    }

    /** Saves my name, gender and picture; groups pick it up through [syncMyProfile]. */
    suspend fun saveProfile(name: String, gender: String, avatar: Int, photo: String) {
        val uid = Auth.uid ?: return
        runCatching {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.updateProfile(
                com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(name).build()
            )?.await()
        }
        db.collection("users").document(uid).set(
            mapOf("name" to name, "gender" to gender, "avatar" to avatar, "photo" to photo), SetOptions.merge()
        )
    }

    fun saveToken(token: String) {
        val uid = Auth.uid ?: return
        db.collection("users").document(uid).set(mapOf("tokens" to FieldValue.arrayUnion(token)), SetOptions.merge())
    }

    // ---- derived -------------------------------------------------------------

    fun live(gid: String): List<Expense> = expenses.value[gid].orEmpty().filter { !it.deleted }

    fun nets(gid: String): Map<String, Long> = Balances.nets(live(gid).map { it.flows })

    fun myNet(gid: String): Long = Auth.uid?.let { nets(gid)[it] } ?: 0L

    fun debts(group: Group): List<com.splitfree.money.Debt> {
        val items = live(group.id).map { it.flows }
        return if (group.simplify) Balances.settle(Balances.nets(items)) else Balances.pairwise(items)
    }

    // ---- groups --------------------------------------------------------------

    fun createGroup(name: String): String {
        val uid = Auth.uid!!
        val ref = db.collection("groups").document()
        ref.set(mapOf(
            "name" to name, "createdBy" to uid, "members" to listOf(uid),
            "memberInfo" to mapOf(uid to mapOf("name" to Auth.name, "email" to Auth.email)),
            "invited" to emptyList<String>(), "simplify" to false, "joinCode" to code(),
            "createdAt" to System.currentTimeMillis(), "deleted" to false
        ))
        log(ref.id, "${Auth.name} created the group", null)
        return ref.id
    }

    fun renameGroup(g: Group, name: String) {
        group(g.id).update("name", name)
        log(g.id, "${Auth.name} renamed the group to “$name”", null)
    }

    fun setSimplify(g: Group, on: Boolean) {
        group(g.id).update("simplify", on)
        log(g.id, "${Auth.name} turned ${if (on) "on" else "off"} simplify debts", null)
    }

    fun deleteGroup(g: Group) {
        group(g.id).update(mapOf("deleted" to true, "deletedAt" to System.currentTimeMillis()))
    }

    fun newInviteLink(g: Group) { group(g.id).update("joinCode", code()) }

    fun setMuted(gid: String, mute: Boolean) {
        val uid = Auth.uid ?: return
        db.collection("users").document(uid).set(
            mapOf("muted" to if (mute) FieldValue.arrayUnion(gid) else FieldValue.arrayRemove(gid)), SetOptions.merge()
        )
    }

    /** Invite by email. The invite waits in Firestore until they sign in; a push goes out if they already have. */
    fun invite(g: Group, email: String) {
        val to = email.trim().lowercase()
        val id = "${g.id}_$to"
        db.collection("invites").document(id).set(mapOf(
            "groupId" to g.id, "groupName" to g.name, "fromUid" to Auth.uid, "fromName" to Auth.name,
            "toEmail" to to, "status" to "pending", "createdAt" to System.currentTimeMillis()
        ))
        group(g.id).update("invited", FieldValue.arrayUnion(to))
        log(g.id, "${Auth.name} invited $to", null)
        queue(JSONObject().put("kind", "invite").put("inviteId", id))
    }

    fun cancelInvite(g: Group, email: String) {
        db.collection("invites").document("${g.id}_$email").delete()
        group(g.id).update("invited", FieldValue.arrayRemove(email))
    }

    /** Answers an invite once the server confirms; returns the group's id. */
    suspend fun respond(invite: Invite, accept: Boolean): String =
        Api.post("/api/invite/respond", JSONObject().put("inviteId", invite.id).put("accept", accept))
            .optString("groupId").ifBlank { invite.groupId }

    /** Pending invites whose group was deleted (they can only be dismissed). */
    val expiredInvites = MutableStateFlow<Set<String>>(emptySet())

    suspend fun checkInvites(ids: List<String>) {
        if (ids.isEmpty()) return
        val res = Api.post("/api/invite/check", JSONObject().put("ids", JSONArray(ids))).optJSONArray("expired") ?: return
        expiredInvites.value = expiredInvites.value + (0 until res.length()).map { res.getString(it) }
    }

    suspend fun dismissInvite(invite: Invite) {
        Api.post("/api/invite/dismiss", JSONObject().put("inviteId", invite.id))
        invites.value = invites.value.filter { it.id != invite.id }
    }

    /** Name of the group an invite points to (checks the code first). */
    suspend fun peek(gid: String, code: String): String =
        Api.post("/api/group/peek", JSONObject().put("groupId", gid).put("code", code)).optString("name")

    suspend fun join(gid: String, code: String): String =
        Api.post("/api/group/join", JSONObject().put("groupId", gid).put("code", code)).optString("name")

    suspend fun leave(g: Group) { Api.post("/api/group/leave", JSONObject().put("groupId", g.id)) }

    suspend fun remove(g: Group, uid: String) {
        Api.post("/api/group/remove", JSONObject().put("groupId", g.id).put("uid", uid))
    }

    /** True while the account is being deleted; the app shows a blocking spinner. */
    val deleting = MutableStateFlow(false)

    suspend fun deleteAccount() { Api.post("/api/account/delete", JSONObject()) }

    // ---- expenses ------------------------------------------------------------

    fun newExpenseId(gid: String): String = group(gid).collection("expenses").document().id

    fun saveExpense(g: Group, e: Expense, isNew: Boolean) {
        group(g.id).collection("expenses").document(e.id).set(e.toMap())
        val me = Auth.name
        val what = if (e.settlement) settleText(g, e) else "“${e.title}” (${Money.format(e.amount)})"
        // Payments: "Asha marked ₹500.00 from Ravi as settled" when the receiver confirms it, otherwise who paid whom.
        val fromName = e.paid.keys.firstOrNull()?.let { g.info[it]?.name } ?: "Someone"
        val settledText = when {
            !e.settlement -> ""
            !isNew -> "$me updated a payment: $what"
            Auth.uid in e.shares.keys -> "$me marked ${Money.format(e.amount)} from $fromName as settled"
            else -> "$me recorded a payment: $what"
        }
        val text = if (e.settlement) settledText else if (isNew) "$me added $what" else "$me edited $what"
        log(g.id, text, e.id, e.involved)
        // Added for someone else: say who paid, then who entered it on their behalf.
        val payers = e.paid.keys.map { g.info[it]?.name ?: "Someone" }
        val payerText = if (payers.size <= 2) payers.joinToString(" and ") else "${payers.size} people"
        val selfPaid = Auth.uid in e.paid.keys
        val body = when {
            e.settlement -> "$settledText."
            selfPaid -> "$me ${if (isNew) "added" else "edited"} $what. You're included."
            else -> "$payerText paid $what. You're included. ${if (isNew) "Added" else "Edited"} by $me on behalf of $payerText."
        }
        notify(g, e.involved, if (e.settlement) "Payment settled in ${g.name}" else if (isNew) "New expense in ${g.name}" else "Expense edited in ${g.name}",
            body, e.id,
            screen = if (e.settlement) "member" else "expense")
    }

    /** A repeating expense was posted by [Recurring]: log it and tell the people in it. */
    fun announceRepeat(g: Group, e: Expense) {
        val what = "“${e.title}” (${Money.format(e.amount)})"
        log(g.id, "Repeating expense $what was added", e.id, e.involved)
        notify(g, e.involved, "Repeating expense in ${g.name}", "$what was added automatically", e.id)
    }

    fun deleteExpense(g: Group, e: Expense) {
        group(g.id).collection("expenses").document(e.id)
            .update(mapOf("deleted" to true, "deletedAt" to System.currentTimeMillis(), "repeat" to Repeat.NONE.name))
        val label = if (e.settlement) "a payment of ${Money.format(e.amount)}" else "“${e.title}” (${Money.format(e.amount)})"
        log(g.id, "${Auth.name} deleted $label", e.id, e.involved)
        notify(g, e.involved, if (e.settlement) "Payment deleted in ${g.name}" else "Expense deleted in ${g.name}", "${Auth.name} deleted $label.", e.id, screen = "group")
    }

    /** Takes back a "marked as settled" payment: the amount is owed again. */
    fun unsettle(g: Group, e: Expense) {
        group(g.id).collection("expenses").document(e.id)
            .update(mapOf("deleted" to true, "deletedAt" to System.currentTimeMillis(), "repeat" to Repeat.NONE.name))
        val from = e.paid.keys.firstOrNull()?.let { g.info[it]?.name } ?: "Someone"
        val text = "${Auth.name} marked ${Money.format(e.amount)} from $from as unsettled"
        log(g.id, text, e.id, e.involved)
        notify(g, e.involved, "Payment unsettled in ${g.name}", "$text. It is owed again.", null, screen = "member")
    }

    fun restoreExpense(g: Group, e: Expense) {
        group(g.id).collection("expenses").document(e.id).update("deleted", false)
        val label = if (e.settlement) "a payment of ${Money.format(e.amount)}" else "“${e.title}” (${Money.format(e.amount)})"
        log(g.id, "${Auth.name} restored $label", e.id, e.involved)
        notify(g, e.involved, if (e.settlement) "Payment restored in ${g.name}" else "Expense restored in ${g.name}", "${Auth.name} restored $label.", e.id)
    }

    /**
     * The payer says "I paid [to] this much". Nothing changes until [to] confirms;
     * until then it waits on the group's Pay back tab.
     */
    fun claimPayment(g: Group, to: String, amount: Long) {
        val now = System.currentTimeMillis()
        val me = Auth.uid!!
        val e = Expense(
            id = newExpenseId(g.id), groupId = g.id, title = "Payment", note = "", category = Category.GENERAL,
            amount = amount, date = now, paid = mapOf(me to amount), shares = mapOf(to to amount),
            mode = "EXACT", inputs = emptyMap(), payerInputs = emptyMap(), createdBy = me, createdAt = now,
            updatedAt = now, deleted = true, deletedAt = 0, settlement = true, repeat = Repeat.NONE, nextDue = 0, templateId = null,
            pending = true
        )
        group(g.id).collection("expenses").document(e.id).set(e.toMap())
        notify(g, listOf(to), "Payment to confirm", "${Auth.name} says they paid you ${Money.format(amount)} in ${g.name}. Tap to confirm.",
            null, force = true, screen = "payback")
    }

    /** The receiver confirms a claimed payment; [alloc] is how it is spread over expenses. */
    fun confirmPayment(g: Group, c: Expense, alloc: Map<String, Long>) {
        val now = System.currentTimeMillis()
        group(g.id).collection("expenses").document(c.id).update(mapOf(
            "pending" to false, "deleted" to false, "deletedAt" to 0L, "date" to now, "updatedAt" to now,
            "inputs" to (if (alloc.isEmpty()) emptyMap() else mapOf("alloc" to Pairs.encode(alloc)))
        ))
        val from = c.paid.keys.firstOrNull()?.let { g.info[it]?.name } ?: "Someone"
        log(g.id, "${Auth.name} confirmed ${Money.format(c.amount)} from $from", c.id, c.involved)
        notify(g, c.involved, "Payment confirmed in ${g.name}", "${Auth.name} confirmed your payment of ${Money.format(c.amount)}.", null, screen = "member")
    }

    /** The receiver says no, or the payer takes the claim back. */
    fun rejectPayment(g: Group, c: Expense) {
        group(g.id).collection("expenses").document(c.id).delete()
        if (Auth.uid !in c.paid.keys) notify(g, c.paid.keys, "Payment not confirmed",
            "${Auth.name} didn't confirm your payment of ${Money.format(c.amount)} in ${g.name}.", null, force = true, screen = "payback")
    }

    /** Records [from] paying [to]. [alloc]: the paise this payment puts towards each expense. */
    fun settle(g: Group, from: String, to: String, amount: Long, date: Long, alloc: Map<String, Long> = emptyMap()) {
        val now = System.currentTimeMillis()
        val e = Expense(
            id = newExpenseId(g.id), groupId = g.id, title = "Payment", note = "", category = Category.GENERAL,
            amount = amount, date = date, paid = mapOf(from to amount), shares = mapOf(to to amount),
            mode = "EXACT", inputs = if (alloc.isEmpty()) emptyMap() else mapOf("alloc" to Pairs.encode(alloc)),
            payerInputs = emptyMap(), createdBy = Auth.uid!!, createdAt = now,
            updatedAt = now, deleted = false, deletedAt = 0, settlement = true, repeat = Repeat.NONE, nextDue = 0, templateId = null
        )
        saveExpense(g, e, isNew = true)
    }

    private fun settleText(g: Group, e: Expense): String {
        val from = e.paid.keys.firstOrNull()?.let { g.info[it]?.name } ?: "Someone"
        val to = e.shares.keys.firstOrNull()?.let { g.info[it]?.name } ?: "someone"
        return "$from paid $to ${Money.format(e.amount)}"
    }

    /** Push text is per-message, so the share line is generic; each person sees their own share in-app. */
    private fun yourShare(e: Expense) = if (e.shares.size == 1) "" else " · split ${e.shares.size} ways"

    fun comments(gid: String, eid: String): Flow<List<Comment>> = callbackFlow {
        val reg = group(gid).collection("expenses").document(eid).collection("comments").orderBy("at")
            .addSnapshotListener { s, _ ->
                trySend(s?.documents.orEmpty().map {
                    Comment(it.id, it.getString("uid").orEmpty(), it.getString("name").orEmpty(), it.getString("text").orEmpty(), it.getLong("at") ?: 0)
                })
            }
        awaitClose { reg.remove() }
    }

    fun comment(g: Group, e: Expense, text: String) {
        group(g.id).collection("expenses").document(e.id).collection("comments").add(
            mapOf("uid" to Auth.uid, "name" to Auth.name, "text" to text, "at" to System.currentTimeMillis())
        )
        val title = if (e.settlement) "a payment" else "“${e.title}”"
        log(g.id, "${Auth.name} commented on $title", e.id, e.involved)
        notify(g, e.involved, "New comment in ${g.name}", "${Auth.name} commented on $title: $text", e.id, screen = "comments")
    }

    /** The one who owes tells the one they paid: "I've paid, please mark it as settled." Opens the payer's page for them. */
    fun askToSettle(g: Group, lender: String, amount: Long) {
        notify(g, listOf(lender), "Payment to confirm", "${Auth.name} says they paid you ${Money.format(amount)} in ${g.name}. Tap to mark it as settled.",
            null, force = true, screen = "member:${Auth.uid}")
    }

    fun remind(g: Group, uid: String, amount: Long) {
        notify(g, listOf(uid), "Payment reminder", "${Auth.name} sent a reminder: you owe ${Money.format(amount)} in ${g.name}.", null, force = true, screen = "member")
    }

    // ---- plumbing ------------------------------------------------------------

    private fun group(gid: String) = db.collection("groups").document(gid)

    /** Every change passes through here: it is written to the group's activity and triggers the automatic backup. */
    private fun log(gid: String, text: String, eid: String?, people: Collection<String> = emptyList()) {
        group(gid).collection("activity").add(mapOf(
            "actor" to Auth.uid, "text" to text, "at" to System.currentTimeMillis(), "expenseId" to eid,
            "people" to people.filter { it != Auth.uid }.distinct()
        ))
        com.splitfree.backup.Backup.onDataChanged()
    }

    fun activity(gid: String): Flow<List<Activity>> = callbackFlow {
        val reg = group(gid).collection("activity").orderBy("at", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { s, _ ->
                @Suppress("UNCHECKED_CAST")
                trySend(s?.documents.orEmpty().map {
                    Activity(it.id, it.getString("actor").orEmpty(), it.getString("text").orEmpty(), it.getLong("at") ?: 0,
                        it.getString("expenseId"), (it.get("people") as? List<String>).orEmpty())
                })
            }
        awaitClose { reg.remove() }
    }

    /** Push to the given people (never yourself). Queued so it still goes out after an offline edit. */
    fun notify(g: Group, to: Collection<String>, title: String, body: String, eid: String?, force: Boolean = false,
               screen: String = if (eid != null) "expense" else "group") {
        val targets = to.filter { it != Auth.uid && it in g.members }
        if (targets.isEmpty()) return
        queue(JSONObject().put("kind", "group").put("groupId", g.id).put("to", JSONArray(targets))
            .put("title", title).put("body", body).put("expenseId", eid ?: "").put("force", force).put("screen", screen))
    }

    private fun queue(json: JSONObject) {
        val work = OneTimeWorkRequestBuilder<NotifyWorker>()
            .setInputData(workDataOf("json" to json.toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueue(work)
    }

    private fun code(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..12).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}

class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val json = JSONObject(inputData.getString("json") ?: return Result.failure())
        return try {
            // The expense itself may still be syncing; give Firestore a moment on first try.
            Api.post("/api/notify", json)
            Result.success()
        } catch (e: ApiException) {
            if (e.code >= 500 || e.code == 401) Result.retry() else Result.failure()
        } catch (e: Exception) {
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }
}
