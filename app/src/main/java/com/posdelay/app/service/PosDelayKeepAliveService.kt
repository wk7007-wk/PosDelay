package com.posdelay.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.posdelay.app.data.OrderTracker

/**
 * PosDelay 메인폰 포그라운드 서비스.
 * - 프로세스 kill 방지 (포그라운드 서비스 우선순위)
 * - WakeLock 유지 (10분 타임아웃 + 9분마다 재획득)
 * - WifiLock 유지 (WiFi 절전 방지)
 * - 데이터 수집 서비스 시작 (GistOrderReader, FirebaseKdsReader)
 */
class PosDelayKeepAliveService : Service() {

    companion object {
        private const val TAG = "PosDelayKeepAlive"
        private const val NOTIFICATION_ID = 2001 // DelayNotificationHelper 상태 알림과 공유
        private const val WAKELOCK_TIMEOUT = 10 * 60 * 1000L // 10분
        private const val REACQUIRE_INTERVAL = 9 * 60 * 1000L // 9분마다 재획득

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PosDelayKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "서비스 시작 실패: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val reacquireRunnable = object : Runnable {
        override fun run() {
            reacquireWakeLock()
            DelayNotificationHelper.update(this@PosDelayKeepAliveService)
            handler.postDelayed(this, REACQUIRE_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // 포그라운드 알림 (기존 상태 알림 ID 공유 → 알림 1개만 표시)
        DelayNotificationHelper.createChannels(this)
        val notification = NotificationCompat.Builder(this, "posdelay_status")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("")
            .setContentText("모니터링 시작...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        // WakeLock + WifiLock
        acquireWakeLock()
        acquireWifiLock()

        // 데이터 수집 서비스 시작
        GistOrderReader.start(this)
        FirebaseKdsReader.start(this)

        // 상태 알림 업데이트 (실제 건수 표시)
        DelayNotificationHelper.update(this)

        // 주기적 WakeLock 갱신 + 알림 업데이트
        handler.postDelayed(reacquireRunnable, REACQUIRE_INTERVAL)

        Log.d(TAG, "KeepAlive 서비스 시작")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        releaseWifiLock()
        Log.d(TAG, "KeepAlive 서비스 종료")
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PosDelay:KeepAlive")
        wakeLock?.acquire(WAKELOCK_TIMEOUT)
    }

    private fun reacquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            acquireWakeLock()
            Log.d(TAG, "WakeLock 재획득")
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun acquireWifiLock() {
        releaseWifiLock()
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PosDelay:KeepAlive")
        wifiLock?.acquire()
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }
}
