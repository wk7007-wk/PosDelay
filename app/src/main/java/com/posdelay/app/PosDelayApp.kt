package com.posdelay.app

import android.app.Application
import com.posdelay.app.data.NotificationLog
import com.posdelay.app.data.OrderTracker
import com.posdelay.app.service.DelayNotificationHelper

class PosDelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OrderTracker.init(this)
        NotificationLog.init(this)
        DelayNotificationHelper.createChannels(this)
    }
}
