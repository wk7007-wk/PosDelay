package com.posdelay.app.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KDS 접근성 서비스 (PosKDS에서 이식).
 * 주방폰에서 MATE KDS 앱의 "조리중 N" 건수를 읽어 Firebase에 업로드.
 * 메인폰에서는 활성화할 필요 없음.
 */
class KdsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KdsAccessibility"
        private const val PREFS_NAME = "poskds_prefs"
        private const val KEY_KDS_PACKAGE = "kds_package"
        private const val KEY_LAST_COUNT = "last_count"
        private const val KEY_LAST_UPLOAD_TIME = "last_upload_time"
        private const val KEY_LOG = "kds_log_text"
        private const val KEY_HISTORY = "kds_history"
        private const val HEARTBEAT_MS = 3 * 60 * 1000L
        private const val AUTO_DUMP_MS = 5 * 60 * 1000L
        private const val MAX_LOG_SIZE = 500_000L
        private const val DEFAULT_KDS_PACKAGE = "com.foodtechkorea.mate_kds"

        var logFile: String = "/sdcard/Download/PosDelay_kds_log.txt"
            private set

        var instance: KdsAccessibilityService? = null
            private set

        @JvmField var dumpRequested = false

        fun isAvailable(): Boolean = instance != null
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var lastCount = -1
    private var lastUploadTime = 0L
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    private var eventCount = 0
    private var lastEventLogTime = 0L
    private var lastDumpTime = 0L

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            // 하트비트마다 건수 재추출 (stale 방지)
            val root = try { rootInActiveWindow } catch (_: Exception) { null }
            if (root != null) {
                try {
                    val count = extractCookingCount(root)
                    if (count != null && count != lastCount) {
                        log("하트비트 건수 보정: $lastCount → $count")
                        lastCount = count
                        prefs.edit().putInt(KEY_LAST_COUNT, count).apply()
                        addHistory(count)
                    }
                } catch (_: Exception) {}
                root.recycle()
            }

            val now = System.currentTimeMillis()
            if (now - lastUploadTime >= HEARTBEAT_MS && lastCount >= 0) {
                log("하트비트 업로드 (건수=$lastCount)")
                uploadKdsStatus(lastCount)
                lastUploadTime = now
                prefs.edit().putLong(KEY_LAST_UPLOAD_TIME, now).apply()
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        instance = this
        val extDir = getExternalFilesDir(null)
        if (extDir != null) {
            logFile = "${extDir.absolutePath}/PosDelay_kds_log.txt"
        }
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 기본 KDS 패키지 설정
        if ((prefs.getString(KEY_KDS_PACKAGE, "") ?: "").isEmpty()) {
            prefs.edit().putString(KEY_KDS_PACKAGE, DEFAULT_KDS_PACKAGE).apply()
        }

        lastCount = prefs.getInt(KEY_LAST_COUNT, -1)
        lastUploadTime = prefs.getLong(KEY_LAST_UPLOAD_TIME, 0L)

        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, DEFAULT_KDS_PACKAGE) ?: DEFAULT_KDS_PACKAGE
        log("KDS 접근성 서비스 연결됨, 패키지=$kdsPackage")
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)

        // 포그라운드 서비스 시작 (프로세스 유지)
        KdsKeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val kdsPackage = prefs.getString(KEY_KDS_PACKAGE, DEFAULT_KDS_PACKAGE) ?: DEFAULT_KDS_PACKAGE

        eventCount++
        val now = System.currentTimeMillis()
        if (now - lastEventLogTime > 60_000) {
            log("이벤트 ${eventCount}건 수신, 패키지=$packageName")
            eventCount = 0
            lastEventLogTime = now
        }

        if (kdsPackage.isEmpty()) return
        if (packageName != kdsPackage) return

        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        if (root == null) return

        try {
            // 건수 추출을 먼저 실행 (덤프보다 우선)
            val count = extractCookingCount(root)
            if (count != null && count != lastCount) {
                log("조리중 건수 변경: $lastCount → $count")
                lastCount = count
                prefs.edit().putInt(KEY_LAST_COUNT, count).apply()

                uploadKdsStatus(count)
                lastUploadTime = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_UPLOAD_TIME, lastUploadTime).apply()

                addHistory(count)
            }

            // 수동 덤프 요청 (건수 추출 후 실행)
            if (dumpRequested) {
                dumpRequested = false
                val sb = StringBuilder()
                dumpNode(root, sb, 0)
                val result = sb.toString()
                log("=== KDS UI 트리 덤프 (${result.lines().size}줄) ===\n$result\n=== 덤프 끝 ===")
                uploadKdsDump(result, result.lines().size)
                uploadKdsLog()
            }

            // 5분마다 자동 덤프
            if (now - lastDumpTime >= AUTO_DUMP_MS) {
                lastDumpTime = now
                val sb = StringBuilder()
                dumpNode(root, sb, 0)
                val result = sb.toString()
                uploadKdsDump(result, result.lines().size)
                log("자동 덤프 완료 (${result.lines().size}줄)")
            }
        } catch (e: Exception) {
            log("노드 탐색 실패: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    // === Firebase 업로드 (FirebaseSettingsSync 경유) ===

    private fun uploadKdsStatus(count: Int) {
        FirebaseSettingsSync.uploadKdsStatus(count)
        uploadKdsLog()
    }

    private fun uploadKdsLog() {
        val logContent = try {
            val f = File(logFile)
            if (f.exists()) f.readLines().takeLast(100).joinToString("\n") else ""
        } catch (_: Exception) { "" }
        if (logContent.isNotEmpty()) {
            FirebaseSettingsSync.uploadKdsLog(logContent)
        }
    }

    private fun uploadKdsDump(tree: String, lineCount: Int) {
        FirebaseSettingsSync.uploadKdsDump(tree, lineCount)
    }

    // === 건수 이력 ===

    private fun addHistory(count: Int) {
        try {
            val historyJson = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
            val arr = try { JSONArray(historyJson) } catch (_: Exception) { JSONArray() }
            arr.put(JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("count", count)
            })
            while (arr.length() > 100) arr.remove(0)
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
            FirebaseSettingsSync.uploadKdsHistory(arr)
        } catch (e: Exception) {
            log("이력 기록 실패: ${e.message}")
        }
    }

    // === 건수 추출 ===

    private fun extractCookingCount(root: AccessibilityNodeInfo): Int? {
        val nodes = root.findAccessibilityNodeInfosByText("조리중")
        if (nodes != null && nodes.isNotEmpty()) {
            for (node in nodes) {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                // "조리중 3", "조리중\n3", "조리중3" 패턴 (개행 포함)
                val match = Regex("조리중[\\s\\n]*(\\d+)").find(text)
                    ?: Regex("조리중[\\s\\n]*(\\d+)").find(desc)
                if (match != null) return match.groupValues[1].toIntOrNull()
            }
            // "조리중" 텍스트는 있지만 숫자 없음 → 0건
            return 0
        }
        val treeResult = findCookingCountInTree(root)
        if (treeResult != null) return treeResult

        // "조리할 주문이 없습니다" → 0건
        val noOrderNodes = root.findAccessibilityNodeInfosByText("조리할 주문이 없습니다")
        if (noOrderNodes != null && noOrderNodes.isNotEmpty()) return 0

        // "주문수" 뒤 숫자 추출 (메뉴별 수량 화면)
        return findOrderCountInTree(root)
    }

    private fun findOrderCountInTree(node: AccessibilityNodeInfo, depth: Int = 0): Int? {
        if (depth > 15) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val combined = "$text $desc"
        val match = Regex("주문수[\\s\\n]*(\\d+)").find(combined)
        if (match != null) return match.groupValues[1].toIntOrNull()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findOrderCountInTree(child, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun findNumberInSiblings(parent: AccessibilityNodeInfo): Int? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val text = child.text?.toString()?.trim() ?: ""
            if (text.matches(Regex("\\d+"))) {
                val num = text.toIntOrNull()
                child.recycle()
                if (num != null && num in 0..99) return num
            }
            child.recycle()
        }
        return null
    }

    private fun findCookingCountInTree(node: AccessibilityNodeInfo, depth: Int = 0): Int? {
        if (depth > 15) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val match = Regex("조리중\\s*(\\d+)").find(text)
            ?: Regex("조리중\\s*(\\d+)").find(desc)
        if (match != null) return match.groupValues[1].toIntOrNull()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findCookingCountInTree(child, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    fun dumpTree(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: return "root 없음"
        val sb = StringBuilder()
        dumpNode(root, sb, 0)
        root.recycle()
        val result = sb.toString()
        log("=== UI 트리 덤프 (${result.lines().size}줄) ===\n$result\n=== 덤프 끝 ===")
        uploadKdsDump(result, result.lines().size)
        uploadKdsLog()
        return result
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 10) return
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
            sb.append("$indent[$cls] ")
            if (id.isNotEmpty()) sb.append("id=$id ")
            if (text.isNotEmpty()) sb.append("t=\"$text\" ")
            if (desc.isNotEmpty()) sb.append("d=\"$desc\" ")
            sb.append("\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, sb, depth + 1)
            child.recycle()
        }
    }

    private fun log(message: String) {
        val time = dateFormat.format(Date())
        val entry = "[$time] $message"
        Log.d(TAG, entry)

        if (::prefs.isInitialized) {
            val existing = prefs.getString(KEY_LOG, "") ?: ""
            val lines = existing.split("\n").takeLast(50)
            val updated = (lines + entry).joinToString("\n")
            prefs.edit().putString(KEY_LOG, updated).apply()
        }

        try {
            val file = File(logFile)
            if (file.length() > MAX_LOG_SIZE) {
                val keep = file.readText().takeLast(MAX_LOG_SIZE.toInt() / 2)
                file.writeText(keep)
            }
            FileWriter(file, true).use { it.write("$entry\n") }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {
        log("KDS 접근성 서비스 중단됨")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(heartbeatRunnable)
        instance = null
    }
}
