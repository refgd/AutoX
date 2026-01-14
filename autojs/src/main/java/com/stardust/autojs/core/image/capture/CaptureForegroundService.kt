package com.stardust.autojs.core.image.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.stardust.app.service.AbstractAutoService
import com.stardust.autojs.R

class CaptureForegroundService : AbstractAutoService() {

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServiceInternal()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setSmallIcon(R.drawable.autojs_logo)
            .setWhen(System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setVibrate(LongArray(0))
            .addAction(createExitAction())
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            NOTIFICATION_TITLE,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = NOTIFICATION_TITLE
            enableLights(false)
            enableVibration(false)
            vibrationPattern = LongArray(0)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createExitAction(): NotificationCompat.Action {
        val pendingIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, CaptureForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(null, "停止截图", pendingIntent).build()
    }

    private fun removeNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    override fun onDestroy() {
        removeNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "STOP_SERVICE"
        private const val NOTIFICATION_ID = 26
        private val CHANNEL_ID = CaptureForegroundService::class.java.name + ".foreground"
        private const val NOTIFICATION_TITLE = "截图服务运行中"
    }
}
