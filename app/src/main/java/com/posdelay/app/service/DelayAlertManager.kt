package com.posdelay.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.posdelay.app.data.AdManager
import com.posdelay.app.data.OrderTracker

/**
 * 지연 자동 계산 + 알림 시스템.
 *
 * 공식: 지연 = max(0, 건수 × 평균완료간격 + 고정조리시간 - 목표시간)
 *
 * 알림 규칙:
 * - 지연상태 진입 (건수>=임계값 AND 지연>0): 즉시 알림 1회, 이후 3분마다 반복
 * - 정상복귀: 알림 1회, 이후 알림 없음
 */
object DelayAlertManager {

    private const val TAG = "DelayAlertManager"
    private const val CHECK_INTERVAL = 60_000L
    private const val ALERT_REPEAT_MS = 3 * 60_000L // 3분마다 반복

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    // 모드: normal / delayed
    private var isDelayed = false
    private var delayStartTime = 0L
    private var lastAlertTime = 0L

    // KDS 조리 속도 (Firebase에서 수신)
    private var avgCompletionInterval = 3.0 // 분/건, 기본값

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** KDS 이력에서 계산된 평균 완료 간격 업데이트 */
    fun updateAvgInterval(intervalMinutes: Double) {
        avgCompletionInterval = intervalMinutes.coerceIn(2.0, 4.0)
    }

    /** 건수 변경 시 호출 */
    fun onCountChanged(count: Int) {
        val now = System.currentTimeMillis()

        // 플랫폼별 지연 계산
        val bDelay = calculateDelay(count, AdManager.getBaeminTargetTime(), AdManager.getBaeminFixedCookTime())
        val cDelay = calculateDelay(count, AdManager.getCoupangTargetTime(), AdManager.getCoupangFixedCookTime())
        val bTh = AdManager.getBaeminDelayThreshold()
        val cTh = AdManager.getCoupangDelayThreshold()

        val msgs = mutableListOf<String>()
        // 지연 건수 = 총건수 - 정상 처리 가능 건수
        val bBreakeven = if (avgCompletionInterval > 0) ((AdManager.getBaeminTargetTime() - AdManager.getBaeminFixedCookTime()) / avgCompletionInterval).toInt() else 0
        val cBreakeven = if (avgCompletionInterval > 0) ((AdManager.getCoupangTargetTime() - AdManager.getCoupangFixedCookTime()) / avgCompletionInterval).toInt() else 0
        if (count >= bTh && bDelay > 0) msgs.add("배민 ${bDelay}분 ${maxOf(0, count - bBreakeven)}건")
        if (count >= cTh && cDelay > 0) msgs.add("쿠팡 ${cDelay}분 ${maxOf(0, count - cBreakeven)}건")

        if (msgs.isNotEmpty()) {
            // 지연 상태
            if (!isDelayed) {
                // 최초 진입 → 즉시 알림
                isDelayed = true
                delayStartTime = now
                lastAlertTime = now
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(now))
                val msg = "${timeStr} 지연 ${msgs.joinToString(", ")}"
                Log.d(TAG, "지연 진입: $msg")
                sendAlert("주문 지연", msg)
                FirebaseSettingsSync.uploadLog("DELAY: $msg")
            } else if (now - lastAlertTime >= ALERT_REPEAT_MS) {
                // 3분마다 반복
                lastAlertTime = now
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(delayStartTime))
                val elapsedMin = (now - delayStartTime) / 60000
                val msg = "${timeStr} 지연 ${msgs.joinToString(", ")} (${elapsedMin}분 경과)"
                Log.d(TAG, "지연 반복: $msg")
                sendAlert("주문 지연 지속", msg)
                FirebaseSettingsSync.uploadLog("DELAY: $msg")
            }
        } else {
            // 정상 상태
            if (isDelayed) {
                // 정상 복귀 1회 알림
                isDelayed = false
                val elapsedMin = (now - delayStartTime) / 60000
                val msg = "정상 복귀 (${elapsedMin}분만에)"
                Log.d(TAG, "정상 복귀: $msg")
                sendAlert("정상 복귀", msg)
                FirebaseSettingsSync.uploadLog("RESOLVED: $msg")
                delayStartTime = 0L
                lastAlertTime = 0L
            }
        }
    }

    private fun sendAlert(title: String, message: String) {
        appContext?.let { DelayNotificationHelper.showDelayAlert(it, title, message) }
    }

    /** 지연 시간 계산: max(0, 건수 × 평균완료간격 + 고정조리시간 - 목표시간) */
    fun calculateDelay(count: Int, targetTime: Int, fixedCookTime: Int): Int {
        val total = count * avgCompletionInterval + fixedCookTime
        return maxOf(0, (total - targetTime).toInt())
    }

    /** 현재 배민 예상 지연 */
    fun getBaeminDelay(): Int = calculateDelay(
        OrderTracker.getOrderCount(),
        AdManager.getBaeminTargetTime(),
        AdManager.getBaeminFixedCookTime()
    )

    /** 현재 쿠팡 예상 지연 */
    fun getCoupangDelay(): Int = calculateDelay(
        OrderTracker.getOrderCount(),
        AdManager.getCoupangTargetTime(),
        AdManager.getCoupangFixedCookTime()
    )

    fun getAvgInterval(): Double = avgCompletionInterval

    private val checkRunnable = object : Runnable {
        override fun run() {
            onCountChanged(OrderTracker.getOrderCount())
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
