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
 * GitHub Gist에서 주문 건수를 주기적으로 읽어옴.
 *
 * 우선순위:
 *   1. KDS (kds_status.json) — 주방 디스플레이 접근성 읽기, 가장 안정적
 *   2. PC  (order_status.json) — mate_monitor OCR, 보조
 *   3. MATE 앱 직접 열기 — 둘 다 미갱신 시 최후 수단
 */
object GistOrderReader {

    private const val TAG = "GistOrderReader"
    private const val GIST_API_URL =
        "https://api.github.com/gists/a67e5de3271d6d0716b276dc6a8391cb"
    private const val INTERVAL_NORMAL = 60_000L
    private const val INTERVAL_SLOW = 120_000L

    private const val KDS_STALE_MS = 5 * 60 * 1000L        // KDS 5분 미갱신 → PC 참조
    private const val ALL_STALE_MS = 8 * 60 * 1000L         // 둘 다 8분 미갱신 → MATE 열기
    private const val MATE_COOLDOWN_MS = 5 * 60 * 1000L
    private const val MATE_PACKAGE = "com.foodtechkorea.posboss"

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var appContext: Context? = null
    private var consecutiveErrors = 0
    private var currentInterval = INTERVAL_NORMAL
    private var lastMateAttempt = 0L
    private var pendingReturnHome = false
    var nextFetchTime = 0L
        private set

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        currentInterval = INTERVAL_NORMAL
        handler.post(fetchRunnable)
        Log.d(TAG, "Gist 모니터링 시작 (KDS 우선, PC 보조)")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(fetchRunnable)
    }

    fun onMateDataRead() {
        if (!pendingReturnHome) return
        pendingReturnHome = false
        handler.postDelayed({ goHome() }, 2000)
    }

    private val fetchRunnable = object : Runnable {
        override fun run() {
            fetchGist()
            handler.post { checkStaleAndRefresh() }
            if (running) {
                nextFetchTime = System.currentTimeMillis() + currentInterval
                handler.postDelayed(this, currentInterval)
            }
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
                    Log.w(TAG, "Rate limit ($responseCode)")
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
                consecutiveErrors = 0

                val gistObj = JSONObject(response)
                val files = gistObj.getJSONObject("files")
                val now = System.currentTimeMillis()
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

                // === 1순위: KDS 데이터 ===
                var kdsApplied = false
                if (files.has("kds_status.json")) {
                    try {
                        val kdsContent = files.getJSONObject("kds_status.json").getString("content")
                        val kdsObj = JSONObject(kdsContent)
                        val kdsCount = kdsObj.getInt("count")
                        val kdsTimeStr = kdsObj.optString("time", "")
                        val kdsTime = if (kdsTimeStr.isNotEmpty()) {
                            try { sdf.parse(kdsTimeStr)?.time ?: 0L } catch (_: Exception) { 0L }
                        } else 0L

                        val kdsAge = if (kdsTime > 0) (now - kdsTime) / 1000 else -1L
                        Log.d(TAG, "KDS: count=$kdsCount, age=${kdsAge}초")

                        // KDS 데이터가 5분 이내면 적용
                        if (kdsTime > 0 && kdsAge < KDS_STALE_MS / 1000) {
                            OrderTracker.syncKdsOrderCount(kdsCount, kdsTime)
                            kdsApplied = true
                            appContext?.let { ctx ->
                                handler.post { DelayNotificationHelper.update(ctx) }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "KDS 파싱 실패: ${e.message}")
                    }
                }

                // === 2순위: PC 데이터 (KDS 미적용 시에만) ===
                if (!kdsApplied && files.has("order_status.json")) {
                    try {
                        val pcContent = files.getJSONObject("order_status.json").getString("content")
                        val pcObj = JSONObject(pcContent)
                        val pcCount = pcObj.getInt("count")
                        val pcTimeStr = pcObj.optString("time", "")
                        val pcTime = if (pcTimeStr.isNotEmpty()) {
                            try { sdf.parse(pcTimeStr)?.time ?: 0L } catch (_: Exception) { 0L }
                        } else 0L

                        val pcAge = if (pcTime > 0) (now - pcTime) / 1000 else -1L
                        Log.d(TAG, "PC(보조): count=$pcCount, age=${pcAge}초")

                        if (pcTime > 0 && pcAge < 600) {
                            OrderTracker.syncPcOrderCount(pcCount, pcTime)
                            appContext?.let { ctx ->
                                handler.post { DelayNotificationHelper.update(ctx) }
                            }
                        } else if (pcTime > 0) {
                            OrderTracker.updatePcSyncTime(pcTime)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "PC 파싱 실패: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                consecutiveErrors++
                Log.w(TAG, "Gist 읽기 실패 (${consecutiveErrors}회): ${e.message}")
            }
        }
    }

    /** KDS+PC 둘 다 미갱신 → MATE 앱 열기 (최후 수단) */
    private fun checkStaleAndRefresh() {
        val ctx = appContext ?: return
        if (!OrderTracker.isEnabled()) return

        val now = System.currentTimeMillis()
        val lastKds = OrderTracker.getLastKdsSyncTime()
        val lastPc = OrderTracker.getLastPcSyncTime()
        val lastAny = maxOf(lastKds, lastPc)

        if (lastAny == 0L) return

        val allStale = now - lastAny >= ALL_STALE_MS

        // KDS+PC 둘 다 미갱신 → MATE 자동 활성화
        if (allStale && OrderTracker.isMatePaused()) {
            OrderTracker.setMatePaused(false)
            OrderTracker.setMateAutoManaged(true)
            val staleMin = (now - lastAny) / 60000
            Log.d(TAG, "KDS+PC ${staleMin}분 미갱신 → MATE 자동 활성화")
            DelayNotificationHelper.showAdAlert(ctx, "KDS+PC ${staleMin}분 미갱신 → M활성화")
        }

        if (!allStale) return
        if (now - lastMateAttempt < MATE_COOLDOWN_MS) return

        lastMateAttempt = now
        val staleMin = (now - lastAny) / 60000

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!pm.isInteractive || km.isKeyguardLocked) {
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
                handler.postDelayed({ forceReturn() }, 8000)
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
        try {
            if (DelayAccessibilityService.isAvailable()) {
                val intent = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                ctx.startActivity(intent)
            }
        } catch (_: Exception) {}
    }
}
