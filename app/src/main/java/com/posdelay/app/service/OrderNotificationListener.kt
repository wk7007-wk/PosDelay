package com.posdelay.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.posdelay.app.data.NotificationLog
import com.posdelay.app.data.OrderTracker

class OrderNotificationListener : NotificationListenerService() {

    companion object {
        const val MATE_PACKAGE = "com.foodtechkorea.posboss"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val packageName = sbn.packageName ?: return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // MATE 알림은 전부 로그에 기록
        if (packageName == MATE_PACKAGE) {
            NotificationLog.add(packageName, title, text)
        }

        if (packageName != MATE_PACKAGE) return
        if (!OrderTracker.isEnabled()) return

        val content = "$title $text"

        // 처리중 건수 파싱 시도 (예: "처리중 8건")
        val countRegex = Regex("""처리중\s*(\d+)""")
        val countMatch = countRegex.find(content)
        if (countMatch != null) {
            val count = countMatch.groupValues[1].toIntOrNull()
            if (count != null) {
                OrderTracker.syncOrderCount(count)
                DelayNotificationHelper.update(applicationContext)
                return
            }
        }

        // 새 주문 알림 → +1 (배차/배달 키워드로는 감소하지 않음 — 부정확하므로)
        // 정확한 건수는 MATE 화면 열 때 AccessibilityService가 동기화
        if (isNewOrderNotification(content)) {
            OrderTracker.incrementOrder()
            DelayNotificationHelper.update(applicationContext)
            checkDelayForNewOrder()
        }
    }

    private fun checkDelayForNewOrder() {
        if (OrderTracker.shouldDelayCoupang()) {
            if (OrderTracker.isAutoMode()) {
                DelayNotificationHelper.notifyDelayTriggered(
                    applicationContext, "쿠팡이츠", auto = true
                )
                DelayAccessibilityService.triggerCoupangDelay(applicationContext)
            } else {
                DelayNotificationHelper.notifyDelayTriggered(
                    applicationContext, "쿠팡이츠", auto = false
                )
            }
        }

        if (OrderTracker.shouldDelayBaemin()) {
            if (OrderTracker.isAutoMode()) {
                DelayNotificationHelper.notifyDelayTriggered(
                    applicationContext, "배달의민족", auto = true
                )
                DelayAccessibilityService.triggerBaeminDelay(applicationContext)
            } else {
                DelayNotificationHelper.notifyDelayTriggered(
                    applicationContext, "배달의민족", auto = false
                )
            }
        }
    }

    private fun isNewOrderNotification(content: String): Boolean {
        val keywords = listOf("새로운 주문이 도착", "새 주문", "신규 주문", "주문 접수", "주문이 들어왔", "새주문", "신규주문")
        return keywords.any { content.contains(it) }
    }
}
