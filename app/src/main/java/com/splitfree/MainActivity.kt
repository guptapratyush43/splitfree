package com.splitfree

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.splitfree.ui.AppRoot
import com.splitfree.ui.theme.SplitTheme
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface Screen {
    data object Home : Screen
    data class Group(val id: String, val tab: Int = 0) : Screen
    data class GroupSettings(val id: String) : Screen
    data class Editor(val groupId: String?, val expenseId: String?) : Screen
    data class Detail(val groupId: String, val expenseId: String, val toComments: Boolean = false) : Screen
    data class SettleUp(val groupId: String, val from: String?, val to: String?, val amount: Long) : Screen
    data class Deleted(val groupId: String) : Screen
    data object Backup : Screen
    data class Member(val groupId: String, val uid: String) : Screen
    data object EditProfile : Screen
    data object Scan : Screen
}

/** A tiny back stack; the app has no animated transitions by design. */
class NavViewModel : ViewModel() {
    val stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    /** An invite link (group id, code) waiting for the user to confirm joining. */
    val pendingJoin = MutableStateFlow<Pair<String, String>?>(null)
    /** True when [pendingJoin] came from scanning a QR code, false for a tapped link. */
    val joinByQr = MutableStateFlow(false)
    /** Bottom tab on the home screen: 0 Groups, 1 Activity, 2 Account. */
    val homeTab = MutableStateFlow(0)
    /** Opened from an invite notification: show the Accept / Reject popup. */
    val showInvites = MutableStateFlow(false)

    fun push(s: Screen) { stack.value = stack.value + s }
    fun pop() { if (stack.value.size > 1) stack.value = stack.value.dropLast(1) }
    fun replace(s: Screen) { stack.value = stack.value.dropLast(1) + s }
    fun home() { stack.value = listOf(Screen.Home) }
}

class MainActivity : ComponentActivity() {
    private lateinit var nav: NavViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nav = ViewModelProvider(this)[NavViewModel::class.java]
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            SplitTheme {
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                AppRoot(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // https://…/j/{groupId}/{code}
        intent.data?.pathSegments?.let { seg ->
            if (seg.size >= 3 && seg[0] == "j") { nav.joinByQr.value = false; nav.pendingJoin.value = seg[1] to seg[2] }
        }
        // Tapped a push: FCM puts the data payload into the extras.
        if (intent.getStringExtra("kind") == "invite") {
            nav.stack.value = listOf(Screen.Home); nav.homeTab.value = 0; nav.showInvites.value = true
            return
        }
        // Open the screen the notification is about, with a sensible back path under it.
        val gid = intent.getStringExtra("groupId")?.takeIf { it.isNotBlank() } ?: return
        val eid = intent.getStringExtra("expenseId")?.takeIf { it.isNotBlank() }
        val me = com.splitfree.data.Auth.uid
        nav.homeTab.value = 0
        val screen = intent.getStringExtra("screen")
        nav.stack.value = when {
            screen == "comments" && eid != null -> listOf(Screen.Home, Screen.Group(gid), Screen.Detail(gid, eid, toComments = true))
            screen == "expense" && eid != null -> listOf(Screen.Home, Screen.Group(gid), Screen.Detail(gid, eid))
            screen == "payback" -> listOf(Screen.Home, Screen.Group(gid, tab = 3))
            screen == "member" && me != null -> listOf(Screen.Home, Screen.Group(gid, tab = 1), Screen.Member(gid, me))
            screen?.startsWith("member:") == true -> listOf(Screen.Home, Screen.Group(gid, tab = 1), Screen.Member(gid, screen.removePrefix("member:")))
            else -> listOf(Screen.Home, Screen.Group(gid))
        }
    }
}
