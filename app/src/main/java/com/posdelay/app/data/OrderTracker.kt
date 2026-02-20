package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object OrderTracker {

    private const val PREFS_NAME = "pos_delay_prefs"
    private lateinit var appContext: android.content.Context
    private const val KEY_ORDER_COUNT = "order_count"
    private const val KEY_COUPANG_THRESHOLD = "coupang_threshold"
    private const val KEY_BAEMIN_THRESHOLD = "baemin_threshold"
    private const val KEY_DELAY_MINUTES = "delay_minutes"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_MODE = "auto_mode"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_LAST_PC_SYNC_TIME = "last_pc_sync_time"
    private const val KEY_MATE_PAUSED = "mate_paused"
    private const val KEY_PC_PAUSED = "pc_paused"
    private const val KEY_MATE_AUTO_MANAGED = "mate_auto_managed"
    private const val KEY_LAST_KDS_SYNC_TIME = "last_kds_sync_time"
    private const val KEY_KDS_PAUSED = "kds_paused"

    private lateinit var prefs: SharedPreferences

    private val _matePaused = MutableLiveData(false)
    val matePaused: LiveData<Boolean> = _matePaused

    private val _pcPaused = MutableLiveData(false)
    val pcPaused: LiveData<Boolean> = _pcPaused

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

    private val _lastPcSyncTime = MutableLiveData(0L)
    val lastPcSyncTime: LiveData<Long> = _lastPcSyncTime

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
        _lastPcSyncTime.postValue(prefs.getLong(KEY_LAST_PC_SYNC_TIME, 0L))
        _matePaused.postValue(prefs.getBoolean(KEY_MATE_PAUSED, true))  // MATE 기본 꺼짐 (보조역할)
        _pcPaused.postValue(prefs.getBoolean(KEY_PC_PAUSED, false))
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

    fun isMatePaused(): Boolean = prefs.getBoolean(KEY_MATE_PAUSED, true)
    fun setMatePaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_MATE_PAUSED, paused).apply()
        _matePaused.postValue(paused)
    }

    fun isMateAutoManaged(): Boolean = prefs.getBoolean(KEY_MATE_AUTO_MANAGED, false)
    fun setMateAutoManaged(value: Boolean) {
        prefs.edit().putBoolean(KEY_MATE_AUTO_MANAGED, value).apply()
    }

    fun getLastPcSyncTime(): Long = prefs.getLong(KEY_LAST_PC_SYNC_TIME, 0L)

    fun isPcPaused(): Boolean = prefs.getBoolean(KEY_PC_PAUSED, false)
    fun setPcPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PC_PAUSED, paused).apply()
        _pcPaused.postValue(paused)
    }

    fun isKdsPaused(): Boolean = prefs.getBoolean(KEY_KDS_PAUSED, false)
    fun setKdsPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_KDS_PAUSED, paused).apply()
        _kdsPaused.postValue(paused)
    }

    fun setOrderCount(count: Int) {
        val value = maxOf(0, count)
        prefs.edit().putInt(KEY_ORDER_COUNT, value).apply()
        _orderCount.postValue(value)
        // 백그라운드에서도 광고 임계값 체크
        try {
            com.posdelay.app.service.AdScheduler.checkFromBackground(appContext, value)
        } catch (_: Exception) {}
        // Firebase 상태 업로드
        try {
            com.posdelay.app.service.FirebaseSettingsSync.onOrderCountChanged()
        } catch (_: Exception) {}
        // 알림 체크
        try {
            com.posdelay.app.service.DelayAlertManager.onCountChanged(value)
        } catch (_: Exception) {}
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

    /** KDS (주방 디스플레이)에서 읽은 건수로 동기화 — 최우선 소스 */
    fun syncKdsOrderCount(count: Int, kdsTime: Long) {
        setOrderCount(count)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, now)
            .putLong(KEY_LAST_KDS_SYNC_TIME, kdsTime)
            .apply()
        _lastSyncTime.postValue(now)
        _lastKdsSyncTime.postValue(kdsTime)
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
        syncOrderCount(0)
    }

    fun shouldDelayCoupang(): Boolean {
        return isEnabled() && getOrderCount() >= getCoupangThreshold()
    }

    fun shouldDelayBaemin(): Boolean {
        return isEnabled() && getOrderCount() >= getBaeminThreshold()
    }
}
