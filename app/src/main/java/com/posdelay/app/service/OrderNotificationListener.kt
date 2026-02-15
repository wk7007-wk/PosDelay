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
                OrderTracker.setOrderCount(count)
                DelayNotificationHelper.update(applicationContext)
                return
            }
        }

        // 직접 건수를 못 읽으면 키워드 기반 증감
        if (isNewOrderNotification(content)) {
            OrderTracker.incrementOrder()
            DelayNotificationHelper.update(applicationContext)
            // 새 주문이 들어온 시점에 임계값 초과면 → 이 새 주문에 지연
            checkDelayForNewOrder()
        } else if (isDeliveryNotification(content)) {
            OrderTracker.decrementOrder()
            DelayNotificationHelper.update(applicationContext)
        }
    }

    // 새 주문이 들어온 시점에만 지연 체크 (해당 새 주문에 지연)
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

    private fun isDeliveryNotification(content: String): Boolean {
        val keywords = listOf("배달대행 배차", "배달중", "배달 시작", "픽업", "배차되었")
        return keywords.any { content.contains(it) }
    }
}
