package com.splitfree.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.splitfree.MainActivity
import com.splitfree.R
import com.splitfree.data.Repo

/**
 * Pushes arrive as notification messages, so Android shows them itself while
 * the app is in the background. This only handles the foreground case and
 * token refreshes.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Repo.saveToken(token)

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        show(this, n.title.orEmpty(), n.body.orEmpty(), message.data["groupId"], message.data["kind"], message.data["expenseId"], message.data["screen"])
    }

    companion object {
        const val CHANNEL = "alerts"

        fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL, "Group activity", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Expenses, invites, comments and reminders"
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 60, 80, 90)
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("activity")
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        fun show(context: Context, title: String, body: String, groupId: String?, kind: String? = null, expenseId: String? = null, screen: String? = null) {
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (groupId != null) open.putExtra("groupId", groupId)
            if (kind != null) open.putExtra("kind", kind)
            if (expenseId != null) open.putExtra("expenseId", expenseId)
            if (screen != null) open.putExtra("screen", screen)
            val pi = PendingIntent.getActivity(context, (groupId + expenseId + screen).hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notif = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_split)
                .setColor(context.getColor(R.color.clay))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notif) }
        }
    }
}
