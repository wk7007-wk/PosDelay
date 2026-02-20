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
 * - 평균완료간격: KDS 건수 감소 간격 (클램핑 2~4분)
 * - 고정조리시간: KDS완료 후 실제 완성까지 (설정값)
 * - 목표시간: 배달앱 약속 시간 (설정값)
 * - 배민/쿠팡 별도 임계값 + 설정
 */
object DelayAlertManager {

    private const val TAG = "DelayAlertManager"
    private const val CHECK_INTERVAL = 60_000L
    private const val ALERT_DELAY_MS = 3 * 60_000L // 임계값 초과 3분 유지 시 알림

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    // 플랫폼별 상태
    private var baeminExceededSince = 0L
    private var baeminAlertFired = false
    private var coupangExceededSince = 0L
    private var coupangAlertFired = false

    // KDS 조리 속도 (Firebase에서 수신)
    private var avgCompletionInterval = 3.0 // 분/건, 기본값

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** KDS 이력에서 계산된 평균 완료 간격 업데이트 */
    fun updateAvgInterval(intervalMinutes: Double) {
        // 클램핑 2~4분
        avgCompletionInterval = intervalMinutes.coerceIn(2.0, 4.0)
    }

    /** 건수 변경 시 호출 */
    fun onCountChanged(count: Int) {
        val now = System.currentTimeMillis()
        checkPlatform(
            "배민", count,
            AdManager.getBaeminDelayThreshold(),
            AdManager.getBaeminTargetTime(),
            AdManager.getBaeminFixedCookTime(),
            now, baeminExceededSince, baeminAlertFired,
            { baeminExceededSince = it }, { baeminAlertFired = it }
        )
        checkPlatform(
            "쿠팡", count,
            AdManager.getCoupangDelayThreshold(),
            AdManager.getCoupangTargetTime(),
            AdManager.getCoupangFixedCookTime(),
            now, coupangExceededSince, coupangAlertFired,
            { coupangExceededSince = it }, { coupangAlertFired = it }
        )
    }

    private fun checkPlatform(
        platform: String, count: Int, threshold: Int,
        targetTime: Int, fixedCookTime: Int,
        now: Long, exceededSince: Long, alertFired: Boolean,
        setExceededSince: (Long) -> Unit, setAlertFired: (Boolean) -> Unit
    ) {
        if (count >= threshold) {
            if (exceededSince == 0L) {
                setExceededSince(now)
                Log.d(TAG, "$platform 임계값 초과: $count >= $threshold")
            }

            val elapsed = now - (if (exceededSince == 0L) now else exceededSince)
            if (elapsed >= ALERT_DELAY_MS && !alertFired) {
                setAlertFired(true)
                val delayMin = calculateDelay(count, targetTime, fixedCookTime)
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
                    .format(java.util.Date(if (exceededSince == 0L) now else exceededSince))
                val msg = "$platform ${timeStr}이후 주문건 지연 ${delayMin}분 (${count}건)"
                Log.d(TAG, "알림: $msg")
                appContext?.let { DelayNotificationHelper.showAdAlert(it, msg) }
                FirebaseSettingsSync.uploadLog("DELAY: $msg")
            }
        } else {
            if (exceededSince > 0L) {
                val elapsedMin = (now - exceededSince) / 60000
                Log.d(TAG, "$platform 임계값 이하 복귀: $count < $threshold (${elapsedMin}분)")
                if (alertFired) {
                    appContext?.let {
                        DelayNotificationHelper.showAdResult(it, "$platform 정상 복귀 (${count}건, ${elapsedMin}분)", true)
                    }
                    FirebaseSettingsSync.uploadLog("RESOLVED: $platform ${count}건 (${elapsedMin}분)")
                }
            }
            setExceededSince(0L)
            setAlertFired(false)
        }
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
            val count = OrderTracker.getOrderCount()
            if (baeminExceededSince > 0L && !baeminAlertFired) onCountChanged(count)
            if (coupangExceededSince > 0L && !coupangAlertFired) onCountChanged(count)
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
