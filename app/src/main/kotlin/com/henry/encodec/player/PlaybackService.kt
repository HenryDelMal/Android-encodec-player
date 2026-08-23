package com.henry.encodec.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

class PlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(PlayerViewModel.MEDIA_NOTIFICATION_ID, preparingNotification())
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EncodecPlayer:Playback")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EncodecPlayer:Streaming")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PlayerViewModel.refreshMediaState()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wifiLock = null
        wakeLock = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                PlayerViewModel.MEDIA_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "EnCodec playback controls" },
        )
    }

    private fun preparingNotification(): Notification =
        Notification.Builder(this, PlayerViewModel.MEDIA_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("EnCodec Player")
            .setContentText("Preparing playback…")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
}
