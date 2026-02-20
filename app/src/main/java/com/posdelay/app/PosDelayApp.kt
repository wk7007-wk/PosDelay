package com.posdelay.app

import android.app.Application
import com.posdelay.app.data.AdActionLog
import com.posdelay.app.data.AdManager
import com.posdelay.app.data.DelayActionLog
import com.posdelay.app.data.NotificationLog
import com.posdelay.app.data.OrderTracker
import com.posdelay.app.service.AdScheduler
import com.posdelay.app.service.DelayNotificationHelper
import com.posdelay.app.service.DelayAlertManager
import com.posdelay.app.service.FirebaseSettingsSync

class PosDelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OrderTracker.init(this)
        NotificationLog.init(this)
        AdManager.init(this)
        AdActionLog.init(this)
        DelayActionLog.init(this)
        DelayNotificationHelper.createChannels(this)
        AdScheduler.scheduleAlarms(this)
        FirebaseSettingsSync.start(this)
        DelayAlertManager.init(this)
        DelayAlertManager.startPeriodicCheck()
    }
}
