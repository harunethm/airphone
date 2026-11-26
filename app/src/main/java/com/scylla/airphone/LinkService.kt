package com.scylla.airphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the TV link alive while the app is in the background or the phone screen is off. Without it Android
 * unregisters background HID apps, the TV sees its keyboard unplug, and the TV's foreground app restarts
 * (keyboard/navigation configuration change) — e.g. a browser loses its page. Runs only while a TV is
 * connected; swiping the app away (stopWithTask) or the notification's Disconnect ends it.
 */
class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            HidLink.disconnect() // DISCONNECTED then calls stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(intent?.getStringExtra(EXTRA_HOST) ?: "your TV")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, n)
        starting = false
        if (stopWanted) { // stop() came before we got here; stopping un-started would crash the app
            stopWanted = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(host: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "TV connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, LinkService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Connected to $host")
            .setContentText("Stays connected while the screen is off")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "link"
        private const val ACTION_DISCONNECT = "com.scylla.airphone.DISCONNECT"
        private const val EXTRA_HOST = "host"

        // Main thread only. startForegroundService() obliges the service to call startForeground(); a stopService()
        // before onStartCommand would make Android kill the app, so a stop in that window is deferred.
        private var starting = false
        private var stopWanted = false

        /** Call while the app is in the foreground (Android 12+ forbids starting it from the background). */
        fun start(ctx: Context, host: String) {
            val i = Intent(ctx, LinkService::class.java).putExtra(EXTRA_HOST, host)
            stopWanted = false
            // ponytail: if Android refuses (app already in background), the link just drops like before.
            starting = runCatching { ctx.startForegroundService(i) }.isSuccess
        }

        fun stop(ctx: Context) {
            if (starting) stopWanted = true else ctx.stopService(Intent(ctx, LinkService::class.java))
        }
    }
}
