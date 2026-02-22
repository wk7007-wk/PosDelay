package com.posdelay.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingReconnect: Runnable? = null

    private val reacquireRunnable = object : Runnable {
        override fun run() {
            reacquireWakeLock()
            DelayNotificationHelper.update(this@PosDelayKeepAliveService)
            logHeartbeat()
            handler.postDelayed(this, REACQUIRE_INTERVAL)
        }
    }

    private fun logHeartbeat() {
        val fg = com.posdelay.app.ui.MainActivity.isInForeground
        val count = OrderTracker.getOrderCount()
        val kdsAge = (System.currentTimeMillis() - OrderTracker.getLastKdsSyncTime()) / 1000
        val pcAge = (System.currentTimeMillis() - OrderTracker.getLastPcSyncTime()) / 1000
        val gistKds = OrderTracker.gistKdsCount
        val wake = wakeLock?.isHeld == true
        val wifi = wifiLock?.isHeld == true
        com.posdelay.app.data.LogFileWriter.append("HB",
            "${if (fg) "포그라운드" else "백그라운드"} 건수=$count KDS=${kdsAge}초전 PC=${pcAge}초전 Gist=$gistKds WL=$wake WF=$wifi")
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
        NativeCookAlertChecker.start(this)

        // 상태 알림 업데이트 (실제 건수 표시)
        DelayNotificationHelper.update(this)

        // 주기적 WakeLock 갱신 + 알림 업데이트
        handler.postDelayed(reacquireRunnable, REACQUIRE_INTERVAL)

        // 네트워크 변경 감지 → SSE 즉시 재연결
        registerNetworkCallback()

        Log.d(TAG, "KeepAlive 서비스 시작")
        com.posdelay.app.data.LogFileWriter.append("HB", "서비스 시작")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        NativeCookAlertChecker.stop()
        unregisterNetworkCallback()
        releaseWakeLock()
        releaseWifiLock()
        Log.d(TAG, "KeepAlive 서비스 종료")
        com.posdelay.app.data.LogFileWriter.append("HB", "서비스 종료")
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

    // === 네트워크 변경 감지 → SSE 즉시 재연결 ===

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 네트워크 복구 → 3초 디바운스 후 SSE 재연결
                    pendingReconnect?.let { handler.removeCallbacks(it) }
                    val task = Runnable {
                        Log.d(TAG, "네트워크 변경 감지 → SSE 재연결")
                        com.posdelay.app.data.LogFileWriter.append("NET", "네트워크 변경 → SSE 재연결")
                        FirebaseKdsReader.restart()
                        FirebaseSettingsSync.restart()
                    }
                    pendingReconnect = task
                    handler.postDelayed(task, 3000)
                }
            }
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
            Log.d(TAG, "네트워크 콜백 등록")
        } catch (e: Exception) {
            Log.w(TAG, "네트워크 콜백 등록 실패: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        networkCallback = null
    }
}
