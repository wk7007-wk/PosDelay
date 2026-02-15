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
    private const val KEY_AUTO_OFF_THRESHOLD = "auto_off_threshold"
    private const val KEY_LAST_AD_ACTION = "last_ad_action"

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

    private val _autoOffThreshold = MutableLiveData(5)
    val autoOffThreshold: LiveData<Int> = _autoOffThreshold

    private val _lastAdAction = MutableLiveData("")
    val lastAdAction: LiveData<String> = _lastAdAction

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
        _autoOffThreshold.postValue(prefs.getInt(KEY_AUTO_OFF_THRESHOLD, 5))
        _lastAdAction.postValue(prefs.getString(KEY_LAST_AD_ACTION, "") ?: "")
    }

    // Getters
    fun isAdEnabled(): Boolean = prefs.getBoolean(KEY_AD_ENABLED, false)
    fun getBaeminAmount(): Int = prefs.getInt(KEY_BAEMIN_AMOUNT, 200)
    fun getBaeminReducedAmount(): Int = prefs.getInt(KEY_BAEMIN_REDUCED_AMOUNT, 50)
    fun isCoupangAdOn(): Boolean = prefs.getBoolean(KEY_COUPANG_AD_ON, true)
    fun getAdOffTime(): String = prefs.getString(KEY_AD_OFF_TIME, "22:00") ?: "22:00"
    fun getAdOnTime(): String = prefs.getString(KEY_AD_ON_TIME, "08:00") ?: "08:00"
    fun isScheduleEnabled(): Boolean = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
    fun isOrderAutoOffEnabled(): Boolean = prefs.getBoolean(KEY_ORDER_AUTO_OFF_ENABLED, false)
    fun getAutoOffThreshold(): Int = prefs.getInt(KEY_AUTO_OFF_THRESHOLD, 5)
    fun getBaeminId(): String = securePrefs.getString(KEY_BAEMIN_ID, "") ?: ""
    fun getBaeminPw(): String = securePrefs.getString(KEY_BAEMIN_PW, "") ?: ""
    fun getCoupangId(): String = securePrefs.getString(KEY_COUPANG_ID, "") ?: ""
    fun getCoupangPw(): String = securePrefs.getString(KEY_COUPANG_PW, "") ?: ""

    // Setters
    fun setAdEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AD_ENABLED, value).apply()
        _adEnabled.postValue(value)
    }

    fun setBaeminAmount(value: Int) {
        val clamped = value.coerceIn(50, 300)
        prefs.edit().putInt(KEY_BAEMIN_AMOUNT, clamped).apply()
        _baeminAmount.postValue(clamped)
    }

    fun setBaeminReducedAmount(value: Int) {
        val clamped = value.coerceIn(50, 300)
        prefs.edit().putInt(KEY_BAEMIN_REDUCED_AMOUNT, clamped).apply()
        _baeminReducedAmount.postValue(clamped)
    }

    fun setCoupangAdOn(value: Boolean) {
        prefs.edit().putBoolean(KEY_COUPANG_AD_ON, value).apply()
        _coupangAdOn.postValue(value)
    }

    fun setAdOffTime(value: String) {
        prefs.edit().putString(KEY_AD_OFF_TIME, value).apply()
        _adOffTime.postValue(value)
    }

    fun setAdOnTime(value: String) {
        prefs.edit().putString(KEY_AD_ON_TIME, value).apply()
        _adOnTime.postValue(value)
    }

    fun setScheduleEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, value).apply()
        _scheduleEnabled.postValue(value)
    }

    fun setOrderAutoOffEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ORDER_AUTO_OFF_ENABLED, value).apply()
        _orderAutoOffEnabled.postValue(value)
    }

    fun setAutoOffThreshold(value: Int) {
        val clamped = value.coerceIn(1, 30)
        prefs.edit().putInt(KEY_AUTO_OFF_THRESHOLD, clamped).apply()
        _autoOffThreshold.postValue(clamped)
    }

    fun setLastAdAction(value: String) {
        prefs.edit().putString(KEY_LAST_AD_ACTION, value).apply()
        _lastAdAction.postValue(value)
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
}
