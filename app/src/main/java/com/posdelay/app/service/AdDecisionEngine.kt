package com.posdelay.app.service

import android.util.Log
import com.posdelay.app.data.AdManager

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
    private const val ACTION_COOLDOWN_MS = 1 * 60 * 1000L  // 1분 쿨다운

    // 액션별 마지막 실행 시간 (반대 액션 차단용)
    private var lastCoupangOnTime = 0L
    private var lastCoupangOffTime = 0L
    private var lastBaeminSetTime = 0L
    private var lastBaeminAmount = -1

    // 쿨다운 대기열: 쿨타임 차단된 액션을 큐에 보관, 쿨타임 후 실행
    private data class PendingAction(val action: AdAction, val executeAfter: Long)
    private val pendingQueue = mutableListOf<PendingAction>()

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
        val settings = fetchSettings()

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
                        val executeAfter = lastCoupangOnTime + ACTION_COOLDOWN_MS
                        enqueuePending(AdAction.CoupangOff, executeAfter)
                        Log.d(TAG, "쿠팡OFF 대기열 (ON후 ${(now - lastCoupangOnTime) / 1000}초, ${(executeAfter - now) / 1000}초 후 실행)")
                        com.posdelay.app.data.LogFileWriter.append("AD", "쿠팡OFF→대기열 (${(executeAfter - now) / 1000}초 후)")
                    }
                }
                cZone == 1 && currentCoupangOn == false -> {
                    // 쿨다운: OFF 실행 후 5분 내 ON 차단
                    if (now - lastCoupangOffTime >= ACTION_COOLDOWN_MS) {
                        actions.add(AdAction.CoupangOn)
                    } else {
                        val executeAfter = lastCoupangOffTime + ACTION_COOLDOWN_MS
                        enqueuePending(AdAction.CoupangOn, executeAfter)
                        Log.d(TAG, "쿠팡ON 대기열 (OFF후 ${(now - lastCoupangOffTime) / 1000}초, ${(executeAfter - now) / 1000}초 후 실행)")
                        com.posdelay.app.data.LogFileWriter.append("AD", "쿠팡ON→대기열 (${(executeAfter - now) / 1000}초 후)")
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
                    val executeAfter = lastBaeminSetTime + ACTION_COOLDOWN_MS
                    enqueuePending(AdAction.BaeminSetAmount(targetAmount), executeAfter)
                    Log.d(TAG, "배민${targetAmount}원 대기열 (${lastBaeminAmount}원 후 ${(now - lastBaeminSetTime) / 1000}초, ${(executeAfter - now) / 1000}초 후)")
                    com.posdelay.app.data.LogFileWriter.append("AD", "배민${targetAmount}원→대기열 (${(executeAfter - now) / 1000}초 후)")
                }
            }
        }

        if (actions.isNotEmpty()) {
            Log.d(TAG, "건수=$count, 액션=${actions.size}개: $actions")
        }
        return actions
    }

    /** 대기열에 액션 추가 (동일 타입 중복 제거) */
    private fun enqueuePending(action: AdAction, executeAfter: Long) {
        // 동일 타입 기존 항목 제거 (최신 것만 유지)
        pendingQueue.removeAll { isSameActionType(it.action, action) }
        pendingQueue.add(PendingAction(action, executeAfter))
    }

    private fun isSameActionType(a: AdAction, b: AdAction): Boolean = when {
        a is AdAction.CoupangOn && b is AdAction.CoupangOn -> true
        a is AdAction.CoupangOff && b is AdAction.CoupangOff -> true
        a is AdAction.BaeminSetAmount && b is AdAction.BaeminSetAmount -> true
        else -> false
    }

    /**
     * 대기열에서 실행 시간이 된 액션 꺼내기.
     * 현재 설정과 상태에 맞는지 재검증 후 반환.
     */
    fun drainPendingQueue(
        count: Int,
        currentBaeminBid: Int,
        currentCoupangOn: Boolean?,
        hasBaemin: Boolean,
        hasCoupang: Boolean
    ): List<AdAction> {
        val now = System.currentTimeMillis()
        val ready = pendingQueue.filter { it.executeAfter <= now }
        if (ready.isEmpty()) return emptyList()

        pendingQueue.removeAll(ready)

        // 현재 설정으로 재검증
        val settings = fetchSettings()
        if (!settings.adEnabled || !settings.orderAutoOffEnabled) return emptyList()
        if (settings.scheduleEnabled && !isWithinWindow(settings.adOnTime, settings.adOffTime)) return emptyList()

        val idx = count.coerceIn(0, 10)
        val valid = mutableListOf<AdAction>()

        for (pa in ready) {
            when (pa.action) {
                is AdAction.CoupangOff -> {
                    if (!hasCoupang || !settings.coupangAutoEnabled) continue
                    val cZone = settings.coupangZones.getOrElse(idx) { 0 }
                    if (cZone == 2 && currentCoupangOn != false) {
                        valid.add(pa.action)
                        Log.d(TAG, "대기열→쿠팡OFF 실행 (재검증OK, zone=$cZone)")
                    } else {
                        Log.d(TAG, "대기열→쿠팡OFF 무효 (zone=$cZone, on=$currentCoupangOn)")
                        com.posdelay.app.data.LogFileWriter.append("AD", "대기열→쿠팡OFF 무효화 (zone=$cZone)")
                    }
                }
                is AdAction.CoupangOn -> {
                    if (!hasCoupang || !settings.coupangAutoEnabled) continue
                    val cZone = settings.coupangZones.getOrElse(idx) { 0 }
                    if (cZone == 1 && currentCoupangOn == false) {
                        valid.add(pa.action)
                        Log.d(TAG, "대기열→쿠팡ON 실행 (재검증OK, zone=$cZone)")
                    } else {
                        Log.d(TAG, "대기열→쿠팡ON 무효 (zone=$cZone, on=$currentCoupangOn)")
                        com.posdelay.app.data.LogFileWriter.append("AD", "대기열→쿠팡ON 무효화 (zone=$cZone)")
                    }
                }
                is AdAction.BaeminSetAmount -> {
                    if (!hasBaemin || !settings.baeminAutoEnabled) continue
                    val bZone = settings.baeminZones.getOrElse(idx) { 0 }
                    val targetAmount = when (bZone) {
                        1 -> settings.baeminAmount
                        2 -> settings.baeminMidAmount
                        3 -> settings.baeminReducedAmount
                        else -> null
                    }
                    if (targetAmount != null && currentBaeminBid != targetAmount) {
                        valid.add(AdAction.BaeminSetAmount(targetAmount))
                        Log.d(TAG, "대기열→배민${targetAmount}원 실행 (재검증OK, zone=$bZone)")
                    } else {
                        Log.d(TAG, "대기열→배민 무효 (zone=$bZone, target=$targetAmount, bid=$currentBaeminBid)")
                        com.posdelay.app.data.LogFileWriter.append("AD", "대기열→배민 무효화 (zone=$bZone)")
                    }
                }
            }
        }
        return valid
    }

    /** 대기열에서 가장 빠른 실행 시각 (스케줄링용). 없으면 0 */
    fun getNextPendingTime(): Long {
        return pendingQueue.minOfOrNull { it.executeAfter } ?: 0L
    }

    /** 대기열 비우기 (앱 상태 변경 시) */
    fun clearPendingQueue() {
        pendingQueue.clear()
    }

    /** 대기열 크기 */
    fun pendingCount(): Int = pendingQueue.size

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

    /** SharedPreferences(AdManager)에서 직접 읽기 — Firebase 조회 제거 (로컬 최우선) */
    fun fetchSettings(): Settings {
        return Settings(
            adEnabled = AdManager.isAdEnabled(),
            scheduleEnabled = AdManager.isScheduleEnabled(),
            adOnTime = AdManager.getAdOnTime(),
            adOffTime = AdManager.getAdOffTime(),
            orderAutoOffEnabled = AdManager.isOrderAutoOffEnabled(),
            coupangAutoEnabled = AdManager.isCoupangAutoEnabled(),
            baeminAutoEnabled = AdManager.isBaeminAutoEnabled(),
            baeminAmount = AdManager.getBaeminAmount(),
            baeminMidAmount = AdManager.getBaeminMidAmount(),
            baeminReducedAmount = AdManager.getBaeminReducedAmount(),
            coupangZones = AdManager.getZones("coupang"),
            baeminZones = AdManager.getZones("baemin")
        )
    }

    /** 쿨다운 종료 절대 타임스탬프 반환 (웹 게이지용) */
    fun getEndTimestamps(): Triple<Long, Long, Long> {
        val coupangOnEnd = if (lastCoupangOnTime > 0) lastCoupangOnTime + ACTION_COOLDOWN_MS else 0L
        val coupangOffEnd = if (lastCoupangOffTime > 0) lastCoupangOffTime + ACTION_COOLDOWN_MS else 0L
        val baeminEnd = if (lastBaeminSetTime > 0) lastBaeminSetTime + ACTION_COOLDOWN_MS else 0L
        return Triple(coupangOnEnd, coupangOffEnd, baeminEnd)
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
