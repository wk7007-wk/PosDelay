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
    private const val GIST_RAW_URL =
        "https://gist.githubusercontent.com/wk7007-wk/a67e5de3271d6d0716b276dc6a8391cb/raw/order_status.json"
    private const val INTERVAL_MS = 60_000L  // 60초

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var appContext: Context? = null

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        handler.post(fetchRunnable)
        Log.d(TAG, "Gist 모니터링 시작 (${INTERVAL_MS / 1000}초 간격)")
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
                val url = URL("$GIST_RAW_URL?t=${System.currentTimeMillis()}")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Cache-Control", "no-cache")

                if (conn.responseCode != 200) {
                    Log.w(TAG, "HTTP ${conn.responseCode}")
                    conn.disconnect()
                    return@thread
                }

                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val obj = JSONObject(json)
                val count = obj.getInt("count")
                val time = obj.optString("time", "")

                if (time.isNotEmpty()) {
                    // PC 시간 파싱 (yyyy-MM-dd HH:mm:ss)
                    val pcTime = try {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        sdf.parse(time)?.time ?: 0L
                    } catch (e: Exception) { 0L }

                    // PC 데이터가 10분 이내일 때만 반영
                    val age = System.currentTimeMillis() - pcTime
                    if (pcTime > 0 && age < 10 * 60 * 1000) {
                        OrderTracker.syncPcOrderCount(count, pcTime)
                        // 알림으로 건수 표시
                        appContext?.let { ctx ->
                            handler.post { DelayNotificationHelper.update(ctx) }
                        }
                    } else if (pcTime > 0) {
                        // 오래된 데이터도 시간은 기록 (UI에 표시용)
                        OrderTracker.updatePcSyncTime(pcTime)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gist 읽기 실패: ${e.message}")
            }
        }
    }
}
