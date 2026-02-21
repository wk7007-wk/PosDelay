package com.posdelay.app.service

import android.util.Log

/**
 * 이상신호 감지 모듈.
 * 중복 실행, 단시간 과다 호출, SSE 연결 문제 등을 감지하여
 * Firebase에 알림 업로드 → 웹 대시보드 + 클로드 분석용.
 *
 * 감지 항목:
 * 1. 동일 광고 액션 단시간 반복 (30초 내 같은 액션 2회 이상)
 * 2. 단시간 과다 호출 (5분 내 광고 실행 5회 이상)
 * 3. 연속 실패 (같은 액션 3회 연속 실패)
 * 4. SSE 빈번한 재연결 (10분 내 5회 이상)
 */
object AnomalyDetector {

    private const val TAG = "AnomalyDetector"

    // 광고 실행 이력: (액션명, 시각, 성공여부)
    private data class ExecRecord(val action: String, val time: Long, val success: Boolean)
    private val execHistory = ArrayList<ExecRecord>()
    private val MAX_HISTORY = 50

    // SSE 재연결 이력
    private val sseReconnects = ArrayList<Long>()

    // 알림 중복 방지: 같은 종류 알림 5분 쿨다운
    private val lastAlertTime = HashMap<String, Long>()
    private const val ALERT_COOLDOWN = 5 * 60 * 1000L

    /** 광고 액션 실행 기록 */
    fun recordExecution(action: String, success: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(execHistory) {
            execHistory.add(ExecRecord(action, now, success))
            if (execHistory.size > MAX_HISTORY) execHistory.removeAt(0)
        }

        checkDuplicateAction(action, now)
        checkExcessiveExecution(now)
        if (!success) checkConsecutiveFailures(action)
    }

    /** SSE 재연결 기록 */
    fun recordSseReconnect(sseType: String) {
        val now = System.currentTimeMillis()
        synchronized(sseReconnects) {
            sseReconnects.add(now)
            // 1시간 이전 기록 제거
            sseReconnects.removeAll { now - it > 60 * 60 * 1000L }
        }
        checkFrequentReconnects(sseType, now)
    }

    /** 1. 동일 액션 30초 내 반복 */
    private fun checkDuplicateAction(action: String, now: Long) {
        synchronized(execHistory) {
            val recent = execHistory.filter { it.action == action && now - it.time < 30_000 }
            if (recent.size >= 2) {
                alert("duplicate_action",
                    "동일 액션 반복: $action ${recent.size}회/30초")
            }
        }
    }

    /** 2. 5분 내 과다 실행 */
    private fun checkExcessiveExecution(now: Long) {
        synchronized(execHistory) {
            val recent = execHistory.filter { now - it.time < 5 * 60 * 1000L }
            if (recent.size >= 5) {
                val actions = recent.groupBy { it.action }.map { "${it.key}=${it.value.size}" }
                alert("excessive_exec",
                    "과다 실행: ${recent.size}회/5분 [${actions.joinToString(", ")}]")
            }
        }
    }

    /** 3. 같은 액션 연속 실패 */
    private fun checkConsecutiveFailures(action: String) {
        synchronized(execHistory) {
            val tail = execHistory.filter { it.action == action }.takeLast(3)
            if (tail.size >= 3 && tail.all { !it.success }) {
                alert("consecutive_fail",
                    "연속 실패: $action ${tail.size}회")
            }
        }
    }

    /** 4. SSE 빈번한 재연결 */
    private fun checkFrequentReconnects(sseType: String, now: Long) {
        synchronized(sseReconnects) {
            val recent = sseReconnects.filter { now - it < 10 * 60 * 1000L }
            if (recent.size >= 5) {
                alert("sse_reconnect",
                    "SSE 빈번한 재연결: $sseType ${recent.size}회/10분")
            }
        }
    }

    /** Firebase에 이상신호 업로드 */
    private fun alert(type: String, message: String) {
        val now = System.currentTimeMillis()
        val last = lastAlertTime[type] ?: 0L
        if (now - last < ALERT_COOLDOWN) return
        lastAlertTime[type] = now

        Log.w(TAG, "⚠ 이상신호: $message")
        FirebaseSettingsSync.uploadLog("[이상신호] $message")

        // Firebase alert 노드에 별도 업로드 (웹 대시보드 표시용)
        kotlin.concurrent.thread {
            try {
                val json = org.json.JSONObject().apply {
                    put("type", type)
                    put("message", message)
                    put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date()))
                    put("timestamp", now)
                }.toString()
                val conn = java.net.URL("https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/posdelay/alert.json")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "PUT"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                java.io.OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    /** 현재 이상신호 요약 (디버깅용) */
    fun getSummary(): String {
        val now = System.currentTimeMillis()
        synchronized(execHistory) {
            val recent5m = execHistory.filter { now - it.time < 5 * 60 * 1000L }
            val fails = recent5m.count { !it.success }
            return "5분내: ${recent5m.size}건 (실패 ${fails}건), SSE재연결: ${sseReconnects.filter { now - it < 10 * 60 * 1000L }.size}회"
        }
    }
}
