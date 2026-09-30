package com.splitfree.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.splitfree.money.Money
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Battery0Bar
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.BuildConfig
import com.splitfree.NavViewModel
import com.splitfree.Screen
import com.splitfree.backup.Backup
import com.splitfree.data.Api
import com.splitfree.data.Auth
import com.splitfree.data.Repo
import kotlinx.coroutines.launch

/** Account tab: who you are, preferences, and signing out. No upsells. */
@Composable
fun AccountTab(nav: NavViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupOn by Backup.enabled.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabBarSpace)) {
        Text("Account", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 16.dp))
        val meNow by Repo.me.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
            Avatar(meNow?.name ?: Auth.name, Auth.uid.orEmpty(), 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(meNow?.name ?: Auth.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(Auth.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            ActionPill("Edit", { nav.push(Screen.EditProfile) }, icon = Icons.Rounded.Edit)
        }
        Spacer(Modifier.height(24.dp))
        HairLine()

        SectionLabel("Preferences", Modifier.padding(start = 20.dp, top = 18.dp))
        SettingRow("Backup settings", if (backupOn) "Automatic backup to your Google Drive is on" else "Automatic backup is off",
            if (backupOn) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, onClick = { nav.push(Screen.Backup) })
        SettingRow("Notification settings", "Allow Split Free to notify you", Icons.Outlined.Notifications, onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        })
        val xiaomiLike = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco", "oppo", "vivo", "realme", "oneplus")
        SettingRow(
            "Battery & Autostart",
            if (xiaomiLike) "Turn on Autostart and set Battery saver to “No restrictions” so notifications arrive on time"
            else "Set battery to “Unrestricted” so notifications arrive on time",
            Icons.Outlined.Bolt, onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            })
        var checking by remember { mutableStateOf(false) }
        SettingRow("Check for updates", if (checking) "Checking…" else "You're on v${BuildConfig.VERSION_NAME}", Icons.Outlined.Update, onClick = {
            if (!checking) {
                checking = true
                scope.launch {
                    try {
                        if (com.splitfree.update.UpdateManager.checkNow() == null) toast(context, "You're on the latest version")
                    } catch (e: Exception) { toast(context, "Couldn't check right now. Try again in a bit.") }
                    finally { checking = false }
                }
            }
        })

        Spacer(Modifier.height(10.dp))
        HairLine()
        SettingRow("Log out", null, Icons.AutoMirrored.Rounded.Logout, onClick = { confirm = "logout" })
        Spacer(Modifier.height(18.dp))
        Footnote("Split Free v${BuildConfig.VERSION_NAME}")
        Spacer(Modifier.height(24.dp))
    }

    when (confirm) {
        "logout" -> ConfirmDialog("Log out?", "Your groups stay safe in the cloud. Sign in again any time to see them.", "Log out",
            onConfirm = { com.splitfree.AppScope.launch { Auth.signOut(context) } }, onDismiss = { confirm = null }, danger = false)
    }
}

/**
 * Backups run by themselves after every change; this page only connects Drive,
 * restores, or deletes the copy in Drive.
 */
@Composable
fun BackupScreen(nav: NavViewModel) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val enabled by Backup.enabled.collectAsStateWithLifecycle()
    val last by Backup.lastBackup.collectAsStateWithLifecycle()
    val busy by Backup.busy.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    val connect = rememberDriveConnect { Backup.setEnabled(true); Backup.onDataChanged() }

    fun run(done: (Any?) -> String, block: suspend () -> Any?) {
        scope.launch { try { toast(context, done(block())) } catch (e: Exception) { toast(context, Api.friendly(e)) } }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Backup settings", onBack = { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            WarmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(if (enabled) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff, size = 52.dp, iconSize = 26.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (enabled) "Automatic backup is on" else "Automatic backup is off", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when {
                                busy != null -> busy!!
                                !enabled -> "Connect Google Drive to back up after every change"
                                last > 0 -> "Last backup ${Fmt.relative(last)}"
                                else -> "First backup will run shortly"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!enabled) {
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Connect Google Drive", null, { connect(activity, null) }, Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(10.dp))
            Footnote("Every new expense, payment or change is backed up right away to your Google Drive.")
            Spacer(Modifier.height(22.dp))
            ListCard {
                SettingRow("Restore", "Brings back missing expenses and deleted groups you created. Never overwrites newer data.", Icons.Rounded.CloudDownload,
                    onClick = {
                        if (busy == null) connect(activity) {
                            run({ r -> (r as Pair<*, *>).let { "Restored ${it.first} group(s), ${it.second} expense(s)" } }) { Backup.restore() }
                        }
                    })
                HairLine()
                SettingRow("Delete backup", "Removes the copy in your Drive. Your groups in the app are not affected.", Icons.Rounded.DeleteOutline,
                    onClick = { confirm = if (Repo.groups.value.any { Repo.myNet(it.id) != 0L }) "dues" else "delete" }, danger = true)
            }
        }
    }

    if (busy != null && busy != "Backing up…") androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        WarmCard(padding = 24.dp, background = MaterialTheme.colorScheme.background) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(18.dp))
                Text(busy!!, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (confirm == "dues") DuesDialog("You have pending dues. Clear them, then you can delete your backup.") { confirm = null }
    if (confirm == "delete") ConfirmDialog("Delete Drive backup?", "Automatic backup will be turned off. Your groups in the app are not affected.", "Delete",
        onConfirm = { run({ "Backup deleted" }) { Backup.deleteBackup(); Backup.setEnabled(false) } }, onDismiss = { confirm = null })
}

/**
 * Returns a function that makes sure Drive access is granted (showing Google's
 * consent screen once if needed), calls [onGranted], then the per-call callback.
 */
@Composable
fun rememberDriveConnect(onGranted: () -> Unit): (Activity, (() -> Unit)?) -> Unit {
    val scope = rememberCoroutineScope()
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { onGranted(); after?.invoke() } else toast(context, "Drive access not granted")
        after = null
    }
    return { activity, then ->
        scope.launch {
            try {
                val r = Backup.authorize(activity)
                if (r.hasResolution()) {
                    after = then
                    launcher.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                } else { onGranted(); then?.invoke() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { toast(context, e.message ?: "Couldn't reach Google") }
        }
    }
}

/** "Settle up first": lists each group where you still owe or are owed. */
@Composable
fun DuesDialog(message: String, onDismiss: () -> Unit) {
    WarmDialog("Settle up first", onDismiss = onDismiss) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        ListCard {
            Repo.groups.value.filter { Repo.myNet(it.id) != 0L }.forEachIndexed { i, g ->
                if (i > 0) HairLine()
                val n = Repo.myNet(g.id)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(if (n < 0) "You owe ${Money.format(-n)}" else "You're owed ${Money.format(n)}",
                        style = MaterialTheme.typography.bodyMedium, color = moneyColor(n))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        PrimaryButton("OK", null, onDismiss, Modifier.fillMaxWidth())
    }
}
