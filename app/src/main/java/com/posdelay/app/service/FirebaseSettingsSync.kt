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
    private const val RECONNECT_DELAY = 10_000L

    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    private var running = false
    private var sseThread: Thread? = null
    @Volatile private var settingsVersion = 0L  // Bug 8: 멀티스레드 접근

    // 원격 적용 중 무한루프 방지 플래그
    @Volatile var isApplyingRemote = false
        private set

    fun start(context: Context) {
        if (running) return
        running = true
        // 초기 업로드
        uploadAllSettings()
        uploadStatus()
        // SSE 리스너 시작 (설정 변경 수신)
        connectSettingsSSE()
        Log.d(TAG, "Firebase 설정 동기화 시작")
    }

    fun stop() {
        running = false
        sseThread?.interrupt()
        sseThread = null
    }

    /** 설정 변경 시 호출 (AdManager setter에서) */
    fun onSettingsChanged() {
        if (isApplyingRemote) return
        uploadAllSettings()
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

    private fun uploadAllSettings() {
        kotlin.concurrent.thread {
            try {
                settingsVersion++
                val json = JSONObject().apply {
                    put("ad_enabled", AdManager.isAdEnabled())
                    put("baemin_amount", AdManager.getBaeminAmount())
                    put("baemin_reduced_amount", AdManager.getBaeminReducedAmount())
                    put("baemin_mid_amount", AdManager.getBaeminMidAmount())
                    put("coupang_ad_on", AdManager.isCoupangAdOn())
                    put("schedule_enabled", AdManager.isScheduleEnabled())
                    put("ad_off_time", AdManager.getAdOffTime())
                    put("ad_on_time", AdManager.getAdOnTime())
                    put("order_auto_off_enabled", AdManager.isOrderAutoOffEnabled())
                    put("coupang_auto_enabled", AdManager.isCoupangAutoEnabled())
                    put("baemin_auto_enabled", AdManager.isBaeminAutoEnabled())
                    put("coupang_zones", AdManager.getZonesJson("coupang"))
                    put("baemin_zones", AdManager.getZonesJson("baemin"))
                    put("delay_minutes", OrderTracker.getDelayMinutes())
                    put("baemin_target_time", AdManager.getBaeminTargetTime())
                    put("baemin_fixed_cook_time", AdManager.getBaeminFixedCookTime())
                    put("baemin_delay_threshold", AdManager.getBaeminDelayThreshold())
                    put("coupang_target_time", AdManager.getCoupangTargetTime())
                    put("coupang_fixed_cook_time", AdManager.getCoupangFixedCookTime())
                    put("coupang_delay_threshold", AdManager.getCoupangDelayThreshold())
                    put("_version", settingsVersion)
                    put("_updated_by", "app")
                    put("_updated_at", dateFormat.format(Date()))
                }.toString()
                firebasePut("$FIREBASE_BASE/posdelay/ad_settings.json", json)
            } catch (e: Exception) {
                Log.w(TAG, "설정 업로드 에러: ${e.message}")
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
                    put("mate_paused", OrderTracker.isMatePaused())
                    put("pc_paused", OrderTracker.isPcPaused())
                    put("kds_paused", OrderTracker.isKdsPaused())
                    put("kds_sync", OrderTracker.getLastKdsSyncTime())
                    put("mate_sync", OrderTracker.getLastSyncTime())
                    put("pc_sync", OrderTracker.getLastPcSyncTime())
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

    private fun connectSettingsSSE() {
        if (!running) return
        sseThread = kotlin.concurrent.thread {
            try {
                val conn = URL("$FIREBASE_BASE/posdelay/ad_settings.json").openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 15000
                conn.readTimeout = 5 * 60 * 1000  // Bug 6: 5분 무응답 시 타임아웃 (staleness 방지)

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    scheduleReconnect()
                    return@thread
                }

                Log.d(TAG, "설정 SSE 연결 성공")
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var eventType = ""

                while (running) {
                    val line = reader.readLine() ?: break  // readTimeout 초과 시 null → 재연결
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
                Log.d(TAG, "설정 SSE 스트림 종료 (재연결)")
            } catch (e: InterruptedException) {
                return@thread
            } catch (e: java.net.SocketTimeoutException) {
                Log.d(TAG, "설정 SSE 타임아웃 (5분 무응답) → 재연결")  // Bug 6
            } catch (e: Exception) {
                Log.w(TAG, "설정 SSE 에러: ${e.message}")
            }
            scheduleReconnect()
        }
    }

    private fun handleRemoteSettings(raw: String) {
        try {
            val wrapper = JSONObject(raw)
            val path = wrapper.optString("path", "/")
            val data = wrapper.opt("data") ?: return
            if (data.toString() == "null") return

            val obj: JSONObject
            if (path == "/") {
                // 전체 설정 (초기 로드 또는 전체 PUT)
                obj = if (data is JSONObject) data else JSONObject(data.toString())
            } else {
                // 부분 업데이트 (PATCH) — path="/baemin_amount" 등
                obj = JSONObject()
                val key = path.removePrefix("/")
                obj.put(key, data)
            }

            val updatedBy = obj.optString("_updated_by", "app")
            if (updatedBy == "app") return

            val remoteVersion = obj.optLong("_version", 0)
            if (path == "/" && remoteVersion <= settingsVersion) return

            Log.d(TAG, "웹에서 설정 변경 수신 (path=$path)")

            handler.post {
                isApplyingRemote = true
                try {
                    applySettings(obj)
                    if (remoteVersion > 0) settingsVersion = remoteVersion
                    Log.d(TAG, "웹 설정 적용 완료")
                } finally {
                    isApplyingRemote = false
                }
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
        if (obj.has("schedule_enabled")) AdManager.setScheduleEnabled(obj.getBoolean("schedule_enabled"))
        if (obj.has("ad_off_time")) AdManager.setAdOffTime(obj.getString("ad_off_time"))
        if (obj.has("ad_on_time")) AdManager.setAdOnTime(obj.getString("ad_on_time"))
        if (obj.has("order_auto_off_enabled")) AdManager.setOrderAutoOffEnabled(obj.getBoolean("order_auto_off_enabled"))
        if (obj.has("coupang_auto_enabled")) AdManager.setCoupangAutoEnabled(obj.getBoolean("coupang_auto_enabled"))
        if (obj.has("baemin_auto_enabled")) AdManager.setBaeminAutoEnabled(obj.getBoolean("baemin_auto_enabled"))
        if (obj.has("coupang_zones")) AdManager.setZonesFromJson("coupang", obj.getJSONArray("coupang_zones"))
        if (obj.has("baemin_zones")) AdManager.setZonesFromJson("baemin", obj.getJSONArray("baemin_zones"))
        if (obj.has("ad_enabled")) AdManager.setAdEnabled(obj.getBoolean("ad_enabled"))
        if (obj.has("delay_minutes")) OrderTracker.setDelayMinutes(obj.getInt("delay_minutes"))
        if (obj.has("baemin_target_time")) AdManager.setBaeminTargetTime(obj.getInt("baemin_target_time"))
        if (obj.has("baemin_fixed_cook_time")) AdManager.setBaeminFixedCookTime(obj.getInt("baemin_fixed_cook_time"))
        if (obj.has("baemin_delay_threshold")) AdManager.setBaeminDelayThreshold(obj.getInt("baemin_delay_threshold"))
        if (obj.has("coupang_target_time")) AdManager.setCoupangTargetTime(obj.getInt("coupang_target_time"))
        if (obj.has("coupang_fixed_cook_time")) AdManager.setCoupangFixedCookTime(obj.getInt("coupang_fixed_cook_time"))
        if (obj.has("coupang_delay_threshold")) AdManager.setCoupangDelayThreshold(obj.getInt("coupang_delay_threshold"))
    }

    private fun scheduleReconnect() {
        if (!running) return
        handler.postDelayed({ connectSettingsSSE() }, RECONNECT_DELAY)
    }

    private fun firebasePut(url: String, json: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(json) }
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) {
            Log.w(TAG, "Firebase PUT 실패: HTTP $code ($url)")
        }
    }

    private fun firebaseGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else null
        } finally {
            conn.disconnect()
        }
    }
}
