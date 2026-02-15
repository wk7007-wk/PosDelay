package com.posdelay.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.posdelay.app.data.OrderTracker
import com.posdelay.app.ui.MainActivity

object DelayNotificationHelper {

    private const val CHANNEL_STATUS = "posdelay_status"
    private const val CHANNEL_ALERT = "posdelay_alert"
    private const val NOTIFICATION_STATUS_ID = 2001
    private const val NOTIFICATION_ALERT_ID = 2002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val statusChannel = NotificationChannel(
                CHANNEL_STATUS, "주문 현황", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "현재 처리 중 주문 수 표시"
                setShowBadge(false)
            }
            manager.createNotificationChannel(statusChannel)

            val alertChannel = NotificationChannel(
                CHANNEL_ALERT, "지연 알림", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "자동 지연 처리 발동 알림"
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    fun update(context: Context) {
        createChannels(context)

        val count = OrderTracker.getOrderCount()
        val coupang = OrderTracker.getCoupangThreshold()
        val baemin = OrderTracker.getBaeminThreshold()
        val enabled = OrderTracker.isEnabled()

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (enabled) {
            "처리중 ${count}건 | 쿠팡 ${coupang}건 배민 ${baemin}건"
        } else {
            "모니터링 중지됨"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("PosDelay · ${count}건")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_STATUS_ID, notification)
    }

    fun notifyDelayTriggered(context: Context, platform: String, auto: Boolean = false) {
        createChannels(context)

        val count = OrderTracker.getOrderCount()
        val delayMin = OrderTracker.getDelayMinutes()

        val message = if (platform == "배달의민족") {
            "처리중 ${count}건 - 배민 POS에서 지연 설정 필요"
        } else if (auto) {
            "처리중 ${count}건 - ${platform} ${delayMin}분 자동 지연 처리 중..."
        } else {
            "처리중 ${count}건 - ${platform}에서 준비 지연 설정 필요"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${platform} 지연 처리!")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ALERT_ID, notification)
    }

    private const val NOTIFICATION_AD_ALERT_ID = 2003

    fun showAdAlert(context: Context, message: String) {
        createChannels(context)

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PosDelay 광고 관리")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_AD_ALERT_ID, notification)
    }

    fun dismiss(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_STATUS_ID)
    }
}
