package com.posdelay.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.posdelay.app.data.OrderTracker

class OrderNotificationListener : NotificationListenerService() {

    companion object {
        const val MATE_PACKAGE = "com.foodtechkorea.posboss"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != MATE_PACKAGE) return
        if (!OrderTracker.isEnabled()) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val content = "$title $text"

        // 새 주문 알림 감지
        if (isNewOrderNotification(content)) {
            OrderTracker.incrementOrder()
            DelayNotificationHelper.update(applicationContext)

            // 쿠팡이츠 지연 체크
            if (OrderTracker.shouldDelayCoupang()) {
                DelayNotificationHelper.notifyDelayTriggered(
                    applicationContext, "쿠팡이츠"
                )
                DelayAccessibilityService.triggerDelay(applicationContext)
            }

            // 배민 지연 체크 (알림만 - 배민은 POS 내에서 수동 처리)
            if (OrderTracker.shouldDelayBaemin()) {
                DelayNotificationHelper.notifyDelayTriggered(
                    applicationContext, "배달의민족"
                )
            }
        }
    }

    private fun isNewOrderNotification(content: String): Boolean {
        val orderKeywords = listOf("새 주문", "신규 주문", "주문 접수", "주문이 들어왔", "새주문", "신규주문")
        return orderKeywords.any { content.contains(it) }
    }
}
