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
    data class Group(val id: String) : Screen
    data class GroupSettings(val id: String) : Screen
    data class Editor(val groupId: String?, val expenseId: String?) : Screen
    data class Detail(val groupId: String, val expenseId: String) : Screen
    data class SettleUp(val groupId: String, val from: String?, val to: String?, val amount: Long) : Screen
    data class Deleted(val groupId: String) : Screen
    data object Backup : Screen
    data class Member(val groupId: String, val uid: String) : Screen
}

/** A tiny back stack; the app has no animated transitions by design. */
class NavViewModel : ViewModel() {
    val stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    /** An invite link (group id, code) waiting for the user to confirm joining. */
    val pendingJoin = MutableStateFlow<Pair<String, String>?>(null)
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
            if (seg.size >= 3 && seg[0] == "j") nav.pendingJoin.value = seg[1] to seg[2]
        }
        // Tapped a push: FCM puts the data payload into the extras.
        if (intent.getStringExtra("kind") == "invite") {
            nav.stack.value = listOf(Screen.Home); nav.homeTab.value = 0; nav.showInvites.value = true
            return
        }
        intent.getStringExtra("groupId")?.takeIf { it.isNotBlank() }?.let { gid ->
            nav.stack.value = listOf(Screen.Home, Screen.Group(gid))
        }
    }
}
