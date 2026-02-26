package com.posdelay.app.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
/**
 * FCM push 수신 서비스 (메인 건수 소스).
 * KDS 건수 변경 시 FCM data message를 수신 → processKdsCount()로 공통 처리.
 * OS가 Doze에서도 전달 보장. SSE는 백업.
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

        if (count == null) {
            Log.w(TAG, "FCM 수신: count 없음 data=$data")
            return
        }

        // orders 콤마 구분 문자열 → List<Int>
        val ordersStr = data["orders"] ?: ""
        val orders = if (ordersStr.isNotEmpty()) {
            ordersStr.split(",").mapNotNull { it.trim().toIntOrNull() }
        } else emptyList()

        Log.d(TAG, "FCM 수신: count=$count, orders=${orders.size}건, time=$time")
        com.posdelay.app.data.LogFileWriter.append("FCM", "수신 count=$count orders=${orders.size}건")

        // 공통 처리 로직 (25분 필터, 30분 보정, OrderTracker 반영)
        FirebaseKdsReader.processKdsCount(count, orders, time, "fcm")
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM 토큰 갱신: ${token.take(20)}...")
        com.posdelay.app.data.LogFileWriter.append("FCM", "토큰 갱신")
    }
}
