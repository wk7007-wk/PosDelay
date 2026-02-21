package com.posdelay.app.service

import android.content.Context
import android.content.SharedPreferences
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
 * 25분 이상 조리중인 주문은 건수에서 제외.
 */
object FirebaseKdsReader {

    private const val TAG = "FirebaseKdsReader"
    private const val FIREBASE_URL =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/kds_status.json"
    private const val RECONNECT_DELAY = 5_000L
    private const val STALE_ORDER_MS = 25 * 60 * 1000L  // 25분
    private const val PREFS_NAME = "kds_order_tracking"

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var appContext: Context? = null
    private var sseThread: Thread? = null
    private lateinit var prefs: SharedPreferences

    // 주문번호별 최초 등장 시간 추적 (SharedPreferences에 영구 저장)
    private val orderFirstSeen = HashMap<Int, Long>()

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadOrderTracking()
        connectSSE()
        Log.d(TAG, "Firebase KDS 실시간 리스너 시작")
    }

    private fun loadOrderTracking() {
        val saved = prefs.getString("order_first_seen", null) ?: return
        try {
            val obj = JSONObject(saved)
            obj.keys().forEach { key ->
                orderFirstSeen[key.toInt()] = obj.getLong(key)
            }
            Log.d(TAG, "주문 추적 복원: ${orderFirstSeen.size}건")
        } catch (_: Exception) {}
    }

    private fun saveOrderTracking() {
        val obj = JSONObject()
        orderFirstSeen.forEach { (k, v) -> obj.put(k.toString(), v) }
        prefs.edit().putString("order_first_seen", obj.toString()).apply()
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

            // orders 배열 파싱 → 25분 초과 주문 제외
            val ordersArr = obj.optJSONArray("orders")
            val adjustedCount = if (ordersArr != null && ordersArr.length() > 0) {
                val now = System.currentTimeMillis()
                val currentOrders = HashSet<Int>()
                for (i in 0 until ordersArr.length()) {
                    currentOrders.add(ordersArr.optInt(i))
                }
                // 새 주문 등록, 사라진 주문 제거
                orderFirstSeen.keys.retainAll(currentOrders)
                for (orderId in currentOrders) {
                    orderFirstSeen.putIfAbsent(orderId, now)
                }
                // 25분 초과 주문 제외
                val staleCount = orderFirstSeen.count { now - it.value > STALE_ORDER_MS }
                val filtered = maxOf(0, currentOrders.size - staleCount)
                if (staleCount > 0) {
                    val staleOrders = orderFirstSeen.filter { now - it.value > STALE_ORDER_MS }.keys
                    Log.d(TAG, "KDS 25분초과 제외: ${staleOrders} → 건수 $count→$filtered")
                }
                saveOrderTracking()
                filtered
            } else {
                count  // orders 배열 없으면 원본 count 사용
            }

            Log.d(TAG, "KDS 실시간: count=$count, adjusted=$adjustedCount, time=$timeStr")

            handler.post {
                OrderTracker.syncKdsOrderCount(adjustedCount, kdsTime)
                appContext?.let { DelayNotificationHelper.update(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "데이터 파싱 실패: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        AnomalyDetector.recordSseReconnect("kds")
        Log.d(TAG, "SSE 재연결 ${RECONNECT_DELAY / 1000}초 후...")
        handler.postDelayed({ connectSSE() }, RECONNECT_DELAY)
    }
}
