package com.posdelay.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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
            val launchIntent = context.packageManager.getLaunchIntentForPackage(COUPANG_EATS_PACKAGE)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
            }
        }

        fun triggerBaeminDelay(context: Context) {
            pendingBaeminDelay = true
            // 배민 지연은 MATE 앱 내에서 처리 → MATE 앱 열기
            val launchIntent = context.packageManager.getLaunchIntentForPackage(MATE_PACKAGE)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
            }
        }

        // 하위 호환
        fun triggerDelay(context: Context) = triggerCoupangDelay(context)

        fun isAvailable(): Boolean = instance != null
    }

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
                if (pendingBaeminDelay) handleBaeminDelay()
            }
            COUPANG_EATS_PACKAGE -> {
                if (pendingCoupangDelay) handleCoupangDelay()
            }
        }
    }

    // ===== MATE 사장님: 처리중 건수 읽기 =====

    private fun readMateOrderCount() {
        if (pendingBaeminDelay) return // 지연 처리 중에는 건수 읽기 스킵

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
                            pendingBaeminDelay = true // MATE 안에서 바로 처리
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
    // UI: "조리시간 추가" 클릭 → 5분/10분/15분/20분/25분/30분 선택 → "조리시간 추가" 확인

    private var baeminStep = 0

    private fun handleBaeminDelay() {
        val rootNode = rootInActiveWindow ?: return

        try {
            when (baeminStep) {
                0 -> {
                    // Step 1: "조리시간 추가" 버튼 클릭
                    val addNode = findNodeByText(rootNode, "조리시간 추가")
                    if (addNode != null) {
                        addNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        baeminStep = 1
                    }
                }
                1 -> {
                    // Step 2: 시간 선택 (5분/10분/15분/20분/25분/30분)
                    val minutes = OrderTracker.getDelayMinutes()
                    // 가장 가까운 선택지 찾기 (5분 단위)
                    val target = ((minutes + 2) / 5 * 5).coerceIn(5, 30)
                    val timeNode = findNodeByText(rootNode, "${target}분")
                    if (timeNode != null) {
                        timeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        baeminStep = 2
                    }
                }
                2 -> {
                    // Step 3: "조리시간 추가" 확인 버튼
                    val confirmNode = findNodeByText(rootNode, "조리시간 추가")
                    if (confirmNode != null) {
                        confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        pendingBaeminDelay = false
                        baeminStep = 0
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    // ===== 쿠팡이츠: 자동 지연 처리 =====
    // UI: "준비 지연" 클릭 → 팝업 (-5/-1/+0분/+1/+5) → 녹색 "준비 지연" 확인

    private var coupangStep = 0
    private var clicksRemaining = 0

    private fun handleCoupangDelay() {
        val rootNode = rootInActiveWindow ?: return

        try {
            when (coupangStep) {
                0 -> {
                    val delayNode = findNodeByText(rootNode, "준비 지연")
                        ?: findNodeByText(rootNode, "준비지연")
                    if (delayNode != null) {
                        delayNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        val minutes = OrderTracker.getDelayMinutes()
                        clicksRemaining = minutes
                        coupangStep = 1
                    }
                }
                1 -> {
                    if (clicksRemaining <= 0) {
                        coupangStep = 2
                        return
                    }
                    if (clicksRemaining >= 5) {
                        val plus5 = findNodeByText(rootNode, "+5")
                        if (plus5 != null) {
                            plus5.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            clicksRemaining -= 5
                        }
                    } else {
                        val plus1 = findNodeByText(rootNode, "+1")
                        if (plus1 != null) {
                            plus1.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            clicksRemaining -= 1
                        }
                    }
                    if (clicksRemaining <= 0) coupangStep = 2
                }
                2 -> {
                    val confirmNode = findNodeByText(rootNode, "준비 지연")
                        ?: findNodeByText(rootNode, "준비지연")
                    if (confirmNode != null) {
                        confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        pendingCoupangDelay = false
                        coupangStep = 0
                        clicksRemaining = 0
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes?.firstOrNull { it.isClickable }
            ?: nodes?.firstOrNull()
    }

    override fun onInterrupt() {
        pendingCoupangDelay = false
        pendingBaeminDelay = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
