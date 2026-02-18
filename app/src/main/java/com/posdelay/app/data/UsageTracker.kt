package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences

object UsageTracker {

    private const val PREFS_NAME = "usage_tracker"
    private lateinit var prefs: SharedPreferences

    // 기능 카테고리 키
    const val AUTO_THRESHOLD_COUPANG = "auto_th_coupang"   // 밀림시 쿠팡 임계값 조정
    const val AUTO_THRESHOLD_BAEMIN = "auto_th_baemin"     // 밀림시 배민 임계값 조정
    const val SCHEDULE_TIME = "schedule_time"               // 스케줄 시간 변경
    const val MANUAL_BAEMIN_NORMAL = "manual_baemin_normal" // 수동: 배민 정상
    const val MANUAL_BAEMIN_REDUCED = "manual_baemin_reduced" // 수동: 배민 축소
    const val MANUAL_COUPANG_ON = "manual_coupang_on"       // 수동: 쿠팡 켜기
    const val MANUAL_COUPANG_OFF = "manual_coupang_off"     // 수동: 쿠팡 끄기
    const val AMOUNT_BAEMIN = "amount_baemin"               // 배민 금액 설정
    const val AMOUNT_BAEMIN_REDUCED = "amount_baemin_reduced" // 배민 축소금액 설정
    const val COUPANG_AD_TOGGLE = "coupang_ad_toggle"       // 쿠팡 광고 켜기/끄기
    const val OPEN_BAEMIN = "open_baemin"                   // 배민 직접열기
    const val OPEN_COUPANG = "open_coupang"                 // 쿠팡 직접열기
    const val LOGIN_BAEMIN = "login_baemin"                 // 배민 로그인
    const val LOGIN_COUPANG = "login_coupang"               // 쿠팡 로그인
    const val MODE_SWITCH = "mode_switch"                   // 모드 전환
    const val ORDER_COUNT = "order_count"                   // 건수 +/-
    const val DELAY_SETTING = "delay_setting"               // 지연시간 설정
    const val DELAY_THRESHOLD = "delay_threshold"           // 지연 임계값
    const val RESET_COUNT = "reset_count"                   // 초기화
    const val MANUAL_DELAY = "manual_delay"                 // 수동 지연
    const val VIEW_LOG = "view_log"                         // 로그 보기
    const val AD_LOG = "ad_log"                             // 광고 로그

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun track(key: String) {
        if (!::prefs.isInitialized) return
        val count = prefs.getInt(key, 0)
        prefs.edit().putInt(key, count + 1).apply()
    }

    fun getCount(key: String): Int {
        if (!::prefs.isInitialized) return 0
        return prefs.getInt(key, 0)
    }

    /** 전체 사용 통계를 빈도 내림차순으로 반환 */
    fun getStats(): List<Pair<String, Int>> {
        if (!::prefs.isInitialized) return emptyList()
        val all = prefs.all
        return all.entries
            .filter { it.value is Int }
            .map { it.key to (it.value as Int) }
            .sortedByDescending { it.second }
    }

    /** 사람이 읽을 수 있는 카테고리 이름 */
    fun displayName(key: String): String = when (key) {
        AUTO_THRESHOLD_COUPANG -> "밀림시 쿠팡 임계값"
        AUTO_THRESHOLD_BAEMIN -> "밀림시 배민 임계값"
        SCHEDULE_TIME -> "스케줄 시간"
        MANUAL_BAEMIN_NORMAL -> "수동: 배민 정상"
        MANUAL_BAEMIN_REDUCED -> "수동: 배민 축소"
        MANUAL_COUPANG_ON -> "수동: 쿠팡 켜기"
        MANUAL_COUPANG_OFF -> "수동: 쿠팡 끄기"
        AMOUNT_BAEMIN -> "배민 금액 설정"
        AMOUNT_BAEMIN_REDUCED -> "배민 축소금액 설정"
        COUPANG_AD_TOGGLE -> "쿠팡 광고 토글"
        OPEN_BAEMIN -> "배민 직접열기"
        OPEN_COUPANG -> "쿠팡 직접열기"
        LOGIN_BAEMIN -> "배민 로그인"
        LOGIN_COUPANG -> "쿠팡 로그인"
        MODE_SWITCH -> "모드 전환"
        ORDER_COUNT -> "건수 +/-"
        DELAY_SETTING -> "지연시간 설정"
        DELAY_THRESHOLD -> "지연 임계값"
        RESET_COUNT -> "초기화"
        MANUAL_DELAY -> "수동 지연"
        VIEW_LOG -> "로그 보기"
        AD_LOG -> "광고 로그"
        else -> key
    }

    /** 포맷된 통계 리포트 */
    fun getReport(): String {
        val stats = getStats()
        if (stats.isEmpty()) return "사용 기록 없음"
        val total = stats.sumOf { it.second }
        val sb = StringBuilder()
        sb.append("=== 기능 사용 빈도 ===\n")
        sb.append("총 사용: ${total}회\n\n")
        for ((key, count) in stats) {
            val pct = if (total > 0) (count * 100 / total) else 0
            val bar = "█".repeat((pct / 5).coerceIn(0, 20))
            sb.append("${displayName(key)}: ${count}회 (${pct}%) $bar\n")
        }
        return sb.toString()
    }

    fun clear() {
        if (!::prefs.isInitialized) return
        prefs.edit().clear().apply()
    }
}
