package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object OrderTracker {

    private val kdsHandler = Handler(Looper.getMainLooper())
    private var kdsStabilizeRunnable: Runnable? = null
    private var kdsLastRawCount = -1
    private const val KDS_STABILIZE_MS = 90_000L  // 90초 안정화 (하트비트 3회분)

    // Gist KDS 교차 보정용
    @Volatile var gistKdsCount = -1       // Gist에서 읽은 KDS 건수
    @Volatile var gistKdsTime = 0L        // Gist KDS 데이터 시간

    // 비-KDS 소스(알림/MATE) 마지막 업데이트 시간 — KDS 0 덮어쓰기 방지용
    @Volatile private var lastNonKdsUpdateTime = 0L
    private const val NON_KDS_PROTECT_MS = 10 * 60 * 1000L  // 10분간 보호

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
        // 포그라운드에서만 광고 판단 (LiveData observer)
        // 백그라운드 화면 강제 전환 제거 — 스케줄 알람만 화면 전환
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
        if (count > 0) lastNonKdsUpdateTime = now
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, now).apply()
        _lastSyncTime.postValue(now)
    }

    /** PC (Gist)에서 읽은 건수로 동기화 — PC 시간 기록 */
    fun syncPcOrderCount(count: Int, pcTime: Long) {
        setOrderCount(count)
        val now = System.currentTimeMillis()
        // Bug 6: sync time = 수신 시각(now) 사용 (stale 판정 일관성)
        prefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, now)
            .putLong(KEY_LAST_PC_SYNC_TIME, now)
            .apply()
        _lastSyncTime.postValue(now)
        _lastPcSyncTime.postValue(now)
    }

    /** PC 동기화 시간만 업데이트 (오래된 데이터용 — 건수는 변경 안 함) */
    fun updatePcSyncTime(pcTime: Long) {
        prefs.edit().putLong(KEY_LAST_PC_SYNC_TIME, pcTime).apply()
        _lastPcSyncTime.postValue(pcTime)
    }

    /** KDS (주방 디스플레이)에서 읽은 건수로 동기화 — 최우선 소스
     *  교차 보정: Firebase 값과 Gist 값 비교 → 한쪽이라도 양수면 양수 신뢰
     *  안정화: 보정 후에도 양수→즉시, 0→90초 대기 (하트비트 3회분) */
    fun syncKdsOrderCount(count: Int, kdsTime: Long) {
        val now = System.currentTimeMillis()
        // KDS sync 시간은 항상 업데이트 (stale 판정용)
        prefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, now)
            .putLong(KEY_LAST_KDS_SYNC_TIME, now)
            .apply()
        _lastSyncTime.postValue(now)
        _lastKdsSyncTime.postValue(now)

        // 교차 보정: Firebase 0 + Gist 양수 → Gist 값 신뢰
        val gistAge = now - gistKdsTime
        val corrected = if (count == 0 && gistKdsCount > 0 && gistAge < 120_000L) {
            // Firebase가 0인데 Gist에 2분 이내 양수 → Gist 값 사용
            android.util.Log.d("OrderTracker", "교차보정: Firebase=0, Gist=$gistKdsCount → $gistKdsCount 사용")
            LogFileWriter.append("SYNC", "교차보정 FB=0→Gist=$gistKdsCount")
            gistKdsCount
        } else if (count > 0 && gistKdsCount == 0 && gistAge < 120_000L) {
            // Firebase 양수, Gist 0 → Firebase 값 신뢰 (Gist 업데이트 지연)
            count
        } else {
            count
        }

        // 건수가 현재와 동일하면 무시
        if (corrected == getOrderCount()) {
            kdsLastRawCount = corrected
            kdsStabilizeRunnable?.let { kdsHandler.removeCallbacks(it) }
            kdsStabilizeRunnable = null
            return
        }

        kdsLastRawCount = corrected

        if (corrected > 0) {
            // 양수 → 즉시 반영 (0 대기 취소)
            kdsStabilizeRunnable?.let { kdsHandler.removeCallbacks(it) }
            kdsStabilizeRunnable = null
            setOrderCount(corrected)
            android.util.Log.d("OrderTracker", "KDS 건수 즉시 반영: $corrected")
            LogFileWriter.append("SYNC", "건수=$corrected (즉시반영)")
        } else {
            // KDS 0인데, 최근 알림/MATE에서 양수 건수 → KDS 0 무시 (주방폰 화면잠김 등)
            val currentCount = getOrderCount()
            val nonKdsAge = now - lastNonKdsUpdateTime
            if (currentCount > 0 && lastNonKdsUpdateTime > 0 && nonKdsAge < NON_KDS_PROTECT_MS) {
                android.util.Log.d("OrderTracker", "KDS 0 무시: 알림/MATE 건수=$currentCount (${nonKdsAge/1000}초전)")
                LogFileWriter.append("SYNC", "KDS 0 무시: 알림건수=$currentCount (${nonKdsAge/1000}초전)")
                kdsStabilizeRunnable?.let { kdsHandler.removeCallbacks(it) }
                kdsStabilizeRunnable = null
                return
            }
            // 0 → Gist도 0인지 재확인, 30초 대기 후 반영
            kdsStabilizeRunnable?.let { kdsHandler.removeCallbacks(it) }
            kdsStabilizeRunnable = Runnable {
                // 안정화 시점에도 비-KDS 보호 재확인
                val curCount = getOrderCount()
                val nkAge = System.currentTimeMillis() - lastNonKdsUpdateTime
                if (curCount > 0 && lastNonKdsUpdateTime > 0 && nkAge < NON_KDS_PROTECT_MS) {
                    android.util.Log.d("OrderTracker", "KDS 0 안정화 취소: 알림건수=$curCount")
                    LogFileWriter.append("SYNC", "KDS 0 안정화취소: 알림건수=$curCount")
                    return@Runnable
                }
                if (kdsLastRawCount == 0 && (gistKdsCount <= 0 || System.currentTimeMillis() - gistKdsTime > 120_000L)) {
                    setOrderCount(0)
                    android.util.Log.d("OrderTracker", "KDS 건수 0 안정화 반영 (Gist도 0 확인)")
                    LogFileWriter.append("SYNC", "건수=0 (90초안정화+Gist확인)")
                } else if (kdsLastRawCount == 0 && gistKdsCount > 0) {
                    // 30초 후에도 Gist가 양수 → Gist 값 사용
                    setOrderCount(gistKdsCount)
                    android.util.Log.d("OrderTracker", "KDS 0 대기중 Gist=$gistKdsCount → Gist값 반영")
                    LogFileWriter.append("SYNC", "건수=Gist $gistKdsCount (90초후 Gist양수)")
                }
            }
            kdsHandler.postDelayed(kdsStabilizeRunnable!!, KDS_STABILIZE_MS)
        }
    }

    fun getLastKdsSyncTime(): Long = prefs.getLong(KEY_LAST_KDS_SYNC_TIME, 0L)

    fun incrementOrder() {
        lastNonKdsUpdateTime = System.currentTimeMillis()
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
