package com.posdelay.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.posdelay.app.data.AdManager
import com.posdelay.app.data.OrderTracker

/**
 * 건수 임계값 초과 시 시간 기반 알림 시스템.
 * - 건수 >= 임계값 지속 시 → "지연요청" 알림 (예측 시간 포함)
 * - 건수 < 임계값 복귀 시 → "해제" 알림
 */
object DelayAlertManager {

    private const val TAG = "DelayAlertManager"
    private const val CHECK_INTERVAL = 60_000L // 1분마다 체크
    private const val ALERT_DELAY_MS = 3 * 60_000L // 3분 초과 유지 시 알림

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var thresholdExceededSince = 0L // 임계값 초과 시작 시각
    private var alertFired = false
    private var lastCount = -1

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 건수 변경 시 호출 */
    fun onCountChanged(count: Int) {
        val threshold = AdManager.getCoupangOffThreshold()
        val now = System.currentTimeMillis()

        if (count >= threshold) {
            if (thresholdExceededSince == 0L) {
                thresholdExceededSince = now
                Log.d(TAG, "임계값 초과 시작: $count >= $threshold")
            }

            val elapsed = now - thresholdExceededSince
            if (elapsed >= ALERT_DELAY_MS && !alertFired) {
                alertFired = true
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(thresholdExceededSince))
                val delayMin = OrderTracker.getDelayMinutes()
                val msg = "${timeStr}이후 주문건 부터 지연 ${delayMin}분"
                Log.d(TAG, "알림 발동: $msg (${count}건)")
                appContext?.let { DelayNotificationHelper.showAdAlert(it, msg) }
                FirebaseSettingsSync.uploadLog("ALERT: $msg (${count}건)")
            }
        } else {
            if (thresholdExceededSince > 0L) {
                val elapsed = (now - thresholdExceededSince) / 60000
                Log.d(TAG, "임계값 이하 복귀: $count < $threshold (${elapsed}분 경과)")
                if (alertFired) {
                    appContext?.let {
                        DelayNotificationHelper.showAdResult(it, "정상 복귀 (${count}건, ${elapsed}분 소요)", true)
                    }
                    FirebaseSettingsSync.uploadLog("RESOLVED: ${count}건, ${elapsed}분 소요")
                }
            }
            thresholdExceededSince = 0L
            alertFired = false
        }

        lastCount = count
    }

    /** 조리 속도 기반 예측 텍스트 */
    private fun getPredictionText(count: Int, threshold: Int): String {
        // KDS history에서 조리 속도를 가져와야 하지만
        // PosDelay 앱에서는 직접 접근 불가하므로 Firebase에서 가져옴
        // 여기서는 간단히 빈 문자열 (웹 대시보드에서 예측 표시)
        return ""
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (thresholdExceededSince > 0L && !alertFired) {
                onCountChanged(OrderTracker.getOrderCount())
            }
            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    fun startPeriodicCheck() {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, CHECK_INTERVAL)
    }

    fun stop() {
        handler.removeCallbacks(checkRunnable)
    }
}
