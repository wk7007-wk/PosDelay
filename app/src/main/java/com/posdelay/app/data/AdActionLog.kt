package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AdActionLog {

    private const val PREFS_NAME = "ad_action_log"
    private const val KEY_LOG = "log_entries"
    private const val MAX_ENTRIES = 200

    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    private val _logs = MutableLiveData<List<String>>(emptyList())
    val logs: LiveData<List<String>> = _logs

    /** 에러 코드 정의 */
    object Code {
        const val OK = "OK"
        const val ERR_NO_CREDENTIALS = "E01"  // 로그인 정보 없음
        const val ERR_PAGE_LOAD = "E02"        // 페이지 로드 실패
        const val ERR_NO_LOGIN_FORM = "E03"    // 로그인 폼 못찾음
        const val ERR_LOGIN_FAILED = "E04"     // 로그인 실패 (여전히 login 페이지)
        const val ERR_NO_AD_FIELD = "E05"      // 광고 금액 필드 못찾음
        const val ERR_NO_TOGGLE = "E06"        // 토글 스위치 못찾음
        const val ERR_TIMEOUT = "E07"          // 시간 초과
        const val ERR_ALREADY_RUNNING = "E08"  // 이미 진행 중
        const val ERR_WEBVIEW = "E09"          // WebView 오류
        const val ERR_JS_EXEC = "E10"          // JS 실행 오류
        const val INFO_STATE = "I01"           // 상태 변경
        const val INFO_PAGE = "I02"            // 페이지 로드 완료
        const val INFO_JS_RESULT = "I03"       // JS 실행 결과
        const val INFO_SCHEDULE = "I04"        // 스케줄 관련
        const val INFO_ORDER = "I05"           // 주문 임계값 관련
        const val INFO_HTML = "H01"            // HTML 스냅샷

        fun describe(code: String): String = when (code) {
            OK -> "성공"
            ERR_NO_CREDENTIALS -> "로그인 정보 없음"
            ERR_PAGE_LOAD -> "페이지 로드 실패"
            ERR_NO_LOGIN_FORM -> "로그인 폼 못찾음"
            ERR_LOGIN_FAILED -> "로그인 실패"
            ERR_NO_AD_FIELD -> "광고 입력 필드 못찾음"
            ERR_NO_TOGGLE -> "토글 스위치 못찾음"
            ERR_TIMEOUT -> "시간 초과"
            ERR_ALREADY_RUNNING -> "이미 실행 중"
            ERR_WEBVIEW -> "WebView 오류"
            ERR_JS_EXEC -> "JS 실행 오류"
            INFO_STATE -> "상태 변경"
            INFO_PAGE -> "페이지 로드"
            INFO_JS_RESULT -> "JS 결과"
            INFO_SCHEDULE -> "스케줄"
            INFO_ORDER -> "주문 감지"
            INFO_HTML -> "HTML 스냅샷"
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
        val isError = code.startsWith("E")
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

    /** HTML 스냅샷 저장 (디버깅용, 최대 500자) */
    fun addHtmlSnapshot(platform: String, url: String, html: String) {
        val snippet = html.take(500).replace("\n", " ").replace("\r", "")
        add(Code.INFO_HTML, platform, "URL: $url\nHTML: $snippet")
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

    fun clear() {
        prefs.edit().remove(KEY_LOG).apply()
        _logs.postValue(emptyList())
    }
}
