package com.posdelay.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.posdelay.app.data.OrderTracker
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Gist에서 KDS 건수를 주기적으로 읽어옴 (모니터링/로깅 전용).
 * KDS는 Firebase SSE로 별도 수신 (FirebaseKdsReader).
 */
object GistOrderReader {

    private const val TAG = "GistOrderReader"
    private const val GIST_API_URL =
        "https://api.github.com/gists/a67e5de3271d6d0716b276dc6a8391cb"
    private const val INTERVAL_NORMAL = 60_000L
    private const val INTERVAL_SLOW = 120_000L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var appContext: Context? = null
    private var consecutiveErrors = 0
    private var currentInterval = INTERVAL_NORMAL
    var nextFetchTime = 0L
        private set

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        currentInterval = INTERVAL_NORMAL
        handler.post(fetchRunnable)
        Log.d(TAG, "Gist 모니터링 시작")
        com.posdelay.app.data.LogFileWriter.append("GIST", "폴링 시작 (${currentInterval/1000}초 간격)")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(fetchRunnable)
    }

    private val fetchRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "fetchRunnable 실행")
            fetchGist()
            if (running) {
                nextFetchTime = System.currentTimeMillis() + currentInterval
                handler.postDelayed(this, currentInterval)
            }
        }
    }

    private fun fetchGist() {
        if (!OrderTracker.isEnabled()) {
            com.posdelay.app.data.LogFileWriter.append("GIST", "스킵: disabled")
            return
        }
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

                // KDS 데이터 로깅 (모니터링 전용)
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
                        Log.d(TAG, "Gist KDS: count=$kdsCount, age=${kdsAge}초")
                        com.posdelay.app.data.LogFileWriter.append("GIST", "KDS=$kdsCount age=${kdsAge}초")
                    } catch (e: Exception) {
                        Log.w(TAG, "Gist KDS 파싱 실패: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                consecutiveErrors++
                Log.w(TAG, "Gist 읽기 실패 (${consecutiveErrors}회): ${e.message}")
            }
        }
    }
}
