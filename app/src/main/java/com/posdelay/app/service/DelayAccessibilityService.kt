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
        private var pendingDelay = false

        fun triggerDelay(context: Context) {
            pendingDelay = true
            val launchIntent = context.packageManager.getLaunchIntentForPackage(COUPANG_EATS_PACKAGE)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
            }
        }

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
            MATE_PACKAGE -> readMateOrderCount()
            COUPANG_EATS_PACKAGE -> if (pendingDelay) handleCoupangDelay()
        }
    }

    // ===== MATE 사장님: 처리중 건수 읽기 =====

    private fun readMateOrderCount() {
        val rootNode = rootInActiveWindow ?: return

        try {
            // "처리중" 탭 텍스트에서 숫자 추출
            // 탭 형태: "처리중 1" 또는 "처리중\n1"
            val count = findProcessingCount(rootNode)
            if (count != null) {
                val current = OrderTracker.getOrderCount()
                if (count != current) {
                    OrderTracker.setOrderCount(count)
                    DelayNotificationHelper.update(applicationContext)

                    // 임계값 체크
                    if (OrderTracker.shouldDelayCoupang()) {
                        DelayNotificationHelper.notifyDelayTriggered(
                            applicationContext, "쿠팡이츠"
                        )
                    }
                    if (OrderTracker.shouldDelayBaemin()) {
                        DelayNotificationHelper.notifyDelayTriggered(
                            applicationContext, "배달의민족"
                        )
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun findProcessingCount(root: AccessibilityNodeInfo): Int? {
        // 방법 1: "처리중" 텍스트가 포함된 노드 찾기
        val nodes = root.findAccessibilityNodeInfosByText("처리중")
        if (nodes != null) {
            for (node in nodes) {
                val text = node.text?.toString() ?: continue
                // "처리중 1" or "처리중1" 형태
                val match = Regex("""처리중\s*(\d+)""").find(text)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
        }

        // 방법 2: 전체 노드 트리에서 탐색
        return findCountInTree(root)
    }

    private fun findCountInTree(node: AccessibilityNodeInfo): Int? {
        val text = node.text?.toString() ?: ""
        if (text.contains("처리중")) {
            val match = Regex("""처리중\s*(\d+)""").find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }

            // "처리중" 텍스트 옆의 숫자 노드 확인 (탭 레이아웃)
            val parent = node.parent
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    val sibText = sibling.text?.toString() ?: ""
                    val num = sibText.trim().toIntOrNull()
                    if (num != null) {
                        return num
                    }
                }
            }
        }

        // 자식 노드 재귀 탐색
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findCountInTree(child)
            if (result != null) return result
        }

        return null
    }

    // ===== 쿠팡이츠: 자동 지연 처리 =====
    // UI: "준비 지연" 클릭 → 팝업 (-5/-1/+0분/+1/+5) → 녹색 "준비 지연" 확인

    private var delayStep = 0       // 0: 준비지연 클릭, 1: +버튼으로 시간 설정, 2: 확인
    private var clicksRemaining = 0 // +5/+1 남은 클릭 수

    private fun handleCoupangDelay() {
        val rootNode = rootInActiveWindow ?: return

        try {
            when (delayStep) {
                0 -> {
                    // Step 1: "준비 지연" 버튼 클릭 (주문 상세 하단)
                    val delayNode = findNodeByText(rootNode, "준비 지연")
                        ?: findNodeByText(rootNode, "준비지연")
                    if (delayNode != null) {
                        delayNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        // 목표 시간 계산: +5 몇번, +1 몇번
                        val minutes = OrderTracker.getDelayMinutes()
                        clicksRemaining = minutes // 총 분 (나중에 +5/+1로 분배)
                        delayStep = 1
                    }
                }
                1 -> {
                    // Step 2: +5/+1 버튼으로 시간 설정
                    if (clicksRemaining <= 0) {
                        delayStep = 2
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

                    if (clicksRemaining <= 0) {
                        delayStep = 2
                    }
                }
                2 -> {
                    // Step 3: 하단 녹색 "준비 지연" 확인 버튼
                    val confirmNode = findNodeByText(rootNode, "준비 지연")
                        ?: findNodeByText(rootNode, "준비지연")
                    if (confirmNode != null) {
                        confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        pendingDelay = false
                        delayStep = 0
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
        pendingDelay = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
