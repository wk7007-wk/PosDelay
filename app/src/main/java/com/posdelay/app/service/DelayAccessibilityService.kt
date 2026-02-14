package com.posdelay.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.posdelay.app.data.OrderTracker

class DelayAccessibilityService : AccessibilityService() {

    companion object {
        const val COUPANG_EATS_PACKAGE = "com.coupang.mobile.eats.merchant"
        private var instance: DelayAccessibilityService? = null
        private var pendingDelay = false

        fun triggerDelay(context: Context) {
            pendingDelay = true
            // 쿠팡이츠 앱 실행
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
        if (!pendingDelay) return
        if (event.packageName?.toString() != COUPANG_EATS_PACKAGE) return

        // 쿠팡이츠 UI에서 지연 설정 버튼 탐색
        val rootNode = rootInActiveWindow ?: return

        // 지연 관련 버튼/텍스트 탐색
        val delayNode = findNodeByText(rootNode, "지연")
            ?: findNodeByText(rootNode, "주문 일시중지")
            ?: findNodeByText(rootNode, "일시정지")
            ?: findNodeByText(rootNode, "운영중지")

        if (delayNode != null) {
            delayNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // 지연 시간 설정 시도
            val minutes = OrderTracker.getDelayMinutes()
            setDelayTime(rootNode, minutes)
            pendingDelay = false
        }

        rootNode.recycle()
    }

    private fun setDelayTime(root: AccessibilityNodeInfo, minutes: Int) {
        // 지연 시간 텍스트 찾기 (쿠팡이츠 UI에 따라 조정 필요)
        val minuteText = "${minutes}분"
        val timeNode = findNodeByText(root, minuteText)
        if (timeNode != null) {
            timeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // 확인/적용 버튼 클릭
        val confirmNode = findNodeByText(root, "확인")
            ?: findNodeByText(root, "적용")
            ?: findNodeByText(root, "저장")
        confirmNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
