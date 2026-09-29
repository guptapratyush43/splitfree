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

/**
 * Short messages at the bottom of the screen. Drawn by the app itself (not the
 * system toast, which shows a launcher icon some phones cache from old versions).
 */
object Toasts {
    data class Msg(val text: String, val quiet: Boolean, val at: Long = System.nanoTime())
    val current = kotlinx.coroutines.flow.MutableStateFlow<Msg?>(null)
    fun show(msg: String, quiet: Boolean = false) { current.value = Msg(msg, quiet) }
}

@Suppress("UNUSED_PARAMETER")
fun toast(context: android.content.Context, msg: String) = Toasts.show(msg)

@Composable
private fun ToastHost(modifier: Modifier) {
    val current by Toasts.current.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf<Toasts.Msg?>(null) }
    LaunchedEffect(current) {
        val c = current ?: return@LaunchedEffect
        shown = c
        kotlinx.coroutines.delay(2200)
        shown = null
    }
    var last by remember { mutableStateOf(Toasts.Msg("", false)) }
    if (shown != null) last = shown!!
    androidx.compose.animation.AnimatedVisibility(shown != null, modifier,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 2 },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))) {
        // Quiet messages ("No invitations at the moment") are smaller and faded.
        Text(last.text, style = if (last.quiet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.background.copy(alpha = if (last.quiet) 0.9f else 1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 96.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (last.quiet) 0.55f else 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(horizontal = if (last.quiet) 14.dp else 18.dp, vertical = if (last.quiet) 8.dp else 11.dp))
    }
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
        } } } }

        ToastHost(Modifier.align(Alignment.BottomCenter))

        // Opened from an invite notification: answer it right here.
        val showInvites by nav.showInvites.collectAsStateWithLifecycle()
        val invites by Repo.invites.collectAsStateWithLifecycle()
        var sawInvite by remember { mutableStateOf(false) }
        if (invites.isNotEmpty()) sawInvite = true
        // Someone invited before they ever installed the app sees it the moment they sign in.
        var askedThisSession by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(invites.size) {
            if (invites.isNotEmpty() && !askedThisSession) { askedThisSession = true; nav.showInvites.value = true }
        }
        LaunchedEffect(showInvites, invites.isEmpty(), sawInvite) {
            // Close once every invite is answered, or after a short wait if none arrive.
            if (showInvites && invites.isEmpty()) { if (!sawInvite) kotlinx.coroutines.delay(5000); nav.showInvites.value = false; sawInvite = false }
        }
        if (showInvites) WarmDialog(if (invites.size > 1) "Group invitations" else "Group invitation", onDismiss = { nav.showInvites.value = false }) {
            if (invites.isEmpty()) Footnote("Loading your invitation…")
            invites.forEachIndexed { i, inv ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                InviteCard(inv) { gid -> nav.push(Screen.Group(gid)) }
            }
        }

        if (user != null) update?.let { UpdateDialog(it) }

        pendingJoin?.let { (gid, code) ->
            var busy by remember { mutableStateOf(false) }
            WarmDialog("Join group?", onDismiss = { nav.pendingJoin.value = null }) {
                Text(
                    "You opened a Split Free invite link. Join this group so you can see and add its expenses?",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton("Not now", null, { nav.pendingJoin.value = null }, Modifier.weight(1f))
                    PrimaryButton(if (busy) "Joining…" else "Join", null, {
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
                    }, Modifier.weight(1f), enabled = !busy)
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
        PrimaryButton(if (busy) "Signing in…" else "Continue with Google", null, {
            busy = true; error = null
            scope.launch {
                try { Auth.signIn(context as Activity) } catch (e: Exception) {
                    error = if (e is androidx.credentials.exceptions.GetCredentialCancellationException) null else (e.message ?: "Sign-in failed")
                } finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), enabled = !busy)
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Footnote("Your groups sync through Split Free's cloud. An optional private backup lives in a hidden app folder in your own Google Drive.")
    }
}
