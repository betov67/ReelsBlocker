package com.example.reelsblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * BlockerForegroundService
 *
 * A lightweight foreground service whose only job is to display a persistent
 * notification so the Android OS deprioritises killing this process during
 * memory pressure.
 *
 * Why a separate service rather than using the AccessibilityService itself?
 * ─────────────────────────────────────────────────────────────────────────
 * AccessibilityServices run in the app's process but the system can still kill
 * the *process* under severe memory pressure, removing the service along with
 * it.  A foreground Service with a visible notification receives a higher
 * process priority (IMPORTANCE_FOREGROUND) that strongly discourages the OS
 * from killing it.
 *
 * Note: on Android 14+ (API 34) foreground services require a `foregroundServiceType`.
 * We use `specialUse` as declared in the manifest.  No runtime permission beyond
 * FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE is needed for this type.
 */
class BlockerForegroundService : Service() {

    companion object {
        const val CHANNEL_ID      = "reels_blocker_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP     = "com.example.reelsblocker.ACTION_STOP_SERVICE"
    }

    override fun onBind(intent: Intent?): IBinder? = null  // Not a bound service

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // START_STICKY: if the OS kills the service, it will be restarted
        // automatically (with a null Intent).
        return START_STICKY
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW   // Silent; no sound/heads-up
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tapping the notification opens MainActivity
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)           // Cannot be swiped away
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
