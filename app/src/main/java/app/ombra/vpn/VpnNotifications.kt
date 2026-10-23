package app.ombra.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.ombra.R
import app.ombra.ui.MainActivity

/** The foreground notification of OmbraVpnService and the "needs your action" alert. */
object VpnNotifications {
    const val STATUS_ID = 1
    private const val ALERT_ID = 2
    private const val CHANNEL_STATUS = "vpn_status"
    private const val CHANNEL_ALERTS = "vpn_alerts"

    fun createChannels(context: Context) {
        fun channel(id: String, name: Int, description: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance).apply {
                this.description = context.getString(description)
                setShowBadge(false)
            }

        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                // Low importance: silent and no heads-up. The lock screen always shows the public
                // version, also when the user lets it show all content (set only at creation).
                channel(
                    CHANNEL_STATUS,
                    R.string.notification_channel_vpn,
                    R.string.notification_channel_vpn_description,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
                channel(
                    CHANNEL_ALERTS,
                    R.string.notification_channel_alerts,
                    R.string.notification_channel_alerts_description,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    /** The lock screen shows only the public version: the state, never a location or a reason. */
    fun status(context: Context, view: StatusView): Notification {
        val builder = base(context, CHANNEL_STATUS, view.titleRes)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setPublicVersion(base(context, CHANNEL_STATUS, view.titleRes).build())
        val text = view.exitCity?.let { context.getString(R.string.notification_exit_city, it) }
            ?: view.problemRes?.let(context::getString)
        if (text != null) builder.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        if (view.canDisconnect) {
            val disconnect = PendingIntent.getService(
                context,
                1,
                OmbraVpnService.disconnectIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val label = context.getString(R.string.action_disconnect)
            // Like the tile: only the owner of a locked phone may turn the protection off.
            builder.addAction(Notification.Action.Builder(null, label, disconnect).setAuthenticationRequired(true).build())
        }
        return builder.build()
    }

    /** Shown when a connect needs VPN consent again. Tapping it asks for consent in the app. */
    fun showPermissionNeeded(context: Context) {
        if (!canNotify(context)) return
        val notification = base(context, CHANNEL_ALERTS, R.string.app_name)
            .setContentText(context.getString(R.string.notification_vpn_permission))
            .setContentIntent(openApp(context, OmbraVpnService.ACTION_CONNECT))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ALERT_ID, notification)
    }

    fun update(context: Context, notification: Notification) {
        if (!canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).notify(STATUS_ID, notification)
    }

    /** Opens MainActivity; with ACTION_CONNECT it asks for the consents and connects. */
    fun openApp(context: Context, action: String? = null): PendingIntent = PendingIntent.getActivity(
        context,
        if (action == null) 0 else 1,
        when (action) {
            OmbraVpnService.ACTION_CONNECT -> MainActivity.connectIntent(context)
            else -> Intent(context, MainActivity::class.java).setAction(action)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun canNotify(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun base(context: Context, channel: String, title: Int): Notification.Builder = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(title))
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setLocalOnly(true)
        .setContentIntent(openApp(context))
}
