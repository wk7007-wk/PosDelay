package com.posdelay.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.posdelay.app.ui.MainActivity

/**
 * KDS 모니터링용 포그라운드 서비스.
 * 주방폰에서 KDS 접근성 서비스가 활성화될 때 시작됨.
 * 알림바에 현재 조리중 건수를 표시하고 프로세스 유지.
 */
class KdsKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "kds_keepalive"
        private const val NOTIFICATION_ID = 3001
        private const val UPDATE_INTERVAL = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, KdsKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, UPDATE_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("KDS 모니터링 시작..."))
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "KDS 모니터링", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val prefs = getSharedPreferences("poskds_prefs", MODE_PRIVATE)
        val count = prefs.getInt("last_count", -1)
        val text = if (KdsAccessibilityService.isAvailable()) {
            if (count >= 0) "조리중: ${count}건" else "대기중..."
        } else {
            "접근성 서비스 꺼짐!"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
}
