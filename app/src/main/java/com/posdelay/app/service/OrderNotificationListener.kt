package com.posdelay.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.posdelay.app.data.NotificationLog

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

        // MATE 알림 로그 기록만 (건수 동기화 비활성화 — KDS 단일 소스)
        if (packageName == MATE_PACKAGE) {
            val combined = "$title $text"
            if (!combined.contains("배차") && !combined.contains("라이더")) {
                NotificationLog.add(packageName, title, text)
            }
        }
    }
}
