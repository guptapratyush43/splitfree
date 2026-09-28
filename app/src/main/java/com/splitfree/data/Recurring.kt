package com.splitfree.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * A repeating expense is a template that carries `nextDue`. Whichever member's
 * phone notices it is due first posts the copy inside a transaction, with an ID
 * built from the template and the date, so two phones can never post it twice.
 */
object Recurring {
    private val zone get() = ZoneId.systemDefault()
    private val idDay = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun next(from: Long, repeat: Repeat): Long {
        val d = Instant.ofEpochMilli(from).atZone(zone)
        return when (repeat) {
            Repeat.WEEKLY -> d.plusWeeks(1)
            Repeat.MONTHLY -> d.plusMonths(1)
            Repeat.NONE -> return 0
        }.toInstant().toEpochMilli()
    }

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "recurring", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecurringWorker>(12, TimeUnit.HOURS).build()
        )
    }

    /** Checks the groups already loaded in the app. */
    suspend fun run() = run(Repo.groups.value.map { it to Repo.live(it.id) })

    suspend fun run(items: List<Pair<Group, List<Expense>>>) {
        val now = System.currentTimeMillis()
        for ((g, list) in items) {
            val due = list.filter { !it.deleted && it.repeat != Repeat.NONE && it.nextDue in 1..now }
            for (t in due) runCatching { post(g, t, now) }
        }
    }

    private suspend fun post(g: Group, template: Expense, now: Long) {
        val db = Repo.db
        val tRef = db.collection("groups").document(g.id).collection("expenses").document(template.id)
        val created = db.runTransaction { tx ->
            val snap = tx.get(tRef)
            val t = snap.toExpense(g.id)
            if (t.deleted || t.repeat == Repeat.NONE || t.nextDue !in 1..now) return@runTransaction emptyList<Expense>()
            val out = ArrayList<Expense>()
            var due = t.nextDue
            while (due in 1..now && out.size < 24) {
                val day = Instant.ofEpochMilli(due).atZone(zone).format(idDay)
                val copy = t.copy(
                    id = "${t.id}_$day", date = due, createdAt = now, updatedAt = now,
                    repeat = Repeat.NONE, nextDue = 0, templateId = t.id, createdBy = Auth.uid ?: t.createdBy
                )
                tx.set(tRef.parent.document(copy.id), copy.toMap())
                out += copy
                due = next(due, t.repeat)
            }
            tx.update(tRef, "nextDue", due)
            out
        }.await()
        for (e in created) Repo.announceRepeat(g, e)
    }
}

class RecurringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Only templates are fetched: they are the expenses with a nextDue set.
        val uid = Auth.uid ?: return Result.success()
        val db = Repo.db
        val groups = db.collection("groups").whereArrayContains("members", uid).get().await().documents.map { it.toGroup() }.filter { !it.deleted }
        val items = groups.map { g ->
            g to db.collection("groups").document(g.id).collection("expenses")
                .whereGreaterThan("nextDue", 0).get().await().documents.map { it.toExpense(g.id) }
        }
        runCatching { Recurring.run(items) }
        return Result.success()
    }
}
