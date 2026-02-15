package com.posdelay.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.posdelay.app.data.OrderTracker

class DelayAccessibilityService : AccessibilityService() {

    companion object {
        const val MATE_PACKAGE = "com.foodtechkorea.posboss"
        const val COUPANG_EATS_PACKAGE = "com.coupang.mobile.eats.merchant"
        private var instance: DelayAccessibilityService? = null
        private var pendingCoupangDelay = false
        private var pendingBaeminDelay = false

        fun triggerCoupangDelay(context: Context) {
            pendingCoupangDelay = true
            instance?.resetCoupangState()
            val launchIntent = context.packageManager.getLaunchIntentForPackage(COUPANG_EATS_PACKAGE)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
            }
        }

        fun triggerBaeminDelay(context: Context) {
            pendingBaeminDelay = true
            instance?.resetBaeminState()
            val launchIntent = context.packageManager.getLaunchIntentForPackage(MATE_PACKAGE)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
            }
        }

        fun triggerDelay(context: Context) = triggerCoupangDelay(context)

        fun isAvailable(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stepBusy = false // step 처리 중 중복 방지

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when (pkg) {
            MATE_PACKAGE -> {
                readMateOrderCount()
                if (pendingBaeminDelay && !stepBusy) handleBaeminDelay()
            }
            COUPANG_EATS_PACKAGE -> {
                if (pendingCoupangDelay && !stepBusy) handleCoupangDelay()
            }
        }
    }

    // ===== MATE 사장님: 처리중 건수 읽기 =====

    private fun readMateOrderCount() {
        if (pendingBaeminDelay) return

        val rootNode = rootInActiveWindow ?: return

        try {
            val count = findProcessingCount(rootNode)
            if (count != null) {
                val current = OrderTracker.getOrderCount()
                if (count != current) {
                    OrderTracker.setOrderCount(count)
                    DelayNotificationHelper.update(applicationContext)

                    if (OrderTracker.shouldDelayCoupang()) {
                        if (OrderTracker.isAutoMode()) {
                            DelayNotificationHelper.notifyDelayTriggered(applicationContext, "쿠팡이츠", auto = true)
                            triggerCoupangDelay(applicationContext)
                        } else {
                            DelayNotificationHelper.notifyDelayTriggered(applicationContext, "쿠팡이츠", auto = false)
                        }
                    }
                    if (OrderTracker.shouldDelayBaemin()) {
                        if (OrderTracker.isAutoMode()) {
                            DelayNotificationHelper.notifyDelayTriggered(applicationContext, "배달의민족", auto = true)
                            pendingBaeminDelay = true
                        } else {
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
    private val BAEMIN_TIMEOUT = 10000L // 10초 타임아웃
    private val BAEMIN_MAX_RETRIES = 5 // step 0에서 버튼 못 찾으면 5회 시도 후 포기

    private fun resetBaeminState() {
        baeminStep = 0
        baeminStartTime = System.currentTimeMillis()
        baeminRetryCount = 0
        stepBusy = false
    }

    private fun cancelBaeminDelay(reason: String) {
        pendingBaeminDelay = false
        resetBaeminState()
        DelayNotificationHelper.notifyDelayTriggered(
            applicationContext, "배민 지연 실패: $reason", auto = false
        )
    }

    private fun handleBaeminDelay() {
        // 타임아웃 체크
        if (baeminStartTime > 0 && System.currentTimeMillis() - baeminStartTime > BAEMIN_TIMEOUT) {
            cancelBaeminDelay("시간 초과")
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            when (baeminStep) {
                0 -> {
                    // Step 1: "조리시간 추가" 버튼 찾기
                    val found = hasNodeWithText(rootNode, "조리시간 추가")
                    if (!found) {
                        baeminRetryCount++
                        if (baeminRetryCount >= BAEMIN_MAX_RETRIES) {
                            // 버튼이 없음 → 기사 확정 등으로 지연 불가 상태
                            cancelBaeminDelay("조리시간 추가 버튼 없음 (지연 불가)")
                        }
                        return
                    }
                    // 버튼이 비활성화(enabled=false)인지 체크
                    val nodes = rootNode.findAccessibilityNodeInfosByText("조리시간 추가")
                    val enabledNode = nodes?.firstOrNull { it.isEnabled }
                    if (enabledNode == null) {
                        cancelBaeminDelay("조리시간 추가 버튼 비활성화")
                        return
                    }
                    if (clickNodeByText(rootNode, "조리시간 추가")) {
                        stepBusy = true
                        baeminStep = 1
                        handler.postDelayed({ stepBusy = false }, 800)
                    }
                }
                1 -> {
                    // Step 2: 팝업이 떴는지 확인 ("선택하세요" 텍스트 감지)
                    val popupVisible = hasNodeWithText(rootNode, "선택하세요")
                    if (!popupVisible) return // 팝업 아직 안 뜸, 다음 이벤트 기다림

                    val minutes = OrderTracker.getDelayMinutes()
                    val target = ((minutes + 2) / 5 * 5).coerceIn(5, 30)
                    if (clickNodeByText(rootNode, "${target}분")) {
                        stepBusy = true
                        baeminStep = 2
                        handler.postDelayed({ stepBusy = false }, 800)
                    }
                }
                2 -> {
                    // Step 3: 하단 확인 버튼 "조리시간 추가" 클릭
                    if (clickLastNodeByText(rootNode, "조리시간 추가")) {
                        pendingBaeminDelay = false
                        resetBaeminState()
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    // ===== 쿠팡이츠: 자동 지연 처리 =====

    private var coupangStep = 0
    private var clicksRemaining = 0
    private var coupangStartTime = 0L
    private var coupangRetryCount = 0
    private val COUPANG_TIMEOUT = 15000L // 15초 타임아웃
    private val COUPANG_MAX_RETRIES = 5

    private fun resetCoupangState() {
        coupangStep = 0
        clicksRemaining = 0
        coupangStartTime = System.currentTimeMillis()
        coupangRetryCount = 0
        stepBusy = false
    }

    private fun cancelCoupangDelay(reason: String) {
        pendingCoupangDelay = false
        resetCoupangState()
        DelayNotificationHelper.notifyDelayTriggered(
            applicationContext, "쿠팡 지연 실패: $reason", auto = false
        )
    }

    private fun handleCoupangDelay() {
        if (coupangStartTime > 0 && System.currentTimeMillis() - coupangStartTime > COUPANG_TIMEOUT) {
            cancelCoupangDelay("시간 초과")
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            when (coupangStep) {
                0 -> {
                    val found = hasNodeWithText(rootNode, "준비 지연") || hasNodeWithText(rootNode, "준비지연")
                    if (!found) {
                        coupangRetryCount++
                        if (coupangRetryCount >= COUPANG_MAX_RETRIES) {
                            cancelCoupangDelay("준비 지연 버튼 없음 (이미 지연 설정됨 또는 불가)")
                        }
                        return
                    }
                    if (clickNodeByText(rootNode, "준비 지연") || clickNodeByText(rootNode, "준비지연")) {
                        val minutes = OrderTracker.getDelayMinutes()
                        clicksRemaining = minutes
                        stepBusy = true
                        coupangStep = 1
                        handler.postDelayed({ stepBusy = false }, 800)
                    }
                }
                1 -> {
                    if (clicksRemaining <= 0) {
                        coupangStep = 2
                        return
                    }
                    if (clicksRemaining >= 5) {
                        if (clickNodeByText(rootNode, "+5")) {
                            clicksRemaining -= 5
                            stepBusy = true
                            handler.postDelayed({ stepBusy = false }, 300)
                        }
                    } else {
                        if (clickNodeByText(rootNode, "+1")) {
                            clicksRemaining -= 1
                            stepBusy = true
                            handler.postDelayed({ stepBusy = false }, 300)
                        }
                    }
                    if (clicksRemaining <= 0) coupangStep = 2
                }
                2 -> {
                    // 확인 버튼 — 팝업 내 마지막 "준비 지연" 버튼
                    if (clickLastNodeByText(rootNode, "준비 지연") || clickLastNodeByText(rootNode, "준비지연")) {
                        pendingCoupangDelay = false
                        resetCoupangState()
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    // ===== 유틸리티 =====

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
        pendingCoupangDelay = false
        pendingBaeminDelay = false
        resetBaeminState()
        resetCoupangState()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        handler.removeCallbacksAndMessages(null)
    }
}
