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
            .setContentTitle("")
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

        val delayMin = OrderTracker.getDelayMinutes()
        val message = if (auto) "${platform} ${delayMin}분 지연완료" else "${platform} 지연필요"

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ALERT_ID, notification)
    }

    private const val NOTIFICATION_AD_ALERT_ID = 2003
    private const val NOTIFICATION_AD_PROGRESS_ID = 2004
    private const val NOTIFICATION_DELAY_ALERT_ID = 2005
    private const val NOTIFICATION_COOK_ALERT_ID = 2006

    fun showDelayAlert(context: Context, message: String) {
        createChannels(context)

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_DELAY_ALERT_ID, notification)
    }

    fun showAdAlert(context: Context, message: String) {
        createChannels(context)

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_AD_ALERT_ID, notification)
    }

    /** 광고 자동화 진행 — 알림바 (TTS 읽기용, 간결하게) */
    fun showAdProgress(context: Context, message: String) {
        createChannels(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_AD_PROGRESS_ID, notification)
    }

    /** 광고 자동화 결과 — 알림바 (TTS 읽기용, 간결하게) */
    fun showAdResult(context: Context, message: String, success: Boolean) {
        createChannels(context)

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(if (success) android.R.drawable.ic_dialog_info else android.R.drawable.ic_dialog_alert)
            .setContentTitle("")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_AD_PROGRESS_ID)
        manager.notify(NOTIFICATION_AD_ALERT_ID, notification)
    }

    /** 조리모드 알림 (주문번호별 고유 ID → 덮어쓰기 방지) */
    fun showCookAlert(context: Context, message: String, orderNum: Int = 0) {
        createChannels(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 주문번호별 고유 ID (3000+주문번호), 0이면 기존 공용 ID
        val notifId = if (orderNum > 0) 3000 + (orderNum % 1000) else NOTIFICATION_COOK_ALERT_ID
        manager.notify(notifId, notification)
    }

    fun dismiss(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_STATUS_ID)
    }
}
