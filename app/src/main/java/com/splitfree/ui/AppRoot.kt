package com.splitfree.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.NavViewModel
import com.splitfree.Screen
import com.splitfree.data.Api
import com.splitfree.data.Auth
import com.splitfree.data.Recurring
import com.splitfree.data.Repo
import kotlinx.coroutines.launch

/** Android's own toast, which carries the app icon. */
fun toast(context: android.content.Context, msg: String) {
        // Internal "coroutine was cancelled" texts are never worth showing.
        if (msg.contains("coroutine", ignoreCase = true) || msg.contains("was cancelled", ignoreCase = true)) return
        Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

@Composable
fun AppRoot(nav: NavViewModel) {
    val user by Auth.user.collectAsStateWithLifecycle()
    val stack by nav.stack.collectAsStateWithLifecycle()
    val pendingJoin by nav.pendingJoin.collectAsStateWithLifecycle()
    val loaded by Repo.groupsLoaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(user?.uid) { if (user != null) Repo.start() else { Repo.stop(); nav.home() } }
    // Post any repeating expenses that fell due while the app was closed.
    LaunchedEffect(loaded) { if (loaded) runCatching { Recurring.run() } }

    BackHandler(enabled = stack.size > 1) { nav.pop() }

    val update by com.splitfree.update.UpdateManager.offer.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { com.splitfree.update.UpdateManager.checkOnLaunch() }

    val connectDrive = rememberDriveConnect { com.splitfree.backup.Backup.setEnabled(true); com.splitfree.backup.Backup.onDataChanged() }
    LaunchedEffect(user?.uid) {
        if (user != null && com.splitfree.backup.Backup.shouldAskOnce()) connectDrive(context as Activity, null)
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
    ) {
        val topInset = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))
        val deleting by Repo.deleting.collectAsStateWithLifecycle()
        if (deleting) androidx.compose.ui.window.Dialog(onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            WarmCard(padding = 22.dp, background = MaterialTheme.colorScheme.background) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("Deleting your account…", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (user == null) {
            Box(topInset) { SignInScreen() }
            return@Box
        }
        // Each screen in the back stack keeps its own saved state (tab, scroll position,
        // typed text), so coming back lands exactly where you left. Popped screens are dropped.
        val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        val keys = stack.mapIndexed { i, sc -> "$i:$sc" }
        val known = remember { mutableSetOf<String>() }
        LaunchedEffect(keys) {
            (known - keys.toSet()).forEach { holder.removeState(it) }
            known.clear(); known.addAll(keys)
        }
        androidx.compose.animation.AnimatedContent(
            targetState = (stack.size - 1) to stack.last(),
            transitionSpec = {
                // New screen fades in over a clean background; the old one leaves at once, so nothing overlaps.
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap()))
                    .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() })
            },
            label = "screen"
        ) { (index, s) -> holder.SaveableStateProvider("$index:$s") { Box(if (s == Screen.Home) Modifier.fillMaxSize() else topInset) { when (s) {
            Screen.Home -> HomeScreen(nav)
            is Screen.Group -> GroupScreen(nav, s.id, s.tab)
            is Screen.GroupSettings -> GroupSettingsScreen(nav, s.id)
            is Screen.Editor -> ExpenseEditor(nav, s.groupId, s.expenseId)
            is Screen.Detail -> ExpenseDetail(nav, s.groupId, s.expenseId, s.toComments)
            is Screen.SettleUp -> ExpenseEditor(nav, s.groupId, null, payment = s)
            is Screen.Deleted -> DeletedScreen(nav, s.groupId)
            Screen.Backup -> BackupScreen(nav)
            is Screen.Member -> MemberScreen(nav, s.groupId, s.uid)
            Screen.EditProfile -> EditProfileScreen(nav)
            Screen.Scan -> ScanScreen(nav)
        } } } }

        // Opened from an invite notification: answer it right here.
        val showInvites by nav.showInvites.collectAsStateWithLifecycle()
        val invites by Repo.invites.collectAsStateWithLifecycle()
        var sawInvite by remember { mutableStateOf(false) }
        if (invites.isNotEmpty()) sawInvite = true
        // Each invite pops up once, the first time it's seen; after that it waits in Activity.
        LaunchedEffect(invites.map { it.id }) {
            runCatching { Repo.checkInvites(invites.map { it.id }) }
            val prefs = context.getSharedPreferences("invites", android.content.Context.MODE_PRIVATE)
            val seen = prefs.getStringSet("popped", emptySet()).orEmpty()
            val fresh = invites.map { it.id }.filter { it !in seen }
            if (fresh.isNotEmpty()) {
                prefs.edit().putStringSet("popped", seen + fresh).apply()
                nav.showInvites.value = true
            }
        }
        LaunchedEffect(showInvites, invites.isEmpty(), sawInvite) {
            // Close once every invite is answered, or after a short wait if none arrive.
            if (showInvites && invites.isEmpty()) { if (!sawInvite) kotlinx.coroutines.delay(5000); nav.showInvites.value = false; sawInvite = false }
        }
        if (showInvites) WarmDialog(if (invites.size > 1) "Group invitations" else "Group invitation", onDismiss = { nav.showInvites.value = false }) {
            if (invites.isEmpty()) Footnote("Loading your invitation…")
            invites.forEachIndexed { i, inv ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                InviteCard(inv, onIgnore = { nav.showInvites.value = false }) { gid -> nav.push(Screen.Group(gid)) }
            }
        }

        if (user != null) update?.let { UpdateDialog(it) }

        pendingJoin?.let { (gid, code) ->
            var busy by remember { mutableStateOf(false) }
            val byQr by nav.joinByQr.collectAsStateWithLifecycle()
            // The group's name, looked up from the invite (null while loading, "" if it can't be found).
            var groupName by remember(gid, code) { mutableStateOf<String?>(null) }
            var peekError by remember(gid, code) { mutableStateOf<String?>(null) }
            var peekExpired by remember(gid, code) { mutableStateOf(false) }
            LaunchedEffect(gid, code) {
                try { groupName = Repo.peek(gid, code) } catch (e: Exception) {
                    groupName = ""; peekError = Api.friendly(e); peekExpired = (e as? com.splitfree.data.ApiException)?.code == 410
                }
            }
            WarmDialog("Join group?", onDismiss = { nav.pendingJoin.value = null }) {
                Text(
                    if (byQr) "You scanned a Split Free invite QR code." else "You opened a Split Free invite link.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                when {
                    groupName == null -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                    peekError != null -> Text(if (peekExpired) "This invite has expired. The group no longer exists or the link was reset." else peekError!!,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    else -> Text(groupName!!, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(20.dp))
                if (peekExpired) PrimaryButton("Dismiss", null, { nav.pendingJoin.value = null }, Modifier.fillMaxWidth())
                else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton("Not now", null, { nav.pendingJoin.value = null }, Modifier.weight(1f))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    PrimaryButton(if (busy) " " else "Join", null, {
                        busy = true
                        scope.launch {
                            try {
                                val name = Repo.join(gid, code)
                                toast(context, "You joined $name")
                                nav.pendingJoin.value = null
                                nav.stack.value = listOf(Screen.Home, Screen.Group(gid))
                            } catch (e: Exception) {
                                toast(context, Api.friendly(e)); busy = false
                            }
                        }
                    }, Modifier.fillMaxWidth(), enabled = !busy && peekError == null)
                    if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun SignInScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(28.dp)
    ) {
        IconBubble(Icons.Rounded.Groups, size = 96.dp, iconSize = 44.dp)
        Spacer(Modifier.height(24.dp))
        Text("Split Free", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        Text(
            "Share expenses with friends and groups. Everyone sees the same balances.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(36.dp))
        // While signing in, a spinner sits beside the label inside the button.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PrimaryButton(if (busy) " " else "Continue with Google", null, {
                busy = true; error = null
                scope.launch {
                    try { Auth.signIn(context as Activity) } catch (e: Exception) {
                        error = if (e is androidx.credentials.exceptions.GetCredentialCancellationException) null else (e.message ?: "Sign-in failed")
                    } finally { busy = false }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            // "Signing in" with the spinner right after it, centred together in the button.
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Signing in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Footnote("Your groups sync through Split Free's cloud. An optional private backup lives in a hidden app folder in your own Google Drive.")
    }
}
