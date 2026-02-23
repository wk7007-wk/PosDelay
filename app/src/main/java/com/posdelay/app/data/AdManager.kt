package com.posdelay.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AdManager {

    private const val PREFS_NAME = "ad_manager_prefs"
    private const val SECURE_PREFS_NAME = "ad_manager_secure_prefs"

    // Regular prefs keys
    private const val KEY_AD_ENABLED = "ad_enabled"
    private const val KEY_BAEMIN_AMOUNT = "baemin_amount"
    private const val KEY_BAEMIN_REDUCED_AMOUNT = "baemin_reduced_amount"
    private const val KEY_COUPANG_AD_ON = "coupang_ad_on"
    private const val KEY_AD_OFF_TIME = "ad_off_time"
    private const val KEY_AD_ON_TIME = "ad_on_time"
    private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    private const val KEY_ORDER_AUTO_OFF_ENABLED = "order_auto_off_enabled"
    private const val KEY_COUPANG_AUTO_ENABLED = "coupang_auto_enabled"
    private const val KEY_BAEMIN_AUTO_ENABLED = "baemin_auto_enabled"
    private const val KEY_COUPANG_OFF_THRESHOLD = "coupang_off_threshold"
    private const val KEY_COUPANG_ON_THRESHOLD = "coupang_on_threshold"
    private const val KEY_BAEMIN_OFF_THRESHOLD = "baemin_off_threshold"
    private const val KEY_BAEMIN_MID_THRESHOLD = "baemin_mid_threshold"
    private const val KEY_BAEMIN_ON_THRESHOLD = "baemin_on_threshold"
    private const val KEY_BAEMIN_MID_UPPER_THRESHOLD = "baemin_mid_upper_threshold"
    private const val KEY_BAEMIN_MID_AMOUNT = "baemin_mid_amount"
    private const val KEY_LAST_AD_ACTION = "last_ad_action"
    private const val KEY_BAEMIN_CURRENT_BID = "baemin_current_bid"
    private const val KEY_COUPANG_CURRENT_ON = "coupang_current_on"

    // 지연 설정 keys (플랫폼별)
    private const val KEY_BAEMIN_TARGET_TIME = "baemin_target_time"
    private const val KEY_BAEMIN_FIXED_COOK_TIME = "baemin_fixed_cook_time"
    private const val KEY_BAEMIN_DELAY_THRESHOLD = "baemin_delay_threshold"
    private const val KEY_COUPANG_TARGET_TIME = "coupang_target_time"
    private const val KEY_COUPANG_FIXED_COOK_TIME = "coupang_fixed_cook_time"
    private const val KEY_COUPANG_DELAY_THRESHOLD = "coupang_delay_threshold"

    // Secure prefs keys
    private const val KEY_BAEMIN_ID = "baemin_id"
    private const val KEY_BAEMIN_PW = "baemin_pw"
    private const val KEY_COUPANG_ID = "coupang_id"
    private const val KEY_COUPANG_PW = "coupang_pw"

    private lateinit var prefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences

    // LiveData
    private val _adEnabled = MutableLiveData(false)
    val adEnabled: LiveData<Boolean> = _adEnabled

    private val _baeminAmount = MutableLiveData(200)
    val baeminAmount: LiveData<Int> = _baeminAmount

    private val _baeminReducedAmount = MutableLiveData(50)
    val baeminReducedAmount: LiveData<Int> = _baeminReducedAmount

    private val _coupangAdOn = MutableLiveData(true)
    val coupangAdOn: LiveData<Boolean> = _coupangAdOn

    private val _adOffTime = MutableLiveData("22:00")
    val adOffTime: LiveData<String> = _adOffTime

    private val _adOnTime = MutableLiveData("08:00")
    val adOnTime: LiveData<String> = _adOnTime

    private val _scheduleEnabled = MutableLiveData(false)
    val scheduleEnabled: LiveData<Boolean> = _scheduleEnabled

    private val _orderAutoOffEnabled = MutableLiveData(false)
    val orderAutoOffEnabled: LiveData<Boolean> = _orderAutoOffEnabled

    private val _coupangAutoEnabled = MutableLiveData(false)
    val coupangAutoEnabled: LiveData<Boolean> = _coupangAutoEnabled

    private val _baeminAutoEnabled = MutableLiveData(false)
    val baeminAutoEnabled: LiveData<Boolean> = _baeminAutoEnabled

    private val _coupangOffThreshold = MutableLiveData(5)
    val coupangOffThreshold: LiveData<Int> = _coupangOffThreshold
    private val _coupangOnThreshold = MutableLiveData(3)
    val coupangOnThreshold: LiveData<Int> = _coupangOnThreshold

    private val _baeminOffThreshold = MutableLiveData(8)
    val baeminOffThreshold: LiveData<Int> = _baeminOffThreshold
    private val _baeminMidThreshold = MutableLiveData(5)
    val baeminMidThreshold: LiveData<Int> = _baeminMidThreshold
    private val _baeminOnThreshold = MutableLiveData(3)
    val baeminOnThreshold: LiveData<Int> = _baeminOnThreshold

    private val _baeminMidUpperThreshold = MutableLiveData(7)
    val baeminMidUpperThreshold: LiveData<Int> = _baeminMidUpperThreshold

    private val _baeminMidAmount = MutableLiveData(100)
    val baeminMidAmount: LiveData<Int> = _baeminMidAmount

    // 로컬 변경 보호: SSE 덮어쓰기 방지 (키별 변경 시각)
    private val localChangeGuard = mutableMapOf<String, Long>()
    private const val GUARD_DURATION_MS = 30_000L // 30초간 SSE 덮어쓰기 차단

    fun isGuarded(key: String): Boolean {
        val ts = localChangeGuard[key] ?: return false
        return System.currentTimeMillis() - ts < GUARD_DURATION_MS
    }

    private fun markLocalChange(key: String) {
        localChangeGuard[key] = System.currentTimeMillis()
    }

    private val _lastAdAction = MutableLiveData("")
    val lastAdAction: LiveData<String> = _lastAdAction

    private val _baeminCurrentBid = MutableLiveData(0)
    val baeminCurrentBid: LiveData<Int> = _baeminCurrentBid

    private val _coupangCurrentOn = MutableLiveData<Boolean?>(null)
    val coupangCurrentOn: LiveData<Boolean?> = _coupangCurrentOn

    // 지연 설정 LiveData
    private val _baeminTargetTime = MutableLiveData(20)
    val baeminTargetTime: LiveData<Int> = _baeminTargetTime
    private val _baeminFixedCookTime = MutableLiveData(15)
    val baeminFixedCookTime: LiveData<Int> = _baeminFixedCookTime
    private val _baeminDelayThreshold = MutableLiveData(5)
    val baeminDelayThreshold: LiveData<Int> = _baeminDelayThreshold
    private val _coupangTargetTime = MutableLiveData(20)
    val coupangTargetTime: LiveData<Int> = _coupangTargetTime
    private val _coupangFixedCookTime = MutableLiveData(15)
    val coupangFixedCookTime: LiveData<Int> = _coupangFixedCookTime
    private val _coupangDelayThreshold = MutableLiveData(5)
    val coupangDelayThreshold: LiveData<Int> = _coupangDelayThreshold

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            securePrefs = EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular prefs if encryption fails
            securePrefs = context.getSharedPreferences(SECURE_PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }

        _adEnabled.postValue(prefs.getBoolean(KEY_AD_ENABLED, false))
        _baeminAmount.postValue(prefs.getInt(KEY_BAEMIN_AMOUNT, 200))
        _baeminReducedAmount.postValue(prefs.getInt(KEY_BAEMIN_REDUCED_AMOUNT, 50))
        _coupangAdOn.postValue(prefs.getBoolean(KEY_COUPANG_AD_ON, true))
        _adOffTime.postValue(prefs.getString(KEY_AD_OFF_TIME, "22:00") ?: "22:00")
        _adOnTime.postValue(prefs.getString(KEY_AD_ON_TIME, "08:00") ?: "08:00")
        _scheduleEnabled.postValue(prefs.getBoolean(KEY_SCHEDULE_ENABLED, false))
        _orderAutoOffEnabled.postValue(prefs.getBoolean(KEY_ORDER_AUTO_OFF_ENABLED, false))
        _coupangAutoEnabled.postValue(prefs.getBoolean(KEY_COUPANG_AUTO_ENABLED, true))
        _baeminAutoEnabled.postValue(prefs.getBoolean(KEY_BAEMIN_AUTO_ENABLED, true))
        _coupangOffThreshold.postValue(prefs.getInt(KEY_COUPANG_OFF_THRESHOLD, 5))
        _coupangOnThreshold.postValue(prefs.getInt(KEY_COUPANG_ON_THRESHOLD, 3))
        _baeminOffThreshold.postValue(prefs.getInt(KEY_BAEMIN_OFF_THRESHOLD, 8))
        _baeminMidThreshold.postValue(prefs.getInt(KEY_BAEMIN_MID_THRESHOLD, 5))
        _baeminOnThreshold.postValue(prefs.getInt(KEY_BAEMIN_ON_THRESHOLD, 3))
        _baeminMidUpperThreshold.postValue(prefs.getInt(KEY_BAEMIN_MID_UPPER_THRESHOLD, getBaeminOffThreshold() - 1))
        _baeminMidAmount.postValue(prefs.getInt(KEY_BAEMIN_MID_AMOUNT, 100))
        _lastAdAction.postValue(prefs.getString(KEY_LAST_AD_ACTION, "") ?: "")
        _baeminCurrentBid.postValue(prefs.getInt(KEY_BAEMIN_CURRENT_BID, 0))
        if (prefs.contains(KEY_COUPANG_CURRENT_ON)) {
            _coupangCurrentOn.postValue(prefs.getBoolean(KEY_COUPANG_CURRENT_ON, true))
        }
        // 지연 설정 로드
        _baeminTargetTime.postValue(prefs.getInt(KEY_BAEMIN_TARGET_TIME, 20))
        _baeminFixedCookTime.postValue(prefs.getInt(KEY_BAEMIN_FIXED_COOK_TIME, 15))
        _baeminDelayThreshold.postValue(prefs.getInt(KEY_BAEMIN_DELAY_THRESHOLD, 5))
        _coupangTargetTime.postValue(prefs.getInt(KEY_COUPANG_TARGET_TIME, 20))
        _coupangFixedCookTime.postValue(prefs.getInt(KEY_COUPANG_FIXED_COOK_TIME, 15))
        _coupangDelayThreshold.postValue(prefs.getInt(KEY_COUPANG_DELAY_THRESHOLD, 5))
    }

    /** SharedPreferences에 설정이 저장된 적 있는지 (초기 설치/업데이트 후 비어있으면 false) */
    fun hasStoredSettings(): Boolean = prefs.contains(KEY_BAEMIN_AMOUNT)

    // Getters
    fun isAdEnabled(): Boolean = prefs.getBoolean(KEY_AD_ENABLED, false)
    fun getBaeminAmount(): Int = prefs.getInt(KEY_BAEMIN_AMOUNT, 200)
    fun getBaeminReducedAmount(): Int = prefs.getInt(KEY_BAEMIN_REDUCED_AMOUNT, 50)
    fun isCoupangAdOn(): Boolean = prefs.getBoolean(KEY_COUPANG_AD_ON, true)
    fun getAdOffTime(): String = prefs.getString(KEY_AD_OFF_TIME, "22:00") ?: "22:00"
    fun getAdOnTime(): String = prefs.getString(KEY_AD_ON_TIME, "08:00") ?: "08:00"
    fun isScheduleEnabled(): Boolean = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
    fun isOrderAutoOffEnabled(): Boolean = prefs.getBoolean(KEY_ORDER_AUTO_OFF_ENABLED, false)
    fun isCoupangAutoEnabled(): Boolean = prefs.getBoolean(KEY_COUPANG_AUTO_ENABLED, true)
    fun isBaeminAutoEnabled(): Boolean = prefs.getBoolean(KEY_BAEMIN_AUTO_ENABLED, true)
    fun getCoupangOffThreshold(): Int = prefs.getInt(KEY_COUPANG_OFF_THRESHOLD, 5)
    fun getCoupangOnThreshold(): Int = prefs.getInt(KEY_COUPANG_ON_THRESHOLD, 3)
    fun getBaeminOffThreshold(): Int = prefs.getInt(KEY_BAEMIN_OFF_THRESHOLD, 8)
    fun getBaeminMidThreshold(): Int = prefs.getInt(KEY_BAEMIN_MID_THRESHOLD, 5)
    fun getBaeminOnThreshold(): Int = prefs.getInt(KEY_BAEMIN_ON_THRESHOLD, 3)
    fun getBaeminMidUpperThreshold(): Int = prefs.getInt(KEY_BAEMIN_MID_UPPER_THRESHOLD, getBaeminOffThreshold() - 1)
    fun getBaeminMidAmount(): Int = prefs.getInt(KEY_BAEMIN_MID_AMOUNT, 100)
    fun getBaeminId(): String = securePrefs.getString(KEY_BAEMIN_ID, "") ?: ""
    fun getBaeminPw(): String = securePrefs.getString(KEY_BAEMIN_PW, "") ?: ""
    fun getCoupangId(): String = securePrefs.getString(KEY_COUPANG_ID, "") ?: ""
    fun getCoupangPw(): String = securePrefs.getString(KEY_COUPANG_PW, "") ?: ""

    private fun notifySettingsChanged() {
        try { com.posdelay.app.service.FirebaseSettingsSync.onSettingsChanged() } catch (_: Exception) {}
    }

    // Setters
    fun setAdEnabled(value: Boolean, fromRemote: Boolean = false) {
        if (fromRemote && isGuarded(KEY_AD_ENABLED)) return
        prefs.edit().putBoolean(KEY_AD_ENABLED, value).apply()
        _adEnabled.postValue(value)
        if (!fromRemote) markLocalChange(KEY_AD_ENABLED)
        notifySettingsChanged()
    }

    fun setBaeminAmount(value: Int) {
        val clamped = value.coerceIn(50, 1000)
        prefs.edit().putInt(KEY_BAEMIN_AMOUNT, clamped).apply()
        _baeminAmount.postValue(clamped)
        notifySettingsChanged()
    }

    fun setBaeminReducedAmount(value: Int) {
        val clamped = value.coerceIn(50, 1000)
        prefs.edit().putInt(KEY_BAEMIN_REDUCED_AMOUNT, clamped).apply()
        _baeminReducedAmount.postValue(clamped)
        notifySettingsChanged()
    }

    fun setCoupangAdOn(value: Boolean) {
        prefs.edit().putBoolean(KEY_COUPANG_AD_ON, value).apply()
        _coupangAdOn.postValue(value)
        notifySettingsChanged()
    }

    fun setAdOffTime(value: String) {
        prefs.edit().putString(KEY_AD_OFF_TIME, value).apply()
        _adOffTime.postValue(value)
        notifySettingsChanged()
    }

    fun setAdOnTime(value: String) {
        prefs.edit().putString(KEY_AD_ON_TIME, value).apply()
        _adOnTime.postValue(value)
        notifySettingsChanged()
    }

    fun setScheduleEnabled(value: Boolean, fromRemote: Boolean = false) {
        if (fromRemote && isGuarded(KEY_SCHEDULE_ENABLED)) return
        prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, value).apply()
        _scheduleEnabled.postValue(value)
        if (!fromRemote) markLocalChange(KEY_SCHEDULE_ENABLED)
        notifySettingsChanged()
    }

    fun setOrderAutoOffEnabled(value: Boolean, fromRemote: Boolean = false) {
        if (fromRemote && isGuarded(KEY_ORDER_AUTO_OFF_ENABLED)) return
        prefs.edit().putBoolean(KEY_ORDER_AUTO_OFF_ENABLED, value).apply()
        _orderAutoOffEnabled.postValue(value)
        if (!fromRemote) markLocalChange(KEY_ORDER_AUTO_OFF_ENABLED)
        notifySettingsChanged()
    }

    fun setCoupangAutoEnabled(value: Boolean, fromRemote: Boolean = false) {
        if (fromRemote && isGuarded(KEY_COUPANG_AUTO_ENABLED)) return
        prefs.edit().putBoolean(KEY_COUPANG_AUTO_ENABLED, value).apply()
        _coupangAutoEnabled.postValue(value)
        if (!fromRemote) markLocalChange(KEY_COUPANG_AUTO_ENABLED)
        notifySettingsChanged()
    }

    fun setBaeminAutoEnabled(value: Boolean, fromRemote: Boolean = false) {
        if (fromRemote && isGuarded(KEY_BAEMIN_AUTO_ENABLED)) return
        prefs.edit().putBoolean(KEY_BAEMIN_AUTO_ENABLED, value).apply()
        _baeminAutoEnabled.postValue(value)
        if (!fromRemote) markLocalChange(KEY_BAEMIN_AUTO_ENABLED)
        notifySettingsChanged()
    }

    fun setCoupangOffThreshold(value: Int) {
        val clamped = value.coerceIn(2, 30)
        prefs.edit().putInt(KEY_COUPANG_OFF_THRESHOLD, clamped).apply()
        _coupangOffThreshold.postValue(clamped)
        if (getCoupangOnThreshold() >= clamped) setCoupangOnThreshold(clamped - 1)
        notifySettingsChanged()
    }
    fun setCoupangOnThreshold(value: Int) {
        val clamped = value.coerceIn(0, getCoupangOffThreshold() - 1)
        prefs.edit().putInt(KEY_COUPANG_ON_THRESHOLD, clamped).apply()
        _coupangOnThreshold.postValue(clamped)
        notifySettingsChanged()
    }
    // 배민 임계값: 켜기 < 중간 < 끄기 순서 보장
    fun setBaeminOffThreshold(value: Int) {
        val clamped = value.coerceIn(3, 30)
        prefs.edit().putInt(KEY_BAEMIN_OFF_THRESHOLD, clamped).apply()
        _baeminOffThreshold.postValue(clamped)
        if (getBaeminMidUpperThreshold() >= clamped) setBaeminMidUpperThreshold(clamped - 1)
        if (getBaeminMidThreshold() >= clamped) setBaeminMidThreshold(clamped - 1)
        notifySettingsChanged()
    }
    fun setBaeminMidThreshold(value: Int) {
        val clamped = value.coerceIn(2, getBaeminOffThreshold() - 1)
        prefs.edit().putInt(KEY_BAEMIN_MID_THRESHOLD, clamped).apply()
        _baeminMidThreshold.postValue(clamped)
        if (getBaeminMidUpperThreshold() < clamped) setBaeminMidUpperThreshold(clamped)
        if (getBaeminOnThreshold() >= clamped) setBaeminOnThreshold(clamped - 1)
        notifySettingsChanged()
    }
    fun setBaeminOnThreshold(value: Int) {
        val clamped = value.coerceIn(0, getBaeminMidThreshold() - 1)
        prefs.edit().putInt(KEY_BAEMIN_ON_THRESHOLD, clamped).apply()
        _baeminOnThreshold.postValue(clamped)
        notifySettingsChanged()
    }
    fun setBaeminMidUpperThreshold(value: Int) {
        val clamped = value.coerceIn(getBaeminMidThreshold(), getBaeminOffThreshold() - 1)
        prefs.edit().putInt(KEY_BAEMIN_MID_UPPER_THRESHOLD, clamped).apply()
        _baeminMidUpperThreshold.postValue(clamped)
        notifySettingsChanged()
    }
    fun setBaeminMidAmount(value: Int) {
        val clamped = value.coerceIn(50, 1000)
        prefs.edit().putInt(KEY_BAEMIN_MID_AMOUNT, clamped).apply()
        _baeminMidAmount.postValue(clamped)
        notifySettingsChanged()
    }

    fun setLastAdAction(value: String) {
        prefs.edit().putString(KEY_LAST_AD_ACTION, value).apply()
        _lastAdAction.postValue(value)
        try { com.posdelay.app.service.FirebaseSettingsSync.onAdStateChanged() } catch (_: Exception) {}
    }

    fun getBaeminCurrentBid(): Int = prefs.getInt(KEY_BAEMIN_CURRENT_BID, 0)

    fun setBaeminCurrentBid(value: Int) {
        prefs.edit().putInt(KEY_BAEMIN_CURRENT_BID, value).apply()
        _baeminCurrentBid.postValue(value)
        try { com.posdelay.app.service.FirebaseSettingsSync.onAdStateChanged() } catch (_: Exception) {}
    }

    fun setCoupangCurrentOn(value: Boolean) {
        prefs.edit().putBoolean(KEY_COUPANG_CURRENT_ON, value).apply()
        _coupangCurrentOn.postValue(value)
        try { com.posdelay.app.service.FirebaseSettingsSync.onAdStateChanged() } catch (_: Exception) {}
    }

    // Credential setters
    fun setBaeminCredentials(id: String, pw: String) {
        securePrefs.edit()
            .putString(KEY_BAEMIN_ID, id)
            .putString(KEY_BAEMIN_PW, pw)
            .apply()
    }

    fun setCoupangCredentials(id: String, pw: String) {
        securePrefs.edit()
            .putString(KEY_COUPANG_ID, id)
            .putString(KEY_COUPANG_PW, pw)
            .apply()
    }

    fun hasBaeminCredentials(): Boolean = getBaeminId().isNotEmpty() && getBaeminPw().isNotEmpty()
    fun hasCoupangCredentials(): Boolean = getCoupangId().isNotEmpty() && getCoupangPw().isNotEmpty()

    // === Zone 배열 (게이지 셀별 색상) ===
    // 쿠팡: 0=hold, 1=on, 2=off
    // 배민: 0=hold, 1=max, 2=mid, 3=min
    private val COUPANG_ZONES_DEF = intArrayOf(1,1,1,0,0,2,2,2,2,2,2)
    private val BAEMIN_ZONES_DEF = intArrayOf(1,1,1,0,2,2,0,3,3,3,3)

    fun getZones(platform: String): IntArray {
        val key = if (platform == "coupang") "coupang_zones" else "baemin_zones"
        val def = if (platform == "coupang") COUPANG_ZONES_DEF else BAEMIN_ZONES_DEF
        val str = prefs.getString(key, null) ?: return def.copyOf()
        return try {
            val arr = org.json.JSONArray(str)
            IntArray(11) { i -> if (i < arr.length()) arr.getInt(i) else 0 }
        } catch (_: Exception) { def.copyOf() }
    }

    fun setZones(platform: String, zones: IntArray) {
        val key = if (platform == "coupang") "coupang_zones" else "baemin_zones"
        val arr = org.json.JSONArray()
        zones.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
        notifySettingsChanged()
    }

    fun getZonesJson(platform: String): org.json.JSONArray {
        val zones = getZones(platform)
        val arr = org.json.JSONArray()
        zones.forEach { arr.put(it) }
        return arr
    }

    fun setZonesFromJson(platform: String, jsonArray: org.json.JSONArray) {
        val zones = IntArray(11) { i -> if (i < jsonArray.length()) jsonArray.getInt(i) else 0 }
        val key = if (platform == "coupang") "coupang_zones" else "baemin_zones"
        prefs.edit().putString(key, jsonArray.toString()).apply()
    }

    /** 현재 건수에 해당하는 쿠팡 zone 값 (0=hold, 1=on, 2=off) */
    fun getCoupangZoneAt(count: Int): Int {
        val idx = count.coerceIn(0, 10)
        return getZones("coupang")[idx]
    }

    /** 현재 건수에 해당하는 배민 zone 값 (0=hold, 1=max, 2=mid, 3=min) */
    fun getBaeminZoneAt(count: Int): Int {
        val idx = count.coerceIn(0, 10)
        return getZones("baemin")[idx]
    }

    // 지연 설정 getter/setter
    fun getBaeminTargetTime(): Int = prefs.getInt(KEY_BAEMIN_TARGET_TIME, 20)
    fun getBaeminFixedCookTime(): Int = prefs.getInt(KEY_BAEMIN_FIXED_COOK_TIME, 15)
    fun getBaeminDelayThreshold(): Int = prefs.getInt(KEY_BAEMIN_DELAY_THRESHOLD, 5)
    fun getCoupangTargetTime(): Int = prefs.getInt(KEY_COUPANG_TARGET_TIME, 20)
    fun getCoupangFixedCookTime(): Int = prefs.getInt(KEY_COUPANG_FIXED_COOK_TIME, 15)
    fun getCoupangDelayThreshold(): Int = prefs.getInt(KEY_COUPANG_DELAY_THRESHOLD, 5)

    fun setBaeminTargetTime(value: Int) {
        val clamped = value.coerceIn(15, 30)
        prefs.edit().putInt(KEY_BAEMIN_TARGET_TIME, clamped).apply()
        _baeminTargetTime.postValue(clamped)
        notifySettingsChanged()
    }
    fun setBaeminFixedCookTime(value: Int) {
        val clamped = value.coerceIn(0, 20)
        prefs.edit().putInt(KEY_BAEMIN_FIXED_COOK_TIME, clamped).apply()
        _baeminFixedCookTime.postValue(clamped)
        notifySettingsChanged()
    }
    fun setBaeminDelayThreshold(value: Int) {
        val clamped = value.coerceIn(1, 10)
        prefs.edit().putInt(KEY_BAEMIN_DELAY_THRESHOLD, clamped).apply()
        _baeminDelayThreshold.postValue(clamped)
        notifySettingsChanged()
    }
    fun setCoupangTargetTime(value: Int) {
        val clamped = value.coerceIn(15, 30)
        prefs.edit().putInt(KEY_COUPANG_TARGET_TIME, clamped).apply()
        _coupangTargetTime.postValue(clamped)
        notifySettingsChanged()
    }
    fun setCoupangFixedCookTime(value: Int) {
        val clamped = value.coerceIn(0, 20)
        prefs.edit().putInt(KEY_COUPANG_FIXED_COOK_TIME, clamped).apply()
        _coupangFixedCookTime.postValue(clamped)
        notifySettingsChanged()
    }
    fun setCoupangDelayThreshold(value: Int) {
        val clamped = value.coerceIn(1, 10)
        prefs.edit().putInt(KEY_COUPANG_DELAY_THRESHOLD, clamped).apply()
        _coupangDelayThreshold.postValue(clamped)
        notifySettingsChanged()
    }
}
