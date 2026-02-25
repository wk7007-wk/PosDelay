package com.posdelay.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.posdelay.app.data.AdManager
import com.posdelay.app.data.OrderTracker
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PosDelay 설정/상태를 Firebase에 양방향 동기화.
 * - 앱→Firebase: 설정 변경 시 업로드
 * - Firebase→앱: SSE로 웹에서 변경한 설정 수신
 */
object FirebaseSettingsSync {

    private const val TAG = "FirebaseSettingsSync"
    private const val FIREBASE_BASE = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"
    // RECONNECT_DELAY는 MIN/MAX_RECONNECT_MS + 지수 백오프로 대체

    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    private var running = false
    private var sseThread: Thread? = null
    private var commandSseThread: Thread? = null
    @Volatile private var sseConnection: HttpURLConnection? = null
    @Volatile private var commandSseConnection: HttpURLConnection? = null
    @Volatile private var sseEpoch = 0  // restart 레이스 컨디션 방지
    @Volatile private var cmdEpoch = 0
    private var appContext: Context? = null
    @Volatile private var settingsVersion = 0L
    private var settingsReconnectDelay = 10_000L
    private var commandReconnectDelay = 10_000L
    private const val MIN_RECONNECT_MS = 10_000L
    private const val MAX_RECONNECT_MS = 60_000L

    // 원격 적용 중 무한루프 방지 플래그
    @Volatile var isApplyingRemote = false
        private set

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        running = true
        uploadStatus()
        val se = ++sseEpoch
        val ce = ++cmdEpoch
        connectSettingsSSE(se)
        connectCommandSSE(ce)
        Log.d(TAG, "Firebase 설정 동기화 시작")
    }

    fun stop() {
        running = false
        sseEpoch++
        cmdEpoch++
        handler.removeCallbacksAndMessages(null)
        try { sseConnection?.disconnect() } catch (_: Exception) {}
        try { commandSseConnection?.disconnect() } catch (_: Exception) {}
        sseConnection = null
        commandSseConnection = null
        sseThread?.interrupt()
        sseThread = null
        commandSseThread?.interrupt()
        commandSseThread = null
    }

    /** SSE 강제 재연결 */
    fun restart() {
        val ctx = appContext ?: return
        stop()
        settingsReconnectDelay = MIN_RECONNECT_MS
        commandReconnectDelay = MIN_RECONNECT_MS
        Log.d(TAG, "강제 재시작")
        start(ctx)
    }

    /** 설정 변경 시 호출 (AdManager setter에서) — Firebase 업로드 안 함
     *  설정은 웹→Firebase→SSE→앱 단방향. 앱은 읽기만. */
    fun onSettingsChanged() {
        // 업로드 안 함 — 설정은 웹에서만 Firebase에 저장
    }

    /** 주문 건수 변경 시 호출 */
    fun onOrderCountChanged() {
        uploadStatus()
    }

    /** 광고 상태 변경 시 호출 */
    fun onAdStateChanged() {
        uploadAdState()
    }

    /** 로그 추가 시 호출 */
    fun uploadLog(logEntry: String) {
        kotlin.concurrent.thread {
            try {
                // 기존 로그 읽기
                val existing = firebaseGet("$FIREBASE_BASE/posdelay/logs.json")
                val arr = try {
                    if (existing != null && existing != "null") org.json.JSONArray(existing)
                    else org.json.JSONArray()
                } catch (_: Exception) { org.json.JSONArray() }

                arr.put(JSONObject().apply {
                    put("time", dateFormat.format(Date()))
                    put("msg", logEntry)
                })
                // 최근 50건
                while (arr.length() > 50) arr.remove(0)
                firebasePut("$FIREBASE_BASE/posdelay/logs.json", arr.toString())
            } catch (e: Exception) {
                Log.w(TAG, "로그 업로드 에러: ${e.message}")
            }
        }
    }

    private fun uploadStatus() {
        kotlin.concurrent.thread {
            try {
                val json = JSONObject().apply {
                    put("order_count", OrderTracker.getOrderCount())
                    put("enabled", OrderTracker.isEnabled())
                    put("auto_mode", OrderTracker.isAutoMode())
                    put("kds_paused", OrderTracker.isKdsPaused())
                    put("kds_sync", OrderTracker.getLastKdsSyncTime())
                    put("time", dateFormat.format(Date()))
                }.toString()
                firebasePut("$FIREBASE_BASE/posdelay/status.json", json)
            } catch (e: Exception) {
                Log.w(TAG, "상태 업로드 에러: ${e.message}")
            }
        }
    }

    private fun uploadAdState() {
        kotlin.concurrent.thread {
            try {
                val json = JSONObject().apply {
                    put("baemin_current_bid", AdManager.getBaeminCurrentBid())
                    put("coupang_current_on", AdManager.coupangCurrentOn.value)
                    put("last_ad_action", AdManager.lastAdAction.value ?: "")
                    put("time", dateFormat.format(Date()))
                }.toString()
                firebasePut("$FIREBASE_BASE/posdelay/ad_state.json", json)
            } catch (e: Exception) {
                Log.w(TAG, "광고 상태 업로드 에러: ${e.message}")
            }
        }
    }

    // === KDS 업로드 (KdsAccessibilityService에서 호출) ===

    fun uploadKdsStatus(count: Int) {
        kotlin.concurrent.thread {
            try {
                val json = JSONObject().apply {
                    put("count", count)
                    put("time", dateFormat.format(Date()))
                    put("source", "kds")
                }.toString()
                firebasePut("$FIREBASE_BASE/kds_status.json", json)
            } catch (e: Exception) {
                Log.w(TAG, "KDS 상태 업로드 에러: ${e.message}")
            }
        }
    }

    fun uploadKdsLog(logContent: String) {
        kotlin.concurrent.thread {
            try {
                firebasePut("$FIREBASE_BASE/kds_log.json", "\"${escapeJson(logContent)}\"")
            } catch (e: Exception) {
                Log.w(TAG, "KDS 로그 업로드 에러: ${e.message}")
            }
        }
    }

    fun uploadKdsDump(tree: String, lineCount: Int) {
        kotlin.concurrent.thread {
            try {
                val json = JSONObject().apply {
                    put("tree", tree)
                    put("time", dateFormat.format(Date()))
                    put("lines", lineCount)
                }.toString()
                firebasePut("$FIREBASE_BASE/kds_dump.json", json)
            } catch (e: Exception) {
                Log.w(TAG, "KDS 덤프 업로드 에러: ${e.message}")
            }
        }
    }

    fun uploadKdsHistory(entries: JSONArray) {
        kotlin.concurrent.thread {
            try {
                val json = JSONObject().apply {
                    put("entries", entries)
                }.toString()
                firebasePut("$FIREBASE_BASE/kds_history.json", json)
            } catch (e: Exception) {
                Log.w(TAG, "KDS 이력 업로드 에러: ${e.message}")
            }
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")

    // === SSE: Firebase → 앱 (웹에서 설정 변경 수신) ===

    private fun connectSettingsSSE(epoch: Int) {
        if (!running || epoch != sseEpoch) return
        sseThread = kotlin.concurrent.thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL("$FIREBASE_BASE/posdelay/ad_settings.json").openConnection() as HttpURLConnection
                sseConnection = conn
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 15000
                conn.readTimeout = 5 * 60 * 1000  // 5분

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    sseConnection = null
                    scheduleSettingsReconnect(epoch)
                    return@thread
                }

                settingsReconnectDelay = MIN_RECONNECT_MS  // 연결 성공 → 백오프 리셋
                Log.d(TAG, "설정 SSE 연결 성공")
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var eventType = ""

                while (running && epoch == sseEpoch) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> eventType = line.substringAfter("event:").trim()
                        line.startsWith("data:") -> {
                            val data = line.substringAfter("data:").trim()
                            if (eventType == "put" || eventType == "patch") {
                                handleRemoteSettings(data)
                            }
                        }
                    }
                }

                reader.close()
                conn.disconnect()
                sseConnection = null
            } catch (e: InterruptedException) {
                try { conn?.disconnect() } catch (_: Exception) {}
                sseConnection = null
                return@thread
            } catch (e: java.net.SocketTimeoutException) {
                Log.d(TAG, "설정 SSE 타임아웃 → 재연결")
                try { conn?.disconnect() } catch (_: Exception) {}
                sseConnection = null
            } catch (e: Exception) {
                Log.w(TAG, "설정 SSE 에러: ${e.message}")
                try { conn?.disconnect() } catch (_: Exception) {}
                sseConnection = null
            }
            scheduleSettingsReconnect(epoch)
        }
    }

    private fun handleRemoteSettings(raw: String) {
        try {
            val wrapper = JSONObject(raw)
            val path = wrapper.optString("path", "/")
            val data = wrapper.opt("data")

            // Firebase 비어있으면 무시
            if (data == null || data.toString() == "null") return

            if (path == "/") {
                // SSE 초기 로드 → 무시 (로컬이 최우선, 과거 데이터 덮어쓰기 방지)
                Log.d(TAG, "SSE 초기 로드 → 무시 (로컬 우선)")
                return
            }

            // ★ 부분 업데이트 (path="/baemin_amount" 등) → 웹에서 명시적 변경만 적용
            val obj = JSONObject()
            val key = path.removePrefix("/")
            obj.put(key, data)

            // 메타 키(_version, _updated_by 등)는 무시
            if (key.startsWith("_")) return

            Log.d(TAG, "웹에서 설정 변경: $key=$data")
            appendLog("[Settings] 웹 변경: $key=$data")

            isApplyingRemote = true
            handler.post {
                applySettings(obj)
                Log.d(TAG, "웹 설정 적용: $key")
                // 500ms 디바운스 + 여유 → 1초 뒤 플래그 해제 (재업로드 방지)
                handler.postDelayed({ isApplyingRemote = false }, 1000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "원격 설정 파싱 실패: ${e.message}")
        }
    }

    private fun applySettings(obj: JSONObject) {
        if (obj.has("baemin_amount")) AdManager.setBaeminAmount(obj.getInt("baemin_amount"))
        if (obj.has("baemin_reduced_amount")) AdManager.setBaeminReducedAmount(obj.getInt("baemin_reduced_amount"))
        if (obj.has("baemin_mid_amount")) AdManager.setBaeminMidAmount(obj.getInt("baemin_mid_amount"))
        if (obj.has("coupang_ad_on")) AdManager.setCoupangAdOn(obj.getBoolean("coupang_ad_on"))
        if (obj.has("schedule_enabled")) AdManager.setScheduleEnabled(obj.getBoolean("schedule_enabled"), fromRemote = true)
        if (obj.has("ad_off_time")) AdManager.setAdOffTime(obj.getString("ad_off_time"))
        if (obj.has("ad_on_time")) AdManager.setAdOnTime(obj.getString("ad_on_time"))
        if (obj.has("order_auto_off_enabled")) AdManager.setOrderAutoOffEnabled(obj.getBoolean("order_auto_off_enabled"), fromRemote = true)
        if (obj.has("coupang_auto_enabled")) AdManager.setCoupangAutoEnabled(obj.getBoolean("coupang_auto_enabled"), fromRemote = true)
        if (obj.has("baemin_auto_enabled")) {
            val remote = obj.getBoolean("baemin_auto_enabled")
            val local = AdManager.isBaeminAutoEnabled()
            if (remote != local) appendLog("[Settings] baemin_auto: 원격=$remote 로컬=$local guarded=${AdManager.isGuarded("baemin_auto_enabled")}")
            AdManager.setBaeminAutoEnabled(remote, fromRemote = true)
        }
        if (obj.has("coupang_zones")) AdManager.setZonesFromJson("coupang", obj.getJSONArray("coupang_zones"))
        if (obj.has("baemin_zones")) AdManager.setZonesFromJson("baemin", obj.getJSONArray("baemin_zones"))
        if (obj.has("ad_enabled")) AdManager.setAdEnabled(obj.getBoolean("ad_enabled"), fromRemote = true)
        if (obj.has("delay_minutes")) OrderTracker.setDelayMinutes(obj.getInt("delay_minutes"))
        if (obj.has("baemin_target_time")) AdManager.setBaeminTargetTime(obj.getInt("baemin_target_time"))
        if (obj.has("baemin_fixed_cook_time")) AdManager.setBaeminFixedCookTime(obj.getInt("baemin_fixed_cook_time"))
        if (obj.has("baemin_delay_threshold")) AdManager.setBaeminDelayThreshold(obj.getInt("baemin_delay_threshold"))
        if (obj.has("coupang_target_time")) AdManager.setCoupangTargetTime(obj.getInt("coupang_target_time"))
        if (obj.has("coupang_fixed_cook_time")) AdManager.setCoupangFixedCookTime(obj.getInt("coupang_fixed_cook_time"))
        if (obj.has("coupang_delay_threshold")) AdManager.setCoupangDelayThreshold(obj.getInt("coupang_delay_threshold"))
    }

    private fun scheduleSettingsReconnect(epoch: Int) {
        if (!running || epoch != sseEpoch) return
        AnomalyDetector.recordSseReconnect("settings")
        val delay = settingsReconnectDelay
        settingsReconnectDelay = (settingsReconnectDelay * 2).coerceAtMost(MAX_RECONNECT_MS)
        handler.postDelayed({ connectSettingsSSE(epoch) }, delay)
    }

    // === 웹 명령 SSE (웹 UI → Firebase → 앱 실행) ===

    private fun connectCommandSSE(epoch: Int) {
        if (!running || epoch != cmdEpoch) return
        commandSseThread = kotlin.concurrent.thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL("$FIREBASE_BASE/posdelay/command.json").openConnection() as HttpURLConnection
                commandSseConnection = conn
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 15000
                conn.readTimeout = 5 * 60 * 1000

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    commandSseConnection = null
                    scheduleCommandReconnect(epoch)
                    return@thread
                }

                commandReconnectDelay = MIN_RECONNECT_MS
                Log.d(TAG, "명령 SSE 연결 성공")
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var eventType = ""

                while (running && epoch == cmdEpoch) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> eventType = line.substringAfter("event:").trim()
                        line.startsWith("data:") -> {
                            val data = line.substringAfter("data:").trim()
                            if (eventType == "put" || eventType == "patch") {
                                handleCommand(data)
                            }
                        }
                    }
                }

                reader.close()
                conn.disconnect()
                commandSseConnection = null
            } catch (e: InterruptedException) {
                try { conn?.disconnect() } catch (_: Exception) {}
                commandSseConnection = null
                return@thread
            } catch (e: java.net.SocketTimeoutException) {
                Log.d(TAG, "명령 SSE 타임아웃 → 재연결")
                try { conn?.disconnect() } catch (_: Exception) {}
                commandSseConnection = null
            } catch (e: Exception) {
                Log.w(TAG, "명령 SSE 에러: ${e.message}")
                try { conn?.disconnect() } catch (_: Exception) {}
                commandSseConnection = null
            }
            scheduleCommandReconnect(epoch)
        }
    }

    private fun handleCommand(raw: String) {
        try {
            val wrapper = JSONObject(raw)
            val path = wrapper.optString("path", "/")
            val data = wrapper.opt("data") ?: return
            if (data.toString() == "null") return  // 삭제 이벤트 무시
            if (path != "/") return  // 부분 업데이트 무시

            val obj = if (data is JSONObject) data else JSONObject(data.toString())
            val action = obj.optString("action", "")
            val amount = obj.optInt("amount", 0)
            val time = obj.optLong("time", 0)
            val source = obj.optString("source", "")

            if (action.isEmpty() || source != "web") return

            val now = System.currentTimeMillis()
            if (now - time > 60_000) {
                Log.d(TAG, "오래된 명령 무시: $action (${(now - time) / 1000}초 전)")
                deleteCommand()
                return
            }

            Log.d(TAG, "웹 명령 수신: $action amount=$amount")
            deleteCommand()  // 즉시 삭제 (재실행 방지)

            when (action) {
                "TOGGLE_KDS" -> handler.post {
                    OrderTracker.setKdsPaused(!OrderTracker.isKdsPaused())
                    onOrderCountChanged()
                }
                else -> {
                    // 광고 실행 명령 → MainActivity 필요
                    val ctx = appContext ?: return
                    val intent = android.content.Intent(ctx, com.posdelay.app.ui.MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("remote_ad_command", action)
                        putExtra("remote_ad_amount", amount)
                    }
                    ctx.startActivity(intent)
                }
            }

            uploadLog("[웹명령] $action${if (amount > 0) " ${amount}원" else ""}")
        } catch (e: Exception) {
            Log.w(TAG, "명령 파싱 실패: ${e.message}")
        }
    }

    private fun deleteCommand() {
        kotlin.concurrent.thread {
            try {
                firebasePut("$FIREBASE_BASE/posdelay/command.json", "null")
            } catch (e: Exception) {
                Log.w(TAG, "명령 삭제 실패: ${e.message}")
            }
        }
    }

    private fun scheduleCommandReconnect(epoch: Int) {
        if (!running || epoch != cmdEpoch) return
        AnomalyDetector.recordSseReconnect("command")
        val delay = commandReconnectDelay
        commandReconnectDelay = (commandReconnectDelay * 2).coerceAtMost(MAX_RECONNECT_MS)
        handler.postDelayed({ connectCommandSSE(epoch) }, delay)
    }

    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 1000L

    private fun firebasePut(url: String, json: String) {
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return
                Log.w(TAG, "Firebase PUT 실패: HTTP $code ($url) 시도${attempt + 1}")
            } catch (e: Exception) {
                Log.w(TAG, "Firebase PUT 에러: ${e.message} 시도${attempt + 1}")
            }
            if (attempt < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS)
        }
    }

    private fun firebasePatch(url: String, json: String) {
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return
                Log.w(TAG, "Firebase PATCH 실패: HTTP $code ($url) 시도${attempt + 1}")
            } catch (e: Exception) {
                Log.w(TAG, "Firebase PATCH 에러: ${e.message} 시도${attempt + 1}")
            }
            if (attempt < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS)
        }
    }

    private fun firebaseGet(url: String): String? {
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                return try {
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.bufferedReader().readText()
                    } else {
                        Log.w(TAG, "Firebase GET 실패: HTTP ${conn.responseCode} 시도${attempt + 1}")
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firebase GET 에러: ${e.message} 시도${attempt + 1}")
            }
            if (attempt < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS)
        }
        return null
    }

    private fun appendLog(msg: String) {
        Log.d(TAG, msg)
        kotlin.concurrent.thread {
            try {
                val obj = JSONObject()
                obj.put("msg", msg)
                obj.put("time", dateFormat.format(Date()))
                obj.put("ts", System.currentTimeMillis())
                val conn = URL("$FIREBASE_BASE/posdelay/logs.json").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                OutputStreamWriter(conn.outputStream).use { it.write(obj.toString()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }
}
