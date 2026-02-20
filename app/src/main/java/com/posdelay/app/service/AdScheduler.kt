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

    /** 현재 시각이 스케줄 활성 시간대(켜기~끄기) 안에 있는지 확인 */
    fun isWithinActiveWindow(): Boolean {
        if (!AdManager.isScheduleEnabled()) return true  // 스케줄 꺼져 있으면 항상 활성
        val onTime = AdManager.getAdOnTime()   // 예: "08:00"
        val offTime = AdManager.getAdOffTime()  // 예: "22:00"
        val onMin = timeToMinutes(onTime) ?: return true
        val offMin = timeToMinutes(offTime) ?: return true

        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        return if (onMin <= offMin) {
            // 같은 날: 08:00~22:00
            nowMin in onMin until offMin
        } else {
            // 자정 넘김: 22:00~08:00 → 22:00이후 또는 08:00이전
            nowMin >= onMin || nowMin < offMin
        }
    }

    private fun timeToMinutes(timeStr: String): Int? {
        val parts = timeStr.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun isAutoEnabled(): Boolean =
        AdManager.isAdEnabled() && isWithinActiveWindow()

    /** 쿠팡: zone=2(off)? */
    fun shouldCoupangOff(): Boolean {
        if (!isAutoEnabled() || !AdManager.isCoupangAutoEnabled()) return false
        return AdManager.getCoupangZoneAt(OrderTracker.getOrderCount()) == 2
    }
    /** 쿠팡: zone=1(on)? */
    fun shouldCoupangOn(): Boolean {
        if (!isAutoEnabled() || !AdManager.isCoupangAutoEnabled()) return false
        return AdManager.getCoupangZoneAt(OrderTracker.getOrderCount()) == 1
    }
    /** 배민: zone=3(min) → 축소금액 */
    fun shouldBaeminOff(): Boolean {
        if (!isAutoEnabled() || !AdManager.isBaeminAutoEnabled()) return false
        return AdManager.getBaeminZoneAt(OrderTracker.getOrderCount()) == 3
    }
    /** 배민: zone=2(mid) → 중간금액 */
    fun shouldBaeminMid(): Boolean {
        if (!isAutoEnabled() || !AdManager.isBaeminAutoEnabled()) return false
        return AdManager.getBaeminZoneAt(OrderTracker.getOrderCount()) == 2
    }
    /** 배민: zone=1(max) → 정상금액 */
    fun shouldBaeminOn(): Boolean {
        if (!isAutoEnabled() || !AdManager.isBaeminAutoEnabled()) return false
        return AdManager.getBaeminZoneAt(OrderTracker.getOrderCount()) == 1
    }

    private const val KEY_LAST_BG_COUPANG = "last_bg_coupang"
    private const val KEY_LAST_BG_BAEMIN = "last_bg_baemin"

    /** 백그라운드에서 주문 건수 변경 시 — 현재 상태 기반으로 필요시 실행 */
    fun checkFromBackground(context: Context, count: Int) {
        if (!AdManager.isAdEnabled()) return
        if (!isWithinActiveWindow()) return
        // 포그라운드면 checkPlatformThresholds()가 처리 → 중복 방지
        if (MainActivity.isInForeground) return
        val prefs = context.getSharedPreferences("ad_scheduler_bg", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var needOff = false
        var needOn = false

        // 쿠팡: zone 기반
        val coupangOn = AdManager.coupangCurrentOn.value
        val cZone = AdManager.getCoupangZoneAt(count)
        val lastCoupang = prefs.getLong(KEY_LAST_BG_COUPANG, 0L)
        if (AdManager.isCoupangAutoEnabled() && now - lastCoupang >= 5 * 60 * 1000) {
            if (cZone == 2 && coupangOn != false) {
                prefs.edit().putLong(KEY_LAST_BG_COUPANG, now).apply()
                needOff = true
            } else if (cZone == 1 && coupangOn == false) {
                prefs.edit().putLong(KEY_LAST_BG_COUPANG, now).apply()
                needOn = true
            }
        }

        // 배민: zone 기반
        val bid = AdManager.getBaeminCurrentBid()
        val normalAmount = AdManager.getBaeminAmount()
        val midAmount = AdManager.getBaeminMidAmount()
        val reducedAmount = AdManager.getBaeminReducedAmount()
        val bZone = AdManager.getBaeminZoneAt(count)
        val lastBaemin = prefs.getLong(KEY_LAST_BG_BAEMIN, 0L)
        if (AdManager.isBaeminAutoEnabled() && now - lastBaemin >= 5 * 60 * 1000) {
            val targetAmount = when (bZone) {
                3 -> reducedAmount
                2 -> midAmount
                1 -> normalAmount
                else -> null  // hold
            }
            if (targetAmount != null && bid > 0 && bid != targetAmount) {
                prefs.edit().putLong(KEY_LAST_BG_BAEMIN, now).apply()
                if (targetAmount < normalAmount) needOff = true else needOn = true
            }
        }

        if (needOff) launchAdAction(context, "ad_auto_off")
        else if (needOn) launchAdAction(context, "ad_auto_on")
    }

    private fun launchAdAction(context: Context, action: String) {
        Log.d(TAG, "Background trigger: $action")
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("ad_scheduled_action", action)
        }
        context.startActivity(intent)
    }
}

class AdAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AdAlarmReceiver", "Received: ${intent.action}")

        AdManager.init(context)
        OrderTracker.init(context)

        when (intent.action) {
            AdScheduler.ACTION_AD_OFF -> {
                val cnt = OrderTracker.getOrderCount()
                AdManager.setLastAdAction("스케줄 광고끄기")
                DelayNotificationHelper.showAdAlert(context, "${cnt}건 광고끄기")
                launchWithAction(context, "ad_off")
                AdScheduler.scheduleAlarms(context)
            }
            AdScheduler.ACTION_AD_ON -> {
                val cnt = OrderTracker.getOrderCount()
                AdManager.setLastAdAction("스케줄 광고켜기")
                DelayNotificationHelper.showAdAlert(context, "${cnt}건 광고켜기")
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
