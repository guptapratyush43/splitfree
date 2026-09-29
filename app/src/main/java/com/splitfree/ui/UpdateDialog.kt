package com.splitfree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.AppScope
import com.splitfree.update.UpdateManager
import com.splitfree.update.UpdateManager.Download
import kotlinx.coroutines.launch

/**
 * "A new version is available": Update downloads it in the app and opens
 * Android's installer; Ignore hides this version for good.
 */
@Composable
fun UpdateDialog(release: UpdateManager.Release) {
    val context = LocalContext.current
    val download by UpdateManager.download.collectAsStateWithLifecycle()
    val running = download is Download.Running
    Dialog(onDismissRequest = { if (!running) UpdateManager.close() }, properties = DialogProperties(dismissOnClickOutside = !running)) {
        WarmCard(padding = 22.dp, background = MaterialTheme.colorScheme.background) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                IconBubble(Icons.Rounded.SystemUpdate, size = 64.dp, iconSize = 30.dp)
                Spacer(Modifier.height(14.dp))
                Text("Split Free v${release.version} is here", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("You have v${UpdateManager.currentVersion}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState())
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("What's new", style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text(release.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            when (val d = download) {
                is Download.Running -> {
                    Text(d.progress?.let { "Downloading… ${(it * 100).toInt()}%" } ?: "Downloading…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(d.progress ?: 0.1f).height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
                    }
                }
                is Download.Ready -> PrimaryButton("Install", null, { UpdateManager.install(context, d.file) }, Modifier.fillMaxWidth())
                else -> {
                    if (d is Download.Failed) {
                        Text(d.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        SecondaryButton("Ignore", null, { UpdateManager.ignore(release) }, Modifier.weight(1f))
                        PrimaryButton("Update", null, { AppScope.launch { UpdateManager.startDownload(release) } }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

