package com.splitfree.backup

import android.app.Activity
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.SetOptions
import com.splitfree.data.Auth
import com.splitfree.data.Repo
import com.splitfree.data.toExpense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A private snapshot of every group you are in (with all its expenses and
 * comments left out), kept in your own hidden Drive app folder. Restore puts
 * back anything that is missing from the cloud: deleted groups you created,
 * and expenses that vanished. It never overwrites newer data.
 */
object Backup {
    private const val FILE = "split-free-backup.json"
    private lateinit var app: Context
    private val lock = Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var pending: kotlinx.coroutines.Job? = null
    val lastBackup = MutableStateFlow(0L)
    val busy = MutableStateFlow<String?>(null)
    val enabled = MutableStateFlow(false)

    fun init(context: Context) {
        app = context.applicationContext
        val p = prefs()
        enabled.value = p.getBoolean("enabled", false)
        lastBackup.value = p.getLong("last", 0)
    }

    private fun prefs() = app.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /** True the first time only: the app offers Drive backup once after sign-in. */
    fun shouldAskOnce(): Boolean {
        if (enabled.value || prefs().getBoolean("asked", false)) return false
        prefs().edit().putBoolean("asked", true).apply()
        return true
    }

    fun setEnabled(on: Boolean) {
        enabled.value = on
        prefs().edit().putBoolean("enabled", on).apply()
        val wm = WorkManager.getInstance(app)
        if (on) wm.enqueueUniquePeriodicWork(
            "auto-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        ) else wm.cancelUniqueWork("auto-backup")
    }

    /**
     * Something changed: back up a minute later. Replacing the pending job
     * means a burst of edits produces one backup, not one per edit.
     */
    fun onDataChanged() {
        if (!enabled.value || !::app.isInitialized) return
        // While the app is open: back up 5 seconds after the last change.
        pending?.cancel()
        pending = scope.launch {
            kotlinx.coroutines.delay(5_000)
            runCatching { backupNow() }
        }
        // Safety net if the app is closed before that runs.
        WorkManager.getInstance(app).enqueueUniqueWork(
            "backup-soon", androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        )
    }

    /** Asks for Drive access if needed; returns a PendingIntent-backed result the UI must launch. */
    suspend fun authorize(activity: Activity): AuthorizationResult = DriveAuth.authorize(activity)

    suspend fun backupNow(): Unit = withDrive("Backing up…") { drive ->
        val db = Repo.db
        val uid = Auth.uid ?: error("Not signed in")
        val groups = db.collection("groups").whereArrayContains("members", uid).get(com.google.firebase.firestore.Source.SERVER).await()
            .documents.filter { it.getBoolean("deleted") != true }
        val out = JSONArray()
        for (g in groups) {
            val exps = db.collection("groups").document(g.id).collection("expenses").get().await().documents
            out.put(JSONObject()
                .put("id", g.id)
                .put("data", toJsonable(g.data!!))
                .put("expenses", JSONArray(exps.map { JSONObject().put("id", it.id).put("data", toJsonable(it.data!!)) })))
        }
        val body = JSONObject().put("version", 1).put("savedAt", System.currentTimeMillis()).put("groups", out).toString().toByteArray()
        withContext(Dispatchers.IO) { drive.upsert(FILE, "application/json", body, drive.find(FILE)?.id) }
        val now = System.currentTimeMillis()
        lastBackup.value = now
        prefs().edit().putLong("last", now).apply()
    }

    /** Returns how many groups and expenses were put back. */
    suspend fun restore(): Pair<Int, Int> = withDrive("Restoring…") { drive ->
        val file = withContext(Dispatchers.IO) { drive.find(FILE) } ?: error("No backup found in your Drive yet")
        val json = JSONObject(String(withContext(Dispatchers.IO) { drive.download(file.id) }))
        val db = Repo.db
        val uid = Auth.uid ?: error("Not signed in")
        var groupsBack = 0; var expensesBack = 0
        val server = com.google.firebase.firestore.Source.SERVER
        // Names of the groups you have right now, so a restore never adds a second "Goa trip".
        val liveNames = db.collection("groups").whereArrayContains("members", uid).get(server).await()
            .documents.filter { it.getBoolean("deleted") != true }.mapNotNull { it.getString("name")?.trim()?.lowercase() }.toMutableSet()
        val groups = json.getJSONArray("groups")
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val gid = g.getString("id")
            val ref = db.collection("groups").document(gid)
            @Suppress("UNCHECKED_CAST")
            val data = fromJson(g.getJSONObject("data")) as Map<String, Any?>
            if (data["deleted"] == true) continue
            // Ask the server itself; if we can't be sure, leave the group alone.
            val live = runCatching { ref.get(server).await() }
            if (live.isFailure) continue
            val doc = live.getOrThrow()
            if (!doc.exists()) {
                val name = (data["name"] as? String).orEmpty().trim().lowercase()
                if (name in liveNames) continue
                // Gone from the cloud entirely: bring it back with you as its only member.
                ref.set(data + mapOf("members" to listOf(uid), "createdBy" to uid, "deleted" to false)).await()
                liveNames += name
                groupsBack++
            } else if (doc.getBoolean("deleted") == true) {
                continue // deleted on purpose: stays deleted
            }
            val existing = runCatching { ref.collection("expenses").get(server).await().documents.map { it.id }.toSet() }.getOrNull() ?: continue
            val exps = g.getJSONArray("expenses")
            for (j in 0 until exps.length()) {
                val e = exps.getJSONObject(j)
                if (e.getString("id") in existing) continue
                @Suppress("UNCHECKED_CAST")
                ref.collection("expenses").document(e.getString("id")).set(fromJson(e.getJSONObject("data")) as Map<String, Any?>, SetOptions.merge())
                expensesBack++
            }
        }
        groupsBack to expensesBack
    }

    suspend fun deleteBackup() = withDrive("Deleting backup…") { drive ->
        withContext(Dispatchers.IO) { drive.find(FILE)?.let { drive.delete(it.id) } }
        lastBackup.value = 0
        prefs().edit().putLong("last", 0).apply()
    }

    private suspend fun <T> withDrive(label: String, block: suspend (Drive) -> T): T = lock.withLock {
        busy.value = label
        try {
            val token = DriveAuth.silentToken(app) ?: error("Allow Drive access first")
            block(Drive(token))
        } finally {
            busy.value = null
        }
    }

    private fun toJsonable(v: Any?): Any? = when (v) {
        is Map<*, *> -> JSONObject(v.entries.associate { it.key.toString() to toJsonable(it.value) })
        is List<*> -> JSONArray(v.map { toJsonable(it) })
        else -> v ?: JSONObject.NULL
    }

    private fun fromJson(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { fromJson(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
        JSONObject.NULL -> null
        is Int -> v.toLong()
        else -> v
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Auth.uid == null) return Result.success()
        return runCatching { Backup.backupNow() }.fold({ Result.success() }, { Result.retry() })
    }
}
