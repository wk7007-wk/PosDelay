package com.posdelay.app.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.posdelay.app.data.OrderTracker
import com.posdelay.app.ui.MainActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * PC의 mate_monitor.py가 업로드한 주문 건수를 GitHub Gist에서 주기적으로 읽어옴.
 * PC가 항상 켜져 있으므로 폰보다 안정적인 주문 건수 소스.
 *
 * 3분 이상 갱신 없으면 MATE 앱을 자동으로 잠깐 열어서
 * AccessibilityService가 건수를 직접 읽도록 함.
 */
object GistOrderReader {

    private const val TAG = "GistOrderReader"
    // API 엔드포인트 사용 (raw URL은 CDN 캐싱 5분 문제)
    private const val GIST_API_URL =
        "https://api.github.com/gists/a67e5de3271d6d0716b276dc6a8391cb"
    private const val INTERVAL_NORMAL = 60_000L   // 60초 기본
    private const val INTERVAL_SLOW = 120_000L    // 120초 (한도 근접 시)

    private const val STALE_MS = 3 * 60 * 1000L       // 3분: 갱신 없으면 MATE 열기
    private const val MATE_COOLDOWN_MS = 5 * 60 * 1000L // 5분: MATE 재시도 쿨다운
    private const val MATE_PACKAGE = "com.foodtechkorea.posboss"

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var appContext: Context? = null
    private var consecutiveErrors = 0
    private var currentInterval = INTERVAL_NORMAL
    private var lastMateAttempt = 0L
    private var pendingReturnHome = false

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        currentInterval = INTERVAL_NORMAL
        handler.post(fetchRunnable)
        Log.d(TAG, "Gist 모니터링 시작 (${currentInterval / 1000}초 간격, API 모드)")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(fetchRunnable)
    }

    /** AccessibilityService가 MATE 건수 읽은 후 호출 → 자동 복귀 */
    fun onMateDataRead() {
        if (!pendingReturnHome) return
        pendingReturnHome = false
        handler.postDelayed({ goHome() }, 2000)
    }

    private val fetchRunnable = object : Runnable {
        override fun run() {
            fetchGist()
            handler.post { checkStaleAndRefresh() }
            if (running) handler.postDelayed(this, currentInterval)
        }
    }

    private fun fetchGist() {
        if (OrderTracker.isPcPaused()) return
        kotlin.concurrent.thread {
            try {
                val url = URL(GIST_API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "PosDelay-Android")

                val responseCode = conn.responseCode

                // Rate limit 자동 조절
                val remaining = conn.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
                if (remaining != null) {
                    val newInterval = if (remaining <= 10) INTERVAL_SLOW else INTERVAL_NORMAL
                    if (newInterval != currentInterval) {
                        currentInterval = newInterval
                        Log.d(TAG, "API 잔여 $remaining → 간격 ${currentInterval/1000}초")
                    }
                }

                if (responseCode == 403 || responseCode == 429) {
                    consecutiveErrors++
                    currentInterval = INTERVAL_SLOW
                    Log.w(TAG, "Rate limit ($responseCode), 간격 ${currentInterval/1000}초로 감속")
                    conn.disconnect()
                    return@thread
                }

                if (responseCode != 200) {
                    consecutiveErrors++
                    Log.w(TAG, "HTTP $responseCode (연속오류: $consecutiveErrors)")
                    conn.disconnect()
                    return@thread
                }

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                // API 응답에서 파일 내용 추출
                val gistObj = JSONObject(response)
                val files = gistObj.getJSONObject("files")
                val orderFile = files.getJSONObject("order_status.json")
                val content = orderFile.getString("content")

                val obj = JSONObject(content)
                val count = obj.getInt("count")
                val time = obj.optString("time", "")

                consecutiveErrors = 0

                if (time.isNotEmpty()) {
                    val pcTime = try {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        sdf.parse(time)?.time ?: 0L
                    } catch (e: Exception) { 0L }

                    val age = if (pcTime > 0) (System.currentTimeMillis() - pcTime) / 1000 else -1L
                    Log.d(TAG, "PC 데이터: count=$count, age=${age}초")

                    // PC 데이터가 10분 이내일 때만 건수 반영
                    if (pcTime > 0 && age < 600) {
                        OrderTracker.syncPcOrderCount(count, pcTime)
                        // PC 복귀 → MATE 자동 활성화 상태였으면 다시 비활성화
                        if (OrderTracker.isMateAutoManaged()) {
                            OrderTracker.setMatePaused(true)
                            OrderTracker.setMateAutoManaged(false)
                            Log.d(TAG, "PC 갱신 복구 → MATE 자동 비활성화")
                        }
                        appContext?.let { ctx ->
                            handler.post { DelayNotificationHelper.update(ctx) }
                        }
                    } else if (pcTime > 0) {
                        OrderTracker.updatePcSyncTime(pcTime)
                        Log.d(TAG, "PC 데이터 오래됨 (${age}초), 시간만 표시")
                    }
                }
            } catch (e: Exception) {
                consecutiveErrors++
                Log.w(TAG, "Gist 읽기 실패 (${consecutiveErrors}회): ${e.message}")
            }
        }
    }

    /** PC 3분 미갱신 → MATE 자동 활성화 (보조역할) + 필요시 MATE 앱 열기 */
    private fun checkStaleAndRefresh() {
        val ctx = appContext ?: return
        val lastPcSync = OrderTracker.getLastPcSyncTime()
        val now = System.currentTimeMillis()

        if (lastPcSync == 0L) return
        if (!OrderTracker.isEnabled()) return

        val pcStale = now - lastPcSync >= STALE_MS

        // PC 3분 미갱신 → MATE 자동 활성화
        if (pcStale && OrderTracker.isMatePaused()) {
            OrderTracker.setMatePaused(false)
            OrderTracker.setMateAutoManaged(true)
            val staleMin = (now - lastPcSync) / 60000
            Log.d(TAG, "PC ${staleMin}분 미갱신 → MATE 자동 활성화")
            DelayNotificationHelper.showAdAlert(ctx, "PC ${staleMin}분 미갱신 → M활성화")
        }

        if (!pcStale) return
        if (now - lastMateAttempt < MATE_COOLDOWN_MS) return

        lastMateAttempt = now
        val staleMin = (now - lastPcSync) / 60000

        // 화면 잠금/꺼짐 상태 확인
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val screenOn = pm.isInteractive
        val locked = km.isKeyguardLocked

        if (!screenOn || locked) {
            // 잠금 상태 → MATE 열어도 소용없음 → 알림으로 경고만
            Log.d(TAG, "화면 잠김/꺼짐 → MATE 실행 불가, 알림만")
            DelayNotificationHelper.showAdAlert(ctx, "${staleMin}분 갱신없음 확인필요")
            return
        }

        Log.d(TAG, "데이터 ${staleMin}분 경과 → MATE 자동 실행")
        DelayNotificationHelper.showAdProgress(ctx, "${staleMin}분 갱신없음 MATE확인")

        try {
            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(MATE_PACKAGE)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                ctx.startActivity(launchIntent)
                pendingReturnHome = true
                // 안전장치: 8초 후 강제 복귀 (AccessibilityService 미응답 대비)
                handler.postDelayed({ forceReturn() }, 8000)
            } else {
                Log.w(TAG, "MATE 앱 없음")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MATE 실행 실패: ${e.message}")
        }
    }

    private fun forceReturn() {
        if (!pendingReturnHome) return
        pendingReturnHome = false
        goHome()
    }

    private fun goHome() {
        val ctx = appContext ?: return
        // AccessibilityService로 홈 버튼 누르기
        val service = try {
            DelayAccessibilityService.isAvailable()
        } catch (_: Exception) { false }

        if (service) {
            try {
                // performGlobalAction은 AccessibilityService에서만 호출 가능
                // → 대신 PosDelay 앱으로 복귀
                val intent = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                ctx.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
