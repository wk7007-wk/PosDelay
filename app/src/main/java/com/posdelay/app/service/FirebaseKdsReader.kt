package com.posdelay.app.service

import android.content.Context
import android.content.SharedPreferences
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
    private const val STALE_ORDER_MS = 25 * 60 * 1000L  // 25분
    private const val PREFS_NAME = "kds_order_tracking"
    private const val MIN_RECONNECT_MS = 5_000L
    private const val MAX_RECONNECT_MS = 60_000L

    private var running = false
    private var appContext: Context? = null
    private var sseThread: Thread? = null
    @Volatile private var sseConnection: HttpURLConnection? = null
    @Volatile private var sseEpoch = 0  // restart 레이스 컨디션 방지
    @Volatile var lastConnectTime = 0L  // 네트워크 콜백에서 연결 상태 확인용
        private set
    private var reconnectDelay = MIN_RECONNECT_MS  // 지수 백오프
    private lateinit var prefs: SharedPreferences

    // 주문번호별 최초 등장 시간 추적 (SharedPreferences에 영구 저장)
    private val orderFirstSeen = HashMap<Int, Long>()
    /** 현재 주문별 최초 등장 시간 (네이티브 조리 알림용) */
    fun getOrderTimestamps(): Map<Int, Long> = synchronized(orderFirstSeen) { HashMap(orderFirstSeen) }

    private var lastLoggedCount = -1  // Firebase 로그 중복 방지
    private var lastCountChangeTime = System.currentTimeMillis()  // 30분 강제보정용
    private var lastCountValue = -1
    private const val FORCE_ZERO_MS = 30 * 60 * 1000L  // 30분

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val epoch = ++sseEpoch
        kotlin.concurrent.thread {
            loadOrderTracking()
            if (epoch == sseEpoch) connectSSE(epoch)
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
                        val existing = orderFirstSeen[orderId]
                        if (existing == null || ts < existing) {
                            orderFirstSeen[orderId] = ts
                            restored++
                        }
                    }
                    if (restored > 0) {
                        Log.d(TAG, "주문 추적 복원 (Firebase): ${restored}건")
                        saveOrderTrackingLocal()
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
        sseEpoch++  // 이전 epoch의 reconnect 무효화
        try { sseConnection?.disconnect() } catch (_: Exception) {}
        sseConnection = null
        sseThread?.interrupt()
        sseThread = null
    }

    /** SSE 강제 재연결 */
    fun restart() {
        val ctx = appContext ?: return
        stop()
        reconnectDelay = MIN_RECONNECT_MS  // 백오프 리셋
        Log.d(TAG, "강제 재시작")
        start(ctx)
    }

    /** 현재 SSE가 살아있는지 (네트워크 콜백에서 확인용) */
    fun isConnected(): Boolean = sseConnection != null && running

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

    private fun connectSSE(epoch: Int) {
        if (!running || epoch != sseEpoch) return
        sseThread = kotlin.concurrent.thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(FIREBASE_URL).openConnection() as HttpURLConnection
                sseConnection = conn
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 15000
                conn.readTimeout = 5 * 60 * 1000  // 5분 (Firebase keepalive ~30초)

                if (conn.responseCode != 200) {
                    Log.w(TAG, "SSE 연결 실패: HTTP ${conn.responseCode}")
                    conn.disconnect()
                    sseConnection = null
                    scheduleReconnect(epoch)
                    return@thread
                }

                lastConnectTime = System.currentTimeMillis()
                reconnectDelay = MIN_RECONNECT_MS  // 연결 성공 → 백오프 리셋
                Log.d(TAG, "SSE 연결 성공")
                com.posdelay.app.data.LogFileWriter.append("KDS", "SSE 연결 성공")
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var eventType = ""

                while (running && epoch == sseEpoch) {
                    val line = reader.readLine() ?: break

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
                sseConnection = null
                if (epoch == sseEpoch) {
                    Log.d(TAG, "SSE 스트림 종료")
                }
            } catch (e: InterruptedException) {
                try { conn?.disconnect() } catch (_: Exception) {}
                sseConnection = null
                return@thread
            } catch (e: java.net.SocketTimeoutException) {
                Log.d(TAG, "SSE 타임아웃 (5분 무응답) → 재연결")
                try { conn?.disconnect() } catch (_: Exception) {}
                sseConnection = null
            } catch (e: Exception) {
                Log.w(TAG, "SSE 에러: ${e.message}")
                try { conn?.disconnect() } catch (_: Exception) {}
                sseConnection = null
            }

            scheduleReconnect(epoch)
        }
    }

    private fun handleData(raw: String) {
        try {
            val wrapper = JSONObject(raw)
            val data = wrapper.opt("data") ?: return
            if (data.toString() == "null") return

            val obj = if (data is JSONObject) data else JSONObject(data.toString())
            val count = obj.optInt("count", -1)
            val timeStr = obj.optString("time", "")

            if (count < 0) return

            // orders 배열 파싱
            val ordersArr = obj.optJSONArray("orders")
            val orders = if (ordersArr != null) {
                (0 until ordersArr.length()).map { ordersArr.optInt(it) }
            } else emptyList()

            processKdsCount(count, orders, timeStr, "sse")
        } catch (e: Exception) {
            Log.w(TAG, "데이터 파싱 실패: ${e.message}")
        }
    }

    /**
     * KDS 건수 처리 공통 로직. SSE/FCM 모두 이 메서드를 호출.
     * 25분 필터, 30분 강제보정, 로깅, OrderTracker 반영.
     */
    fun processKdsCount(count: Int, orders: List<Int>, timeStr: String, source: String = "sse") {
        try {
            if (!OrderTracker.isEnabled()) return
            if (OrderTracker.isKdsPaused()) return

            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val kdsTime = if (timeStr.isNotEmpty()) {
                try { sdf.parse(timeStr)?.time ?: System.currentTimeMillis() }
                catch (_: Exception) { System.currentTimeMillis() }
            } else System.currentTimeMillis()

            // orders → 25분 초과 주문 제외
            val adjustedCount = if (orders.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val currentOrders = HashSet(orders)
                synchronized(orderFirstSeen) {
                    orderFirstSeen.keys.retainAll(currentOrders)
                    for (orderId in currentOrders) {
                        orderFirstSeen.putIfAbsent(orderId, now)
                    }
                    val staleCount = orderFirstSeen.count { now - it.value > STALE_ORDER_MS }
                    val filtered = maxOf(0, currentOrders.size - staleCount)
                    if (staleCount > 0) {
                        val staleOrders = orderFirstSeen.filter { now - it.value > STALE_ORDER_MS }.keys
                        Log.d(TAG, "[$source] 25분초과 제외: ${staleOrders} → 건수 $count→$filtered")
                    }
                    saveOrderTracking()
                    filtered
                }
            } else if (count > 0 && orders.isEmpty()) {
                // 탭 건수(조리중 N) 신뢰 — 주문번호 추출 실패(rootInActiveWindow 문제)일 수 있음
                Log.d(TAG, "[$source] count=$count, orders=[] → 탭건수 신뢰")
                count
            } else {
                // count=0 + orders=[] → 주문 추적 맵 정리
                if (count == 0) {
                    synchronized(orderFirstSeen) {
                        if (orderFirstSeen.isNotEmpty()) {
                            orderFirstSeen.clear()
                            saveOrderTracking()
                        }
                    }
                }
                if (count != lastCountValue) {
                    lastCountValue = count
                    lastCountChangeTime = System.currentTimeMillis()
                }
                if (count > 0 && System.currentTimeMillis() - lastCountChangeTime >= FORCE_ZERO_MS) {
                    Log.d(TAG, "[$source] 30분 강제보정: count=$count → 0 (변동 없음 ${(System.currentTimeMillis() - lastCountChangeTime) / 60000}분)")
                    lastCountValue = 0
                    lastCountChangeTime = System.currentTimeMillis()
                    0
                } else {
                    count
                }
            }

            Log.d(TAG, "[$source] KDS: count=$count, adjusted=$adjustedCount, time=$timeStr")

            // 건수 변동 시에만 로그 (과다호출 방지)
            if (adjustedCount != lastLoggedCount) {
                com.posdelay.app.data.LogFileWriter.append("KDS", "[$source] 건수=$adjustedCount" + if (count != adjustedCount) " (원본=$count 보정)" else "")
                val ordersStr = if (orders.isNotEmpty()) orders.toString() else "[]"
                val msg = if (count != adjustedCount) {
                    "[KDS/$source] 건수 $count→$adjustedCount (보정) orders=$ordersStr"
                } else {
                    "[KDS/$source] 건수=$adjustedCount orders=$ordersStr"
                }
                lastLoggedCount = adjustedCount
                appContext?.let { FirebaseSettingsSync.uploadLog(msg) }
            }

            // handler.post 사용 금지 — Doze에서 밀리고 복귀 시 몰림
            OrderTracker.syncKdsOrderCount(adjustedCount, kdsTime)
            appContext?.let { DelayNotificationHelper.update(it) }
        } catch (e: Exception) {
            Log.w(TAG, "[$source] 건수 처리 실패: ${e.message}")
        }
    }

    private fun scheduleReconnect(epoch: Int) {
        if (!running || epoch != sseEpoch) return
        AnomalyDetector.recordSseReconnect("kds")
        com.posdelay.app.data.LogFileWriter.append("KDS", "SSE 재연결 ${reconnectDelay / 1000}초후")
        Log.d(TAG, "SSE 재연결 ${reconnectDelay / 1000}초 후...")
        val delay = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_MS)  // 지수 백오프
        // handler.postDelayed 대신 독립 스레드 — Doze에서 밀리지 않음
        kotlin.concurrent.thread {
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return@thread }
            if (running && epoch == sseEpoch) connectSSE(epoch)
        }
    }
}
