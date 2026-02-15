package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DelayActionLog {

    private const val PREFS_NAME = "delay_action_log"
    private const val KEY_LOG = "log_entries"
    private const val MAX_ENTRIES = 200

    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    private val _logs = MutableLiveData<List<String>>(emptyList())
    val logs: LiveData<List<String>> = _logs

    /** 에러 코드 정의 */
    object Code {
        const val OK = "OK"
        const val ERR_NO_SERVICE = "D01"       // 접근성 서비스 비활성화
        const val ERR_NO_TAB = "D02"           // 주문 관리 탭 못찾음
        const val ERR_NO_ORDER = "D03"         // 주문 카드 못찾음
        const val ERR_NO_DELAY_BTN = "D04"     // 준비 지연 버튼 못찾음
        const val ERR_TIME_ADJUST = "D05"      // 시간 조절 실패
        const val ERR_NO_CONFIRM = "D06"       // 확인 버튼 못찾음
        const val ERR_TIMEOUT = "D07"          // 시간 초과
        const val ERR_NO_COOK_BTN = "D08"      // 조리시간 추가 못찾음
        const val ERR_NO_POPUP = "D09"         // 팝업 못찾음
        const val ERR_NO_ROOT = "D10"          // rootNode 없음
        const val ERR_APP_LAUNCH = "D11"       // 앱 실행 실패
        const val INFO_STATE = "DI01"          // 상태(step) 변경
        const val INFO_COUNT = "DI02"          // 처리중 건수 감지
        const val INFO_CLICK = "DI03"          // 버튼 클릭 성공
        const val INFO_DONE = "DI04"           // 지연 완료
        const val INFO_TRIGGER = "DI05"        // 트리거 발동
        const val INFO_SCROLL = "DI06"         // 스크롤 수행
        const val INFO_UI = "DI07"             // UI 요소 감지

        fun describe(code: String): String = when (code) {
            OK -> "성공"
            ERR_NO_SERVICE -> "접근성 서비스 비활성화"
            ERR_NO_TAB -> "주문 관리 탭 못찾음"
            ERR_NO_ORDER -> "주문 카드 못찾음"
            ERR_NO_DELAY_BTN -> "준비 지연 버튼 못찾음"
            ERR_TIME_ADJUST -> "시간 조절 실패"
            ERR_NO_CONFIRM -> "확인 버튼 못찾음"
            ERR_TIMEOUT -> "시간 초과"
            ERR_NO_COOK_BTN -> "조리시간 추가 못찾음"
            ERR_NO_POPUP -> "팝업 못찾음"
            ERR_NO_ROOT -> "rootNode 없음"
            ERR_APP_LAUNCH -> "앱 실행 실패"
            INFO_STATE -> "단계 변경"
            INFO_COUNT -> "건수 감지"
            INFO_CLICK -> "클릭 성공"
            INFO_DONE -> "지연 완료"
            INFO_TRIGGER -> "트리거"
            INFO_SCROLL -> "스크롤"
            INFO_UI -> "UI 감지"
            else -> code
        }
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _logs.postValue(getLogs())
    }

    /** 로그 추가: [코드] 플랫폼 | 메시지 */
    fun add(code: String, platform: String, message: String) {
        val time = dateFormat.format(Date())
        val isError = code.startsWith("D0") || code.startsWith("D1")
        val prefix = if (isError) "!!" else "  "
        val entry = "$prefix[$time] [$code] $platform | $message"

        val current = getLogs().toMutableList()
        current.add(0, entry)
        if (current.size > MAX_ENTRIES) {
            current.subList(MAX_ENTRIES, current.size).clear()
        }

        prefs.edit().putString(KEY_LOG, current.joinToString("\n===\n")).apply()
        _logs.postValue(current)
    }

    fun getLogs(): List<String> {
        val raw = prefs.getString(KEY_LOG, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("\n===\n")
    }

    /** 에러 로그만 필터 */
    fun getErrorLogs(): List<String> {
        return getLogs().filter { it.trimStart().startsWith("!!") }
    }

    /** 플랫폼별 필터 */
    fun getLogsByPlatform(platform: String): List<String> {
        return getLogs().filter { it.contains(platform) }
    }

    fun clear() {
        prefs.edit().remove(KEY_LOG).apply()
        _logs.postValue(emptyList())
    }

    /** 에러 통계 */
    fun getErrorStats(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        val codeRegex = Regex("""\[(D\d+)\]""")
        for (log in getLogs()) {
            val match = codeRegex.find(log) ?: continue
            val code = match.groupValues[1]
            stats[code] = (stats[code] ?: 0) + 1
        }
        return stats
    }
}
