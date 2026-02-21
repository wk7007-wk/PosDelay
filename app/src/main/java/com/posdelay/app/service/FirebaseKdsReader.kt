package com.posdelay.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.posdelay.app.data.OrderTracker
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Firebase Realtime Database SSE 리스너.
 * KDS 건수를 실시간으로 수신 (폴링 없음).
 */
object FirebaseKdsReader {

    private const val TAG = "FirebaseKdsReader"
    private const val FIREBASE_URL =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/kds_status.json"
    private const val RECONNECT_DELAY = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var appContext: Context? = null
    private var sseThread: Thread? = null

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        connectSSE()
        Log.d(TAG, "Firebase KDS 실시간 리스너 시작")
    }

    fun stop() {
        running = false
        sseThread?.interrupt()
        sseThread = null
    }

    private fun connectSSE() {
        if (!running) return
        sseThread = kotlin.concurrent.thread {
            try {
                val conn = URL(FIREBASE_URL).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 15000
                conn.readTimeout = 2 * 60 * 1000  // Bug 5: 2분 무응답 시 타임아웃 (staleness 방지)

                if (conn.responseCode != 200) {
                    Log.w(TAG, "SSE 연결 실패: HTTP ${conn.responseCode}")
                    conn.disconnect()
                    scheduleReconnect()
                    return@thread
                }

                Log.d(TAG, "SSE 연결 성공")
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var eventType = ""

                while (running) {
                    val line = reader.readLine() ?: break  // readTimeout 초과 시 null → 재연결

                    when {
                        line.startsWith("event:") -> {
                            eventType = line.substringAfter("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            val data = line.substringAfter("data:").trim()
                            if (eventType == "put" || eventType == "patch") {
                                handleData(data)
                            }
                        }
                    }
                }

                reader.close()
                conn.disconnect()
                Log.d(TAG, "SSE 스트림 종료 (재연결)")
            } catch (e: InterruptedException) {
                Log.d(TAG, "SSE 중단됨")
                return@thread
            } catch (e: java.net.SocketTimeoutException) {
                Log.d(TAG, "SSE 타임아웃 (2분 무응답) → 재연결")
            } catch (e: Exception) {
                Log.w(TAG, "SSE 에러: ${e.message}")
            }

            scheduleReconnect()
        }
    }

    private fun handleData(raw: String) {
        try {
            if (!OrderTracker.isEnabled()) return
            if (OrderTracker.isKdsPaused()) return

            val wrapper = JSONObject(raw)
            val data = wrapper.opt("data") ?: return
            if (data.toString() == "null") return

            val obj = if (data is JSONObject) data else JSONObject(data.toString())
            val count = obj.optInt("count", -1)
            val timeStr = obj.optString("time", "")

            if (count < 0) return

            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val kdsTime = if (timeStr.isNotEmpty()) {
                try { sdf.parse(timeStr)?.time ?: System.currentTimeMillis() }
                catch (_: Exception) { System.currentTimeMillis() }
            } else System.currentTimeMillis()

            Log.d(TAG, "KDS 실시간: count=$count, time=$timeStr")

            handler.post {
                OrderTracker.syncKdsOrderCount(count, kdsTime)
                appContext?.let { DelayNotificationHelper.update(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "데이터 파싱 실패: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        Log.d(TAG, "SSE 재연결 ${RECONNECT_DELAY / 1000}초 후...")
        handler.postDelayed({ connectSSE() }, RECONNECT_DELAY)
    }
}
