package com.posdelay.app.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.posdelay.app.data.OrderTracker

/**
 * FCM push 수신 서비스.
 * KDS 건수 변경 시 FCM data message를 수신하여 OrderTracker 즉시 업데이트.
 * OS가 Doze에서도 전달 보장 → SSE 끊김 보완.
 */
class FcmKdsReceiver : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmKdsReceiver"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) return

        val count = data["count"]?.toIntOrNull()
        val time = data["time"] ?: ""
        val source = data["source"] ?: "fcm"

        if (count == null) {
            Log.w(TAG, "FCM 수신: count 없음 data=$data")
            return
        }

        val currentCount = OrderTracker.getOrderCount()
        val kdsAge = (System.currentTimeMillis() - OrderTracker.getLastKdsSyncTime()) / 1000

        if (count == currentCount && kdsAge < 120) {
            // SSE가 이미 반영한 값과 동일 + 최근 동기화 → 스킵
            Log.d(TAG, "FCM 수신: count=$count (SSE와 동일, 스킵)")
            return
        }

        // SSE보다 FCM이 먼저 도착했거나, SSE가 끊긴 상태 → 반영
        Log.d(TAG, "FCM 수신 반영: count=$count (기존=$currentCount, KDS=${kdsAge}초전)")
        com.posdelay.app.data.LogFileWriter.append("FCM", "수신 count=$count (기존=$currentCount, KDS=${kdsAge}초전)")

        OrderTracker.setOrderCount(count)

        // 지연 알림 체크
        DelayAlertManager.onCountChanged(count)
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM 토큰 갱신: ${token.take(20)}...")
        com.posdelay.app.data.LogFileWriter.append("FCM", "토큰 갱신")
    }
}
