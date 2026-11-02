package com.meow.padlink

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the Bluetooth gamepad alive while the screen changes, the app is
 * in the background, or the tablet screen turns off.
 */
class PadService : Service() {

    companion object {
        var pad: HidGamepad? = null
            private set

        /** Single shared gamepad, created once and reused by every screen. */
        fun obtain(ctx: android.content.Context): HidGamepad =
            pad ?: HidGamepad(ctx.applicationContext).also { pad = it }
        const val ACTION_STOP = "com.meow.padlink.STOP"
        private const val CHANNEL = "padlink"
    }

    override fun onCreate() {
        super.onCreate()
        obtain(this).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            pad?.stop(); pad = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "PadLink", NotificationManager.IMPORTANCE_LOW))

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("PadLink is running")
            .setContentText("Stays connected to the TV in the background")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, n)
        return START_STICKY
    }

    override fun onDestroy() {
        pad?.stop(); pad = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
