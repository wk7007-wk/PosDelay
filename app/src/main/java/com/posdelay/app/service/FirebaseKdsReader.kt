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
import java.io.OutputStreamWriter
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
    private const val FIREBASE_BASE =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"
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
    private var lastLoggedCount = -1  // Firebase 로그 중복 방지
    private var lastCountChangeTime = System.currentTimeMillis()  // 30분 강제보정용
    private var lastCountValue = -1
    private const val FORCE_ZERO_MS = 30 * 60 * 1000L  // 30분

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Firebase 로드 완료 후 SSE 연결 (동기 순서 보장)
        kotlin.concurrent.thread {
            loadOrderTracking()
            connectSSE()
        }
        Log.d(TAG, "Firebase KDS 실시간 리스너 시작")
    }

    private fun loadOrderTracking() {
        // 1차: 로컬 SharedPreferences
        val saved = prefs.getString("order_first_seen", null)
        if (saved != null) {
            try {
                val obj = JSONObject(saved)
                obj.keys().forEach { key ->
                    orderFirstSeen[key.toInt()] = obj.getLong(key)
                }
                Log.d(TAG, "주문 추적 복원 (로컬): ${orderFirstSeen.size}건")
            } catch (_: Exception) {}
        }
        // 2차: Firebase에서 동기 로드 (재설치 대비 — SSE 연결 전에 완료)
        try {
            val conn = URL("$FIREBASE_BASE/posdelay/order_tracking.json").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (json != "null" && json.isNotEmpty()) {
                    val obj = JSONObject(json)
                    var restored = 0
                    obj.keys().forEach { key ->
                        val ts = obj.getLong(key)
                        val orderId = key.toInt()
                        // Firebase 시간이 더 오래됐으면 (=더 정확한 원본) Firebase 우선
                        val existing = orderFirstSeen[orderId]
                        if (existing == null || ts < existing) {
                            orderFirstSeen[orderId] = ts
                            restored++
                        }
                    }
                    if (restored > 0) {
                        Log.d(TAG, "주문 추적 복원 (Firebase): ${restored}건")
                        saveOrderTrackingLocal()  // 로컬에 반영 (Firebase 재업로드 불필요)
                    }
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase 주문 추적 로드 실패: ${e.message}")
        }
    }

    private fun saveOrderTrackingLocal() {
        val obj = JSONObject()
        orderFirstSeen.forEach { (k, v) -> obj.put(k.toString(), v) }
        prefs.edit().putString("order_first_seen", obj.toString()).apply()
    }

    private fun saveOrderTracking() {
        val obj = JSONObject()
        orderFirstSeen.forEach { (k, v) -> obj.put(k.toString(), v) }
        val json = obj.toString()
        prefs.edit().putString("order_first_seen", json).apply()
        // Firebase 동기화 (재설치 대비)
        kotlin.concurrent.thread {
            try {
                val conn = URL("$FIREBASE_BASE/posdelay/order_tracking.json").openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        sseThread?.interrupt()
        sseThread = null
    }

    /** SSE 강제 재연결 + 즉시 1회 조회 */
    fun restart() {
        val ctx = appContext ?: return
        stop()
        Log.d(TAG, "강제 재시작")
        start(ctx)
    }

    /** Firebase에서 KDS 건수 1회 직접 조회 (SSE와 별개) */
    fun fetchOnce() {
        kotlin.concurrent.thread {
            try {
                val conn = URL(FIREBASE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode in 200..299) {
                    val json = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    if (json != "null" && json.isNotEmpty()) {
                        val wrapper = JSONObject("{\"path\":\"/\",\"data\":$json}")
                        handleData(wrapper.toString())
                        Log.d(TAG, "1회 조회 완료")
                    }
                } else {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "1회 조회 실패: ${e.message}")
            }
        }
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
                appContext?.let { FirebaseSettingsSync.uploadLog("[KDS] SSE 연결 성공") }
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
            } else if (count > 0 && ordersArr != null && ordersArr.length() == 0) {
                // 교차검증: count>0인데 orders=[] 빈배열이면 0으로 보정
                // ordersArr==null(필드 없음)은 KDS가 orders를 안 보낸 것이므로 count 신뢰
                Log.d(TAG, "KDS 교차검증: count=$count, orders=[] → 0건 보정")
                0
            } else {
                // 30분 강제보정: orders 없이 건수 변동 없으면 0
                if (count != lastCountValue) {
                    lastCountValue = count
                    lastCountChangeTime = System.currentTimeMillis()
                }
                if (count > 0 && System.currentTimeMillis() - lastCountChangeTime >= FORCE_ZERO_MS) {
                    Log.d(TAG, "KDS 30분 강제보정: count=$count → 0 (변동 없음 ${(System.currentTimeMillis() - lastCountChangeTime) / 60000}분)")
                    lastCountValue = 0
                    lastCountChangeTime = System.currentTimeMillis()
                    0
                } else {
                    count
                }
            }

            Log.d(TAG, "KDS 실시간: count=$count, adjusted=$adjustedCount, time=$timeStr")

            // 건수 변동 시에만 Firebase 로그 (과다호출 방지)
            if (adjustedCount != lastLoggedCount) {
                val ordersStr = if (ordersArr != null && ordersArr.length() > 0) {
                    val list = mutableListOf<Int>()
                    for (i in 0 until ordersArr.length()) list.add(ordersArr.optInt(i))
                    list.toString()
                } else "[]"
                val msg = if (count != adjustedCount) {
                    "[KDS] 건수 $count→$adjustedCount (보정) orders=$ordersStr"
                } else {
                    "[KDS] 건수=$adjustedCount orders=$ordersStr"
                }
                lastLoggedCount = adjustedCount
                appContext?.let { FirebaseSettingsSync.uploadLog(msg) }
            }

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
        appContext?.let { FirebaseSettingsSync.uploadLog("[KDS] SSE 재연결 대기") }
        handler.postDelayed({ connectSSE() }, RECONNECT_DELAY)
    }
}
