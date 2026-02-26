package com.posdelay.app.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 네이티브 조리모드 알림 체커.
 * WebView JS가 백그라운드에서 일시정지되어도 포그라운드 서비스에서 동작.
 * 독립 스레드에서 10초 간격 루프 (Handler 대신 Thread.sleep — Doze 지연 방지).
 */
object NativeCookAlertChecker {

    private const val TAG = "NativeCookAlert"
    private const val PREFS_NAME = "cook_mode_settings"
    private const val CHECK_INTERVAL = 10_000L  // 10초

    private var appContext: Context? = null
    @Volatile private var running = false
    private lateinit var prefs: SharedPreferences
    private var checkerThread: Thread? = null

    // 알림 발생 추적 (주문번호 → 발생한 알림 타입)
    private val firedAlerts = HashMap<Int, MutableSet<String>>()  // "cook", "pkg", "over"
    // 주문 사라진 시각 (SSE 플래핑 시 즉시 삭제 방지)
    private val orderGoneTime = HashMap<Int, Long>()

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        running = true
        checkerThread = kotlin.concurrent.thread(name = "CookAlertChecker") {
            Log.d(TAG, "네이티브 조리 알림 시작 (독립 스레드)")
            while (running) {
                try {
                    Thread.sleep(CHECK_INTERVAL)
                    if (running) checkAlerts()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "체크 오류: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        running = false
        checkerThread?.interrupt()
        checkerThread = null
    }

    /** WebView JS에서 조리모드 설정 동기화 */
    fun syncSettings(on: Boolean, targetSec: Int, midSec: Int, finishSec: Int) {
        prefs.edit()
            .putBoolean("cook_on", on)
            .putInt("target", targetSec)
            .putInt("mid", midSec)
            .putInt("finish", finishSec)
            .apply()
    }

    private fun checkAlerts() {
        val ctx = appContext ?: return
        if (!prefs.getBoolean("cook_on", false)) return

        val tgtMs = prefs.getInt("target", 1500) * 1000L
        val midMs = prefs.getInt("mid", 1020) * 1000L
        val finMs = prefs.getInt("finish", 240) * 1000L
        val cookStartMs = tgtMs - midMs
        val pkgStartMs = tgtMs - finMs
        val now = System.currentTimeMillis()

        val orders = FirebaseKdsReader.getOrderTimestamps()
        if (orders.isEmpty()) return

        // 현재 KDS에 있는 주문번호 (orders 배열)
        val activeOrders = orders.keys

        // 사라진 주문: 즉시 삭제하지 않고 5분 유예 (SSE 플래핑 방지)
        val goneExpiry = 5 * 60 * 1000L
        for (id in firedAlerts.keys.toSet()) {
            if (id in activeOrders) {
                orderGoneTime.remove(id)
            } else {
                val gone = orderGoneTime.getOrPut(id) { now }
                if (now - gone > goneExpiry) {
                    firedAlerts.remove(id)
                    orderGoneTime.remove(id)
                }
            }
        }

        var alertCount = 0  // 한 사이클 최대 2개 제한 (몰림 방지)

        for ((orderNum, firstSeen) in orders) {
            if (alertCount >= 2) break

            val elMs = now - firstSeen
            val fired = firedAlerts.getOrPut(orderNum) { mutableSetOf() }

            // 60분 초과 → 무시
            if (elMs > 3600000) continue

            // 현재 단계 판단 (가장 진행된 단계만 알림, 지나간 단계는 무음 처리)
            val currentStage = when {
                elMs >= tgtMs -> "over"
                elMs >= pkgStartMs -> "pkg"
                elMs >= cookStartMs -> "cook"
                else -> null
            } ?: continue

            // 이전 단계들은 무음으로 기록만 (SSE 재연결 후 몰림 방지)
            if (currentStage == "over" || currentStage == "pkg") fired.add("cook")
            if (currentStage == "over") fired.add("pkg")

            if (currentStage !in fired) {
                fired.add(currentStage)
                val label = when (currentStage) {
                    "cook" -> "조리"
                    "pkg" -> "포장"
                    "over" -> "시간초과"
                    else -> currentStage
                }
                DelayNotificationHelper.showCookAlert(ctx, "${orderNum}번 $label", orderNum)
                Log.d(TAG, "${orderNum}번 $label 알림 (네이티브)")
                com.posdelay.app.data.LogFileWriter.append("조리", "${orderNum}번 $label (${elMs/60000}분)")
                alertCount++
            }
        }
    }
}
