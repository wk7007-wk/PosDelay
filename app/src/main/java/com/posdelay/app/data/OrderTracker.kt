package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object OrderTracker {

    private var kdsLastRawCount = -1

    private const val PREFS_NAME = "pos_delay_prefs"
    private lateinit var appContext: android.content.Context
    private const val KEY_ORDER_COUNT = "order_count"
    private const val KEY_COUPANG_THRESHOLD = "coupang_threshold"
    private const val KEY_BAEMIN_THRESHOLD = "baemin_threshold"
    private const val KEY_DELAY_MINUTES = "delay_minutes"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_MODE = "auto_mode"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_LAST_KDS_SYNC_TIME = "last_kds_sync_time"
    private const val KEY_KDS_PAUSED = "kds_paused"

    private lateinit var prefs: SharedPreferences

    private val _kdsPaused = MutableLiveData(false)
    val kdsPaused: LiveData<Boolean> = _kdsPaused

    private val _orderCount = MutableLiveData(0)
    val orderCount: LiveData<Int> = _orderCount

    private val _coupangThreshold = MutableLiveData(10)
    val coupangThreshold: LiveData<Int> = _coupangThreshold

    private val _baeminThreshold = MutableLiveData(15)
    val baeminThreshold: LiveData<Int> = _baeminThreshold

    private val _delayMinutes = MutableLiveData(10)
    val delayMinutes: LiveData<Int> = _delayMinutes

    private val _enabled = MutableLiveData(true)
    val enabled: LiveData<Boolean> = _enabled

    private val _autoMode = MutableLiveData(false)
    val autoMode: LiveData<Boolean> = _autoMode

    private val _lastSyncTime = MutableLiveData(0L)
    val lastSyncTime: LiveData<Long> = _lastSyncTime

    private val _lastKdsSyncTime = MutableLiveData(0L)
    val lastKdsSyncTime: LiveData<Long> = _lastKdsSyncTime

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _orderCount.postValue(prefs.getInt(KEY_ORDER_COUNT, 0))
        _coupangThreshold.postValue(prefs.getInt(KEY_COUPANG_THRESHOLD, 10))
        _baeminThreshold.postValue(prefs.getInt(KEY_BAEMIN_THRESHOLD, 15))
        _delayMinutes.postValue(prefs.getInt(KEY_DELAY_MINUTES, 10))
        _enabled.postValue(prefs.getBoolean(KEY_ENABLED, true))
        _autoMode.postValue(prefs.getBoolean(KEY_AUTO_MODE, false))
        _lastSyncTime.postValue(prefs.getLong(KEY_LAST_SYNC_TIME, 0L))
        _kdsPaused.postValue(prefs.getBoolean(KEY_KDS_PAUSED, false))
        _lastKdsSyncTime.postValue(prefs.getLong(KEY_LAST_KDS_SYNC_TIME, 0L))
    }

    fun getOrderCount(): Int = prefs.getInt(KEY_ORDER_COUNT, 0)
    fun getCoupangThreshold(): Int = prefs.getInt(KEY_COUPANG_THRESHOLD, 10)
    fun getBaeminThreshold(): Int = prefs.getInt(KEY_BAEMIN_THRESHOLD, 15)
    fun getDelayMinutes(): Int = prefs.getInt(KEY_DELAY_MINUTES, 10)
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    fun isAutoMode(): Boolean = prefs.getBoolean(KEY_AUTO_MODE, false)
    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    fun isKdsPaused(): Boolean = prefs.getBoolean(KEY_KDS_PAUSED, false)
    fun setKdsPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_KDS_PAUSED, paused).apply()
        _kdsPaused.postValue(paused)
    }

    fun setOrderCount(count: Int) {
        val value = maxOf(0, count)
        prefs.edit().putInt(KEY_ORDER_COUNT, value).apply()
        _orderCount.postValue(value)
        try {
            com.posdelay.app.service.FirebaseSettingsSync.onOrderCountChanged()
        } catch (_: Exception) {}
        try {
            com.posdelay.app.service.DelayAlertManager.onCountChanged(value)
        } catch (_: Exception) {}
    }

    /** KDS (주방 디스플레이)에서 읽은 건수로 동기화 — 유일한 소스, 즉시 반영 */
    fun syncKdsOrderCount(count: Int, kdsTime: Long) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, now)
            .putLong(KEY_LAST_KDS_SYNC_TIME, now)
            .apply()
        _lastSyncTime.postValue(now)
        _lastKdsSyncTime.postValue(now)

        if (count == getOrderCount()) {
            kdsLastRawCount = count
            return
        }

        kdsLastRawCount = count
        setOrderCount(count)
        android.util.Log.d("OrderTracker", "KDS 건수 즉시 반영: $count")
        LogFileWriter.append("SYNC", "건수=$count (즉시반영)")
    }

    fun getLastKdsSyncTime(): Long = prefs.getLong(KEY_LAST_KDS_SYNC_TIME, 0L)

    fun incrementOrder() {
        setOrderCount(getOrderCount() + 1)
    }

    fun setCoupangThreshold(value: Int) {
        val clamped = value.coerceIn(1, 50)
        prefs.edit().putInt(KEY_COUPANG_THRESHOLD, clamped).apply()
        _coupangThreshold.postValue(clamped)
    }

    fun setBaeminThreshold(value: Int) {
        val clamped = value.coerceIn(1, 50)
        prefs.edit().putInt(KEY_BAEMIN_THRESHOLD, clamped).apply()
        _baeminThreshold.postValue(clamped)
    }

    fun setDelayMinutes(value: Int) {
        val clamped = value.coerceIn(5, 60)
        prefs.edit().putInt(KEY_DELAY_MINUTES, clamped).apply()
        _delayMinutes.postValue(clamped)
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.postValue(value)
    }

    fun setAutoMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MODE, value).apply()
        _autoMode.postValue(value)
    }

    fun resetCount() {
        setOrderCount(0)
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, now).apply()
        _lastSyncTime.postValue(now)
    }

    fun shouldDelayCoupang(): Boolean {
        return isEnabled() && getOrderCount() >= getCoupangThreshold()
    }

    fun shouldDelayBaemin(): Boolean {
        return isEnabled() && getOrderCount() >= getBaeminThreshold()
    }
}
