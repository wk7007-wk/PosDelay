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
 * PC의 mate_monitor.py가 업로드한 주문 건수를 GitHub Gist에서 주기적으로 읽어옴.
 * PC가 항상 켜져 있으므로 폰보다 안정적인 주문 건수 소스.
 */
object GistOrderReader {

    private const val TAG = "GistOrderReader"
    // API 엔드포인트 사용 (raw URL은 CDN 캐싱 5분 문제)
    private const val GIST_API_URL =
        "https://api.github.com/gists/a67e5de3271d6d0716b276dc6a8391cb"
    private const val INTERVAL_MS = 90_000L  // 90초 (API rate limit 60/h 대응)

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var appContext: Context? = null
    private var consecutiveErrors = 0

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        handler.post(fetchRunnable)
        Log.d(TAG, "Gist 모니터링 시작 (${INTERVAL_MS / 1000}초 간격, API 모드)")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(fetchRunnable)
    }

    private val fetchRunnable = object : Runnable {
        override fun run() {
            fetchGist()
            if (running) handler.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun fetchGist() {
        kotlin.concurrent.thread {
            try {
                val url = URL(GIST_API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "PosDelay-Android")

                val responseCode = conn.responseCode
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
}
