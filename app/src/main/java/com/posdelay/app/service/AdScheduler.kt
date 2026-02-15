package com.posdelay.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.posdelay.app.data.AdManager
import com.posdelay.app.data.OrderTracker
import com.posdelay.app.ui.MainActivity
import java.util.Calendar

object AdScheduler {

    private const val TAG = "AdScheduler"
    const val ACTION_AD_OFF = "com.posdelay.app.AD_OFF"
    const val ACTION_AD_ON = "com.posdelay.app.AD_ON"
    private const val REQUEST_AD_OFF = 3001
    private const val REQUEST_AD_ON = 3002

    fun scheduleAlarms(context: Context) {
        if (!AdManager.isAdEnabled() || !AdManager.isScheduleEnabled()) {
            cancelAlarms(context)
            return
        }
        scheduleAlarm(context, AdManager.getAdOffTime(), ACTION_AD_OFF, REQUEST_AD_OFF)
        scheduleAlarm(context, AdManager.getAdOnTime(), ACTION_AD_ON, REQUEST_AD_ON)
        Log.d(TAG, "Alarms scheduled: OFF=${AdManager.getAdOffTime()}, ON=${AdManager.getAdOnTime()}")
    }

    private fun scheduleAlarm(context: Context, timeStr: String, action: String, requestCode: Int) {
        val parts = timeStr.split(":")
        if (parts.size != 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val intent = Intent(context, AdAlarmReceiver::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
        }
    }

    fun cancelAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(ACTION_AD_OFF to REQUEST_AD_OFF, ACTION_AD_ON to REQUEST_AD_ON).forEach { (action, code) ->
            val intent = Intent(context, AdAlarmReceiver::class.java).apply {
                this.action = action
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    /** OrderTracker.orderCount 변경 시 호출 — 임계값 초과하면 true 반환 */
    fun checkOrderThreshold(): Boolean {
        if (!AdManager.isAdEnabled() || !AdManager.isOrderAutoOffEnabled()) return false
        val count = OrderTracker.getOrderCount()
        val threshold = AdManager.getAutoOffThreshold()
        return count >= threshold
    }
}

class AdAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AdAlarmReceiver", "Received: ${intent.action}")

        AdManager.init(context)
        OrderTracker.init(context)

        when (intent.action) {
            AdScheduler.ACTION_AD_OFF -> {
                AdManager.setLastAdAction("스케줄: 광고 끄기 시간")
                // 알림 표시 + 앱 실행해서 WebView 자동화 실행
                DelayNotificationHelper.showAdAlert(context, "스케줄: 광고 끄기 시간입니다")
                launchWithAction(context, "ad_off")
                // 내일 같은 시간으로 재등록
                AdScheduler.scheduleAlarms(context)
            }
            AdScheduler.ACTION_AD_ON -> {
                AdManager.setLastAdAction("스케줄: 광고 켜기 시간")
                DelayNotificationHelper.showAdAlert(context, "스케줄: 광고 켜기 시간입니다")
                launchWithAction(context, "ad_on")
                AdScheduler.scheduleAlarms(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                AdScheduler.scheduleAlarms(context)
            }
        }
    }

    private fun launchWithAction(context: Context, action: String) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("ad_scheduled_action", action)
        }
        context.startActivity(launchIntent)
    }
}
