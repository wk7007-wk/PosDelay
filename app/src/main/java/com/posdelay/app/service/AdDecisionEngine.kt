package com.posdelay.app.service

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 광고 자동화 의사결정 엔진 (Single Source of Truth = Firebase).
 *
 * 모든 광고 실행 판단은 이 엔진을 통해:
 * 1. Firebase에서 최신 설정 GET
 * 2. 건수 + 설정 → 필요한 액션 결정
 * 3. 현재 상태와 비교 → 변경 필요한 것만 반환
 */
object AdDecisionEngine {

    private const val TAG = "AdDecision"
    private const val FIREBASE_BASE = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val ACTION_COOLDOWN_MS = 5 * 60 * 1000L  // 5분 쿨다운

    // 액션별 마지막 실행 시간 (반대 액션 차단용)
    private var lastCoupangOnTime = 0L
    private var lastCoupangOffTime = 0L
    private var lastBaeminSetTime = 0L
    private var lastBaeminAmount = -1

    /** 광고 실행 완료 시 호출 (쿨다운 기록) */
    fun recordExecution(action: AdAction) {
        val now = System.currentTimeMillis()
        when (action) {
            is AdAction.CoupangOn -> lastCoupangOnTime = now
            is AdAction.CoupangOff -> lastCoupangOffTime = now
            is AdAction.BaeminSetAmount -> { lastBaeminSetTime = now; lastBaeminAmount = action.amount }
        }
    }

    /** 광고 액션 종류 */
    sealed class AdAction {
        data class BaeminSetAmount(val amount: Int) : AdAction()
        object CoupangOn : AdAction()
        object CoupangOff : AdAction()
    }

    /** Firebase에서 직접 읽은 설정 */
    data class Settings(
        val adEnabled: Boolean = false,
        val scheduleEnabled: Boolean = false,
        val adOnTime: String = "08:00",
        val adOffTime: String = "22:00",
        val orderAutoOffEnabled: Boolean = false,
        val coupangAutoEnabled: Boolean = false,
        val baeminAutoEnabled: Boolean = false,
        val baeminAmount: Int = 200,
        val baeminMidAmount: Int = 100,
        val baeminReducedAmount: Int = 50,
        val coupangZones: IntArray = intArrayOf(1,1,1,0,0,2,2,2,2,2,2),
        val baeminZones: IntArray = intArrayOf(1,1,1,0,2,2,0,3,3,3,3)
    )

    /**
     * Firebase에서 최신 설정을 가져와 필요한 액션 목록을 반환.
     * 네트워크 호출이므로 백그라운드 스레드에서 호출할 것.
     *
     * @param count 현재 주문 건수
     * @param currentBaeminBid 현재 배민 입찰 금액 (서버에서 확인된 값)
     * @param currentCoupangOn 현재 쿠팡 광고 상태 (null=모름)
     * @param hasBaemin 배민 로그인 정보 있는지
     * @param hasCoupang 쿠팡 로그인 정보 있는지
     * @return 실행해야 할 액션 목록 (빈 리스트 = 변경 불필요)
     */
    fun evaluate(
        count: Int,
        currentBaeminBid: Int,
        currentCoupangOn: Boolean?,
        hasBaemin: Boolean,
        hasCoupang: Boolean
    ): List<AdAction> {
        val settings = fetchSettings() ?: run {
            Log.w(TAG, "Firebase 설정 조회 실패 — 액션 없음")
            return emptyList()
        }

        if (!settings.adEnabled) return emptyList()
        if (!settings.orderAutoOffEnabled) return emptyList()
        if (settings.scheduleEnabled && !isWithinWindow(settings.adOnTime, settings.adOffTime)) {
            return emptyList()
        }

        val actions = mutableListOf<AdAction>()
        val idx = count.coerceIn(0, 10)
        val now = System.currentTimeMillis()

        // 쿠팡
        if (hasCoupang && settings.coupangAutoEnabled) {
            val cZone = settings.coupangZones.getOrElse(idx) { 0 }
            when {
                cZone == 2 && currentCoupangOn != false -> {
                    // 쿨다운: ON 실행 후 5분 내 OFF 차단
                    if (now - lastCoupangOnTime >= ACTION_COOLDOWN_MS) {
                        actions.add(AdAction.CoupangOff)
                    } else {
                        Log.d(TAG, "쿠팡OFF 쿨다운 (ON후 ${(now - lastCoupangOnTime) / 1000}초)")
                    }
                }
                cZone == 1 && currentCoupangOn == false -> {
                    // 쿨다운: OFF 실행 후 5분 내 ON 차단
                    if (now - lastCoupangOffTime >= ACTION_COOLDOWN_MS) {
                        actions.add(AdAction.CoupangOn)
                    } else {
                        Log.d(TAG, "쿠팡ON 쿨다운 (OFF후 ${(now - lastCoupangOffTime) / 1000}초)")
                    }
                }
            }
        }

        // 배민
        if (hasBaemin && settings.baeminAutoEnabled) {
            val bZone = settings.baeminZones.getOrElse(idx) { 0 }
            val targetAmount = when (bZone) {
                1 -> settings.baeminAmount
                2 -> settings.baeminMidAmount
                3 -> settings.baeminReducedAmount
                else -> null // hold
            }
            if (targetAmount != null && currentBaeminBid != targetAmount) {
                // 쿨다운: 다른 금액으로 변경 후 5분 내 재변경 차단
                if (now - lastBaeminSetTime >= ACTION_COOLDOWN_MS || lastBaeminAmount == -1) {
                    actions.add(AdAction.BaeminSetAmount(targetAmount))
                } else {
                    Log.d(TAG, "배민금액 쿨다운 (${lastBaeminAmount}원 후 ${(now - lastBaeminSetTime) / 1000}초)")
                }
            }
        }

        if (actions.isNotEmpty()) {
            Log.d(TAG, "건수=$count, 액션=${actions.size}개: $actions")
        }
        return actions
    }

    /**
     * 건수 기반 즉각 판단 (게이지 탭 등 수동 실행용).
     * Firebase 조회 없이 인자로 받은 설정 사용.
     */
    fun resolveAmount(count: Int, settings: Settings): Int? {
        val idx = count.coerceIn(0, 10)
        val bZone = settings.baeminZones.getOrElse(idx) { 0 }
        return when (bZone) {
            1 -> settings.baeminAmount
            2 -> settings.baeminMidAmount
            3 -> settings.baeminReducedAmount
            else -> null
        }
    }

    /** Firebase에서 ad_settings.json GET */
    fun fetchSettings(): Settings? {
        return try {
            val conn = URL("$FIREBASE_BASE/posdelay/ad_settings.json").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseSettings(body)
        } catch (e: Exception) {
            Log.w(TAG, "설정 조회 실패: ${e.message}")
            null
        }
    }

    private fun parseSettings(json: String): Settings? {
        if (json == "null" || json.isBlank()) return null
        val obj = JSONObject(json)
        return Settings(
            adEnabled = obj.optBoolean("ad_enabled", false),
            scheduleEnabled = obj.optBoolean("schedule_enabled", false),
            adOnTime = obj.optString("ad_on_time", "08:00"),
            adOffTime = obj.optString("ad_off_time", "22:00"),
            orderAutoOffEnabled = obj.optBoolean("order_auto_off_enabled", false),
            coupangAutoEnabled = obj.optBoolean("coupang_auto_enabled", false),
            baeminAutoEnabled = obj.optBoolean("baemin_auto_enabled", false),
            baeminAmount = obj.optInt("baemin_amount", 200),
            baeminMidAmount = obj.optInt("baemin_mid_amount", 100),
            baeminReducedAmount = obj.optInt("baemin_reduced_amount", 50),
            coupangZones = parseZones(obj, "coupang_zones", intArrayOf(1,1,1,0,0,2,2,2,2,2,2)),
            baeminZones = parseZones(obj, "baemin_zones", intArrayOf(1,1,1,0,2,2,0,3,3,3,3))
        )
    }

    private fun parseZones(obj: JSONObject, key: String, default: IntArray): IntArray {
        val arr = obj.optJSONArray(key) ?: return default.copyOf()
        return IntArray(11) { i -> if (i < arr.length()) arr.optInt(i, 0) else 0 }
    }

    private fun isWithinWindow(onTime: String, offTime: String): Boolean {
        val onMin = timeToMin(onTime) ?: return true
        val offMin = timeToMin(offTime) ?: return true
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (onMin <= offMin) nowMin in onMin until offMin
        else nowMin >= onMin || nowMin < offMin
    }

    private fun timeToMin(t: String): Int? {
        val p = t.split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return h * 60 + m
    }
}
