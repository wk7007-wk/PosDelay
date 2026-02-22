package com.posdelay.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.posdelay.app.data.DelayActionLog
import com.posdelay.app.data.DelayActionLog.Code
import com.posdelay.app.data.OrderTracker

class DelayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DelayAccessibility"
        const val MATE_PACKAGE = "com.foodtechkorea.posboss"
        const val COUPANG_EATS_PACKAGE = "com.coupang.mobile.eats.merchant"
        private var instance: DelayAccessibilityService? = null
        private var pendingCoupangDelay = false
        private var pendingBaeminDelay = false

        fun triggerCoupangDelay(context: Context) {
            log(Code.INFO_TRIGGER, "쿠팡", "쿠팡이츠 지연 트리거 발동")
            pendingCoupangDelay = true
            instance?.resetCoupangState()
            val launchIntent = context.packageManager.getLaunchIntentForPackage(COUPANG_EATS_PACKAGE)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                log(Code.INFO_TRIGGER, "쿠팡", "쿠팡이츠 앱 실행")
            } else {
                log(Code.ERR_APP_LAUNCH, "쿠팡", "쿠팡이츠 앱을 찾을 수 없음 (패키지: $COUPANG_EATS_PACKAGE)")
            }
        }

        fun triggerBaeminDelay(context: Context) {
            log(Code.INFO_TRIGGER, "배민", "배민 지연 트리거 발동 (MATE 앱)")
            pendingBaeminDelay = true
            instance?.resetBaeminState()
            val launchIntent = context.packageManager.getLaunchIntentForPackage(MATE_PACKAGE)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                log(Code.INFO_TRIGGER, "배민", "MATE 앱 실행")
            } else {
                log(Code.ERR_APP_LAUNCH, "배민", "MATE 앱을 찾을 수 없음 (패키지: $MATE_PACKAGE)")
            }
        }

        fun triggerDelay(context: Context) = triggerCoupangDelay(context)

        fun isAvailable(): Boolean = instance != null

        private fun log(code: String, platform: String, message: String) {
            Log.d(TAG, "[$code] $platform | $message")
            try {
                DelayActionLog.add(code, platform, message)
            } catch (_: Exception) {
                // DelayActionLog 미초기화 시 무시
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stepBusy = false // step 처리 중 중복 방지
    private var mateInForeground = false
    private val MATE_POLL_INTERVAL = 15_000L  // 15초마다 MATE 화면 폴링

    private val matePollRunnable = object : Runnable {
        override fun run() {
            if (!mateInForeground) return
            if (!OrderTracker.isEnabled()) return
            // MATE가 아직 포그라운드인지 확인
            val root = rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString()
                if (pkg != MATE_PACKAGE) {
                    mateInForeground = false
                    root.recycle()
                    return
                }
                root.recycle()
            }
            readMateOrderCount()
            handler.postDelayed(this, MATE_POLL_INTERVAL)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log(Code.INFO_STATE, "시스템", "접근성 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when (pkg) {
            MATE_PACKAGE -> {
                if (!mateInForeground) {
                    mateInForeground = true
                    handler.removeCallbacks(matePollRunnable)
                    handler.postDelayed(matePollRunnable, MATE_POLL_INTERVAL)
                    log(Code.INFO_STATE, "MATE", "포그라운드 감지 → 15초 폴링 시작")
                }
                readMateOrderCount()
                if (pendingBaeminDelay && !stepBusy) handleBaeminDelay()
            }
            COUPANG_EATS_PACKAGE -> {
                mateInForeground = false
                handler.removeCallbacks(matePollRunnable)
                if (pendingCoupangDelay && !stepBusy) handleCoupangDelay()
            }
            else -> {
                mateInForeground = false
                handler.removeCallbacks(matePollRunnable)
            }
        }
    }

    // ===== MATE 사장님: 처리중 건수 읽기 =====

    private fun readMateOrderCount() {
        if (pendingBaeminDelay) return
        if (OrderTracker.isMatePaused()) return
        // KDS가 활성 + 최근 5분 이내 데이터면 MATE 읽기 스킵 (KDS 우선)
        if (!OrderTracker.isKdsPaused()) {
            val kdsAge = System.currentTimeMillis() - OrderTracker.getLastKdsSyncTime()
            if (kdsAge < 5 * 60 * 1000L && OrderTracker.getLastKdsSyncTime() > 0) return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            val count = findProcessingCount(rootNode)
            if (count != null) {
                val current = OrderTracker.getOrderCount()
                // MATE 건수 읽힘 → 동기화 시간 갱신 + 자동 복귀 트리거
                OrderTracker.syncOrderCount(count)
                GistOrderReader.onMateDataRead()
                if (count != current) {
                    log(Code.INFO_COUNT, "MATE", "처리중 건수 변경: $current → $count")
                    DelayNotificationHelper.update(applicationContext)

                    if (OrderTracker.shouldDelayCoupang()) {
                        if (OrderTracker.isAutoMode()) {
                            log(Code.INFO_TRIGGER, "쿠팡", "자동 모드: 쿠팡 지연 트리거 (처리중 ${count}건)")
                            DelayNotificationHelper.notifyDelayTriggered(applicationContext, "쿠팡이츠", auto = true)
                            triggerCoupangDelay(applicationContext)
                        } else {
                            log(Code.INFO_TRIGGER, "쿠팡", "반자동 모드: 쿠팡 지연 알림만 (처리중 ${count}건)")
                            DelayNotificationHelper.notifyDelayTriggered(applicationContext, "쿠팡이츠", auto = false)
                        }
                    }
                    if (OrderTracker.shouldDelayBaemin()) {
                        if (OrderTracker.isAutoMode()) {
                            log(Code.INFO_TRIGGER, "배민", "자동 모드: 배민 지연 트리거 (처리중 ${count}건)")
                            DelayNotificationHelper.notifyDelayTriggered(applicationContext, "배달의민족", auto = true)
                            pendingBaeminDelay = true
                        } else {
                            log(Code.INFO_TRIGGER, "배민", "반자동 모드: 배민 지연 알림만 (처리중 ${count}건)")
                            DelayNotificationHelper.notifyDelayTriggered(applicationContext, "배달의민족", auto = false)
                        }
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun findProcessingCount(root: AccessibilityNodeInfo): Int? {
        val nodes = root.findAccessibilityNodeInfosByText("처리중")
        if (nodes != null) {
            for (node in nodes) {
                val text = node.text?.toString() ?: continue
                val match = Regex("""처리중\s*(\d+)""").find(text)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
        }
        return findCountInTree(root)
    }

    private fun findCountInTree(node: AccessibilityNodeInfo): Int? {
        val text = node.text?.toString() ?: ""
        if (text.contains("처리중")) {
            val match = Regex("""처리중\s*(\d+)""").find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }

            val parent = node.parent
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    val sibText = sibling.text?.toString() ?: ""
                    val num = sibText.trim().toIntOrNull()
                    if (num != null) return num
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findCountInTree(child)
            if (result != null) return result
        }
        return null
    }

    // ===== 배민 지연: MATE 앱 내 "조리시간 추가" =====

    private var baeminStep = 0
    private var baeminStartTime = 0L
    private var baeminRetryCount = 0
    private var baeminScrolled = false
    private val BAEMIN_TIMEOUT = 15000L // 15초 타임아웃
    private val BAEMIN_MAX_RETRIES = 8

    private fun resetBaeminState() {
        baeminStep = 0
        baeminStartTime = System.currentTimeMillis()
        baeminRetryCount = 0
        baeminScrolled = false
        stepBusy = false
    }

    private fun cancelBaeminDelay(reason: String) {
        log(Code.ERR_NO_COOK_BTN, "배민", "배민 지연 실패: $reason (step=$baeminStep, retry=$baeminRetryCount)")
        pendingBaeminDelay = false
        resetBaeminState()
        DelayNotificationHelper.notifyDelayTriggered(
            applicationContext, "배민 지연 실패: $reason", auto = false
        )
    }

    private fun handleBaeminDelay() {
        if (baeminStartTime > 0 && System.currentTimeMillis() - baeminStartTime > BAEMIN_TIMEOUT) {
            log(Code.ERR_TIMEOUT, "배민", "배민 지연 시간 초과 (${BAEMIN_TIMEOUT / 1000}초, step=$baeminStep)")
            cancelBaeminDelay("시간 초과")
            return
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            log(Code.ERR_NO_ROOT, "배민", "rootNode 없음 (step=$baeminStep)")
            return
        }

        try {
            when (baeminStep) {
                0 -> {
                    // 먼저 목록 맨 아래로 스크롤 (최신 주문이 맨 밑)
                    if (!baeminScrolled) {
                        log(Code.INFO_SCROLL, "배민", "주문 목록 스크롤 다운")
                        scrollToBottom(rootNode)
                        baeminScrolled = true
                        stepBusy = true
                        handler.postDelayed({ stepBusy = false }, 500)
                        return
                    }

                    val found = hasNodeWithText(rootNode, "조리시간 추가")
                    if (!found) {
                        baeminRetryCount++
                        log(Code.INFO_UI, "배민", "'조리시간 추가' 버튼 탐색 중 (retry=$baeminRetryCount/$BAEMIN_MAX_RETRIES)")
                        if (baeminRetryCount >= BAEMIN_MAX_RETRIES) {
                            dumpNodeTree(rootNode, "배민")
                            cancelBaeminDelay("조리시간 추가 버튼 없음 (지연 불가)")
                        }
                        return
                    }
                    val nodes = rootNode.findAccessibilityNodeInfosByText("조리시간 추가")
                    val enabledNode = nodes?.lastOrNull { it.isEnabled }
                    if (enabledNode == null) {
                        log(Code.ERR_NO_COOK_BTN, "배민", "'조리시간 추가' 버튼 비활성화 상태")
                        cancelBaeminDelay("조리시간 추가 버튼 비활성화")
                        return
                    }
                    // 맨 아래(최신) 주문의 버튼 클릭
                    if (clickLastNodeByText(rootNode, "조리시간 추가")) {
                        log(Code.INFO_CLICK, "배민", "'조리시간 추가' 클릭 성공 → step 1")
                        stepBusy = true
                        baeminStep = 1
                        handler.postDelayed({ stepBusy = false }, 800)
                    }
                }
                1 -> {
                    // Step 2: 팝업이 떴는지 확인 ("선택하세요" 텍스트 감지)
                    val popupVisible = hasNodeWithText(rootNode, "선택하세요")
                    if (!popupVisible) {
                        log(Code.INFO_UI, "배민", "팝업 대기 중 ('선택하세요' 텍스트 탐색)")
                        return // 팝업 아직 안 뜸, 다음 이벤트 기다림
                    }

                    val minutes = OrderTracker.getDelayMinutes()
                    val target = ((minutes + 2) / 5 * 5).coerceIn(5, 30)
                    log(Code.INFO_STATE, "배민", "팝업 감지! 목표 시간: ${target}분 (설정: ${minutes}분)")
                    if (clickNodeByText(rootNode, "${target}분")) {
                        log(Code.INFO_CLICK, "배민", "'${target}분' 클릭 → step 2")
                        stepBusy = true
                        baeminStep = 2
                        handler.postDelayed({ stepBusy = false }, 800)
                    } else {
                        log(Code.ERR_NO_POPUP, "배민", "'${target}분' 버튼 못찾음")
                    }
                }
                2 -> {
                    // Step 3: 하단 확인 버튼 "조리시간 추가" 클릭
                    if (clickLastNodeByText(rootNode, "조리시간 추가")) {
                        log(Code.INFO_DONE, "배민", "배민 조리시간 추가 완료!")
                        pendingBaeminDelay = false
                        resetBaeminState()
                    } else {
                        log(Code.INFO_UI, "배민", "확인 버튼 대기 ('조리시간 추가')")
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    // ===== 쿠팡이츠: 자동 지연 처리 =====
    // 흐름: 홈 → "주문 관리" 탭 → 주문 목록 스크롤 → 최신 주문 클릭 → "준비 지연" → 시간 조절 → 확인

    private var coupangStep = 0
    private var clicksRemaining = 0
    private var coupangStartTime = 0L
    private var coupangRetryCount = 0
    private val COUPANG_TIMEOUT = 25000L // 25초 타임아웃
    private val COUPANG_MAX_RETRIES = 8

    private fun resetCoupangState() {
        coupangStep = 0
        clicksRemaining = 0
        coupangStartTime = System.currentTimeMillis()
        coupangRetryCount = 0
        stepBusy = false
    }

    private fun cancelCoupangDelay(reason: String) {
        log(Code.ERR_NO_DELAY_BTN, "쿠팡", "쿠팡 지연 실패: $reason (step=$coupangStep, retry=$coupangRetryCount)")
        pendingCoupangDelay = false
        resetCoupangState()
        DelayNotificationHelper.notifyDelayTriggered(
            applicationContext, "쿠팡 지연 실패: $reason", auto = false
        )
    }

    private fun handleCoupangDelay() {
        if (coupangStartTime > 0 && System.currentTimeMillis() - coupangStartTime > COUPANG_TIMEOUT) {
            log(Code.ERR_TIMEOUT, "쿠팡", "쿠팡 지연 시간 초과 (${COUPANG_TIMEOUT / 1000}초, step=$coupangStep)")
            cancelCoupangDelay("시간 초과")
            return
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            log(Code.ERR_NO_ROOT, "쿠팡", "rootNode 없음 (step=$coupangStep)")
            return
        }

        try {
            when (coupangStep) {
                0 -> {
                    // Step 0: "주문 관리" 탭 클릭 (하단 네비게이션)
                    if (hasNodeWithText(rootNode, "주문 관리") && hasNodeWithText(rootNode, "처리 중")) {
                        log(Code.INFO_STATE, "쿠팡", "이미 주문 관리 화면 → step 1")
                        coupangStep = 1
                        return
                    }
                    if (clickNodeByText(rootNode, "주문 관리")) {
                        log(Code.INFO_CLICK, "쿠팡", "'주문 관리' 탭 클릭 → step 1")
                        stepBusy = true
                        coupangStep = 1
                        handler.postDelayed({ stepBusy = false }, 1000)
                    } else {
                        coupangRetryCount++
                        log(Code.INFO_UI, "쿠팡", "'주문 관리' 탭 탐색 (retry=$coupangRetryCount/$COUPANG_MAX_RETRIES)")
                        if (coupangRetryCount >= COUPANG_MAX_RETRIES) {
                            dumpNodeTree(rootNode, "쿠팡")
                            cancelCoupangDelay("주문 관리 탭을 찾을 수 없음")
                        }
                    }
                }
                1 -> {
                    // Step 1: "처리 중" 탭이 보이는지 확인 + 목록 맨 아래로 스크롤
                    if (!hasNodeWithText(rootNode, "처리 중")) {
                        log(Code.INFO_UI, "쿠팡", "'처리 중' 텍스트 대기 (로딩 중)")
                        return
                    }

                    log(Code.INFO_SCROLL, "쿠팡", "주문 목록 스크롤 다운")
                    scrollToBottom(rootNode)
                    stepBusy = true
                    coupangStep = 2
                    handler.postDelayed({ stepBusy = false }, 800)
                }
                2 -> {
                    // Step 2: 접수시간이 가장 최신인 주문 카드 클릭 → 주문 상세
                    if (hasNodeWithText(rootNode, "주문 상세")) {
                        log(Code.INFO_STATE, "쿠팡", "이미 주문 상세 화면 → step 3")
                        coupangStep = 3
                        return
                    }
                    val newestNode = findNewestOrderNode(rootNode)
                    if (newestNode != null && clickNodeOrParent(newestNode)) {
                        log(Code.INFO_CLICK, "쿠팡", "최신 주문 카드 클릭 → step 3")
                        stepBusy = true
                        coupangStep = 3
                        handler.postDelayed({ stepBusy = false }, 1000)
                        return
                    }
                    coupangRetryCount++
                    log(Code.INFO_UI, "쿠팡", "주문 카드 탐색 (retry=$coupangRetryCount/$COUPANG_MAX_RETRIES)")
                    if (coupangRetryCount >= COUPANG_MAX_RETRIES) {
                        dumpNodeTree(rootNode, "쿠팡")
                        cancelCoupangDelay("주문 카드를 찾을 수 없음")
                    }
                }
                3 -> {
                    // Step 3: 주문 상세에서 "준비 지연" 버튼 클릭
                    val found = hasNodeWithText(rootNode, "준비 지연") || hasNodeWithText(rootNode, "준비지연")
                    if (!found) {
                        coupangRetryCount++
                        log(Code.INFO_UI, "쿠팡", "'준비 지연' 버튼 탐색 (retry=$coupangRetryCount/$COUPANG_MAX_RETRIES)")
                        if (coupangRetryCount >= COUPANG_MAX_RETRIES) {
                            dumpNodeTree(rootNode, "쿠팡")
                            cancelCoupangDelay("준비 지연 버튼 없음 (이미 지연됨 또는 불가)")
                        }
                        return
                    }
                    if (clickLastNodeByText(rootNode, "준비 지연") || clickLastNodeByText(rootNode, "준비지연")) {
                        val minutes = OrderTracker.getDelayMinutes()
                        clicksRemaining = minutes
                        coupangRetryCount = 0
                        log(Code.INFO_CLICK, "쿠팡", "'준비 지연' 클릭 → 시간 조절 (목표: ${minutes}분)")
                        stepBusy = true
                        coupangStep = 4
                        handler.postDelayed({ stepBusy = false }, 800)
                    }
                }
                4 -> {
                    // Step 4: 시간 조절 (+5/+1)
                    if (clicksRemaining <= 0) {
                        coupangStep = 5
                        return
                    }
                    if (clicksRemaining >= 5) {
                        if (clickNodeByText(rootNode, "+5")) {
                            clicksRemaining -= 5
                            log(Code.INFO_CLICK, "쿠팡", "+5 클릭 (남은: ${clicksRemaining}분)")
                            stepBusy = true
                            handler.postDelayed({ stepBusy = false }, 300)
                        }
                    } else {
                        if (clickNodeByText(rootNode, "+1")) {
                            clicksRemaining -= 1
                            log(Code.INFO_CLICK, "쿠팡", "+1 클릭 (남은: ${clicksRemaining}분)")
                            stepBusy = true
                            handler.postDelayed({ stepBusy = false }, 300)
                        }
                    }
                    if (clicksRemaining <= 0) coupangStep = 5
                }
                5 -> {
                    // Step 5: 확인 버튼 "준비 지연" 클릭
                    if (clickLastNodeByText(rootNode, "준비 지연") || clickLastNodeByText(rootNode, "준비지연")) {
                        log(Code.INFO_DONE, "쿠팡", "쿠팡 준비 지연 완료! (${OrderTracker.getDelayMinutes()}분)")
                        pendingCoupangDelay = false
                        resetCoupangState()
                    } else {
                        log(Code.INFO_UI, "쿠팡", "확인 버튼 대기 ('준비 지연')")
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    // ===== 유틸리티 =====

    /** UI 트리 덤프 (디버깅: 현재 화면의 주요 텍스트 노드 기록) */
    private fun dumpNodeTree(root: AccessibilityNodeInfo, platform: String) {
        val texts = mutableListOf<String>()
        collectTexts(root, texts, 0)
        val dump = texts.take(30).joinToString(" | ")
        log(Code.INFO_UI, platform, "UI 트리: $dump")
    }

    private fun collectTexts(node: AccessibilityNodeInfo, texts: MutableList<String>, depth: Int) {
        if (depth > 10 || texts.size > 30) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length < 50) {
            val clickable = if (node.isClickable) "[클릭]" else ""
            texts.add("$text$clickable")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, texts, depth + 1)
        }
    }

    /** 접수시간(오전/오후 HH:MM)이 가장 최신인 주문 노드를 찾기 */
    private fun findNewestOrderNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val timeRegex = Regex("""(오전|오후)\s*(\d{1,2}):(\d{2})""")
        val timeNodes = mutableListOf<Pair<Int, AccessibilityNodeInfo>>() // agoMinutes, node

        // 현재 시간 (자정 넘김 처리 기준)
        val now = java.util.Calendar.getInstance()
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)

        // "오전" / "오후" 포함 노드 수집
        val amNodes = root.findAccessibilityNodeInfosByText("오전") ?: emptyList()
        val pmNodes = root.findAccessibilityNodeInfosByText("오후") ?: emptyList()
        val allNodes = amNodes + pmNodes

        for (node in allNodes) {
            val text = node.text?.toString() ?: continue
            val match = timeRegex.find(text) ?: continue
            val ampm = match.groupValues[1]
            var hour = match.groupValues[2].toIntOrNull() ?: continue
            val minute = match.groupValues[3].toIntOrNull() ?: continue

            // 24시간제로 변환
            if (ampm == "오후" && hour < 12) hour += 12
            if (ampm == "오전" && hour == 12) hour = 0

            // 자정 넘김 처리: 현재 시간 기준 몇 분 전인지 계산
            val totalMinutes = hour * 60 + minute
            val agoMinutes = (currentMinutes - totalMinutes + 1440) % 1440
            timeNodes.add(Pair(agoMinutes, node))
        }

        if (timeNodes.isEmpty()) return null

        // 가장 최신(현재로부터 가장 적게 경과) 노드 반환
        return timeNodes.minByOrNull { it.first }?.second
    }

    /** 스크롤 가능한 뷰를 찾아 맨 아래로 스크롤 (최신 주문 표시) */
    private fun scrollToBottom(root: AccessibilityNodeInfo) {
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            // 여러 번 스크롤해서 맨 아래까지
            for (i in 0 until 10) {
                if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            }
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }

    /** 텍스트로 노드를 찾아 클릭. 클릭 가능한 노드 우선, 안 되면 부모까지 탐색 */
    private fun clickNodeByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        // 클릭 가능한 노드 우선
        val clickable = nodes.firstOrNull { it.isClickable }
        if (clickable != null) {
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // 클릭 불가면 부모 탐색
        for (node in nodes) {
            if (clickNodeOrParent(node)) return true
        }
        return false
    }

    /** 텍스트와 매칭되는 노드 중 마지막(하단) 것을 클릭 — 팝업 확인 버튼용 */
    private fun clickLastNodeByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        // 마지막 클릭 가능한 노드 (팝업 확인 버튼은 보통 화면 하단)
        val clickable = nodes.lastOrNull { it.isClickable }
        if (clickable != null) {
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // 클릭 불가면 마지막 노드의 부모 탐색
        val last = nodes.lastOrNull()
        if (last != null) {
            return clickNodeOrParent(last)
        }
        return false
    }

    /** 노드 자체가 클릭 불가하면 클릭 가능한 부모까지 올라가서 클릭 */
    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    /** 특정 텍스트를 포함한 노드가 존재하는지 확인 */
    private fun hasNodeWithText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes != null && nodes.isNotEmpty()
    }

    override fun onInterrupt() {
        log(Code.INFO_STATE, "시스템", "접근성 서비스 중단됨")
        pendingCoupangDelay = false
        pendingBaeminDelay = false
        resetBaeminState()
        resetCoupangState()
    }

    override fun onDestroy() {
        super.onDestroy()
        log(Code.INFO_STATE, "시스템", "접근성 서비스 종료")
        instance = null
        handler.removeCallbacksAndMessages(null)
    }
}
