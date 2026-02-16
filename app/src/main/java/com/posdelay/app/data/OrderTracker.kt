package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object OrderTracker {

    private const val PREFS_NAME = "pos_delay_prefs"
    private const val KEY_ORDER_COUNT = "order_count"
    private const val KEY_COUPANG_THRESHOLD = "coupang_threshold"
    private const val KEY_BAEMIN_THRESHOLD = "baemin_threshold"
    private const val KEY_DELAY_MINUTES = "delay_minutes"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_MODE = "auto_mode"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_LAST_PC_SYNC_TIME = "last_pc_sync_time"

    private lateinit var prefs: SharedPreferences

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

    private val _lastPcSyncTime = MutableLiveData(0L)
    val lastPcSyncTime: LiveData<Long> = _lastPcSyncTime

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _orderCount.postValue(prefs.getInt(KEY_ORDER_COUNT, 0))
        _coupangThreshold.postValue(prefs.getInt(KEY_COUPANG_THRESHOLD, 10))
        _baeminThreshold.postValue(prefs.getInt(KEY_BAEMIN_THRESHOLD, 15))
        _delayMinutes.postValue(prefs.getInt(KEY_DELAY_MINUTES, 10))
        _enabled.postValue(prefs.getBoolean(KEY_ENABLED, true))
        _autoMode.postValue(prefs.getBoolean(KEY_AUTO_MODE, false))
        _lastSyncTime.postValue(prefs.getLong(KEY_LAST_SYNC_TIME, 0L))
        _lastPcSyncTime.postValue(prefs.getLong(KEY_LAST_PC_SYNC_TIME, 0L))
    }

    fun getOrderCount(): Int = prefs.getInt(KEY_ORDER_COUNT, 0)
    fun getCoupangThreshold(): Int = prefs.getInt(KEY_COUPANG_THRESHOLD, 10)
    fun getBaeminThreshold(): Int = prefs.getInt(KEY_BAEMIN_THRESHOLD, 15)
    fun getDelayMinutes(): Int = prefs.getInt(KEY_DELAY_MINUTES, 10)
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    fun isAutoMode(): Boolean = prefs.getBoolean(KEY_AUTO_MODE, false)
    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    fun setOrderCount(count: Int) {
        val value = maxOf(0, count)
        prefs.edit().putInt(KEY_ORDER_COUNT, value).apply()
        _orderCount.postValue(value)
    }

    /** MATE 화면에서 읽은 정확한 건수로 동기화 (시간 기록) */
    fun syncOrderCount(count: Int) {
        setOrderCount(count)
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, now).apply()
        _lastSyncTime.postValue(now)
    }

    /** PC (Gist)에서 읽은 건수로 동기화 — PC 시간 기록 */
    fun syncPcOrderCount(count: Int, pcTime: Long) {
        setOrderCount(count)
        prefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .putLong(KEY_LAST_PC_SYNC_TIME, pcTime)
            .apply()
        _lastSyncTime.postValue(System.currentTimeMillis())
        _lastPcSyncTime.postValue(pcTime)
    }

    /** PC 동기화 시간만 업데이트 (오래된 데이터용 — 건수는 변경 안 함) */
    fun updatePcSyncTime(pcTime: Long) {
        prefs.edit().putLong(KEY_LAST_PC_SYNC_TIME, pcTime).apply()
        _lastPcSyncTime.postValue(pcTime)
    }

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
        syncOrderCount(0)
    }

    fun shouldDelayCoupang(): Boolean {
        return isEnabled() && getOrderCount() >= getCoupangThreshold()
    }

    fun shouldDelayBaemin(): Boolean {
        return isEnabled() && getOrderCount() >= getBaeminThreshold()
    }
}
