package com.splitfree.ui

import androidx.compose.material.icons.rounded.Edit
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.splitfree.BuildConfig
import com.splitfree.NavViewModel
import com.splitfree.Screen
import com.splitfree.data.Api
import com.splitfree.data.Auth
import com.splitfree.data.Repo
import com.splitfree.money.Money
import kotlinx.coroutines.launch

@Composable
fun GroupSettingsScreen(nav: NavViewModel, gid: String) {
    val groups by Repo.groups.collectAsStateWithLifecycle()
    val muted by Repo.muted.collectAsStateWithLifecycle()
    val loaded by Repo.groupsLoaded.collectAsStateWithLifecycle()
    val group = groups.firstOrNull { it.id == gid }
    if (group == null) { LaunchedEffect(loaded) { if (loaded) { kotlinx.coroutines.delay(1500); nav.home() } }; return }
    val me = Auth.uid!!
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nets = Repo.nets(gid)
    val isCreator = group.createdBy == me
    val link = "${BuildConfig.API_URL}/j/${group.id}/${group.joinCode}"

    var name by remember(group.name) { mutableStateOf(group.name) }
    var email by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var linkSheet by remember { mutableStateOf(false) }

    fun call(done: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block(); toast(context, done) } catch (e: Exception) { toast(context, Api.friendly(e)) } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Group settings", onBack = { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            // ---- name ----
            WarmCard(padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupBadge(group.name, group.id, 52.dp, group.cover)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        RowBody("Created by ${group.name(group.createdBy, me)}")
                    }
                    ActionPill("Edit", { renaming = true }, icon = Icons.Rounded.Edit)
                }
            }
            Spacer(Modifier.height(22.dp))

            // ---- members ----
            SectionLabel("Invite members")
            ListCard {
                SettingRow("Add people to group", "Invite one or more by their Google email", Icons.Rounded.PersonAdd, onClick = { adding = true })
                HairLine()
                SettingRow("Invite via link", "Share on WhatsApp or show a QR code", Icons.Rounded.Link, onClick = { linkSheet = true })
                HairLine()
                SettingRow("Reset link", "The old link and QR code stop working", Icons.Rounded.Refresh, onClick = { confirm = "reset" })
            }
            Spacer(Modifier.height(22.dp))

            // ---- everyone in the group, creator first ----
            SectionLabel("Members (${group.members.size})")
            ListCard {
                group.members.sortedByDescending { it == group.createdBy }.forEachIndexed { i, uid ->
                    if (i > 0) HairLine()
                    val info = group.info[uid]
                    val n = nets[uid] ?: 0
                    // Same three lines for everyone (name, email, balance pill), so every row lines up.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Avatar(info?.name ?: "?", uid, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(group.name(uid, me), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (uid == group.createdBy) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("Creator", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(50))
                                            .padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                            Text(info?.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            val tone = if (n == 0L) MaterialTheme.colorScheme.onSurfaceVariant else moneyColor(n)
                            Text(when { n > 0 -> "Gets back ${Money.format(n)}"; n < 0 -> "Owes ${Money.format(-n)}"; else -> "Settled up" },
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = tone, maxLines = 1,
                                modifier = Modifier.background(tone.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp))
                        }
                        if (isCreator && uid != me) {
                            Spacer(Modifier.width(10.dp))
                            RoundAction(Icons.Outlined.PersonRemove, "Remove ${group.name(uid, me)}") { removing = uid }
                        }
                    }
                }
                group.invited.forEach { mail ->
                    HairLine()
                    SettingRow(mail, "Invitation sent · waiting for reply", Icons.Rounded.MailOutline) {
                        RoundAction(Icons.Rounded.Close, "Cancel invite to $mail") { Repo.cancelInvite(group, mail) }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))

            // ---- notifications ----
            SectionLabel("Notifications")
            ListCard {
                SettingRow("Mute this group", "No pushes from this group on your phone. Reminders still come through.", Icons.Rounded.NotificationsOff) {
                    Toggle(gid in muted) { Repo.setMuted(gid, it) }
                }
            }
            Spacer(Modifier.height(22.dp))

            // ---- advanced ----
            SectionLabel("Advanced settings")
            ListCard {
                SettingRow(
                    "Simplify group debts",
                    "Automatically combines debts to reduce the total number of repayments between group members.",
                    Icons.Rounded.CallMerge
                ) { Toggle(group.simplify) { Repo.setSimplify(group, it) } }
                HairLine()
                SettingRow("Recently deleted", "Restore expenses or payments deleted by mistake", Icons.Rounded.Restore, onClick = { nav.push(Screen.Deleted(gid)) })
                HairLine()
                SettingRow("Leave group", "Only when your balance here is ₹0.00", Icons.AutoMirrored.Rounded.ExitToApp, onClick = {
                    if ((nets[me] ?: 0) != 0L) toast(context, "Settle up first: your balance must be ₹0.00") else confirm = "leave"
                }, danger = true)
                if (isCreator) {
                    HairLine()
                    SettingRow("Delete group", "Only when everyone is settled up", Icons.Rounded.DeleteOutline, onClick = {
                        if (nets.isNotEmpty()) toast(context, "Everyone must be settled up first") else confirm = "delete"
                    }, danger = true)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (renaming) WarmDialog("Group name", onDismiss = { renaming = false }) {
        Field(name, { name = it.take(60) })
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Cancel", null, { renaming = false; name = group.name }, Modifier.weight(1f))
            PrimaryButton("Save", null, { Repo.renameGroup(group, name.trim()); renaming = false; toast(context, "Renamed") },
                Modifier.weight(1f), enabled = name.isNotBlank() && name.trim() != group.name)
        }
    }
    if (adding) WarmDialog("Add people", onDismiss = { adding = false }) {
        // Several at once: separated by commas, spaces, semicolons or new lines.
        val typed = email.split(Regex("[,;\\s]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val members = group.info.filterKeys { it in group.members }.values.map { it.email.lowercase() }.toSet()
        val bad = typed.filter { !android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches() }
        val already = typed.filter { it in members || it in group.invited }
        val send = typed - bad.toSet() - already.toSet()
        Field(email, { email = it }, placeholder = "asha@gmail.com, ravi@gmail.com", keyboard = KeyboardType.Email, singleLine = false)
        Spacer(Modifier.height(8.dp))
        Text("Add one or more emails, separated by commas or spaces. Each person gets an invitation in Split Free to accept or reject.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (bad.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("Not an email: ${bad.joinToString(", ")}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (already.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("Already in or invited: ${already.joinToString(", ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Cancel", null, { adding = false }, Modifier.weight(1f))
            PrimaryButton(if (send.size > 1) "Send ${send.size} invites" else "Send invite", null, {
                send.forEach { Repo.invite(group, it) }
                toast(context, if (send.size > 1) "${send.size} invites sent" else "Invite sent")
                email = ""; adding = false
            }, Modifier.weight(1f), enabled = send.isNotEmpty() && bad.isEmpty())
        }
    }
    if (linkSheet) InviteLinkSheet(group) { linkSheet = false }
    when (confirm) {
        "reset" -> ConfirmDialog("Reset invite link?", "Anyone holding the old link or QR code won't be able to join with it.", "Reset",
            onConfirm = { Repo.newInviteLink(group); toast(context, "New link ready") }, onDismiss = { confirm = null }, danger = false)
        "leave" -> ConfirmDialog("Leave ${group.name}?", "You'll stop seeing this group. Your past expenses stay for the others.", "Leave",
            onConfirm = { call("You left ${group.name}") { Repo.leave(group); nav.home() } }, onDismiss = { confirm = null })
        "delete" -> ConfirmDialog("Delete ${group.name}?", "The group disappears for everyone. It can be brought back from your Drive backup.", "Delete",
            onConfirm = { Repo.deleteGroup(group); toast(context, "Group deleted"); nav.home() }, onDismiss = { confirm = null })
    }
    removing?.let { uid ->
        val n = nets[uid] ?: 0
        if (n != 0L) {
            ConfirmDialog("Can't remove yet", "${group.name(uid, me)} ${if (n > 0) "is owed" else "owes"} ${Money.format(kotlin.math.abs(n))}. Settle up first.", "OK",
                onConfirm = {}, onDismiss = { removing = null }, danger = false)
        } else {
            ConfirmDialog("Remove ${group.name(uid, me)}?", "They'll lose access to this group. Past expenses keep their name.", "Remove",
                onConfirm = { call("Removed") { Repo.remove(group, uid) } }, onDismiss = { removing = null })
        }
    }
}

/** Opens the share sheet with the group's join link. */
fun shareInviteLink(context: android.content.Context, group: com.splitfree.data.Group) {
    val link = "${BuildConfig.API_URL}/j/${group.id}/${group.joinCode}"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join “${group.name}” on Split Free: $link")
    context.startActivity(Intent.createChooser(send, "Share invite link"))
}

/** The join link as a QR code to scan. */
@Composable
fun InviteQrDialog(group: com.splitfree.data.Group, onDismiss: () -> Unit) {
    val link = "${BuildConfig.API_URL}/j/${group.id}/${group.joinCode}"
    WarmDialog("Scan to join", onDismiss = onDismiss) {
        val bmp = remember(link) { qr(link, 720) }
        Image(bmp.asImageBitmap(), "Invite QR code", Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(12.dp))
        Spacer(Modifier.height(12.dp))
        Footnote("Opens “${group.name}” in Split Free.")
    }
}

/** "Invite via link": share the join link or show it as a QR code. */
@Composable
fun InviteLinkSheet(group: com.splitfree.data.Group, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = "${BuildConfig.API_URL}/j/${group.id}/${group.joinCode}"
    var showQr by remember { mutableStateOf(false) }
    if (!showQr) WarmDialog("Invite via link", onDismiss = onDismiss) {
        Text("Anyone with this link can join ${group.name}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        ListCard {
            SettingRow("Share link", null, Icons.Rounded.Share, onClick = {
                onDismiss()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join “${group.name}” on Split Free: $link")
                context.startActivity(Intent.createChooser(send, "Share invite link"))
            })
            HairLine()
            SettingRow("Show QR code", null, Icons.Rounded.QrCode2, onClick = { showQr = true })
        }
    } else WarmDialog("Scan to join", onDismiss = onDismiss) {
        val bmp = remember(link) { qr(link, 720) }
        Image(bmp.asImageBitmap(), "Invite QR code", Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(12.dp))
        Spacer(Modifier.height(12.dp))
        Footnote("Opens “${group.name}” in Split Free.")
    }
}

private fun qr(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF1F1E1D.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** A small round red action (remove a member, cancel an invite), the same size and place on every row. */
@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).pressScale(src, 0.88f)
        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f), androidx.compose.foundation.shape.CircleShape)
        .clickable(interactionSource = src, indication = null) { Haptics.tick(ctx); onClick() }) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
    }
}
